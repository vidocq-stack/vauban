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
package io.vidocq.vauban.core.bean.validation;

import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Definition rules {@link ClassValidator} checks on the index alone: conditional observers on beans that are, or
 * default to, {@code @Dependent}; {@code @Named} without a value and type variables on initializer parameters; and
 * the {@code @Priority} values a bean takes from its stereotypes.
 */
@DisplayName("ClassValidator: conditional observers, initializer parameters and stereotype priorities")
class ClassValidatorDefinitionRulesTest {

    private static final String DEPENDENT = "jakarta.enterprise.context.Dependent";
    private static final String APPLICATION_SCOPED = "jakarta.enterprise.context.ApplicationScoped";
    private static final String OBSERVES = "jakarta.enterprise.event.Observes";
    private static final String NAMED = "jakarta.inject.Named";
    private static final String PRIORITY = "jakarta.annotation.Priority";
    private static final String STEREOTYPE = "jakarta.enterprise.inject.Stereotype";
    private static final String BEAN = "com.example.Bean";
    private static final String VALUE = "value";

    private static AnnotationInfo ann(String name) {
        return new AnnotationInfo(DotName.of(name), Map.of());
    }

    private static AnnotationInfo ann(String name, String member, AnnotationValue value) {
        return new AnnotationInfo(DotName.of(name), Map.of(member, value));
    }

    private static ClassInfo type(String name, ClassInfo.ClassKind kind, List<MethodInfo> methods,
                                  List<AnnotationInfo> annotations) {
        return new ClassInfo(DotName.of(name), DotName.of("java.lang.Object"), List.of(), 0x0001, List.of(), methods,
                annotations, kind);
    }

    private static MethodInfo method(String name, List<AnnotationInfo> annotations, ParameterInfo... parameters) {
        return new MethodInfo(name, new TypeInfo.VoidType(), List.of(parameters), List.of(), 0x0001, annotations);
    }

    private static ParameterInfo parameter(TypeInfo type, AnnotationInfo... annotations) {
        return new ParameterInfo("p", type, List.of(annotations));
    }

    private static List<String> validate(ClassInfo... classes) {
        var builder = new IndexBuilder();
        for (var info : classes) {
            builder.add(info);
        }
        return ClassValidator.validate(builder.build());
    }

    private static AnnotationValue notify(String constant) {
        return new AnnotationValue.EnumVal(DotName.of("jakarta.enterprise.event.Reception"), constant);
    }

    static Stream<Arguments> conditionalObservers() {
        var ifExists = ann(OBSERVES, "notifyObserver", notify("IF_EXISTS"));
        return Stream.of(
                Arguments.of(List.of(ann(DEPENDENT)), ifExists, true),
                Arguments.of(List.of(), ifExists, true),
                Arguments.of(List.of(ann(APPLICATION_SCOPED)), ifExists, false),
                Arguments.of(List.of(ann(DEPENDENT)), ann(OBSERVES, "notifyObserver", notify("ALWAYS")), false),
                Arguments.of(List.of(ann(DEPENDENT)), ann(OBSERVES, "notifyObserver", new AnnotationValue.StringVal("IF_EXISTS")), false),
                Arguments.of(List.of(ann(DEPENDENT)), ann(OBSERVES), false));
    }

    @ParameterizedTest(name = "[{index}] error expected: {2}")
    @MethodSource("conditionalObservers")
    @DisplayName("a conditional observer is a definition error on a bean that is, or defaults to, @Dependent")
    void conditionalObserver(List<AnnotationInfo> beanAnnotations, AnnotationInfo observes, boolean error) {
        var observer = method("onEvent", List.of(), parameter(new TypeInfo.ClassType(DotName.of("java.lang.String")), observes));

        var errors = validate(type(BEAN, ClassInfo.ClassKind.CLASS, List.of(observer), beanAnnotations));

        assertEquals(error, errors.stream().anyMatch(e -> e.startsWith("Conditional observer on @Dependent bean")),
                errors::toString);
    }

    static Stream<Arguments> namedParameters() {
        return Stream.of(
                Arguments.of(ann(NAMED), true),
                Arguments.of(ann(NAMED, VALUE, new AnnotationValue.StringVal("")), true),
                Arguments.of(ann(NAMED, VALUE, new AnnotationValue.StringVal("given")), false),
                Arguments.of(ann(NAMED, VALUE, new AnnotationValue.IntVal(1)), false));
    }

    @ParameterizedTest(name = "[{index}] error expected: {1}")
    @MethodSource("namedParameters")
    @DisplayName("an initializer parameter's @Named must give a non-empty value")
    void namedInitializerParameter(AnnotationInfo named, boolean error) {
        var initializer = method("init", List.of(ann("jakarta.inject.Inject")),
                parameter(new TypeInfo.ClassType(DotName.of("java.lang.String")), named));

        var errors = validate(type(BEAN, ClassInfo.ClassKind.CLASS, List.of(initializer), List.of(ann(DEPENDENT))));

        assertEquals(error, errors.stream().anyMatch(e -> e.startsWith("@Named without value on non-field injection point")),
                errors::toString);
    }

    @Test
    @DisplayName("an initializer parameter of a type variable type is a definition error")
    void typeVariableInitializerParameter() {
        var initializer = method("init", List.of(ann("jakarta.inject.Inject")),
                parameter(new TypeInfo.TypeVariable("T", List.of())));

        var errors = validate(type(BEAN, ClassInfo.ClassKind.CLASS, List.of(initializer), List.of(ann(DEPENDENT))));

        assertTrue(errors.stream().anyMatch(e -> e.startsWith("Injection point has type variable type")), errors::toString);
    }

    @Test
    @DisplayName("stereotype priorities that differ conflict; a non-int @Priority on a stereotype counts for nothing")
    void stereotypePriorities() {
        var low = type("com.example.Low", ClassInfo.ClassKind.ANNOTATION, List.of(),
                List.of(ann(STEREOTYPE), ann(PRIORITY, VALUE, new AnnotationValue.IntVal(1))));
        var high = type("com.example.High", ClassInfo.ClassKind.ANNOTATION, List.of(),
                List.of(ann(STEREOTYPE), ann(PRIORITY, VALUE, new AnnotationValue.IntVal(2))));
        var odd = type("com.example.Odd", ClassInfo.ClassKind.ANNOTATION, List.of(),
                List.of(ann(STEREOTYPE), ann(PRIORITY, VALUE, new AnnotationValue.StringVal("3"))));
        var conflicting = type(BEAN, ClassInfo.ClassKind.CLASS, List.of(),
                List.of(ann("com.example.Low"), ann("com.example.High")));
        var single = type("com.example.Single", ClassInfo.ClassKind.CLASS, List.of(),
                List.of(ann("com.example.Low"), ann("com.example.Odd")));

        var errors = validate(low, high, odd, conflicting, single);

        assertEquals(1, errors.size(), errors::toString);
        assertTrue(errors.getFirst().contains("has conflicting @Priority values from stereotypes"), errors::toString);
    }
}
