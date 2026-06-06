package io.vidocq.vauban.processor.codegen.provider;

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
                + "implements io.vidocq.vauban.core.VaubanComponentProvider"), s);
        assertTrue(s.contains("case \"app.Foo\" -> new app.Foo();"), s);
        assertTrue(s.contains("case \"app.web.Bar\" -> new app.web.Bar();"), s);
        assertTrue(s.contains("default -> null;"), s);
    }

    @Test
    @DisplayName("default package omits the package declaration")
    void defaultPackageOmitsPackageDecl() {
        var gen = ComponentProviderGenerator.generate("", List.of("Foo"));

        assertEquals("_VaubanComponents", gen.className());
        assertFalse(gen.source().contains("package "), gen.source());
        assertTrue(gen.source().contains("case \"Foo\" -> new Foo();"), gen.source());
    }
}
