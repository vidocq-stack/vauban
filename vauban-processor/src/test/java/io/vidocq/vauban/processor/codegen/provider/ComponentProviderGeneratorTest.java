package io.vidocq.vauban.processor.codegen.provider;

import io.vidocq.vauban.indexer.codegen.Component;
import io.vidocq.vauban.indexer.codegen.FieldInject;
import io.vidocq.vauban.indexer.codegen.MethodInvoke;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ComponentProviderGenerator")
class ComponentProviderGeneratorTest {

    @Test
    @DisplayName("emits a switch-on-name that instantiates each component in-module")
    void generatesSwitchSource() {
        var gen = ComponentProviderGenerator.generate("app", List.of("app.Foo", "app.web.Bar"));

        assertEquals("app._VaubanComponents", gen.className());
        var s = gen.source();
        assertTrue(s.contains("package app;"), s);
        assertTrue(s.contains("public final class _VaubanComponents "
                + "implements io.vidocq.vauban.api.VaubanComponentProvider"), s);
        assertTrue(s.contains("case \"app.Foo\" -> new app.Foo();"), s);
        assertTrue(s.contains("case \"app.web.Bar\" -> new app.web.Bar();"), s);
        assertTrue(s.contains("default -> null;"), s);
    }

    @Test
    @DisplayName("emits a second switch casting resolved args for injected-constructor components")
    void generatesArgAwareSwitch() {
        var gen = ComponentProviderGenerator.generateFrom("app", List.of(
                new Component("app.Foo", List.of()),
                new Component("app.Service",
                        List.of("app.Repo", "app.Clock"))));

        var s = gen.source();
        assertTrue(s.contains("case \"app.Foo\" -> new app.Foo();"), s);
        assertTrue(s.contains("public Object create(String className, Object[] args)"), s);
        assertTrue(s.contains("if (args == null || args.length == 0) return create(className);"), s);
        assertTrue(s.contains(
                "case \"app.Service\" -> new app.Service((app.Repo) args[0], (app.Clock) args[1]);"), s);
    }

    @Test
    @DisplayName("no injected-constructor component omits the args overload")
    void noArgsOverloadWhenAllNoArg() {
        var gen = ComponentProviderGenerator.generateFrom("app",
                List.of(new Component("app.Foo", List.of())));

        assertFalse(gen.source().contains("Object[] args"), gen.source());
    }

    @Test
    @DisplayName("default package omits the package declaration")
    void defaultPackageOmitsPackageDecl() {
        var gen = ComponentProviderGenerator.generate("", List.of("Foo"));

        assertEquals("_VaubanComponents", gen.className());
        assertFalse(gen.source().contains("package "), gen.source());
        assertTrue(gen.source().contains("case \"Foo\" -> new Foo();"), gen.source());
    }

    @Test
    @DisplayName("emits injectField switch for non-private @Inject fields grouped by class")
    void generatesInjectFieldSwitch() {
        var fieldInjects = List.of(
                new FieldInject("app.Service", "repo", "app.Repo"),
                new FieldInject("app.Service", "clock", "app.Clock"),
                new FieldInject("app.Foo", "dep", "app.Dep"));

        var gen = ComponentProviderGenerator.generateFrom("app",
                List.of(new Component("app.Service", List.of()),
                        new Component("app.Foo", List.of())),
                fieldInjects);

        var s = gen.source();
        assertTrue(s.contains("public boolean injectField(Object bean, String className, String fieldName, Object value)"), s);
        // outer switch cases
        assertTrue(s.contains("case \"app.Service\""), s);
        assertTrue(s.contains("case \"app.Foo\""), s);
        // inner field assignments
        assertTrue(s.contains("case \"repo\" -> { b.repo = (app.Repo) value; return true; }"), s);
        assertTrue(s.contains("case \"clock\" -> { b.clock = (app.Clock) value; return true; }"), s);
        assertTrue(s.contains("case \"dep\" -> { b.dep = (app.Dep) value; return true; }"), s);
        // fallback returns
        assertTrue(s.contains("default -> { return false; }"), s);
    }

    @Test
    @DisplayName("no injectField method emitted when fieldInjects is empty")
    void noInjectFieldWhenEmpty() {
        var gen = ComponentProviderGenerator.generateFrom("app",
                List.of(new Component("app.Foo", List.of())),
                List.of());

        assertFalse(gen.source().contains("injectField"), gen.source());
    }

    @Test
    @DisplayName("emits invoke switch for void, non-void, and static methods")
    void generatesInvokeSwitch() {
        var methodInvokes = List.of(
                // void instance method with one parameter
                new MethodInvoke(
                        "app.Service", "record",
                        List.of("java.lang.String"),
                        false, true, null),
                // non-void instance method, no parameters
                new MethodInvoke(
                        "app.Service", "make",
                        List.of(),
                        false, false, "app.Product"),
                // static producer method, no parameters
                new MethodInvoke(
                        "app.Service", "staticProduce",
                        List.of(),
                        true, false, "app.Product"));

        var gen = ComponentProviderGenerator.generateFrom("app",
                List.of(new Component("app.Service", List.of())),
                List.of(),
                methodInvokes);

        var s = gen.source();
        assertTrue(s.contains("public Object invoke(Object target, String className, String methodId, Object[] args)"), s);
        // outer switch on className
        assertTrue(s.contains("case \"app.Service\""), s);
        // void method: call then return null
        assertTrue(s.contains("case \"record(java.lang.String)\""), s);
        assertTrue(s.contains("((app.Service) target).record((java.lang.String) args[0]);"), s);
        assertTrue(s.contains("return null;"), s);
        // non-void instance method
        assertTrue(s.contains("case \"make()\""), s);
        assertTrue(s.contains("return ((app.Service) target).make();"), s);
        // static method — no target cast
        assertTrue(s.contains("case \"staticProduce()\""), s);
        assertTrue(s.contains("return app.Service.staticProduce();"), s);
        // sentinel for unmatched method and unmatched class
        assertTrue(s.contains("return io.vidocq.vauban.api.VaubanComponentProvider.NOT_INVOKED;"), s);
    }

    @Test
    @DisplayName("no invoke method emitted when methodInvokes is empty")
    void noInvokeMethodWhenEmpty() {
        var gen = ComponentProviderGenerator.generateFrom("app",
                List.of(new Component("app.Foo", List.of())),
                List.of(),
                List.of());

        assertFalse(gen.source().contains("invoke"), gen.source());
    }
}
