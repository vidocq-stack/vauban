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
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lookups forwarded through helpers that converge, through an interface two ways, from two call sites, and the class
 * files and constants the scan passes over.
 */
class ServiceUsesForwardingTest {

    private static final ClassDesc HELPER = ClassDesc.of("lib.Helper");
    private static final ClassDesc FINDER = ClassDesc.of("lib.Finder");
    private static final ClassDesc NARROW_FINDER = ClassDesc.of("lib.NarrowFinder");
    private static final ClassDesc SERVICE_LOADER = ClassDesc.of("java.util.ServiceLoader");
    private static final ClassDesc ITERABLE = ClassDesc.of("java.lang.Iterable");
    private static final MethodTypeDesc LOOKUP = MethodTypeDesc.of(ITERABLE, ConstantDescs.CD_Class);
    private static final MethodTypeDesc VOID = MethodTypeDesc.of(ConstantDescs.CD_void);
    private static final int PUBLIC_STATIC = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC;
    private static final String SERVICE_A = "svc.Alpha";
    private static final String SERVICE_B = "svc.Beta";

    @TempDir
    Path tmp;

    private Path helperJar;
    private Path appJar;
    private final List<String> log = new ArrayList<>();
    private ServiceUsesScanner scanner;

    private static byte[] type(ClassDesc self, Consumer<ClassBuilder> body) {
        return ClassFile.of().build(self, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            body.accept(clb);
        });
    }

    private static byte[] iface(ClassDesc self, ClassDesc... supers) {
        return ClassFile.of().build(self, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withInterfaceSymbols(supers);
            clb.withMethod("find", LOOKUP, ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, mb -> { });
        });
    }

    /** {@code static Iterable name(Class c)} calling each of {@code callees} with {@code c}, then returning null. */
    private static void forwarding(ClassBuilder clb, String name, ClassDesc owner, String... callees) {
        clb.withMethodBody(name, LOOKUP, PUBLIC_STATIC, cob -> {
            for (String callee : callees) {
                cob.aload(0);
                cob.invokestatic(owner, callee, LOOKUP);
                cob.pop();
            }
            cob.aload(0);
            cob.invokestatic(ClassDesc.of("java.util.Objects"), "requireNonNull",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object));
            cob.pop();
            cob.aconst_null();
            cob.areturn();
        });
    }

    private static void finderImplementation(ClassBuilder clb, boolean looksUp) {
        clb.withInterfaceSymbols(NARROW_FINDER);
        clb.withMethodBody(ConstantDescs.INIT_NAME, VOID, ClassFile.ACC_PUBLIC, cob -> {
            cob.aload(0);
            cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, VOID);
            cob.return_();
        });
        clb.withMethodBody("find", LOOKUP, ClassFile.ACC_PUBLIC, cob -> {
            if (looksUp) {
                cob.aload(1);
                cob.invokestatic(HELPER, "load", LOOKUP);
            } else {
                cob.aconst_null();
            }
            cob.areturn();
        });
    }

    private static void callSite(ClassBuilder clb, String name, String service, Consumer<CodeBuilder> lookup) {
        clb.withMethodBody(name, VOID, PUBLIC_STATIC, cob -> {
            cob.ldc(ClassDesc.of(service));
            lookup.accept(cob);
            cob.pop();
            cob.return_();
        });
    }

    private static Path jar(Path file, Map<String, byte[]> entries) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    /** A class whose header parses but whose code holds an undefined opcode: reading the code fails. */
    private static byte[] withUndefinedOpcode() {
        byte[] bytes = type(ClassDesc.of("app.BadOpcode"), clb -> clb.withMethodBody("run", VOID, PUBLIC_STATIC, cob -> {
            cob.bipush(0x5A);
            cob.pop();
            cob.return_();
        }));
        for (int i = 0; i + 3 < bytes.length; i++) {
            if (bytes[i] == 0x10 && bytes[i + 1] == 0x5A && bytes[i + 2] == 0x57 && bytes[i + 3] == (byte) 0xB1) {
                bytes[i + 2] = (byte) 0xE0;
                return bytes;
            }
        }
        throw new IllegalStateException("the code of run() was not found");
    }

    @BeforeEach
    void scan() throws IOException {
        var helpers = new LinkedHashMap<String, byte[]>();
        helpers.put("lib/", new byte[0]);
        helpers.put("lib/Helper.class", type(HELPER, clb -> {
            clb.withMethodBody("load", LOOKUP, PUBLIC_STATIC, cob -> {
                cob.aload(0);
                cob.invokestatic(SERVICE_LOADER, "load", MethodTypeDesc.of(SERVICE_LOADER, ConstantDescs.CD_Class));
                cob.areturn();
            });
            forwarding(clb, "left", HELPER, "load");
            forwarding(clb, "right", HELPER, "load");
            forwarding(clb, "converging", HELPER, "left", "right");
            clb.withMethodBody("bothWays", MethodTypeDesc.of(ITERABLE, ConstantDescs.CD_Class, NARROW_FINDER),
                    PUBLIC_STATIC, cob -> {
                        cob.aload(1);
                        cob.aload(0);
                        cob.invokeinterface(NARROW_FINDER, "find", LOOKUP);
                        cob.pop();
                        cob.aload(1);
                        cob.aload(0);
                        cob.invokeinterface(FINDER, "find", LOOKUP);
                        cob.areturn();
                    });
        }));
        helpers.put("lib/Finder.class", iface(FINDER));
        helpers.put("lib/NarrowFinder.class", iface(NARROW_FINDER, FINDER));
        helpers.put("lib/FinderImpl.class", type(ClassDesc.of("lib.FinderImpl"), clb -> finderImplementation(clb, true)));
        helpers.put("lib/IdleFinder.class", type(ClassDesc.of("lib.IdleFinder"), clb -> finderImplementation(clb, false)));
        helpers.put("module-info.class", new byte[] {1, 2, 3});
        helpers.put("lib/readme.txt", new byte[] {1});
        helperJar = jar(tmp.resolve("helper.jar"), helpers);

        var app = new LinkedHashMap<String, byte[]>();
        app.put("app/Main.class", type(ClassDesc.of("app.Main"), clb -> {
            callSite(clb, "first", SERVICE_A, cob -> cob.invokestatic(HELPER, "converging", LOOKUP));
            callSite(clb, "second", SERVICE_A, cob -> cob.invokestatic(HELPER, "converging", LOOKUP));
            callSite(clb, "third", SERVICE_B, cob -> {
                cob.aconst_null();
                cob.invokestatic(HELPER, "bothWays", MethodTypeDesc.of(ITERABLE, ConstantDescs.CD_Class, NARROW_FINDER));
            });
            clb.withMethodBody("arrays", VOID, PUBLIC_STATIC, cob -> {
                cob.ldc(ConstantDescs.CD_String.arrayType());
                cob.invokestatic(SERVICE_LOADER, "load", MethodTypeDesc.of(SERVICE_LOADER, ConstantDescs.CD_Class));
                cob.pop();
                cob.return_();
            });
            clb.withMethodBody("failing", VOID, PUBLIC_STATIC, cob -> {
                cob.ldc(ClassDesc.of("svc.Thrown"));
                cob.pop();
                cob.new_(ClassDesc.of("java.lang.IllegalStateException"));
                cob.dup();
                cob.invokespecial(ClassDesc.of("java.lang.IllegalStateException"), ConstantDescs.INIT_NAME, VOID);
                cob.athrow();
            });
        }));
        app.put("app/Broken.class", new byte[] {(byte) 0xCA, (byte) 0xFE, 0, 0});
        var valid = type(ClassDesc.of("app.Truncated"), clb -> forwarding(clb, "go", HELPER, "load"));
        app.put("app/Truncated.class", Arrays.copyOf(valid, valid.length - 6));
        app.put("app/BadOpcode.class", withUndefinedOpcode());
        appJar = jar(tmp.resolve("app.jar"), app);

        scanner = ServiceUsesScanner.over(List.of(helperJar, appJar), log::add);
    }

    @Test
    void theCallingJarDeclaresEveryServiceItLooksUpThroughHelpers() {
        assertEquals(Set.of(SERVICE_A, SERVICE_B), scanner.scan(appJar));
    }

    @Test
    void theHelpersOnTheForwardingChainDeclareThemToo() {
        assertEquals(Set.of(SERVICE_A, SERVICE_B), scanner.scan(helperJar));
    }

    @Test
    void classFilesThatCannotBeReadAreCounted() {
        assertEquals(List.of("DEBUG 3 class file(s) of app.jar could not be parsed — uses scan may be incomplete"), log);
    }
}
