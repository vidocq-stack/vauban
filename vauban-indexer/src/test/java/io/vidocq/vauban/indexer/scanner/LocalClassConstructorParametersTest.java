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
package io.vidocq.vauban.indexer.scanner;

import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.EnclosingMethodAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261118-32: the constructor of a local class takes the outer instance first, which javac marks mandated and the
 * index keeps, as an inner member class's, and the values the class captures last, which javac marks synthetic and
 * the index leaves out. Its parameter annotations, its generic types and its type annotations number the parameters
 * the source declares.
 */
@DisplayName("BUG-20261118-32 - a local class's constructor")
class LocalClassConstructorParametersTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.PARAMETER, ElementType.TYPE_USE})
    @interface Marked {
    }

    private final String prefix = "p";

    /** A local class in an instance method, which captures {@code count}. */
    Supplier<String> local(int count) {
        class Local implements Supplier<String> {
            private final String label;

            Local(@Marked String label) {
                this.label = label;
            }

            @Override
            public String get() {
                return prefix + label + count;
            }
        }
        return new Local("x");
    }

    /** A local class in a static method, which captures {@code count}: no outer instance. */
    static Supplier<String> staticLocal(int count) {
        class StaticLocal implements Supplier<String> {
            private final String label;

            StaticLocal(@Marked String label) {
                this.label = label;
            }

            @Override
            public String get() {
                return label + count;
            }
        }
        return new StaticLocal("x");
    }

    /**
     * A local record in an instance method, whose first component is of the class that declares it: a record has no
     * outer instance, and its compact constructor's parameters are all mandated.
     */
    Supplier<String> localRecord() {
        record Pair(LocalClassConstructorParametersTest owner, @Marked List<String> labels)
                implements Supplier<String> {
            Pair {
                labels = List.copyOf(labels);
            }

            @Override
            public String get() {
                return owner.prefix + labels;
            }
        }
        return new Pair(this, List.of("x"));
    }

    @Test
    @DisplayName("a local record, compiled by javac 25: its components, the first of the declaring class's type")
    void aLocalRecordOfJavac25() throws IOException {
        assertEquals("[LocalClassConstructorParametersTest [], ParameterizedType[rawType=java.util.List,"
                + " typeArguments=[ClassType[name=java.lang.String]]] [Marked]] [PARAMETER 1 Marked]",
                described(constructor(localRecord().getClass())));
    }

    @Test
    @DisplayName("in an instance method, compiled by javac 25: the outer instance, the declared parameter")
    void aLocalClassOfJavac25() throws IOException {
        assertEquals("[LocalClassConstructorParametersTest [], String [Marked]] [PARAMETER 1 Marked]",
                described(constructor(local(1).getClass())));
    }

    @Test
    @DisplayName("in a static method, compiled by javac 25: the declared parameter")
    void aStaticLocalClassOfJavac25() throws IOException {
        assertEquals("[String [Marked]] [PARAMETER 0 Marked]", described(constructor(staticLocal(1).getClass())));
    }

    /**
     * A local class's constructor as javac before Java 21 writes it: a {@code Signature} that names the declared
     * parameters, no {@code MethodParameters}, the parameter annotations for the declared parameters only.
     */
    @Test
    @DisplayName("in an instance method, without MethodParameters, as javac before 21")
    void aLocalClassBeforeJava21() {
        var outer = ClassDesc.of("p.Outer");
        var local = ClassDesc.of("p.Outer$1Local");
        var marked = java.lang.classfile.Annotation.of(Marked.class.describeConstable().orElseThrow());
        var bytes = ClassFile.of().build(local, type -> type
                .withFlags(ClassFile.ACC_SUPER)
                .with(EnclosingMethodAttribute.of(outer, Optional.of("make"),
                        Optional.of(MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int))))
                .with(InnerClassesAttribute.of(InnerClassInfo.of(local, Optional.empty(), Optional.of("Local"), 0)))
                .withMethod(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, outer,
                        ConstantDescs.CD_String, ConstantDescs.CD_int), 0, method -> method
                        .with(SignatureAttribute.of(MethodSignature.parseFrom("(Ljava/lang/String;)V")))
                        .with(RuntimeVisibleParameterAnnotationsAttribute.of(List.of(List.of(marked))))
                        .with(RuntimeVisibleTypeAnnotationsAttribute.of(TypeAnnotation.of(
                                TypeAnnotation.TargetInfo.ofMethodFormalParameter(0), List.of(), marked)))));

        assertEquals("[Outer [], String [Marked]] [PARAMETER 1 Marked]",
                described(constructor(ClassFileScanner.scan(bytes))));
    }

    private static MethodInfo constructor(Class<?> local) throws IOException {
        var name = local.getName().substring(local.getName().lastIndexOf('.') + 1) + ".class";
        try (var in = local.getResourceAsStream(name)) {
            return constructor(ClassFileScanner.scan(in.readAllBytes()));
        }
    }

    private static MethodInfo constructor(io.vidocq.vauban.indexer.model.ClassInfo type) {
        return type.methods().stream().filter(MethodInfo::isConstructor).findFirst().orElseThrow();
    }

    private static String described(MethodInfo constructor) {
        return constructor.parameters().stream().map(LocalClassConstructorParametersTest::described).toList() + " "
                + constructor.typeAnnotations().stream().map(LocalClassConstructorParametersTest::described).toList();
    }

    private static String described(ParameterInfo parameter) {
        var type = parameter.type() instanceof TypeInfo.ClassType(var name) ? simple(name.toString())
                : parameter.type().toString();
        return type + " " + parameter.annotations().stream().map(annotation -> simple(annotation.name().toString()))
                .toList();
    }

    private static String described(TypeAnnotationInfo annotation) {
        return annotation.target() + " " + annotation.index() + " " + simple(annotation.annotation().name().toString());
    }

    private static String simple(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }
}
