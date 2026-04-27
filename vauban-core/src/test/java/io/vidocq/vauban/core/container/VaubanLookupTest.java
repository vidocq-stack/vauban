package io.vidocq.vauban.core.container;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de l'utilitaire d'acces reflectif centralise de Vauban.
 *
 * <h2>Contexte Vidocq</h2>
 * Vidocq est un framework modulaire JPMS. L'application utilisateur est un
 * module nomme ({@code module io.vidocq.mpserver.app}) qui ouvre ses packages
 * a {@code io.vidocq.vauban.core}. Mais {@code opens ... to} ne suffit pas :
 * le module appelant doit aussi <em>lire</em> le module cible via
 * {@code Module.addReads()}, sinon {@code MethodHandles.privateLookupIn()}
 * echoue avec {@code IllegalAccessException: module io.vidocq.vauban.core
 * does not read module io.vidocq.mpserver.app}.
 *
 * <h2>Fix</h2>
 * {@code VaubanLookup.lookupFor()} appelle desormais {@code addReads()} avant
 * {@code privateLookupIn()} quand les modules ne se lisent pas encore.
 *
 * <h2>Suppression de sun.misc.Unsafe</h2>
 * L'ancien code utilisait {@code sun.misc.Unsafe::staticFieldOffset} pour
 * acceder a {@code IMPL_LOOKUP} en fallback. Cette API est terminally
 * deprecated depuis JDK 23 et sera supprimee. Le fix utilise le root
 * {@code Lookup} fourni par l'utilisateur via {@code VaubanContainer.builder()
 * .lookup(MethodHandles.lookup())} — plus propre et JPMS-compliant.
 *
 * <h2>Pourquoi le TCK ne couvre pas ce cas</h2>
 * Le TCK CDI 4.1 s'execute en classpath (unnamed module). Les problemes
 * {@code addReads} n'apparaissent que quand l'application est un module
 * JPMS nomme, ce que le TCK ne teste jamais. Le workaround classpath
 * ({@code --add-reads} en CLI) masquait le bug en dev mais pas en production
 * modulaire.
 */
@DisplayName("VaubanLookup - acces reflectif via MethodHandles")
class VaubanLookupTest {

    private VaubanLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new VaubanLookup(MethodHandles.lookup());
    }

    // -- Classes de test --

    public static class SimpleBean {
        public SimpleBean() {}
        public String greet() { return "hello"; }
    }

    public static class BeanWithArgs {
        private final String name;
        private final int value;

        public BeanWithArgs(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public int getValue() { return value; }
    }

    public static class BeanWithField {
        public String message;
        private int count;
    }

    public static class BeanWithStaticField {
        public static String shared = "initial";
    }

    public static class BeanWithMethods {
        public String echo(String input) { return input; }
        public int add(int a, int b) { return a + b; }
        public static String staticMethod() { return "static"; }
        public void voidMethod() {}
    }

    public static class BeanWithPrivateConstructor {
        private BeanWithPrivateConstructor() {}
    }

    // -- Tests --

    /**
     * Teste le coeur du fix JPMS : {@code lookupFor} doit appeler
     * {@code Module.addReads()} avant {@code privateLookupIn()} pour que
     * vauban.core puisse lire les modules JDK ou applicatifs.
     * Les tests avec java.sql et java.logging verifient que
     * {@code canRead()} passe a {@code true} apres l'appel.
     */
    @Nested
    @DisplayName("lookupFor - obtention d'un Lookup prive")
    class LookupFor {

        @Test
        @DisplayName("retourne un Lookup pour une classe du meme module")
        void shouldReturnLookupForSameModuleClass() {
            var result = lookup.lookupFor(SimpleBean.class);
            assertNotNull(result);
        }

        @Test
        @DisplayName("echoue proprement pour une classe JDK dont le package n'est pas ouvert")
        void shouldThrowForClosedJdkPackage() {
            // java.lang.String est dans java.base mais le package n'est pas ouvert
            var ex = assertThrows(RuntimeException.class, () -> lookup.lookupFor(String.class));
            assertTrue(ex.getMessage().contains("opens java.lang"),
                    "Le message d'erreur doit indiquer le package a ouvrir");
        }

        @Test
        @DisplayName("met en cache le Lookup pour les appels successifs")
        void shouldCacheLookupResults() {
            var first = lookup.lookupFor(SimpleBean.class);
            var second = lookup.lookupFor(SimpleBean.class);
            assertSame(first, second);
        }

        @Test
        @DisplayName("addReads est appele pour une classe d'un autre module JDK (java.sql)")
        void shouldAddReadsForCrossModuleAccess() throws Exception {
            // java.sql.Connection est dans le module java.sql (nomme)
            Class<?> connectionClass = Class.forName("java.sql.Connection");
            Module targetModule = connectionClass.getModule();
            assertTrue(targetModule.isNamed(), "java.sql.Connection doit etre dans un module nomme");
            assertEquals("java.sql", targetModule.getName());

            // lookupFor va appeler addReads avant privateLookupIn
            // privateLookupIn echouera car java.sql n'ouvre pas ses packages,
            // mais addReads aura ete appele avant l'exception
            try {
                lookup.lookupFor(connectionClass);
            } catch (RuntimeException ignored) {
                // L'exception de privateLookupIn est attendue pour les modules JDK fermes
            }

            // Verification cle : addReads a ete appele, le module peut maintenant lire java.sql
            Module vaubanModule = VaubanLookup.class.getModule();
            assertTrue(vaubanModule.canRead(targetModule),
                    "Apres lookupFor, le module vauban.core doit pouvoir lire java.sql grace a addReads");
        }

        @Test
        @DisplayName("addReads est appele pour java.logging (module nomme)")
        void shouldAddReadsForLoggingModule() throws Exception {
            Class<?> loggerClass = Class.forName("java.util.logging.Logger");
            Module targetModule = loggerClass.getModule();
            assertEquals("java.logging", targetModule.getName());

            try {
                lookup.lookupFor(loggerClass);
            } catch (RuntimeException ignored) {
                // L'exception de privateLookupIn est attendue pour les modules JDK fermes
            }

            Module vaubanModule = VaubanLookup.class.getModule();
            assertTrue(vaubanModule.canRead(targetModule),
                    "Apres lookupFor, le module vauban.core doit pouvoir lire java.logging grace a addReads");
        }

        @Test
        @DisplayName("lookupFor reussit pour une classe utilisateur (package ouvert implicitement)")
        void shouldSucceedForUserClassInSameModule() {
            // Les classes de test sont dans le meme module -- pas de probleme d'acces
            var result = lookup.lookupFor(SimpleBean.class);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("newInstance - creation d'instances")
    class NewInstance {

        @Test
        @DisplayName("cree une instance avec le constructeur sans argument")
        void shouldCreateInstanceWithNoArgConstructor() {
            var instance = lookup.newInstance(SimpleBean.class);
            assertNotNull(instance);
            assertInstanceOf(SimpleBean.class, instance);
        }

        @Test
        @DisplayName("chaque appel cree une nouvelle instance")
        void shouldCreateDistinctInstances() {
            var a = lookup.newInstance(SimpleBean.class);
            var b = lookup.newInstance(SimpleBean.class);
            assertNotSame(a, b);
        }

        @Test
        @DisplayName("l'instance creee est fonctionnelle")
        void shouldCreateFunctionalInstance() {
            var instance = lookup.newInstance(SimpleBean.class);
            assertEquals("hello", instance.greet());
        }

        @Test
        @DisplayName("lance une exception pour une classe sans constructeur sans-arg")
        void shouldThrowWhenNoNoArgConstructor() {
            assertThrows(RuntimeException.class, () -> lookup.newInstance(BeanWithArgs.class));
        }
    }

    @Nested
    @DisplayName("newInstance avec constructeur et arguments")
    class NewInstanceWithConstructor {

        @Test
        @DisplayName("cree une instance avec les arguments fournis")
        void shouldCreateInstanceWithArgs() throws Exception {
            Constructor<?> ctor = BeanWithArgs.class.getConstructor(String.class, int.class);
            var instance = (BeanWithArgs) lookup.newInstance(ctor, "test", 42);
            assertNotNull(instance);
            assertEquals("test", instance.getName());
            assertEquals(42, instance.getValue());
        }

        @Test
        @DisplayName("lance CreationException si les arguments sont incorrects")
        void shouldThrowOnWrongArgs() throws Exception {
            Constructor<?> ctor = BeanWithArgs.class.getConstructor(String.class, int.class);
            assertThrows(Exception.class, () -> lookup.newInstance(ctor, 123, "wrong"));
        }
    }

    @Nested
    @DisplayName("setField / getField - acces aux champs")
    class FieldAccess {

        @Test
        @DisplayName("ecrit et lit un champ public")
        void shouldSetAndGetPublicField() throws Exception {
            var instance = new BeanWithField();
            Field field = BeanWithField.class.getDeclaredField("message");

            lookup.setField(instance, field, "bonjour");
            var result = lookup.getField(instance, field);

            assertEquals("bonjour", result);
        }

        @Test
        @DisplayName("ecrit et lit un champ prive")
        void shouldSetAndGetPrivateField() throws Exception {
            var instance = new BeanWithField();
            Field field = BeanWithField.class.getDeclaredField("count");

            lookup.setField(instance, field, 7);
            var result = lookup.getField(instance, field);

            assertEquals(7, result);
        }

        @Test
        @DisplayName("ecrit et lit un champ statique")
        void shouldSetAndGetStaticField() throws Exception {
            Field field = BeanWithStaticField.class.getDeclaredField("shared");

            lookup.setField(null, field, "modified");
            var result = lookup.getField(null, field);

            assertEquals("modified", result);
            // Reset
            BeanWithStaticField.shared = "initial";
        }

        @Test
        @DisplayName("getField retourne null pour un champ non initialise")
        void shouldReturnNullForUninitializedField() throws Exception {
            var instance = new BeanWithField();
            Field field = BeanWithField.class.getDeclaredField("message");

            var result = lookup.getField(instance, field);
            assertNull(result);
        }

        @Test
        @DisplayName("getField retourne 0 pour un int non initialise")
        void shouldReturnZeroForUninitializedIntField() throws Exception {
            var instance = new BeanWithField();
            Field field = BeanWithField.class.getDeclaredField("count");

            var result = lookup.getField(instance, field);
            assertEquals(0, result);
        }
    }

    @Nested
    @DisplayName("invokeMethod - invocation de methodes")
    class InvokeMethod {

        @Test
        @DisplayName("invoque une methode d'instance avec un argument")
        void shouldInvokeInstanceMethodWithArg() throws Exception {
            var instance = new BeanWithMethods();
            Method method = BeanWithMethods.class.getMethod("echo", String.class);

            var result = lookup.invokeMethod(instance, method, "test");
            assertEquals("test", result);
        }

        @Test
        @DisplayName("invoque une methode avec plusieurs arguments")
        void shouldInvokeMethodWithMultipleArgs() throws Exception {
            var instance = new BeanWithMethods();
            Method method = BeanWithMethods.class.getMethod("add", int.class, int.class);

            var result = lookup.invokeMethod(instance, method, 3, 4);
            assertEquals(7, result);
        }

        @Test
        @DisplayName("invoque une methode statique")
        void shouldInvokeStaticMethod() throws Exception {
            Method method = BeanWithMethods.class.getMethod("staticMethod");

            var result = lookup.invokeStaticMethod(method);
            assertEquals("static", result);
        }

        @Test
        @DisplayName("invoque une methode void sans erreur")
        void shouldInvokeVoidMethod() throws Exception {
            var instance = new BeanWithMethods();
            Method method = BeanWithMethods.class.getMethod("voidMethod");

            assertDoesNotThrow(() -> lookup.invokeMethod(instance, method));
        }

        @Test
        @DisplayName("invoque une methode void via invokeStaticMethod sur instance")
        void shouldInvokeStaticMethodWithNullInstance() throws Exception {
            Method method = BeanWithMethods.class.getMethod("staticMethod");

            var result = lookup.invokeMethod(null, method);
            assertEquals("static", result);
        }
    }

    @Nested
    @DisplayName("makeAccessible - rend un membre accessible")
    class MakeAccessible {

        @Test
        @DisplayName("rend un constructeur prive accessible apres appel")
        void shouldMakePrivateConstructorAccessible() throws Exception {
            Constructor<?> ctor = BeanWithPrivateConstructor.class.getDeclaredConstructor();

            // makeAccessible ne doit pas lancer d'exception
            assertDoesNotThrow(() -> lookup.makeAccessible(ctor));

            // Apres l'appel, le constructeur doit etre accessible
            assertTrue(ctor.canAccess(null));
        }

        @Test
        @DisplayName("rend un champ prive accessible apres appel")
        void shouldMakePrivateFieldAccessible() throws Exception {
            Field field = BeanWithField.class.getDeclaredField("count");
            var instance = new BeanWithField();

            assertDoesNotThrow(() -> lookup.makeAccessible(field));

            // Apres l'appel, le champ doit etre accessible
            assertTrue(field.canAccess(instance));
        }

        @Test
        @DisplayName("rend une methode accessible apres appel")
        void shouldMakeMethodAccessible() throws Exception {
            // Utilisons une methode privee fictive via une inner class
            Method method = BeanWithMethods.class.getMethod("echo", String.class);

            assertDoesNotThrow(() -> lookup.makeAccessible(method));
            assertTrue(method.canAccess(new BeanWithMethods()));
        }
    }

    @Nested
    @DisplayName("constructeur par defaut")
    class DefaultConstructor {

        @Test
        @DisplayName("utilise MethodHandles.lookup() quand aucun Lookup n'est fourni")
        void shouldUseDefaultLookupWhenNoneProvided() {
            var defaultLookup = new VaubanLookup();
            // Doit pouvoir creer une instance d'une classe dans le meme package
            var instance = defaultLookup.newInstance(SimpleBean.class);
            assertNotNull(instance);
        }
    }
}
