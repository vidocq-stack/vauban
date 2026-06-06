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
        // Compute the expected set from the runtime Class path
        InterceptedShape fromClassShape = InterceptorSubclassGenerator.fromClass(FixtureBean.class);
        Set<String> expectedKeys = fromClassShape.methods().stream()
                .map(InterceptedShapeFromElementsTest::methodKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Compute the actual set from the Elements path (in-process javac)
        Set<String> actualKeys = computeFromElements();

        assertEquals(expectedKeys, actualKeys,
                "fromElements and fromClass disagree on interceptable methods.\n" +
                "fromClass only: " + difference(expectedKeys, actualKeys) + "\n" +
                "fromElements only: " + difference(actualKeys, expectedKeys));
    }

    // ---- helpers ----

    private static String methodKey(MethodShape m) {
        String params = m.params().stream()
                .map(TypeRef::toString)
                .collect(Collectors.joining(","));
        return m.name() + "(" + params + ")";
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
    private Set<String> computeFromElements() throws Exception {
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

            task.setProcessors(List.of(new CaptureProcessor(captured)));
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
     * on {@link FixtureBean}.
     */
    private static class CaptureProcessor extends javax.annotation.processing.AbstractProcessor {

        private final AtomicReference<Set<String>> sink;

        CaptureProcessor(AtomicReference<Set<String>> sink) {
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
            String fixtureBinaryName = FixtureBean.class.getName().replace('$', '.');
            TypeElement beanElement = elements.getTypeElement(fixtureBinaryName);
            if (beanElement == null) {
                // Fixture not on the compilation classpath — skip
                return false;
            }

            InterceptedShape shape = InterceptedShapeFromElements.from(beanElement, elements, types);
            Set<String> keys = new LinkedHashSet<>();
            for (MethodShape m : shape.methods()) {
                keys.add(methodKey(m));
            }
            sink.set(keys);
            return false;
        }

        private static String methodKey(MethodShape m) {
            String params = m.params().stream()
                    .map(TypeRef::toString)
                    .collect(Collectors.joining(","));
            return m.name() + "(" + params + ")";
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
