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
package io.vidocq.vauban.classloader.spi;

/**
 * Transforms class bytes before they are defined by the Vauban class loader.
 *
 * <p>Discovered via {@code ServiceLoader}, ordered by {@link #priority()} (lower first),
 * chained after the {@link ByteSourcePlugin} that produced the bytes:
 * {@code source → transformer* → defineClass}. A transformer can be disabled by name with
 * {@code -Dvauban.classloader.transformers.disabled=<name,name>}.
 *
 * <p><strong>Contract.</strong> {@link #transform} MUST be idempotent — a class already
 * transformed at build time passes through unchanged — and MUST NOT change the observable
 * semantics of the class: the same transformation applied at build time and at load time
 * must yield equivalent classes (AOT and JVM stay interchangeable).
 *
 * <p><strong>Experimental.</strong> This SPI is public by construction (ServiceLoader) but
 * its shape may still evolve while its only shipped client is the {@code cdi-proxifier};
 * treat it as experimental until announced otherwise.
 */
public interface ClassTransformerPlugin {

    /** Unique name, for logs, diagnostics and the disable property (e.g. "cdi-proxifier"). */
    String name();

    /**
     * Cheap pre-filter: whether this transformer wants to see {@code className} (binary
     * name) from {@code archive}. Called once per class; must not parse the bytes.
     */
    boolean interested(String className, ArchiveContext archive);

    /**
     * Returns the transformed bytes, or {@code null} when the class is left unchanged.
     *
     * @param className binary name (e.g. {@code com.example.Foo})
     * @param bytes     the class-file bytes produced by the source (and previous
     *                  transformers)
     */
    byte[] transform(String className, byte[] bytes, ArchiveContext archive);

    /** Lower runs first. Default 1000. */
    default int priority() {
        return 1000;
    }
}
