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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.classfile.ClassFile;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261117-58: an enum's constructor takes the name and the ordinal of its constant first, two synthetic
 * parameters its source does not declare. The index records the parameters the source declares, as the processor
 * does, with their annotations and the annotations on their types: whether javac wrote a {@code Signature} and a
 * {@code MethodParameters} attribute (Java 21 and later) or neither (before).
 */
@DisplayName("BUG-20261117-58 - an enum constructor's parameters are those its source declares")
class EnumConstructorParametersTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.PARAMETER, ElementType.TYPE_USE})
    @interface Marked {
    }

    enum Level {
        LOW("low", true);

        private final String label;

        Level(@Marked String label, boolean flag) {
            this.label = flag ? label : label.toUpperCase();
        }

        String label() {
            return label;
        }
    }

    @Test
    @DisplayName("an enum compiled by javac 25")
    void anEnumOfJavac25() throws IOException {
        try (var in = Level.class.getResourceAsStream("EnumConstructorParametersTest$Level.class")) {
            assertEquals("[String [Marked], boolean []] [PARAMETER 0 Marked]",
                    described(constructor(ClassFileScanner.scan(in.readAllBytes()))));
        }
    }

    /**
     * An enum's constructor as javac before Java 21 writes it: no {@code Signature}, no {@code MethodParameters}, the
     * parameter annotations for the declared parameters only, and a type annotation numbering them the same way.
     */
    @Test
    @DisplayName("an enum without Signature nor MethodParameters on its constructor, as javac before 21")
    void anEnumBeforeJava21() {
        var level = ClassDesc.of("p.Level");
        var marked = java.lang.classfile.Annotation.of(Marked.class.describeConstable().orElseThrow());
        var bytes = ClassFile.of().build(level, type -> type
                .withFlags(AccessFlag.FINAL, AccessFlag.SUPER, AccessFlag.ENUM)
                .withSuperclass(ClassDesc.of("java.lang.Enum"))
                .withMethod(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String,
                        ConstantDescs.CD_int, ConstantDescs.CD_String), ClassFile.ACC_PRIVATE, method -> method
                        .with(RuntimeVisibleParameterAnnotationsAttribute.of(List.of(List.of(marked))))
                        .with(RuntimeVisibleTypeAnnotationsAttribute.of(TypeAnnotation.of(
                                TypeAnnotation.TargetInfo.ofMethodFormalParameter(0), List.of(), marked)))));

        assertEquals("[String [Marked]] [PARAMETER 0 Marked]",
                described(constructor(ClassFileScanner.scan(bytes))));
    }

    private static MethodInfo constructor(io.vidocq.vauban.indexer.model.ClassInfo type) {
        return type.methods().stream().filter(MethodInfo::isConstructor).findFirst().orElseThrow();
    }

    private static String described(MethodInfo constructor) {
        return constructor.parameters().stream().map(EnumConstructorParametersTest::described).toList() + " "
                + constructor.typeAnnotations().stream().map(EnumConstructorParametersTest::described).toList();
    }

    private static String described(ParameterInfo parameter) {
        var type = switch (parameter.type()) {
            case io.vidocq.vauban.indexer.model.TypeInfo.ClassType(var name) -> simple(name.toString());
            case io.vidocq.vauban.indexer.model.TypeInfo.PrimitiveType(var kind) -> kind.name().toLowerCase();
            default -> parameter.type().toString();
        };
        return type + " "
                + parameter.annotations().stream().map(annotation -> simple(annotation.name().toString())).toList();
    }

    private static String described(TypeAnnotationInfo annotation) {
        return annotation.target() + " " + annotation.index() + " " + simple(annotation.annotation().name().toString());
    }

    private static String simple(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }
}
