/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.sjar;

import io.vidocq.vauban.classloader.spi.ArchiveReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClassLoader that loads classes from an SJAR archive.
 * Decrypted class bytes are cached in memory (never written to disk).
 */
public final class SjarClassLoader extends ClassLoader {

    private final ArchiveReader reader;
    private final ConcurrentHashMap<String, byte[]> classBytes = new ConcurrentHashMap<>();
    private volatile boolean initialized;

    public SjarClassLoader(ArchiveReader reader, ClassLoader parent) {
        super(parent);
        this.reader = reader;
    }

    /**
     * Pre-load all class entries into the cache for fast access.
     */
    public void preload() throws IOException {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            for (var entry : reader.classEntries()) {
                var className = entry.replace('/', '.').replace(".class", "");
                classBytes.put(className, reader.readClass(entry));
            }
            initialized = true;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        var bytes = classBytes.get(name);
        if (bytes == null) {
            // Try lazy load
            var entryName = name.replace('.', '/') + ".class";
            try {
                bytes = reader.readClass(entryName);
                classBytes.put(name, bytes);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
        if (bytes != null) {
            return defineClass(name, bytes, 0, bytes.length);
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        // For .class resources, return decrypted bytes
        if (name.endsWith(".class")) {
            var className = name.replace('/', '.').replace(".class", "");
            var bytes = classBytes.get(className);
            if (bytes != null) {
                return new ByteArrayInputStream(bytes);
            }
            // Try reading from the archive
            try {
                var classFileBytes = reader.readClass(name);
                return new ByteArrayInputStream(classFileBytes);
            } catch (IOException e) {
                // fall through to parent
            }
        }
        // For non-class resources, try the archive then parent
        try {
            var resource = reader.readResource(name);
            if (resource.isPresent()) {
                return new ByteArrayInputStream(resource.get());
            }
        } catch (IOException e) {
            // fall through to parent
        }
        return super.getResourceAsStream(name);
    }

    /**
     * Check if this classloader has a class available (without loading it).
     */
    public boolean hasClass(String className) {
        if (classBytes.containsKey(className)) return true;
        var entryName = className.replace('.', '/') + ".class";
        try {
            reader.readClass(entryName);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
