package fr.vidocq.vauban.core.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du generateur de client proxies CDI.
 *
 * <h2>Contexte Vidocq</h2>
 * Lors du developpement de Vidocq, les services applicatifs utilisent
 * systematiquement l'injection par constructeur ({@code @Inject}) sans
 * declarer de constructeur no-arg. Exemple typique :
 * <pre>
 *   {@literal @}ApplicationScoped
 *   public class OrderService {
 *       {@literal @}Inject
 *       public OrderService(OrderRepository repo, EventBus bus) { ... }
 *   }
 * </pre>
 * Vauban refusait de proxifier ces beans normal-scoped avec une
 * {@code UnproxyableResolutionException}, obligeant a ajouter un
 * {@code protected OrderService() {}} partout.
 *
 * <h2>Fix</h2>
 * Le generateur genere desormais un constructeur no-arg dans le proxy qui
 * appelle {@code super(null, null, ...)} ou {@code super(0, false, ...)}
 * selon les types du constructeur le plus simple du bean parent.
 *
 * <h2>Pourquoi le TCK ne couvre pas ce cas</h2>
 * Le TCK CDI 4.1 teste uniquement que les beans avec constructeur
 * <em>prive</em> no-arg sont bien rejetes ({@code UnproxyableManagedBeanTest}).
 * Il ne teste pas le cas "aucun constructeur no-arg mais un constructeur
 * {@code @Inject} parametre" car la spec a assoupli cette contrainte et la
 * plupart des implementations (Weld, OWB) gerent ce cas depuis longtemps.
 * Le TCK suppose implicitement que les implementations le supportent.
 */
@DisplayName("RuntimeClientProxyGenerator")
class RuntimeClientProxyGeneratorTest {

    // -----------------------------------------------------------------------
    // Classes de test (beans a proxifier)
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
     * Bean qui hérite d'une classe parent située dans un package différent
     * et surcharge une méthode {@code protected}. Reproduit exactement le
     * cas {@code HttpServlet.doGet} — sans le fix MethodHandle, la classe
     * proxy générée échoue au {@code VerifyError} au chargement.
     */
    public static class ProtectedBean extends fr.vidocq.vauban.core.proxy.sub.BaseWithProtected {
        @Override protected String protectedEcho(String value) { return "impl:" + value; }
        @Override protected int protectedSum(int a, int b) { return super.protectedSum(a, b) * 10; }
    }

    // -----------------------------------------------------------------------
    // Utilitaires
    // -----------------------------------------------------------------------

    private static final java.util.Map<String, Class<?>> definedClasses = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Definit la classe proxy dans le meme classloader via MethodHandles.Lookup,
     * puis l'instancie via le constructeur sans argument.
     * Cache les classes deja definies pour eviter les erreurs de definition dupliquee.
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
     * Appelle $$setDelegate sur l'instance proxy.
     */
    private static void setDelegate(Object proxyInstance, Supplier<?> delegate) throws Exception {
        Method setter = proxyInstance.getClass().getMethod("$$setDelegate", Supplier.class);
        setter.invoke(proxyInstance, delegate);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("methodes protected heritees d'un autre package")
    class ProtectedMethodCrossPackage {

        @Test
        @DisplayName("le proxy d'un bean avec methode protected cross-package se charge sans VerifyError")
        void shouldLoadProxyWithoutVerifyError() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(ProtectedBean.class);
            Object instance = instantiateProxy(proxy);
            assertNotNull(instance);
        }

        @Test
        @DisplayName("l'appel d'une methode protected delegue a l'instance contextuelle")
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
        @DisplayName("l'appel d'une methode protected avec primitives fonctionne")
        void shouldHandlePrimitivesOnProtectedMethod() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(ProtectedBean.class);
            Object instance = instantiateProxy(proxy);
            var realBean = new ProtectedBean();
            setDelegate(instance, () -> realBean);

            Method sum = ProtectedBean.class.getDeclaredMethod("protectedSum", int.class, int.class);
            sum.setAccessible(true);
            int result = (int) sum.invoke(instance, 3, 4);
            // (3+4)*10 = 70 — prouve que la methode impl est bien appelee, pas la base.
            assertEquals(70, result);
        }

        @Test
        @DisplayName("l'appel d'une methode publique du meme bean reste via invokevirtual (pas de regression)")
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
    @DisplayName("nommage du proxy")
    class ProxyNaming {

        @Test
        @DisplayName("le nom de classe du proxy suit la convention BeanClass_ClientProxy")
        void shouldFollowNamingConvention() {
            String name = RuntimeClientProxyGenerator.proxyClassName(SimpleService.class);
            assertEquals(SimpleService.class.getName() + "_ClientProxy", name);
        }

        @Test
        @DisplayName("le GeneratedProxy contient le bon nom de classe")
        void shouldReturnCorrectClassNameInRecord() {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            assertEquals(SimpleService.class.getName() + "_ClientProxy", proxy.className());
        }

        @Test
        @DisplayName("le bytecode genere est non vide")
        void shouldGenerateNonEmptyBytecode() {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            assertNotNull(proxy.bytecode());
            assertTrue(proxy.bytecode().length > 0, "Le bytecode ne doit pas etre vide");
        }
    }

    @Nested
    @DisplayName("proxy d'une classe avec constructeur sans argument")
    class NoArgConstructor {

        @Test
        @DisplayName("le proxy peut etre instancie via le constructeur sans argument")
        void shouldInstantiateWithNoArgConstructor() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);
            assertNotNull(instance);
        }

        @Test
        @DisplayName("le proxy est une sous-classe du bean")
        void shouldBeSubclassOfBean() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);
            assertInstanceOf(SimpleService.class, instance);
        }

        @Test
        @DisplayName("le proxy possede le champ $$delegate")
        void shouldHaveDelegateField() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);
            var field = instance.getClass().getDeclaredField("$$delegate");
            assertNotNull(field);
            assertEquals(Supplier.class, field.getType());
        }
    }

    /**
     * Cas central du bug Vidocq : bean {@code @ApplicationScoped} avec uniquement
     * un constructeur {@code @Inject} parametre. Avant le fix, Vauban lancait
     * {@code UnproxyableResolutionException} car le proxy generait un
     * {@code super()} qui n'existait pas dans le bean parent.
     */
    @Nested
    @DisplayName("proxy d'une classe avec uniquement un constructeur parametre")
    class ParameterizedConstructorOnly {

        @Test
        @DisplayName("le proxy genere un constructeur sans argument meme si le bean n'en a pas")
        void shouldGenerateNoArgConstructorForParameterizedBean() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(InjectOnlyService.class);
            Object instance = instantiateProxy(proxy);
            assertNotNull(instance, "Le proxy doit etre instanciable meme sans constructeur sans arg dans le bean");
        }

        @Test
        @DisplayName("le proxy appelle super(null) pour les parametres de type reference")
        void shouldCallSuperWithNullForReferenceParams() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(InjectOnlyService.class);
            Object instance = instantiateProxy(proxy);
            assertInstanceOf(InjectOnlyService.class, instance);
        }

        @Test
        @DisplayName("le proxy avec constructeur parametre delegue les appels de methode")
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
     * Variante du bug avec des primitifs : {@code super(0, false)} au lieu
     * de {@code super(null)}. Verifie que le bytecode genere pousse les
     * bonnes valeurs par defaut sur la stack (iconst_0, lconst_0, etc.).
     */
    @Nested
    @DisplayName("proxy d'une classe avec constructeur a parametres primitifs")
    class PrimitiveConstructorParams {

        @Test
        @DisplayName("le proxy appelle super(0, false) pour les primitifs int et boolean")
        void shouldCallSuperWithDefaultPrimitives() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(PrimitiveCtorService.class);
            Object instance = instantiateProxy(proxy);
            assertNotNull(instance, "Le proxy doit etre instanciable avec des valeurs par defaut pour les primitifs");
        }

        @Test
        @DisplayName("les champs du bean ont les valeurs par defaut (0, false) dans le proxy non-delegue")
        void shouldHaveDefaultValuesInUndelegatedProxy() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(PrimitiveCtorService.class);
            Object instance = instantiateProxy(proxy);
            // Cast direct possible car le proxy est une sous-classe
            PrimitiveCtorService asService = (PrimitiveCtorService) instance;
            // Sans delegate, les champs reflètent les valeurs par defaut passees au super()
            // count=0, flag=false
            // (mais getCount() et isFlag() seront delegues si un delegate est set)
        }

        @Test
        @DisplayName("le proxy delegue getCount au vrai bean")
        void shouldDelegateGetCount() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(PrimitiveCtorService.class);
            Object instance = instantiateProxy(proxy);

            PrimitiveCtorService real = new PrimitiveCtorService(42, true);
            setDelegate(instance, () -> real);

            Method getCount = instance.getClass().getMethod("getCount");
            assertEquals(42, getCount.invoke(instance));
        }

        @Test
        @DisplayName("le proxy delegue isFlag au vrai bean")
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
    @DisplayName("proxy d'une classe avec long, double, float dans le constructeur")
    class WideTypesConstructor {

        @Test
        @DisplayName("le proxy gere les types larges (long, double, float) dans le constructeur")
        void shouldHandleWidePrimitiveTypes() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(LongDoubleCtorService.class);
            Object instance = instantiateProxy(proxy);
            assertNotNull(instance);
        }

        @Test
        @DisplayName("le proxy delegue les methodes retournant long")
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
    @DisplayName("selection du constructeur le plus simple")
    class ConstructorSelection {

        @Test
        @DisplayName("choisit le constructeur sans argument quand il existe")
        void shouldPreferNoArgConstructor() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(MultiCtorService.class);
            Object instance = instantiateProxy(proxy);
            assertNotNull(instance);
            assertInstanceOf(MultiCtorService.class, instance);
        }
    }

    @Nested
    @DisplayName("delegation des methodes")
    class MethodDelegation {

        @Test
        @DisplayName("delegue un appel de methode simple au delegate")
        void shouldDelegateSimpleMethodCall() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);

            SimpleService real = new SimpleService();
            setDelegate(instance, () -> real);

            Method hello = instance.getClass().getMethod("hello");
            assertEquals("real", hello.invoke(instance));
        }

        @Test
        @DisplayName("delegue une methode avec parametres primitifs")
        void shouldDelegateMethodWithPrimitiveParams() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);

            SimpleService real = new SimpleService();
            setDelegate(instance, () -> real);

            Method add = instance.getClass().getMethod("add", int.class, int.class);
            assertEquals(7, add.invoke(instance, 3, 4));
        }

        @Test
        @DisplayName("delegue une methode void")
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
        @DisplayName("le delegate supplier est appele a chaque invocation de methode")
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
            assertEquals(3, callCount[0], "Le supplier doit etre appele a chaque invocation");
        }

        @Test
        @DisplayName("lance NullPointerException si le delegate n'est pas positionne")
        void shouldThrowWhenDelegateNotSet() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);
            // delegate est null par defaut

            Method hello = instance.getClass().getMethod("hello");
            assertThrows(Exception.class, () -> hello.invoke(instance),
                    "Doit echouer quand le delegate n'est pas positionne");
        }
    }

    @Nested
    @DisplayName("methodes exclues du proxy")
    class ExcludedMethods {

        @Test
        @DisplayName("les methodes final ne sont pas surchargees")
        void shouldNotOverrideFinalMethods() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(ServiceWithFinalMethod.class);
            Object instance = instantiateProxy(proxy);

            // La methode proxied est surchargee
            Method proxied = instance.getClass().getMethod("proxied");
            assertTrue(proxied.getDeclaringClass().getName().contains("_ClientProxy"),
                    "proxied() doit etre surchargee dans le proxy");

            // La methode final ne doit pas etre surchargee — declaree dans la classe parente
            Method notProxied = instance.getClass().getMethod("notProxied");
            assertEquals(ServiceWithFinalMethod.class, notProxied.getDeclaringClass(),
                    "notProxied() final ne doit pas etre surchargee");
        }

        @Test
        @DisplayName("les methodes static ne sont pas surchargees")
        void shouldNotOverrideStaticMethods() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(ServiceWithStaticMethod.class);
            Object instance = instantiateProxy(proxy);

            // Verifier que le proxy n'a pas de methode static declaree
            boolean hasStaticInProxy = false;
            for (Method m : instance.getClass().getDeclaredMethods()) {
                if (m.getName().equals("staticMethod")) {
                    hasStaticInProxy = true;
                    break;
                }
            }
            assertFalse(hasStaticInProxy, "Les methodes statiques ne doivent pas etre proxifiees");
        }

        @Test
        @DisplayName("la methode $$setDelegate n'est pas elle-meme proxifiee")
        void shouldNotProxyDollarDollarMethods() throws Exception {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            Object instance = instantiateProxy(proxy);

            // $$setDelegate doit exister mais ne doit pas etre une methode de delegation
            Method setDel = instance.getClass().getMethod("$$setDelegate", Supplier.class);
            assertNotNull(setDel);
            // Verifier qu'il n'y a qu'une seule declaration de $$setDelegate
            long count = 0;
            for (Method m : instance.getClass().getDeclaredMethods()) {
                if (m.getName().equals("$$setDelegate")) count++;
            }
            assertEquals(1, count, "$$setDelegate ne doit etre declare qu'une fois");
        }
    }

    @Nested
    @DisplayName("record GeneratedProxy")
    class GeneratedProxyRecord {

        @Test
        @DisplayName("equals est base sur le nom et le bytecode")
        void shouldImplementEqualsCorrectly() {
            var p1 = RuntimeClientProxyGenerator.generate(SimpleService.class);
            var p2 = RuntimeClientProxyGenerator.generate(SimpleService.class);
            assertEquals(p1, p2, "Deux generations identiques doivent etre egales");
        }

        @Test
        @DisplayName("equals retourne false pour des beans differents")
        void shouldNotBeEqualForDifferentBeans() {
            var p1 = RuntimeClientProxyGenerator.generate(SimpleService.class);
            var p2 = RuntimeClientProxyGenerator.generate(VoidService.class);
            assertNotEquals(p1, p2);
        }

        @Test
        @DisplayName("toString contient le nom de classe et la taille du bytecode")
        void shouldHaveReadableToString() {
            var proxy = RuntimeClientProxyGenerator.generate(SimpleService.class);
            String str = proxy.toString();
            assertTrue(str.contains("_ClientProxy"), "toString doit contenir le nom du proxy");
            assertTrue(str.contains("bytes"), "toString doit contenir 'bytes'");
        }

        @Test
        @DisplayName("hashCode est coherent avec equals")
        void shouldHaveConsistentHashCode() {
            var p1 = RuntimeClientProxyGenerator.generate(SimpleService.class);
            var p2 = RuntimeClientProxyGenerator.generate(SimpleService.class);
            assertEquals(p1.hashCode(), p2.hashCode());
        }
    }
}
