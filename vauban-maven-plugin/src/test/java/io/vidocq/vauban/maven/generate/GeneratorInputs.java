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
package io.vidocq.vauban.maven.generate;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** Class files, compiled packages and jars the generator tests feed to {@link VaubanGenerator}. */
final class GeneratorInputs {

    static final ClassDesc APPLICATION_SCOPED = ClassDesc.of("jakarta.enterprise.context.ApplicationScoped");
    static final ClassDesc DEPENDENT = ClassDesc.of("jakarta.enterprise.context.Dependent");
    static final ClassDesc PRODUCES = ClassDesc.of("jakarta.enterprise.inject.Produces");
    static final String PROVIDER_SERVICE = "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider";

    private GeneratorInputs() {
    }

    /** A public class with a public no-arg constructor, annotated with {@code scope}. */
    static byte[] beanClass(String fqn, ClassDesc scope) {
        return ClassFile.of().build(ClassDesc.of(fqn), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(scope)));
            withConstructor(clb);
        });
    }

    /** A {@code holderScope} bean class declaring one {@code @Produces @ApplicationScoped} method of {@code produced}. */
    static byte[] producerHolder(String fqn, ClassDesc holderScope, Class<?> produced) {
        var producedDesc = ClassDesc.of(produced.getName());
        return ClassFile.of().build(ClassDesc.of(fqn), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(holderScope)));
            withConstructor(clb);
            clb.withMethod("make", MethodTypeDesc.of(producedDesc), ClassFile.ACC_PUBLIC, mb -> {
                mb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(PRODUCES),
                        Annotation.of(APPLICATION_SCOPED)));
                mb.withCode(cob -> {
                    cob.new_(producedDesc);
                    cob.dup();
                    cob.invokespecial(producedDesc, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void));
                    cob.areturn();
                });
            });
        });
    }

    private static void withConstructor(java.lang.classfile.ClassBuilder clb) {
        clb.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC,
                cob -> {
                    cob.aload(0);
                    cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void));
                    cob.return_();
                });
    }

    /** Writes {@code bytes} as the class file of {@code fqn} under {@code classesDir}. */
    static void writeClass(Path classesDir, String fqn, byte[] bytes) throws IOException {
        var file = classesDir.resolve(fqn.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** Copies the compiled classes of {@code anchor}'s package, as a project's classes directory holds them. */
    static Path copyPackage(Class<?> anchor, Path classesDir) throws IOException {
        var relative = anchor.getPackageName().replace('.', '/');
        var target = classesDir.resolve(relative);
        Files.createDirectories(target);
        try (Stream<Path> files = Files.list(testClasses().resolve(relative))) {
            for (var file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
        return classesDir;
    }

    /** A jar holding {@code entries}, entry name to bytes. */
    static Path jar(Path file, Map<String, byte[]> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); var jar = new JarOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return file;
    }

    /** The bytes of a compiled test class. */
    static byte[] compiled(Class<?> type) throws IOException {
        return Files.readAllBytes(testClasses().resolve(type.getName().replace('.', '/') + ".class"));
    }

    /**
     * The class names a generated class refers to, as class constants or string constants: the classes a provider
     * instantiates are among them.
     */
    static Set<String> names(Path classFile) throws IOException {
        var names = new HashSet<String>();
        for (var entry : ClassFile.of().parse(Files.readAllBytes(classFile)).constantPool()) {
            if (entry instanceof StringEntry s) {
                names.add(s.stringValue());
            } else if (entry instanceof ClassEntry c) {
                names.add(c.asInternalName().replace('/', '.'));
            }
        }
        return names;
    }

    private static Path testClasses() {
        try {
            return Path.of(GeneratorInputs.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
