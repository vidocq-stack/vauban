package io.vidocq.vauban.core.provider;

import io.vidocq.vauban.api.VaubanComponentProvider;
import io.vidocq.vauban.core.container.ProvidedBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the plugin's bytecode {@code _VaubanComponents} generator produces a loadable provider
 * that instantiates the listed no-arg beans in-module and returns {@code null} for the rest.
 */
@DisplayName("ComponentProviderClassGenerator (bytecode)")
class ComponentProviderClassGeneratorTest {

    @Test
    @DisplayName("the generated provider instantiates a listed bean and yields null otherwise")
    void generatesLoadableProvider() throws Exception {
        var gen = ComponentProviderClassGenerator.generate(
                "io.vidocq.vauban.core.provider._GenTestComponents",
                java.util.List.of(ProvidedBean.class.getName()));

        var loader = new ByteClassLoader(getClass().getClassLoader());
        var providerClass = loader.define(gen.className(), gen.bytecode());
        var provider = (VaubanComponentProvider) providerClass.getDeclaredConstructor().newInstance();

        var first = provider.create(ProvidedBean.class.getName());
        assertInstanceOf(ProvidedBean.class, first, "should instantiate the listed bean in-module");
        var second = provider.create(ProvidedBean.class.getName());
        assertInstanceOf(ProvidedBean.class, second);
        assertNotSame(first, second, "each create() call must return a fresh instance");

        assertNull(provider.create("does.not.Exist"), "unlisted class must return null");

        assertTrue(providerClass.isAnnotationPresent(jakarta.enterprise.inject.Vetoed.class),
                "@Vetoed keeps Weld (bean-discovery-mode=all) from loading it as a bean");
    }

    /** Minimal loader exposing {@code defineClass} for the generated provider bytecode. */
    private static final class ByteClassLoader extends ClassLoader {
        ByteClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
