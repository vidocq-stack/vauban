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
package io.vidocq.vauban.processor.codegen.proxy;

import io.vidocq.vauban.core.proxy.ClientProxyShape;
import io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-check between the two client-proxy code paths, which must not diverge: the APT/source path
 * ({@link ProducerProxyEligibility}, {@link ClientProxyShapeFromElements}) and the bytecode path
 * ({@link io.vidocq.vauban.core.proxy.ProducerProxyEligibility}, {@link RuntimeClientProxyGenerator}).
 *
 * <p>The bytecode path walks the superclass hierarchy; the source path historically looked only at
 * {@code getEnclosedElements()} — declared methods. That asymmetry silently produced a proxy which
 * neither overrides nor forwards an <em>inherited</em> method: invoking it on the client proxy runs
 * the superclass body against the proxy's own (default-initialised) state instead of the contextual
 * instance. This test pins the two paths together.
 *
 * <p>Deliberate residual divergence: the source path stops at {@code java.lang.Object}, whereas the
 * bytecode path also forwards {@code toString/equals/hashCode}. CDI 4.1 leaves the behaviour of
 * {@code Object} methods (except {@code toString}) undefined, so the comparison below excludes them.
 * And a method whose parameters or return type use a type variable the bean binds is declared, in
 * source, with the signature the bean sees ({@code echo(String)}), in bytecode with the erased
 * descriptor ({@code echo(Object)}): each is the only override its language accepts.
 */
@DisplayName("Client proxy — source and bytecode paths agree on inherited members")
class ClientProxyInheritanceCrossCheckTest {

    // ---- Fixtures: all bases are in this same package, so only visibility varies ----

    /** A clean, fully-public base: its method must be forwarded by the proxy. */
    public static class CleanBase {
        public String inherited() { return "base"; }
    }

    /** Eligible: everything overridable is public, declared or inherited. */
    public static class CleanChild extends CleanBase {
        public String own() { return "own"; }
    }

    /** A base with a public {@code final} method — CDI 4.1 §3.10 makes the subtype unproxyable. */
    public static class FinalBase {
        public final String pinned() { return "pinned"; }
    }

    /** Ineligible through inheritance only: nothing is final here. */
    public static class FinalChild extends FinalBase {
        public String own() { return "own"; }
    }

    /** A base with a package-private overridable method: not overridable across packages. */
    public static class HiddenBase {
        String packagePrivate() { return "hidden"; }
    }

    /** Ineligible through inheritance only. */
    public static class HiddenChild extends HiddenBase {
        public String own() { return "own"; }
    }

    // ---- Eligibility parity ----

    @Test
    @DisplayName("an inherited public final method makes the produced type ineligible")
    void inheritedFinalIsRejected() throws Exception {
        assertEquals(ProducerProxyEligibility.Reason.FINAL_VIRTUALS.name(),
                eligibilityFromElements(FinalChild.class),
                "the source path ignored a final method inherited from " + FinalBase.class.getSimpleName()
                        + " and would have emitted a proxy that silently fails to forward it");
    }

    @Test
    @DisplayName("an inherited package-private method makes the produced type ineligible")
    void inheritedPackagePrivateIsRejected() throws Exception {
        assertEquals(ProducerProxyEligibility.Reason.PACKAGE_PRIVATE_VIRTUALS.name(),
                eligibilityFromElements(HiddenChild.class));
    }

    @Test
    @DisplayName("a fully-public hierarchy stays eligible")
    void cleanHierarchyStaysEligible() throws Exception {
        assertEquals(ProducerProxyEligibility.Reason.ELIGIBLE.name(),
                eligibilityFromElements(CleanChild.class));
    }

    @Test
    @DisplayName("every verdict matches the bytecode path's verdict for the same type")
    void verdictsMatchTheBytecodePath() throws Exception {
        for (var fixture : List.of(CleanChild.class, FinalChild.class, HiddenChild.class)) {
            assertEquals(
                    io.vidocq.vauban.core.proxy.ProducerProxyEligibility.of(fixture).name(),
                    eligibilityFromElements(fixture),
                    "source and bytecode eligibility disagree on " + fixture.getSimpleName());
        }
    }

    // ---- Shape parity ----

    @Test
    @DisplayName("the source shape forwards inherited public methods, like the bytecode shape")
    void shapeIncludesInheritedMethods() throws Exception {
        Set<String> fromElements = shapeFromElements(CleanChild.class);
        assertTrue(fromElements.contains("inherited()"),
                "the proxy would not forward CleanBase.inherited(); it would run against the "
                        + "proxy's empty state. Shape was: " + fromElements);

        ClientProxyShape fromClass = RuntimeClientProxyGenerator.shapeOf(CleanChild.class);
        Set<String> expected = fromClass.methods().stream()
                .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                        .collect(Collectors.joining(",")) + ")")
                .filter(k -> !OBJECT_METHODS.contains(k))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(expected, fromElements, "source and bytecode shapes disagree");
    }

    /** Inherits a protected member from a superclass in another package. */
    public static class ForeignChild extends io.vidocq.vauban.processor.fixture.colocated.ForeignBase {
        public String own() { return "own"; }
    }

    @Test
    @DisplayName("the co-located shape forwards inherited non-public members, like the bytecode shape")
    void colocatedShapeMatchesTheBytecodePath() throws Exception {
        // A placed proxy (#42 Stage 4) lives in the produced type's own package. There an inherited
        // package-private member of a same-package superclass CAN be overridden, and an inherited
        // protected one can be forwarded through a MethodHandle. Leaving either out lets a class of
        // that package call it on the proxy and read the proxy's own empty state.
        Set<String> hidden = colocatedShapeFromElements(HiddenChild.class);
        assertTrue(hidden.contains("packagePrivate()"),
                "HiddenBase.packagePrivate() must be forwarded by a co-located proxy. Shape was: " + hidden);

        for (var fixture : List.of(HiddenChild.class, ForeignChild.class, CleanChild.class)) {
            ClientProxyShape fromClass = RuntimeClientProxyGenerator.shapeOf(fixture);
            Set<String> expected = fromClass.methods().stream()
                    .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                            .collect(Collectors.joining(",")) + ")" + (m.needsMethodHandle() ? "#mh" : ""))
                    .filter(k -> !OBJECT_METHODS.contains(k.replace("#mh", "")))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            assertEquals(expected, colocatedShapeFromElements(fixture),
                    "co-located source and bytecode shapes disagree on " + fixture.getSimpleName()
                            + " (method set or MethodHandle dispatch)");
        }
    }

    /** Default methods: one inherited as is, one overridden by the class, one re-declared lower. */
    public interface Greeting {
        default String greet() { return "hi"; }
        default String rebound() { return "iface"; }
        default String refined() { return "greeting"; }
    }

    /** Overrides {@link Greeting#refined()} with a default method of its own. */
    public interface RefinedGreeting extends Greeting {
        @Override
        default String refined() { return "refined"; }
    }

    public static class DefaultChild implements RefinedGreeting {
        public String own() { return "own"; }

        @Override
        public String rebound() { return "class"; }
    }

    @Test
    @DisplayName("every path forwards an inherited interface default method, once (BUG-20261004-02)")
    void defaultMethodsAreForwarded() throws Exception {
        // Not forwarding greet() lets a call on the proxy run the default body against the proxy
        // itself: the contextual instance and its interceptors are bypassed.
        ClientProxyShape fromClass = RuntimeClientProxyGenerator.shapeOf(DefaultChild.class);
        Set<String> bytecode = fromClass.methods().stream()
                .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                        .collect(Collectors.joining(",")) + ")" + (m.needsMethodHandle() ? "#mh" : ""))
                .filter(k -> !OBJECT_METHODS.contains(k))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> expected = Set.of("own()", "rebound()", "greet()", "refined()");
        assertEquals(expected, bytecode, "bytecode shape");
        assertEquals(fromClass.methods().size() - 3, expected.size(),
                "each signature once (plus the 3 Object methods): " + fromClass.methods());
        assertEquals(expected, shapeFromElements(DefaultChild.class), "source shape");
        assertEquals(expected, colocatedShapeFromElements(DefaultChild.class), "co-located shape");
    }

    /** A generic base and interface whose methods take their type variable. */
    public static class TaggedBase<T> {
        public String echo(T value) { return "echo " + value; }
    }

    public interface Tagged<T> {
        default String tag(T value) { return "tag " + value; }
    }

    public static class GenericChild extends TaggedBase<String> implements Tagged<String> {
    }

    @Test
    @DisplayName("the source shape overrides a generic method as the bean sees it, the bytecode shapes by descriptor")
    void genericMethodsUseTheMemberSignatureInSource() throws Exception {
        // Java source can only override echo(String) in a subclass of TaggedBase<String>: echo(Object)
        // is a name clash (BUG-20261004-06). A class file overrides by descriptor, echo(Object).
        assertEquals(Set.of("echo(java.lang.String)", "tag(java.lang.String)"),
                shapeFromElements(GenericChild.class), "source shape");
        Set<String> bytecode = Set.of("echo(java.lang.Object)", "tag(java.lang.Object)");
        assertEquals(bytecode, colocatedShapeFromElements(GenericChild.class), "co-located (bytecode) shape");
        assertEquals(bytecode, RuntimeClientProxyGenerator.shapeOf(GenericChild.class).methods().stream()
                .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                        .collect(Collectors.joining(",")) + ")")
                .filter(k -> !OBJECT_METHODS.contains(k))
                .collect(Collectors.toSet()), "run-time shape");
    }

    private Set<String> colocatedShapeFromElements(Class<?> fixture) throws Exception {
        String joined = capture(fixture, (element, env) -> {
            var shape = ClientProxyShapeFromElements.fromColocated(element, env.elements(), env.types());
            return shape.methods().stream()
                    .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                            .collect(Collectors.joining(",")) + ")" + (m.needsMethodHandle() ? "#mh" : ""))
                    .filter(k -> !OBJECT_METHODS.contains(k.replace("#mh", "")))
                    .collect(Collectors.joining(";"));
        });
        var set = new LinkedHashSet<String>();
        for (var part : joined.split(";")) {
            if (!part.isBlank()) set.add(part);
        }
        return set;
    }

    private static final Set<String> OBJECT_METHODS =
            Set.of("toString()", "hashCode()", "equals(java.lang.Object)");

    // ---- javac harness (mirrors InterceptedShapeFromElementsTest) ----

    private String eligibilityFromElements(Class<?> fixture) throws Exception {
        return capture(fixture, (element, env) ->
                ProducerProxyEligibility.of(element).name());
    }

    private Set<String> shapeFromElements(Class<?> fixture) throws Exception {
        String joined = capture(fixture, (element, env) -> {
            var shape = ClientProxyShapeFromElements.from(element, env.elements(), env.types());
            return shape.methods().stream()
                    .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                            .collect(Collectors.joining(",")) + ")")
                    .filter(k -> !OBJECT_METHODS.contains(k))
                    .collect(Collectors.joining(";"));
        });
        var set = new LinkedHashSet<String>();
        for (var part : joined.split(";")) {
            if (!part.isBlank()) set.add(part);
        }
        return set;
    }

    /** The Elements/Types pair handed to the callback. */
    record Env(Elements elements, Types types) {}

    /** Runs javac in-process over a trigger source and invokes {@code body} on the fixture's element. */
    private String capture(Class<?> fixture, BiFunction<TypeElement, Env, String> body) throws Exception {
        var captured = new AtomicReference<String>();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();

        var triggerAnnotation = sourceFile("ProxyCrossCheckTrigger.java",
                "package proxycrosscheck; import java.lang.annotation.*;"
                        + " @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)"
                        + " @interface ProxyCrossCheckTrigger {}");
        var trigger = sourceFile("Trigger.java",
                "package proxycrosscheck; @ProxyCrossCheckTrigger class Trigger {}");

        var options = List.of("-proc:only", "--release", "25", "-classpath", classpath());
        try (var fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, fm, diagnostics, options, null,
                    List.of(triggerAnnotation, trigger));
            task.setProcessors(List.of(new CaptureProcessor(fixture, body, captured)));
            task.call();
        }
        assertNotNull(captured.get(), "the processor captured nothing — compilation likely failed: "
                + diagnostics.getDiagnostics().stream()
                        .map(d -> d.getKind() + ": " + d.getMessage(null))
                        .collect(Collectors.joining("; ")));
        return captured.get();
    }

    private static JavaFileObject sourceFile(String name, String content) {
        return new SimpleJavaFileObject(URI.create("file:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    private static final class CaptureProcessor extends AbstractProcessor {
        private final Class<?> fixture;
        private final BiFunction<TypeElement, Env, String> body;
        private final AtomicReference<String> sink;

        CaptureProcessor(Class<?> fixture, BiFunction<TypeElement, Env, String> body,
                AtomicReference<String> sink) {
            this.fixture = fixture;
            this.body = body;
            this.sink = sink;
        }

        @Override public Set<String> getSupportedAnnotationTypes() {
            return Set.of("proxycrosscheck.ProxyCrossCheckTrigger");
        }

        @Override public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
            if (env.processingOver()) return false;
            var elements = processingEnv.getElementUtils();
            var types = processingEnv.getTypeUtils();
            var element = elements.getTypeElement(fixture.getName().replace('$', '.'));
            if (element == null) return false;
            sink.set(body.apply(element, new Env(elements, types)));
            return false;
        }
    }
}
