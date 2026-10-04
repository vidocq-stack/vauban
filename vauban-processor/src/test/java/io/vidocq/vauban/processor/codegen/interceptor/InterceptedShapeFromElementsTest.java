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
package io.vidocq.vauban.processor.codegen.interceptor;

import io.vidocq.vauban.core.interceptor.InterceptedShape;
import io.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator;
import io.vidocq.vauban.core.interceptor.MethodShape;
import io.vidocq.vauban.core.interceptor.TypeRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.ProcessingEnvironment;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-check: verifies that {@link InterceptedShapeFromElements#from} yields the same
 * set of interceptable method signatures as
 * {@link InterceptorSubclassGenerator#fromClass(Class)} for a shared hierarchy fixture.
 *
 * <p>Uses an in-process javac compilation to obtain a live {@link TypeElement} and
 * {@link Elements}/{@link Types} utility, mirroring the pattern used in
 * {@code ComponentProviderCompileTimeTest}.
 */
@DisplayName("InterceptedShapeFromElements — cross-check against fromClass")
class InterceptedShapeFromElementsTest {

    // ---- Fixture hierarchy ----

    /**
     * Superclass with a public non-final method — must appear via {@code getAllMembers} inherited path.
     */
    public static class FixtureSuper {
        public String inheritedMethod() { return "super"; }
        public final String finalInherited() { return "final"; } // must be excluded
    }

    /**
     * Bean that:
     * - overrides {@code inheritedMethod()}
     * - declares a void no-arg method
     * - has a method with long+double (category-2) params
     * - has an int-returning method
     * - has a String[]-array-param method
     * - has a method that should be excluded: @AroundInvoke
     */
    public static class FixtureBean extends FixtureSuper {

        public void voidNoArg() {}

        public void primitiveParams(boolean b, byte by, char c, short s, int i, long l, float f, double d) {}

        public int intReturn(int x) { return x; }

        public String arrayParam(String[] values) { return ""; }

        @Override
        public String inheritedMethod() { return "overridden"; }

        @jakarta.interceptor.AroundInvoke
        public Object aroundInvoke(jakarta.interceptor.InvocationContext ctx) throws Exception {
            return ctx.proceed();
        }

        @jakarta.inject.Inject
        public void injectInitializer(Object dep) {}
    }

    // ---- Test ----

    @Test
    @DisplayName("fromElements and fromClass agree on the interceptable method set")
    void methodSetsAgree() throws Exception {
        assertSameMethodSet(FixtureBean.class);
    }

    /** Non-public methods of a same-package superclass, and of one in another package. */
    public static class NonPublicBase extends io.vidocq.vauban.processor.fixture.colocated.ForeignInterceptedBase {
        protected String inheritedProtected() { return "prot"; }
        String inheritedPackagePrivate() { return "pp"; }
        private String inheritedPrivate() { return "private"; }
        protected String overriddenProtected() { return "base"; }
    }

    /** Inherits every kind of non-public method; overrides one with a {@code final} declaration. */
    public static class NonPublicBean extends NonPublicBase {
        public String own() { return "own"; }
        @Override
        protected final String overriddenProtected() { return "bean"; }
    }

    @Test
    @DisplayName("fromClass intercepts inherited non-public methods exactly as fromElements does (BUG-20261004-03)")
    void inheritedNonPublicMethodsAgree() throws Exception {
        Set<String> fromClass = assertSameMethodSet(NonPublicBean.class);
        // Business methods are the non-private, non-static ones the bean inherits: a protected one
        // wherever it is declared, a package-private one only from its own package (JLS 8.4.8) —
        // a subclass in another package could not override it. A final override hides the rest.
        assertEquals(Set.of("own()", "inheritedProtected()", "inheritedPackagePrivate()", "foreignProtected()"),
                fromClass);
    }

    /** A plain superclass whose method implements {@code GenericLabel<String>.label(T)} for its subclass. */
    public static class PlainLabelBase {
        public String label(String value) { return "plain " + value; }
    }

    public interface GenericLabel<T> {
        default String label(T value) { return "label " + value; }
    }

    public static class LabelledBySuperclass extends PlainLabelBase implements GenericLabel<String> {
    }

    public static class GenericRelabelBase<T> {
        public String relabel(T value) { return "base " + value; }
    }

    /** Overrides the generic {@link GenericRelabelBase#relabel} with the type it binds. */
    public static class OverridingRelabel extends GenericRelabelBase<String> {
        @Override
        public String relabel(String value) { return "child " + value; }
    }

    @Test
    @DisplayName("two declarations that are the same member of the bean give one method (BUG-20261004-06)")
    void oneMethodPerMemberSignature() throws Exception {
        // PlainLabelBase.label(String) implements GenericLabel.label(T) for LabelledBySuperclass:
        // getAllMembers lists both, and keyed on the erased declarations they made two methods that
        // the source renderer declared as the same label(String).
        assertEquals(Set.of("label(java.lang.String)"), assertSameMethodSet(LabelledBySuperclass.class));
        assertEquals(Set.of("relabel(java.lang.String)"), assertSameMethodSet(OverridingRelabel.class));
    }

    /** A default method with the descriptor of the public {@link PlainLabelBase#label(String)}. */
    public interface PlainLabel {
        default String label(String value) { return "plain-label " + value; }
    }

    public static class PlainLabelledBySuperclass extends PlainLabelBase implements PlainLabel {
    }

    @Test
    @DisplayName("a public superclass method with a default method's descriptor is no shadow: one method")
    void inheritedClassMethodIsNoShadow() throws Exception {
        assertEquals(Set.of("label(java.lang.String)"), assertSameMethodSet(PlainLabelledBySuperclass.class));
    }

    /** A private method with the signature of {@link ShadowHider#hidden()}. */
    public static class PrivateHiddenBase {
        @SuppressWarnings("unused")
        private String hidden() { return "private"; }
    }

    public interface ShadowHider {
        default String hidden() { return "default"; }
    }

    public static class ShadowedBean extends PrivateHiddenBase implements ShadowHider {
    }

    /** Inherits a shadowed default whose only interface is package-private in another package. */
    public static class CarriedBean extends io.vidocq.vauban.processor.fixture.colocated.DefaultCarrier {
        public String own() { return "own"; }
    }

    @Test
    @DisplayName("a shadowed default method is reached through its interface, or left out (BUG-20261004-08)")
    void shadowedDefaultMethods() throws Exception {
        // super.hidden() would resolve to the private PrivateHiddenBase.hidden(): both front-ends
        // mark the method so that the bridge calls ShadowHider's default explicitly.
        assertEquals(Set.of("hidden()@" + ShadowHider.class.getName()), assertSameMethodSet(ShadowedBean.class));
        // No interface CarriedBean can name carries carried(): neither front-end intercepts it.
        assertEquals(Set.of("own()"), assertSameMethodSet(CarriedBean.class));
    }

    // ---- helpers ----

    /** The run-time and the processor front-ends select the same methods of {@code fixture}. */
    private Set<String> assertSameMethodSet(Class<?> fixture) throws Exception {
        // Compute the expected set from the runtime Class path
        InterceptedShape fromClassShape = InterceptorSubclassGenerator.fromClass(fixture);
        Set<String> expectedKeys = fromClassShape.methods().stream()
                .map(InterceptedShapeFromElementsTest::methodKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Compute the actual set from the Elements path (in-process javac)
        Set<String> actualKeys = computeFromElements(fixture);

        assertEquals(expectedKeys, actualKeys,
                "fromElements and fromClass disagree on interceptable methods.\n" +
                "fromClass only: " + difference(expectedKeys, actualKeys) + "\n" +
                "fromElements only: " + difference(actualKeys, expectedKeys));
        return expectedKeys;
    }

    /** {@code name(params)}, then {@code @<interface>} when the bridge calls that interface's default explicitly. */
    static String methodKey(MethodShape m) {
        String params = m.params().stream()
                .map(TypeRef::toString)
                .collect(Collectors.joining(","));
        return m.name() + "(" + params + ")" + (m.defaultOwner() != null ? "@" + m.defaultOwner() : "");
    }

    private static <T> Set<T> difference(Set<T> a, Set<T> b) {
        var diff = new LinkedHashSet<>(a);
        diff.removeAll(b);
        return diff;
    }

    /**
     * Spins up an in-process javac compilation over the fixture source to obtain
     * live {@link TypeElement} and {@link Elements}/{@link Types} utilities, then
     * calls {@link InterceptedShapeFromElements#from}.
     */
    private Set<String> computeFromElements(Class<?> fixture) throws Exception {
        // We build the source for FixtureBean and FixtureSuper from their class names
        // by referencing the already-compiled classes on the classpath.
        // Rather than re-compiling the fixture source, we use a "no-op" annotation
        // processor that captures the Elements/Types environment, then calls our method.

        var captured = new AtomicReference<Set<String>>();

        // Source to trigger processing: a stub that imports the fixture class
        String source = "package crosscheck; @CrossCheckTrigger class Trigger {}";
        String triggerSource = "package crosscheck; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) @interface CrossCheckTrigger {}";

        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        var triggerFile = new SimpleJavaFileObject(
                URI.create("file:///CrossCheckTrigger.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return triggerSource;
            }
        };
        var sourceFile = new SimpleJavaFileObject(
                URI.create("file:///Trigger.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        var options = List.of(
                "-proc:only",
                "--release", "25",
                "-classpath", buildClasspath());

        try (var fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, fm, diagnostics, options, null,
                    List.of(triggerFile, sourceFile));

            task.setProcessors(List.of(new CaptureProcessor(fixture, captured)));
            task.call();
        }

        Set<String> result = captured.get();
        assertNotNull(result, "Processor did not capture the shape — compilation may have failed. " +
                "Diagnostics: " + diagnostics.getDiagnostics().stream()
                        .map(d -> d.getKind() + ": " + d.getMessage(null))
                        .collect(Collectors.joining("; ")));
        return result;
    }

    /**
     * A minimal annotation processor that, when it sees {@code @CrossCheckTrigger},
     * captures the Elements/Types environment and calls {@link InterceptedShapeFromElements#from}
     * on the fixture.
     */
    private static class CaptureProcessor extends javax.annotation.processing.AbstractProcessor {

        private final Class<?> fixture;
        private final AtomicReference<Set<String>> sink;

        CaptureProcessor(Class<?> fixture, AtomicReference<Set<String>> sink) {
            this.fixture = fixture;
            this.sink = sink;
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("crosscheck.CrossCheckTrigger");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends javax.lang.model.element.TypeElement> annotations,
                               javax.annotation.processing.RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) return false;
            Elements elements = processingEnv.getElementUtils();
            Types types = processingEnv.getTypeUtils();

            // getTypeElement takes the canonical qualified name (dots, no $) for nested types
            String fixtureBinaryName = fixture.getName().replace('$', '.');
            TypeElement beanElement = elements.getTypeElement(fixtureBinaryName);
            if (beanElement == null) {
                // Fixture not on the compilation classpath — skip
                return false;
            }

            InterceptedShape shape = InterceptedShapeFromElements.from(beanElement, elements, types);
            Set<String> keys = new LinkedHashSet<>();
            Set<String> memberSignatures = new LinkedHashSet<>();
            for (MethodShape m : shape.methods()) {
                keys.add(methodKey(m));
                // The source renderer declares each method with its member signature: two alike
                // would be "already defined". Surface them as a key no front-end ever produces.
                var member = m.name() + "(" + m.memberParams().stream().map(TypeRef::toString)
                        .collect(Collectors.joining(",")) + ")";
                if (!memberSignatures.add(member)) {
                    keys.add("DUPLICATE MEMBER SIGNATURE " + member);
                }
            }
            sink.set(keys);
            return false;
        }

        private static String methodKey(MethodShape m) {
            return InterceptedShapeFromElementsTest.methodKey(m);
        }
    }

    private static String buildClasspath() {
        var paths = new LinkedHashSet<String>();
        var cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            Collections.addAll(paths, cp.split(File.pathSeparator));
        }
        for (Class<?> clazz : List.of(FixtureBean.class, InterceptedShapeFromElements.class,
                InterceptorSubclassGenerator.class,
                jakarta.interceptor.AroundInvoke.class, jakarta.inject.Inject.class)) {
            try {
                var loc = clazz.getProtectionDomain().getCodeSource().getLocation();
                if (loc != null) paths.add(new File(loc.toURI()).getPath());
            } catch (Exception ignored) {
                // best-effort
            }
        }
        ModuleLayer.boot().configuration().modules().forEach(rm ->
                rm.reference().location().ifPresent(uri -> {
                    if ("file".equals(uri.getScheme())) {
                        try { paths.add(new File(uri).getPath()); }
                        catch (Exception ignored) {}
                    }
                }));
        return String.join(File.pathSeparator, paths);
    }
}
