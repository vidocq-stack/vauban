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

    // ---- The shapes found by the reviews, each pinned on both front-ends ----

    public interface TagDefault {
        default String tag(String value) { return "default " + value; }
    }

    /** Inherits a package-private {@code tag(String)} of another package, under a default of the same descriptor. */
    public static class CrossPackageShadowedBean
            extends io.vidocq.vauban.processor.fixture.colocated.ForeignPackagePrivateTagBase implements TagDefault {
    }

    @Test
    @DisplayName("a package-private method of a superclass in another package is no member: the default is intercepted")
    void crossPackagePackagePrivateIsNoMember() throws Exception {
        // ForeignPackagePrivateTagBase.tag(String) is not inherited (JLS 8.4.8): the bean's member is
        // the default, reached through TagDefault since a super.tag(…) resolves to the other one.
        assertEquals(Set.of("tag(java.lang.String)@" + TagDefault.class.getName()),
                assertSameMethodSet(CrossPackageShadowedBean.class));
    }

    /** A generic bean: its subclass extends it raw, where every inherited member is erased. */
    public static class GenericNoShadow<X> implements GenericLabel<String> {
    }

    public static class GenericShadowed extends PrivateObjectLabel implements GenericLabel<String> {
    }

    /** A generic bean whose shadowed default comes from a generic interface. */
    public static class GenericShadowedGeneric<X> extends PrivateObjectLabel implements GenericLabel<String> {
    }

    @Test
    @DisplayName("a generic bean is extended raw: the source overrides the erased member (regression)")
    void genericBeanIsExtendedRaw() throws Exception {
        // label(String) is the member of GenericNoShadow<X>, but the subclass extends the raw
        // GenericNoShadow, whose member is label(Object): the override label(String) "does not
        // override or implement a method from a supertype". A non-generic bean keeps its bound
        // member signature (GenericShadowed: label(String) overrides, with the erased bridge).
        assertEquals(Set.of("label(java.lang.Object)"), assertSameMethodSet(GenericNoShadow.class));
        assertEquals(Set.of("label(java.lang.Object)"), memberSignatures(GenericNoShadow.class));
        var owner = "@" + GenericLabel.class.getName();
        assertEquals(Set.of("label(java.lang.Object)" + owner), assertSameMethodSet(GenericShadowed.class));
        assertEquals(Set.of("label(java.lang.String)"), memberSignatures(GenericShadowed.class));
        assertEquals(Set.of("label(java.lang.Object)" + owner), assertSameMethodSet(GenericShadowedGeneric.class));
        assertEquals(Set.of("label(java.lang.Object)"), memberSignatures(GenericShadowedGeneric.class));
    }

    /** Inherits a shadowed default whose member signature names a type this package cannot. */
    public static class HiddenArgBean extends io.vidocq.vauban.processor.fixture.colocated.HiddenArgumentBase {
        public String own() { return "own"; }
    }

    @Test
    @DisplayName("a shadowed default whose type argument cannot be named is left out of the source, intercepted in bytecode")
    void shadowedDefaultWithAnInaccessibleTypeArgument() throws Exception {
        // The member is label(HiddenArgument), HiddenArgument being package-private in another
        // package: the rendered subclass can neither declare that override nor list
        // HiddenLabeled<HiddenArgument>, so it leaves the method out — not intercepted — rather
        // than breaking the build. The run-time subclass overrides by descriptor, through the raw
        // interface: the one documented divergence between the two front-ends.
        var owner = io.vidocq.vauban.processor.fixture.colocated.HiddenLabeled.class.getName();
        assertEquals(Set.of("own()", "label(java.lang.Object)@" + owner),
                runtimeMethodSet(HiddenArgBean.class), "run-time shape");
        assertEquals(Set.of("own()"), computeFromElements(HiddenArgBean.class, KEYS), "processor shape");
    }

    /** A private nested type of this package, bound under a private shadow (BUG-20261004-09, n11). */
    public static class PrivateNestedShadowOuter {
        private static class Secret {
        }

        public static class ShadowedSecretBase extends PrivateObjectLabel implements GenericLabel<Secret> {
        }
    }

    /** Same package as {@link PrivateNestedShadowOuter}: inherits the shadowed member {@code label(Secret)}. */
    public static class PrivateNestedShadowedBean extends PrivateNestedShadowOuter.ShadowedSecretBase {
        public String own() { return "own"; }
    }

    @Test
    @DisplayName("a shadowed default whose type argument is a private nested type of the same package is left out of the source")
    void shadowedDefaultWithAPrivateNestedTypeArgument() throws Exception {
        // The member is label(Secret), and the subclass would list GenericLabel<Secret>: Secret is
        // private, so a generated top-level class of the same package cannot name it (JLS 6.6.1).
        assertEquals(Set.of("own()", "label(java.lang.Object)@" + GenericLabel.class.getName()),
                runtimeMethodSet(PrivateNestedShadowedBean.class), "run-time shape");
        assertEquals(Set.of("own()"), computeFromElements(PrivateNestedShadowedBean.class, KEYS), "processor shape");
        var report = onlyReport(PrivateNestedShadowedBean.class);
        assertTrue(report.contains("label(") && report.contains("Secret") && report.contains("keep it"), report);
    }

    @Test
    @DisplayName("the report of a default the source subclass leaves out compares it with the bytecode generators")
    void omittedDefaultReportsCompareTheGenerators() throws Exception {
        // No nameable interface: InterceptorSubclassGenerator leaves the method out too, so the
        // report must not announce a difference.
        var noOwner = onlyReport(CarriedBean.class);
        assertTrue(noOwner.contains("does not intercept") && noOwner.contains("carried()")
                && noOwner.contains("leave it out too"), noOwner);
        // Unnameable in source only: the bytecode generators override by descriptor and keep it.
        // The generated class is used on the class path and on the module path alike, so the
        // difference is between generators, not between the two paths.
        var unnameable = onlyReport(HiddenArgBean.class);
        assertTrue(unnameable.contains("does not intercept") && unnameable.contains("keep it"), unnameable);
        for (var report : List.of(noOwner, unnameable)) {
            assertTrue(report.contains("as on a plain instance of the bean"), report);
            assertFalse(report.contains("module path"), report);
        }
    }

    /** The one report {@link InterceptedShapeFromElements#from} gives for {@code fixture}. */
    private String onlyReport(Class<?> fixture) throws Exception {
        var omitted = new ArrayList<String>();
        computeFromElements(fixture, KEYS, omitted::add);
        assertEquals(1, omitted.size(), "one omitted method reported: " + omitted);
        return omitted.getFirst();
    }

    /** A private {@code label(Object)}: the erased descriptor of {@link GenericLabel#label(Object)}. */
    public static class PrivateObjectLabel {
        @SuppressWarnings("unused")
        private String label(Object value) { return "private " + value; }
    }

    /** A private shadow above a public member: the member wins, through {@code super}. */
    public static class PrivateTopLabel {
        @SuppressWarnings("unused")
        private String label(String value) { return "private " + value; }
    }

    public static class PublicMidLabel extends PrivateTopLabel {
        public String label(String value) { return "mid " + value; }
    }

    public static class MemberOverPrivate extends PublicMidLabel implements PlainLabel {
    }

    /** Two paths to {@code GenericLabel<String>}. */
    public interface SubLabel extends GenericLabel<String> {
    }

    public static class TwoPaths extends PrivateObjectLabel implements SubLabel, GenericLabel<String> {
    }

    /** The parameterisation comes through a generic superclass. */
    public static class MidLabel<T> extends PrivateObjectLabel implements GenericLabel<T> {
    }

    public static class ThroughMid extends MidLabel<Integer> {
    }

    public static class Holder {
        public static class Item {
        }
    }

    /** A wildcard and a nested type as the type argument. */
    public static class NestedArg extends PrivateObjectLabel
            implements GenericLabel<java.util.List<? extends Holder.Item>> {
    }

    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    public @interface NonEmpty {
    }

    /** A type-annotated type argument. */
    public static class AnnotatedArg extends PrivateObjectLabel implements GenericLabel<@NonEmpty String> {
    }

    @Test
    @DisplayName("the shadowed-default shapes found by the reviews: both front-ends agree")
    void reviewedShadowedDefaultShapes() throws Exception {
        assertEquals(Set.of("label(java.lang.String)"), assertSameMethodSet(MemberOverPrivate.class));
        var throughGenericLabel = Set.of("label(java.lang.Object)@" + GenericLabel.class.getName());
        for (var fixture : List.of(TwoPaths.class, ThroughMid.class, NestedArg.class, AnnotatedArg.class)) {
            assertEquals(throughGenericLabel, assertSameMethodSet(fixture), fixture.getSimpleName());
        }
        // The member signatures the source declares: Integer through MidLabel<Integer>, the
        // erasure of the wildcard for NestedArg.
        assertEquals(Set.of("label(java.lang.Integer)"), memberSignatures(ThroughMid.class));
        assertEquals(Set.of("label(java.util.List)"), memberSignatures(NestedArg.class));
        assertEquals(Set.of("label(java.lang.String)"), memberSignatures(AnnotatedArg.class));
    }

    // ---- helpers ----

    /** The run-time and the processor front-ends select the same methods of {@code fixture}. */
    private Set<String> assertSameMethodSet(Class<?> fixture) throws Exception {
        // Compute the expected set from the runtime Class path
        Set<String> expectedKeys = runtimeMethodSet(fixture);

        // Compute the actual set from the Elements path (in-process javac)
        Set<String> actualKeys = computeFromElements(fixture, KEYS);

        assertEquals(expectedKeys, actualKeys,
                "fromElements and fromClass disagree on interceptable methods.\n" +
                "fromClass only: " + difference(expectedKeys, actualKeys) + "\n" +
                "fromElements only: " + difference(actualKeys, expectedKeys));
        return expectedKeys;
    }

    /** The run-time front-end's keys, through the same duplicate detection as the processor's. */
    private static Set<String> runtimeMethodSet(Class<?> fixture) {
        return KEYS.apply(InterceptorSubclassGenerator.fromClass(fixture));
    }

    /** The member signatures the processor's source renderer declares the overrides with. */
    private Set<String> memberSignatures(Class<?> fixture) throws Exception {
        return computeFromElements(fixture, shape -> shape.methods().stream()
                .map(InterceptedShapeFromElementsTest::memberSignature)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    /**
     * The keys of a shape, with a key no front-end ever produces for a signature listed twice —
     * a subclass would declare it twice — whatever its {@code @owner} suffix, and for two methods
     * with the same member signature, which the source renderer would declare alike.
     */
    private static final java.util.function.Function<InterceptedShape, Set<String>> KEYS = shape -> {
        Set<String> keys = new LinkedHashSet<>();
        Set<String> signatures = new LinkedHashSet<>();
        Set<String> memberSignatures = new LinkedHashSet<>();
        for (MethodShape m : shape.methods()) {
            var key = methodKey(m);
            keys.add(key);
            if (!signatures.add(key.replaceAll("@.*$", ""))) {
                keys.add("DUPLICATE SIGNATURE " + key);
            }
            if (!memberSignatures.add(memberSignature(m))) {
                keys.add("DUPLICATE MEMBER SIGNATURE " + memberSignature(m));
            }
        }
        return keys;
    };

    /** {@code name(memberParams)}: the signature the source renderer declares the override with. */
    static String memberSignature(MethodShape m) {
        return m.name() + "(" + m.memberParams().stream().map(TypeRef::toString)
                .collect(Collectors.joining(",")) + ")";
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
    private Set<String> computeFromElements(Class<?> fixture,
            java.util.function.Function<InterceptedShape, Set<String>> keys) throws Exception {
        return computeFromElements(fixture, keys, omitted -> {});
    }

    /** The same, {@code omitted} receiving what the processor would report as left out. */
    private Set<String> computeFromElements(Class<?> fixture,
            java.util.function.Function<InterceptedShape, Set<String>> keys,
            java.util.function.Consumer<String> omitted) throws Exception {
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

            task.setProcessors(List.of(new CaptureProcessor(fixture, keys, captured, omitted)));
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
        private final java.util.function.Function<InterceptedShape, Set<String>> keys;
        private final AtomicReference<Set<String>> sink;
        private final java.util.function.Consumer<String> omitted;

        CaptureProcessor(Class<?> fixture, java.util.function.Function<InterceptedShape, Set<String>> keys,
                AtomicReference<Set<String>> sink, java.util.function.Consumer<String> omitted) {
            this.fixture = fixture;
            this.keys = keys;
            this.sink = sink;
            this.omitted = omitted;
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

            sink.set(keys.apply(InterceptedShapeFromElements.from(beanElement, elements, types, omitted)));
            return false;
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
