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

import io.vidocq.vauban.api.ProxyLink;
import io.vidocq.vauban.core.bean.model.*;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.*;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDI 4.1 "Unproxyable bean types", checked against the <strong>index</strong>.
 *
 * <h2>Context (Vidocq/vauban#24)</h2>
 * The proxyability checks used to rely exclusively on {@code Class.forName} over the
 * thread-context classloader, silently skipping on {@code ClassNotFoundException}. At
 * compile time (classes being compiled are not loadable) and on the module path (app
 * modules invisible to the TCCL) the checks were inert: an unproxyable bean sailed
 * through validation and failed later with an NPE inside the generated proxy. The
 * validator must read the index first — reflection stays as a fallback only.
 *
 * <p>The class names below are deliberately unloadable ({@code com.acme.*}): a check
 * that passes here proves the index path, not the reflection one.
 */
@DisplayName("DeploymentValidator — unproxyable normal-scoped beans (index-based)")
class ProxyableBeanValidationTest {

    private static final int PUBLIC = 0x0001;
    private static final int PROTECTED = 0x0004;
    private static final int FINAL = 0x0010;

    // ---- Index-path cases (com.acme.* is not loadable: reflection cannot help) ------------------

    @Test
    @DisplayName("a normal-scoped bean with only parameterized constructors is a deployment error")
    void beanWithoutNoArgConstructorIsReported() {
        var bean = classInfo("com.acme.Oidc", PUBLIC,
                List.of(ctor(PUBLIC, param("com.acme.Config"))));

        var errors = validate(bean);

        // Distinct kind: the container start treats it as a DeploymentException, while the
        // processor downgrades it to a warning (the plugin weaves the marker at process-classes).
        assertTrue(errors.stream().anyMatch(e ->
                        e.kind() == DeploymentValidator.ValidationError.Kind.UNPROXYABLE_BEAN
                                && e.message().contains("constructor")),
                "expected an unproxyable-bean error, got: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.message().contains("ProxyLink")),
                "the error must point at the ProxyLink escape hatch: " + errors);
    }

    @Test
    @DisplayName("a ProxyLink marker constructor makes the same bean proxyable")
    void markerConstructorLiftsTheRestriction() {
        var bean = classInfo("com.acme.Oidc", PUBLIC,
                List.of(ctor(PUBLIC, param("com.acme.Config")),
                        ctor(PROTECTED, param(ProxyLink.CLASS_NAME))));

        var errors = validate(bean);

        assertTrue(errors.isEmpty(), "marker constructor must satisfy proxyability: " + errors);
    }

    @Test
    @DisplayName("a final normal-scoped bean class is a deployment error")
    void finalClassIsReported() {
        var bean = classInfo("com.acme.Oidc", PUBLIC | FINAL, List.of(ctor(PUBLIC)));

        var errors = validate(bean);

        assertTrue(errors.stream().anyMatch(e ->
                        e.kind() == DeploymentValidator.ValidationError.Kind.DEPLOYMENT_ERROR
                                && e.message().contains("final")),
                "expected a final-class deployment error, got: " + errors);
    }

    @Test
    @DisplayName("a non-private final method on a normal-scoped bean is a deployment error")
    void finalMethodIsReported() {
        var bean = classInfo("com.acme.Oidc", PUBLIC,
                List.of(ctor(PUBLIC), method("issuer", PUBLIC | FINAL)));

        var errors = validate(bean);

        assertTrue(errors.stream().anyMatch(e ->
                        e.kind() == DeploymentValidator.ValidationError.Kind.DEPLOYMENT_ERROR
                                && e.message().contains("final method")),
                "expected a final-method deployment error, got: " + errors);
    }

    @Test
    @DisplayName("a bean with a non-private no-arg constructor and no final member passes")
    void plainProxyableBeanPasses() {
        var bean = classInfo("com.acme.Oidc", PUBLIC,
                List.of(ctor(PUBLIC), method("issuer", PUBLIC)));

        var errors = validate(bean);

        assertTrue(errors.isEmpty(), "expected no errors, got: " + errors);
    }

    // ---- Reflection-fallback case (real class, absent from the index) ---------------------------

    /** Loadable by the TCCL, so the reflection fallback sees it; not registered in the index. */
    public static class MarkerOnly {
        public MarkerOnly(ProxyLink link) {
        }

        public String id() {
            return "x";
        }
    }

    @Test
    @DisplayName("the reflection fallback also accepts the ProxyLink marker constructor")
    void reflectionFallbackAcceptsTheMarker() {
        String fqn = MarkerOnly.class.getName();
        var beans = List.of(
                makeBean(fqn, List.of()),
                makeBean("com.acme.Consumer", List.of(injectionPointOn(fqn))));
        var resolver = new BeanResolver(beans, new AssignabilityRules(index()));
        var validator = new DeploymentValidator(beans, resolver, index());

        var errors = validator.validate();

        assertTrue(errors.isEmpty(),
                "reflection fallback must accept a marker-only bean: " + errors);
    }

    // ---- Harness --------------------------------------------------------------------------------

    /** Runs the validator over {@code beanClass} injected into a consumer, index-registered. */
    private static List<DeploymentValidator.ValidationError> validate(ClassInfo beanClass) {
        var index = index(beanClass);
        var beans = List.of(
                makeBean(beanClass.name().value(), List.of()),
                makeBean("com.acme.Consumer", List.of(injectionPointOn(beanClass.name().value()))));
        var resolver = new BeanResolver(beans, new AssignabilityRules(index));
        return new DeploymentValidator(beans, resolver, index).validate();
    }

    private static VaubanIndex index(ClassInfo... classes) {
        var builder = new IndexBuilder();
        builder.add(new ClassInfo(DotName.of("java.lang.Object"), null, List.of(),
                PUBLIC, List.of(), List.of(), List.of(), ClassKind.CLASS));
        for (var c : classes) builder.add(c);
        return builder.build();
    }

    private static BeanDescriptor makeBean(String name, List<InjectionPointInfo> ips) {
        return new BeanDescriptor(
                BeanId.of(DotName.of(name)), DotName.of(name), BeanDescriptor.BeanKind.MANAGED,
                Set.of(new TypeInfo.ClassType(DotName.of(name)),
                        new TypeInfo.ClassType(DotName.of("java.lang.Object"))),
                Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY),
                ScopeInfo.APPLICATION, false, 0, ips, null);
    }

    private static InjectionPointInfo injectionPointOn(String typeName) {
        return new InjectionPointInfo(
                new TypeInfo.ClassType(DotName.of(typeName)),
                Set.of(QualifierInstance.DEFAULT),
                InjectionPointInfo.InjectionKind.FIELD,
                "field Consumer.target");
    }

    private static ClassInfo classInfo(String fqn, int accessFlags, List<MethodInfo> methods) {
        return new ClassInfo(DotName.of(fqn), DotName.of("java.lang.Object"), List.of(),
                accessFlags, List.of(), methods, List.of(), ClassKind.CLASS);
    }

    private static MethodInfo ctor(int accessFlags, ParameterInfo... params) {
        return new MethodInfo("<init>", new TypeInfo.ClassType(DotName.of("void")),
                List.of(params), List.of(), accessFlags, List.of());
    }

    private static MethodInfo method(String name, int accessFlags) {
        return new MethodInfo(name, new TypeInfo.ClassType(DotName.of("java.lang.String")),
                List.of(), List.of(), accessFlags, List.of());
    }

    private static ParameterInfo param(String typeName) {
        return new ParameterInfo("p", new TypeInfo.ClassType(DotName.of(typeName)), List.of());
    }
}
