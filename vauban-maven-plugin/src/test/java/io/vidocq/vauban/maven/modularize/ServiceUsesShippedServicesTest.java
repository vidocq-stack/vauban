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
package io.vidocq.vauban.maven.modularize;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A jar whose service locator takes the service type as a parameter (Liquibase's {@code StandardServiceLocator})
 * names no service in its bytecode: the services it declares in its own {@code META-INF/services} are the ones it
 * looks up, and its descriptor must use them (tutorial, 2026-11-15: {@code liquibase.logging.LogService: module
 * liquibase.core does not declare `uses`} in a jlink image).
 */
@DisplayName("modularize: a jar that looks services up by a parameter uses the services it ships")
class ServiceUsesShippedServicesTest {

    private static final ClassDesc SERVICE_LOADER = ClassDesc.of("java.util.ServiceLoader");
    private static final MethodTypeDesc LOAD = MethodTypeDesc.of(SERVICE_LOADER, ConstantDescs.CD_Class);
    private static final MethodTypeDesc FIND = MethodTypeDesc.of(SERVICE_LOADER, ConstantDescs.CD_Class);

    @TempDir
    Path tmp;

    private static byte[] iface(String name) {
        return ClassFile.of().build(ClassDesc.of(name), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
            clb.withSuperclass(ConstantDescs.CD_Object);
        });
    }

    /** {@code public static ServiceLoader find(Class type) { return ServiceLoader.load(type); }} */
    private static byte[] locator(String name) {
        return ClassFile.of().build(ClassDesc.of(name), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withMethodBody("find", FIND, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
                cob.aload(0);
                cob.invokestatic(SERVICE_LOADER, "load", LOAD);
                cob.areturn();
            });
        });
    }

    private static String classEntry(String name) {
        return name.replace('.', '/') + ".class";
    }

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private Path jar(String file, Map<String, byte[]> entries) throws IOException {
        var path = tmp.resolve(file);
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return path;
    }

    @Test
    @DisplayName("the services of its own types it ships are used; a type it does not define is not")
    void aParameterLocatorUsesTheServicesItShips() throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put(classEntry("lib.Locator"), locator("lib.Locator"));
        entries.put(classEntry("lib.LogService"), iface("lib.LogService"));
        entries.put(classEntry("lib.Change"), iface("lib.Change"));
        entries.put("META-INF/services/", new byte[0]);
        entries.put("META-INF/services/lib.LogService", text("lib.impl.DefaultLog\n"));
        entries.put("META-INF/services/lib.Change", text("lib.impl.CreateTable\n"));
        entries.put("META-INF/services/pro.LicenseService", text("pro.impl.License\n"));
        var locatorJar = jar("locator.jar", entries);
        var log = new ArrayList<String>();

        var scanner = ServiceUsesScanner.over(List.of(locatorJar), log::add);

        assertEquals(Set.of("lib.Change", "lib.LogService"), scanner.scan(locatorJar));
    }

    @Test
    @DisplayName("a jar that only provides services and looks nothing up uses none")
    void aProviderOnlyJarUsesNothing() throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put(classEntry("lib.LogService"), iface("lib.LogService"));
        entries.put("META-INF/services/lib.LogService", text("lib.impl.DefaultLog\n"));
        var providerJar = jar("provider.jar", entries);

        var scanner = ServiceUsesScanner.over(List.of(providerJar), line -> { });

        assertEquals(Set.of(), scanner.scan(providerJar));
    }
}
