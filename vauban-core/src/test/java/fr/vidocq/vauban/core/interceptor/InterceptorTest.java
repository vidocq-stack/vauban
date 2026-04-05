package fr.vidocq.vauban.core.interceptor;

import fr.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import fr.vidocq.vauban.core.bean.model.InterceptorDescriptor;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.model.*;
import fr.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("java:S2187") // Test scaffold — helpers and data classes for future tests
@DisplayName("Interceptor support")
class InterceptorTest {

    // --- Helper methods to build index entries ---

    /**
     * Creates a ClassInfo for an annotation that is an @InterceptorBinding.
     */
    static ClassInfo makeInterceptorBindingAnnotation(String name) {
        return new ClassInfo(
                DotName.of(name), DotName.of("java.lang.Object"), List.of(),
                0x2601, // PUBLIC + INTERFACE + ANNOTATION + ABSTRACT
                List.of(),
                List.of(),
                List.of(new AnnotationInfo(DotName.of("jakarta.interceptor.InterceptorBinding"), Map.of())),
                ClassKind.ANNOTATION
        );
    }

    /**
     * Creates a ClassInfo for an interceptor class.
     */
    static ClassInfo makeInterceptorClass(String name, String bindingAnnotation, int priority) {
        var annotations = new ArrayList<AnnotationInfo>();
        annotations.add(new AnnotationInfo(DotName.of("jakarta.interceptor.Interceptor"), Map.of()));
        annotations.add(new AnnotationInfo(DotName.of(bindingAnnotation), Map.of()));
        annotations.add(new AnnotationInfo(DotName.of("jakarta.annotation.Priority"),
                Map.of("value", new AnnotationValue.IntVal(priority))));

        var aroundInvokeMethod = new MethodInfo(
                "intercept",
                new TypeInfo.ClassType(DotName.of("java.lang.Object")),
                List.of(new ParameterInfo(
                        "ctx",
                        new TypeInfo.ClassType(DotName.of("jakarta.interceptor.InvocationContext")),
                        List.of()
                )),
                List.of(),
                0x0001, // PUBLIC
                List.of(new AnnotationInfo(DotName.of("jakarta.interceptor.AroundInvoke"), Map.of()))
        );

        return new ClassInfo(
                DotName.of(name), DotName.of("java.lang.Object"), List.of(),
                0x0001,
                List.of(),
                List.of(
                        new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of()),
                        aroundInvokeMethod
                ),
                annotations,
                ClassKind.CLASS
        );
    }

    @Nested
    @DisplayName("Interceptor discovery")
    class Discovery {

        @Test
        @DisplayName("discovers an interceptor with its binding")
        void shouldDiscoverInterceptor() {
            var builder = new IndexBuilder();
            builder.add(makeInterceptorBindingAnnotation("com.example.Logged"));
            builder.add(makeInterceptorClass("com.example.LoggingInterceptor", "com.example.Logged", 100));

            var discovery = new BeanDiscovery(builder.build());
            var interceptors = discovery.discoverInterceptors();

            assertEquals(1, interceptors.size());
            var interceptor = interceptors.getFirst();
            assertEquals(DotName.of("com.example.LoggingInterceptor"), interceptor.interceptorClass());
            assertEquals(Set.of(DotName.of("com.example.Logged")), interceptor.bindings());
            assertEquals("intercept", interceptor.aroundInvokeMethod());
            assertEquals(100, interceptor.priority());
        }

        @Test
        @DisplayName("ignores classes that are not @Interceptor")
        void shouldIgnoreNonInterceptorClasses() {
            var builder = new IndexBuilder();
            // A normal @ApplicationScoped class without @Interceptor
            builder.add(new ClassInfo(
                    DotName.of("com.example.NormalService"), DotName.of("java.lang.Object"), List.of(),
                    0x0001,
                    List.of(),
                    List.of(new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of())),
                    List.of(new AnnotationInfo(DotName.of("jakarta.enterprise.context.ApplicationScoped"), Map.of())),
                    ClassKind.CLASS
            ));

            var discovery = new BeanDiscovery(builder.build());
            var interceptors = discovery.discoverInterceptors();

            assertTrue(interceptors.isEmpty());
        }

        @Test
        @DisplayName("sorts interceptors by priority")
        void shouldSortByPriority() {
            var builder = new IndexBuilder();
            builder.add(makeInterceptorBindingAnnotation("com.example.Logged"));
            builder.add(makeInterceptorClass("com.example.HighPriority", "com.example.Logged", 200));
            builder.add(makeInterceptorClass("com.example.LowPriority", "com.example.Logged", 50));
            builder.add(makeInterceptorClass("com.example.MidPriority", "com.example.Logged", 100));

            var discovery = new BeanDiscovery(builder.build());
            var interceptors = discovery.discoverInterceptors();

            assertEquals(3, interceptors.size());
            assertEquals(50, interceptors.get(0).priority());
            assertEquals(100, interceptors.get(1).priority());
            assertEquals(200, interceptors.get(2).priority());
        }

        @Test
        @DisplayName("discovers interceptor with no @AroundInvoke method")
        void shouldDiscoverInterceptorWithNoAroundInvoke() {
            var builder = new IndexBuilder();
            builder.add(makeInterceptorBindingAnnotation("com.example.Logged"));

            // Interceptor without @AroundInvoke method
            var interceptorClass = new ClassInfo(
                    DotName.of("com.example.EmptyInterceptor"), DotName.of("java.lang.Object"), List.of(),
                    0x0001,
                    List.of(),
                    List.of(new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of())),
                    List.of(
                            new AnnotationInfo(DotName.of("jakarta.interceptor.Interceptor"), Map.of()),
                            new AnnotationInfo(DotName.of("com.example.Logged"), Map.of())
                    ),
                    ClassKind.CLASS
            );
            builder.add(interceptorClass);

            var discovery = new BeanDiscovery(builder.build());
            var interceptors = discovery.discoverInterceptors();

            assertEquals(1, interceptors.size());
            assertNull(interceptors.getFirst().aroundInvokeMethod());
        }

        @Test
        @DisplayName("identifies interceptor binding annotations")
        void shouldIdentifyInterceptorBindings() {
            var builder = new IndexBuilder();
            builder.add(makeInterceptorBindingAnnotation("com.example.Logged"));

            // Also add a non-binding annotation
            builder.add(new ClassInfo(
                    DotName.of("com.example.NotABinding"), DotName.of("java.lang.Object"), List.of(),
                    0x2601,
                    List.of(),
                    List.of(),
                    List.of(), // No @InterceptorBinding
                    ClassKind.ANNOTATION
            ));

            var discovery = new BeanDiscovery(builder.build());

            assertTrue(discovery.isInterceptorBinding(DotName.of("com.example.Logged")));
            assertFalse(discovery.isInterceptorBinding(DotName.of("com.example.NotABinding")));
            assertFalse(discovery.isInterceptorBinding(DotName.of("com.example.NonExistent")));
        }
    }

    @Nested
    @DisplayName("InterceptorManager")
    class ManagerTest {

        @Test
        @DisplayName("resolves interceptors matching bindings")
        void shouldResolveMatchingInterceptors() {
            var descriptor = new InterceptorDescriptor(
                    DotName.of("com.example.LoggingInterceptor"),
                    Set.of(DotName.of("com.example.Logged")),
                    "intercept",
                    100
            );
            var manager = new InterceptorManager(List.of(descriptor));

            var resolved = manager.resolveInterceptors(Set.of(DotName.of("com.example.Logged")));
            assertEquals(1, resolved.size());
            assertEquals(descriptor, resolved.getFirst());
        }

        @Test
        @DisplayName("does not resolve interceptors with non-matching bindings")
        void shouldNotResolveNonMatchingInterceptors() {
            var descriptor = new InterceptorDescriptor(
                    DotName.of("com.example.LoggingInterceptor"),
                    Set.of(DotName.of("com.example.Logged")),
                    "intercept",
                    100
            );
            var manager = new InterceptorManager(List.of(descriptor));

            var resolved = manager.resolveInterceptors(Set.of(DotName.of("com.example.Other")));
            assertTrue(resolved.isEmpty());
        }

        @Test
        @DisplayName("does not resolve interceptors with empty bindings")
        void shouldNotResolveEmptyBindings() {
            var descriptor = new InterceptorDescriptor(
                    DotName.of("com.example.LoggingInterceptor"),
                    Set.of(),
                    "intercept",
                    100
            );
            var manager = new InterceptorManager(List.of(descriptor));

            var resolved = manager.resolveInterceptors(Set.of(DotName.of("com.example.Logged")));
            assertTrue(resolved.isEmpty());
        }
    }

    @Nested
    @DisplayName("VaubanInvocationContext")
    class InvocationContextTest {

        @Test
        @DisplayName("proceed() invokes the target method when chain is empty")
        void shouldInvokeTargetMethod() throws Exception {
            var target = new SampleTarget();
            var method = SampleTarget.class.getMethod("greet", String.class);

            var ctx = new VaubanInvocationContext(target, method, new Object[]{"World"}, List.of());

            var result = ctx.proceed();
            assertEquals("Hello World", result);
        }

        @Test
        @DisplayName("proceed() chains interceptors correctly")
        void shouldChainInterceptors() throws Exception {
            var target = new SampleTarget();
            var method = SampleTarget.class.getMethod("greet", String.class);

            var interceptor1 = new SampleInterceptor("A");
            var interceptor2 = new SampleInterceptor("B");

            var aroundInvoke = SampleInterceptor.class.getMethod("aroundInvoke",
                    jakarta.interceptor.InvocationContext.class);

            var chain = List.of(
                    new VaubanInvocationContext.InterceptorInvocation(interceptor1, aroundInvoke),
                    new VaubanInvocationContext.InterceptorInvocation(interceptor2, aroundInvoke)
            );

            var ctx = new VaubanInvocationContext(target, method, new Object[]{"World"}, chain);
            var result = ctx.proceed();

            assertEquals("[A:[B:Hello World]]", result);
        }

        @Test
        @DisplayName("getTarget() returns the target object")
        void shouldReturnTarget() {
            var target = new SampleTarget();
            var method = SampleTarget.class.getMethods()[0];
            var ctx = new VaubanInvocationContext(target, method, new Object[0], List.of());

            assertSame(target, ctx.getTarget());
        }

        @Test
        @DisplayName("getMethod() returns the target method")
        void shouldReturnMethod() throws Exception {
            var target = new SampleTarget();
            var method = SampleTarget.class.getMethod("greet", String.class);
            var ctx = new VaubanInvocationContext(target, method, new Object[]{"X"}, List.of());

            assertEquals(method, ctx.getMethod());
        }

        @Test
        @DisplayName("setParameters() modifies the invocation parameters")
        void shouldModifyParameters() throws Exception {
            var target = new SampleTarget();
            var method = SampleTarget.class.getMethod("greet", String.class);
            var ctx = new VaubanInvocationContext(target, method, new Object[]{"Original"}, List.of());

            ctx.setParameters(new Object[]{"Modified"});
            var result = ctx.proceed();
            assertEquals("Hello Modified", result);
        }

        @Test
        @DisplayName("getContextData() returns a shared mutable map")
        void shouldShareContextData() throws Exception {
            var target = new SampleTarget();
            var method = SampleTarget.class.getMethod("greet", String.class);
            var ctx = new VaubanInvocationContext(target, method, new Object[]{"X"}, List.of());

            ctx.getContextData().put("key", "value");
            assertEquals("value", ctx.getContextData().get("key"));
        }
    }

    // --- Test helpers ---

    public static class SampleTarget {
        public String greet(String name) {
            return "Hello " + name;
        }
    }

    public static class SampleInterceptor {
        private final String tag;

        public SampleInterceptor(String tag) {
            this.tag = tag;
        }

        public Object aroundInvoke(jakarta.interceptor.InvocationContext ctx) throws Exception {
            var result = ctx.proceed();
            return "[" + tag + ":" + result + "]";
        }
    }
}
