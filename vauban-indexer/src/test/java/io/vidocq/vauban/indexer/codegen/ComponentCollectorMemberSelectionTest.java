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
package io.vidocq.vauban.indexer.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The members a generated provider may reach without reflection, and the erasures it casts to. */
class ComponentCollectorMemberSelectionTest {

    private static final String BEAN = "app.Bean";
    private static final int PUBLIC = 0x0001;
    private static final int PRIVATE = 0x0002;
    private static final int STATIC = 0x0008;
    private static final int ABSTRACT = 0x0400;
    private static final TypeInfo STRING = new TypeInfo.ClassType(DotName.of("java.lang.String"));
    private static final TypeInfo INT = new TypeInfo.PrimitiveType(TypeInfo.PrimitiveType.Kind.INT);
    private static final TypeInfo VOID = new TypeInfo.VoidType();

    private static List<AnnotationInfo> annotated(String annotation) {
        return List.of(new AnnotationInfo(DotName.of(annotation), Map.of()));
    }

    private static ClassInfo bean(List<FieldInfo> fields, List<MethodInfo> methods) {
        return new ClassInfo(DotName.of(BEAN), DotName.of("java.lang.Object"), List.of(), PUBLIC, fields, methods,
                List.of(), ClassKind.CLASS);
    }

    private static MethodInfo method(String name, TypeInfo returned, int flags, String annotation,
            ParameterInfo... parameters) {
        return new MethodInfo(name, returned, List.of(parameters), List.of(), flags,
                annotation == null ? List.of() : annotated(annotation));
    }

    static Stream<Arguments> erasures() {
        return Stream.of(
                Arguments.of(new TypeInfo.ParameterizedType(DotName.of("java.util.List"), List.of(STRING)),
                        "java.util.List"),
                Arguments.of(new TypeInfo.ArrayType(STRING, 2), "java.lang.String[][]"),
                Arguments.of(new TypeInfo.ArrayType(INT, 1), null),
                Arguments.of(INT, null),
                Arguments.of(VOID, null));
    }

    @ParameterizedTest
    @MethodSource("erasures")
    void theErasureIsTheRawTypeAndNoneForAPrimitive(TypeInfo type, String erasure) {
        assertEquals(erasure, ComponentCollector.erasure(type));
    }

    @Test
    void staticAndPrimitiveInjectedFieldsAreLeftOut() {
        var fields = List.of(
                new FieldInfo("shared", STRING, PUBLIC | STATIC, annotated("jakarta.inject.Inject")),
                new FieldInfo("count", INT, PUBLIC, annotated("jakarta.inject.Inject")),
                new FieldInfo("label", STRING, PUBLIC, List.of()),
                new FieldInfo("name", STRING, PUBLIC, annotated("jakarta.inject.Inject")));
        var warnings = new ArrayList<String>();

        var collected = ComponentCollector.collectFields(bean(fields, List.of()), warnings);

        assertEquals(List.of(new FieldInject(BEAN, "name", "java.lang.String")), collected);
        assertEquals(List.of(), warnings);
    }

    @Test
    void onlyInvocableMethodsWithNameableSignaturesAreCollected() {
        var observed = new ParameterInfo("event", new TypeInfo.ClassType(DotName.of("app.Outer$Event")),
                annotated("jakarta.enterprise.event.Observes"));
        var methods = List.of(
                method("<init>", VOID, PUBLIC, "jakarta.inject.Inject"),
                method("<clinit>", VOID, STATIC, "jakarta.inject.Inject"),
                method("hidden", VOID, PRIVATE, "jakarta.annotation.PostConstruct"),
                method("produceLater", STRING, PUBLIC | ABSTRACT, "jakarta.enterprise.inject.Produces"),
                method("count", INT, PUBLIC, "jakarta.enterprise.inject.Produces"),
                method("init", VOID, PUBLIC, "jakarta.inject.Inject", new ParameterInfo("size", INT, List.of())),
                method("onEvent", VOID, PUBLIC, null, observed),
                method("start", VOID, PUBLIC, "jakarta.annotation.PostConstruct"),
                method("name", STRING, PUBLIC, "jakarta.enterprise.inject.Produces"));
        var warnings = new ArrayList<String>();

        var collected = ComponentCollector.collectMethods(bean(List.of(), methods), warnings);

        assertEquals(List.of("start", "name"), collected.stream().map(MethodInvoke::methodName).toList());
        assertEquals(List.of(
                "Skipping method app.Bean#count: primitive return type — only void or reference returns are supported",
                "Skipping method app.Bean#init: parameter type is a non-nameable/primitive — cannot emit invokevirtual",
                "Skipping method app.Bean#onEvent: parameter erasure 'app.Outer$Event' contains '$' (nested type)"
                        + " — cast would not compile"), warnings);
    }

    @Test
    void packagePrivateInterceptorMethodsAreCollected() {
        var context = new ParameterInfo("ctx", new TypeInfo.ClassType(DotName.of("jakarta.interceptor.InvocationContext")),
                List.of());
        var object = new TypeInfo.ClassType(DotName.of("java.lang.Object"));
        var methods = List.of(
                method("construct", object, 0, "jakarta.interceptor.AroundConstruct", context),
                method("created", VOID, 0, "jakarta.annotation.PostConstruct", context),
                method("guard", object, 0, "jakarta.interceptor.AroundInvoke", context),
                method("hiddenGuard", object, PRIVATE, "jakarta.interceptor.AroundInvoke", context));
        var warnings = new ArrayList<String>();

        var collected = ComponentCollector.collectMethods(bean(List.of(), methods), warnings);

        assertEquals(List.of("construct(jakarta.interceptor.InvocationContext)",
                        "created(jakarta.interceptor.InvocationContext)", "guard(jakarta.interceptor.InvocationContext)"),
                collected.stream().map(MethodInvoke::methodId).toList());
        assertEquals(List.of(), warnings);
    }
}
