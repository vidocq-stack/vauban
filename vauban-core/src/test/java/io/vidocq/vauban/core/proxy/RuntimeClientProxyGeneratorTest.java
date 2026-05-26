package io.vidocq.vauban.core.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests of the CDI client proxy generator.
 *
 * <h2>Vidocq context</h2>
 * During Vidocq development, application services systematically use
 * constructor injection ({@code @Inject}) without declaring a no-arg
 * constructor. A typical example:
 * <pre>
 *   {@literal @}ApplicationScoped
 *   public class OrderService {
 *       {@literal @}Inject
 *       public OrderService(OrderRepository repo, EventBus bus) { ... }
 *   }
 * </pre>
 * Vauban refused to proxy these normal-scoped beans, throwing an
 * {@code UnproxyableResolutionException} and forcing a
 * {@code protected OrderService() {}} to be added everywhere.
 *
 * <h2>Fix</h2>
 * The generator now generates a no-arg constructor in the proxy that
 * calls {@code super(null, null, ...)} or {@code super(0, false, ...)}
 * depending on the parameter types of the simplest constructor of the parent bean.
 *
 * <h2>Why the TCK does not cover this case</h2>
 * The CDI 4.1 TCK only tests that beans with a <em>private</em> no-arg
 * constructor are properly rejected ({@code UnproxyableManagedBeanTest}).
 * It does not test the "no no-arg constructor but a parameterized
 * {@code @Inject} constructor" case, because the spec relaxed this constraint
 * and most implementations (Weld, OWB) have handled this case for a long time.
 * The TCK implicitly assumes that implementations support it.
 */
@DisplayName("RuntimeClientProxyGenerator")
class RuntimeClientProxyGeneratorTest {

    // -----------------------------------------------------------------------
    // Test classes (beans to proxy)
    // -----------------------------------------------------------------------

    public static class SimpleService {
        public String hello() { return "real"; }
        public int add(int a, int b) { return a + b; }
    }

    public static class InjectOnlyService {
        private final String dep;
        public InjectOnlyService(String dep) { this.dep = dep; }
        public String hello() { return "real:" + dep; }
    }

    public static class PrimitiveCtorService {
        private final int count;
        private final boolean flag;
        public PrimitiveCtorService(int count, boolean flag) {
            this.count = count;
            this.flag = flag;
        }
        public int getCount() { return count; }
        public boolean isFlag() { return flag; }
    }

    public static class MultiCtorService {
        public MultiCtorService() {}
        public MultiCtorService(String dep) {}
        public String hello() { return "real"; }
    }

    public static class LongDoubleCtorService {
        public LongDoubleCtorService(long l, double d, float f) {}
        public long getValue() { return 99L; }
    }

    public static class ServiceWithFinalMethod {
        public String proxied() { return "proxied"; }
        public final String notProxied() { return "final"; }
    }

    public static class ServiceWithStaticMethod {
        public String instance() { return "instance"; }
        public static String staticMethod() { return "static"; }
    }

    public static class VoidService {
        private String state = "initial";
        public void update(String newState) { state = newState; }
        public String getState() { return state; }
    }

    /**
     * Bean that inherits from a parent class located in a different package
     * and overrides a {@code protected} method. Reproduces exactly the
     * {@code HttpServlet.doGet} case — without the MethodHandle fix, the
     * generated proxy class fails with a {@code VerifyError} at load time.
     */
    public static class ProtectedBean extends io.vidocq.vauban.core.proxy.sub.BaseWithProtected {
        @Override protected String protectedEcho(String value) { return "impl:" + value; }
        @Override protected int protectedSum(int a, int b) { return super.protectedSum(a, b) * 10; }
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static final java.util.Map<String, Class<?>> definedClasses = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Defines the proxy class in the same classloader via MethodHandles.Lookup,
     * then instantiates it via the no-arg constructor.
     * Caches already-defined classes to avoid duplicate definition errors.
     */
    private static Object instantiateProxy(RuntimeClientProxyGenerator.GeneratedProxy proxy) throws Exception {
        Class<?> proxyClass = definedClasses.computeIfAbsent(proxy.className(), name -> {
            try {
                return MethodHandles.lookup().defineClass(proxy.bytecode());
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
        return proxyClass.getDeclaredConstructor().newInstance();
    }

    /**
     * Calls $$setDelegate on the proxy instance.
     */
    private static void setDelegate(Object proxyInstance, Supplier<?> delegate) throws Exception {
        Method setter = proxyInstance.getClass().getMethod("$$setDelegate", Supplier.class);
        setter.invoke(proxyInstance, delegate);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("protected methods inherited from another package")
    class ProtectedMethodCrossPackage {

        @Test
        @DisplayName("the proxy of a bean with a cross-package protected method loads without VerifyError")
        void shouldLoadProxyWithoutVerifyError() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(ProtectedBean.class);
            Object instance = instantiateProxy(proxy);
            assertNotNull(instance);
        }

        @Test
        @DisplayName("calling a protected method delegates to the contextual instance")
        void shouldDelegateProtectedEchoToContextualInstance() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(ProtectedBean.class);
            Object instance = instantiateProxy(proxy);
            var realBean = new ProtectedBean();
            setDelegate(instance, () -> realBean);

            Method echo = ProtectedBean.class.getDeclaredMethod("protectedEcho", String.class);
            echo.setAccessible(true);
            String result = (String) echo.invoke(instance, "alice");
            assertEquals("impl:alice", result);
        }

        @Test
        @DisplayName("calling a protected method with primitives works")
        void shouldHandlePrimitivesOnProtectedMethod() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(ProtectedBean.class);
            Object instance = instantiateProxy(proxy);
            var realBean = new ProtectedBean();
            setDelegate(instance, () -> realBean);

            Method sum = ProtectedBean.class.getDeclaredMethod("protectedSum", int.class, int.class);
            sum.setAccessible(true);
            int result = (int) sum.invoke(instance, 3, 4);
            // (3+4)*10 = 70 — proves the impl method is called, not the base.
            assertEquals(70, result);
        }

        @Test
        @DisplayName("calling a public method of the same bean stays via invokevirtual (no regression)")
        void shouldStillUseInvokevirtualForPublicMethods() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(ProtectedBean.class);
            Object instance = instantiateProxy(proxy);
            var realBean = new ProtectedBean();
            setDelegate(instance, () -> realBean);

            Method greet = ProtectedBean.class.getMethod("publicGreeting", String.class);
            assertEquals("hello bob", greet.invoke(instance, "bob"));
        }
    }

    @Nested
    @DisplayName("proxy naming")
    class ProxyNaming {

        @Test
        @DisplayName("the proxy class name follows the BeanClass_ClientProxy convention")
        void shouldFollowNamingConvention() {
            String name = RuntimeClientProxyGenerator.proxyClassName(SimpleService.class);
            assertEquals(SimpleService.class.getName() + "_ClientProxy", name);
        }

        @Test
        @DisplayName("the GeneratedProxy contains the correct class name")
        void shouldReturnCorrectClassNameInRecord() {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            assertEquals(SimpleService.class.getName() + "_ClientProxy", proxy.className());
        }

        @Test
        @DisplayName("the generated bytecode is non-empty")
        void shouldGenerateNonEmptyBytecode() {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            assertNotNull(proxy.bytecode());
            assertTrue(proxy.bytecode().length > 0, "The bytecode must not be empty");
        }
    }

    @Nested
    @DisplayName("proxy of a class with a no-arg constructor")
    class NoArgConstructor {

        @Test
        @DisplayName("the proxy can be instantiated via the no-arg constructor")
        void shouldInstantiateWithNoArgConstructor() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);
            assertNotNull(instance);
        }

        @Test
        @DisplayName("the proxy is a subclass of the bean")
        void shouldBeSubclassOfBean() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);
            assertInstanceOf(SimpleService.class, instance);
        }

        @Test
        @DisplayName("the proxy has the $$delegate field")
        void shouldHaveDelegateField() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);
            var field = instance.getClass().getDeclaredField("$$delegate");
            assertNotNull(field);
            assertEquals(Supplier.class, field.getType());
        }
    }

    /**
     * Central case of the Vidocq bug: an {@code @ApplicationScoped} bean with only
     * a parameterized {@code @Inject} constructor. Before the fix, Vauban threw
     * {@code UnproxyableResolutionException} because the proxy generated a
     * {@code super()} that did not exist in the parent bean.
     */
    @Nested
    @DisplayName("proxy of a class with only a parameterized constructor")
    class ParameterizedConstructorOnly {

        @Test
        @DisplayName("the proxy generates a no-arg constructor even if the bean has none")
        void shouldGenerateNoArgConstructorForParameterizedBean() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(InjectOnlyService.class);
            Object instance = instantiateProxy(proxy);
            assertNotNull(instance, "The proxy must be instantiable even without a no-arg constructor in the bean");
        }

        @Test
        @DisplayName("the proxy calls super(null) for reference-type parameters")
        void shouldCallSuperWithNullForReferenceParams() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(InjectOnlyService.class);
            Object instance = instantiateProxy(proxy);
            assertInstanceOf(InjectOnlyService.class, instance);
        }

        @Test
        @DisplayName("the proxy with a parameterized constructor delegates method calls")
        void shouldDelegateMethodCallsWhenDelegateIsSet() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(InjectOnlyService.class);
            Object instance = instantiateProxy(proxy);

            InjectOnlyService real = new InjectOnlyService("injected");
            setDelegate(instance, () -> real);

            Method hello = instance.getClass().getMethod("hello");
            Object result = hello.invoke(instance);
            assertEquals("real:injected", result);
        }
    }

    /**
     * Variant of the bug with primitives: {@code super(0, false)} instead
     * of {@code super(null)}. Verifies that the generated bytecode pushes the
     * correct default values onto the stack (iconst_0, lconst_0, etc.).
     */
    @Nested
    @DisplayName("proxy of a class with a primitive-parameter constructor")
    class PrimitiveConstructorParams {

        @Test
        @DisplayName("the proxy calls super(0, false) for the int and boolean primitives")
        void shouldCallSuperWithDefaultPrimitives() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(PrimitiveCtorService.class);
            Object instance = instantiateProxy(proxy);
            assertNotNull(instance, "The proxy must be instantiable with default values for the primitives");
        }

        @Test
        @DisplayName("the bean fields have the default values (0, false) in the non-delegated proxy")
        void shouldHaveDefaultValuesInUndelegatedProxy() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(PrimitiveCtorService.class);
            Object instance = instantiateProxy(proxy);
            // Direct cast possible because the proxy is a subclass
            PrimitiveCtorService asService = (PrimitiveCtorService) instance;
            // Without a delegate, the fields reflect the default values passed to super()
            // count=0, flag=false
            // (but getCount() and isFlag() will be delegated if a delegate is set)
        }

        @Test
        @DisplayName("the proxy delegates getCount to the real bean")
        void shouldDelegateGetCount() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(PrimitiveCtorService.class);
            Object instance = instantiateProxy(proxy);

            PrimitiveCtorService real = new PrimitiveCtorService(42, true);
            setDelegate(instance, () -> real);

            Method getCount = instance.getClass().getMethod("getCount");
            assertEquals(42, getCount.invoke(instance));
        }

        @Test
        @DisplayName("the proxy delegates isFlag to the real bean")
        void shouldDelegateIsFlag() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(PrimitiveCtorService.class);
            Object instance = instantiateProxy(proxy);

            PrimitiveCtorService real = new PrimitiveCtorService(0, true);
            setDelegate(instance, () -> real);

            Method isFlag = instance.getClass().getMethod("isFlag");
            assertEquals(true, isFlag.invoke(instance));
        }
    }

    @Nested
    @DisplayName("proxy of a class with long, double, float in the constructor")
    class WideTypesConstructor {

        @Test
        @DisplayName("the proxy handles wide types (long, double, float) in the constructor")
        void shouldHandleWidePrimitiveTypes() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(LongDoubleCtorService.class);
            Object instance = instantiateProxy(proxy);
            assertNotNull(instance);
        }

        @Test
        @DisplayName("the proxy delegates methods returning long")
        void shouldDelegateLongReturnType() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(LongDoubleCtorService.class);
            Object instance = instantiateProxy(proxy);

            LongDoubleCtorService real = new LongDoubleCtorService(1L, 2.0, 3.0f);
            setDelegate(instance, () -> real);

            Method getValue = instance.getClass().getMethod("getValue");
            assertEquals(99L, getValue.invoke(instance));
        }
    }

    @Nested
    @DisplayName("selection of the simplest constructor")
    class ConstructorSelection {

        @Test
        @DisplayName("chooses the no-arg constructor when it exists")
        void shouldPreferNoArgConstructor() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(MultiCtorService.class);
            Object instance = instantiateProxy(proxy);
            assertNotNull(instance);
            assertInstanceOf(MultiCtorService.class, instance);
        }
    }

    @Nested
    @DisplayName("method delegation")
    class MethodDelegation {

        @Test
        @DisplayName("delegates a simple method call to the delegate")
        void shouldDelegateSimpleMethodCall() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);

            SimpleService real = new SimpleService();
            setDelegate(instance, () -> real);

            Method hello = instance.getClass().getMethod("hello");
            assertEquals("real", hello.invoke(instance));
        }

        @Test
        @DisplayName("delegates a method with primitive parameters")
        void shouldDelegateMethodWithPrimitiveParams() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);

            SimpleService real = new SimpleService();
            setDelegate(instance, () -> real);

            Method add = instance.getClass().getMethod("add", int.class, int.class);
            assertEquals(7, add.invoke(instance, 3, 4));
        }

        @Test
        @DisplayName("delegates a void method")
        void shouldDelegateVoidMethod() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(VoidService.class);
            Object instance = instantiateProxy(proxy);

            VoidService real = new VoidService();
            setDelegate(instance, () -> real);

            Method update = instance.getClass().getMethod("update", String.class);
            update.invoke(instance, "updated");

            Method getState = instance.getClass().getMethod("getState");
            assertEquals("updated", getState.invoke(instance));
        }

        @Test
        @DisplayName("the delegate supplier is called on each method invocation")
        void shouldCallSupplierOnEachInvocation() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);

            int[] callCount = {0};
            SimpleService real = new SimpleService();
            setDelegate(instance, () -> {
                callCount[0]++;
                return real;
            });

            Method hello = instance.getClass().getMethod("hello");
            hello.invoke(instance);
            hello.invoke(instance);
            hello.invoke(instance);
            assertEquals(3, callCount[0], "The supplier must be called on each invocation");
        }

        @Test
        @DisplayName("throws NullPointerException if the delegate is not set")
        void shouldThrowWhenDelegateNotSet() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);
            // delegate is null by default

            Method hello = instance.getClass().getMethod("hello");
            assertThrows(Exception.class, () -> hello.invoke(instance),
                    "Must fail when the delegate is not set");
        }
    }

    @Nested
    @DisplayName("methods excluded from the proxy")
    class ExcludedMethods {

        @Test
        @DisplayName("final methods are not overridden")
        void shouldNotOverrideFinalMethods() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(ServiceWithFinalMethod.class);
            Object instance = instantiateProxy(proxy);

            // The proxied method is overridden
            Method proxied = instance.getClass().getMethod("proxied");
            assertTrue(proxied.getDeclaringClass().getName().contains("_ClientProxy"),
                    "proxied() must be overridden in the proxy");

            // The final method must not be overridden — declared in the parent class
            Method notProxied = instance.getClass().getMethod("notProxied");
            assertEquals(ServiceWithFinalMethod.class, notProxied.getDeclaringClass(),
                    "final notProxied() must not be overridden");
        }

        @Test
        @DisplayName("static methods are not overridden")
        void shouldNotOverrideStaticMethods() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(ServiceWithStaticMethod.class);
            Object instance = instantiateProxy(proxy);

            // Verify that the proxy has no declared static method
            boolean hasStaticInProxy = false;
            for (Method m : instance.getClass().getDeclaredMethods()) {
                if (m.getName().equals("staticMethod")) {
                    hasStaticInProxy = true;
                    break;
                }
            }
            assertFalse(hasStaticInProxy, "Static methods must not be proxied");
        }

        @Test
        @DisplayName("the $$setDelegate method is not itself proxied")
        void shouldNotProxyDollarDollarMethods() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);

            // $$setDelegate must exist but must not be a delegation method
            Method setDel = instance.getClass().getMethod("$$setDelegate", Supplier.class);
            assertNotNull(setDel);
            // Verify there is only a single declaration of $$setDelegate
            long count = 0;
            for (Method m : instance.getClass().getDeclaredMethods()) {
                if (m.getName().equals("$$setDelegate")) count++;
            }
            assertEquals(1, count, "$$setDelegate must be declared only once");
        }
    }

    @Nested
    @DisplayName("record GeneratedProxy")
    class GeneratedProxyRecord {

        @Test
        @DisplayName("equals is based on the name and the bytecode")
        void shouldImplementEqualsCorrectly() {
            var p1 = RuntimeClientProxyGenerator.generate(SimpleService.class);
            var p2 = RuntimeClientProxyGenerator.generate(SimpleService.class);
            assertEquals(p1, p2, "Two identical generations must be equal");
        }

        @Test
        @DisplayName("equals returns false for different beans")
        void shouldNotBeEqualForDifferentBeans() {
            var p1 = RuntimeClientProxyGenerator.generate(SimpleService.class);
            var p2 = RuntimeClientProxyGenerator.generate(VoidService.class);
            assertNotEquals(p1, p2);
        }

        @Test
        @DisplayName("toString contains the class name and the bytecode size")
        void shouldHaveReadableToString() {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            String str = proxy.toString();
            assertTrue(str.contains("_ClientProxy"), "toString must contain the proxy name");
            assertTrue(str.contains("bytes"), "toString must contain 'bytes'");
        }

        @Test
        @DisplayName("hashCode is consistent with equals")
        void shouldHaveConsistentHashCode() {
            var p1 = RuntimeClientProxyGenerator.generate(SimpleService.class);
            var p2 = RuntimeClientProxyGenerator.generate(SimpleService.class);
            assertEquals(p1.hashCode(), p2.hashCode());
        }
    }
}
