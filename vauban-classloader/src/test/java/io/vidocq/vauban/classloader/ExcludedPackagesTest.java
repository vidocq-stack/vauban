package io.vidocq.vauban.classloader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The packages the loader refuses to define, as a launcher outside this module reads them (vauban#154). */
@DisplayName("VaubanClassLoader.excludesPackage — public, same rule as the loader")
class ExcludedPackagesTest {

    @Test
    @DisplayName("a launcher outside the module can call it")
    void isPublic() throws Exception {
        var method = VaubanClassLoader.class.getDeclaredMethod("excludesPackage", String.class);
        assertTrue(Modifier.isPublic(method.getModifiers()), "Vidocq's build-time split needs the rule");
    }

    @Test
    @DisplayName("platform and container packages are refused, an application package is not")
    void rule() {
        assertTrue(VaubanClassLoader.excludesPackage("javax.annotation"));
        assertTrue(VaubanClassLoader.excludesPackage("sun.misc"));
        assertTrue(VaubanClassLoader.excludesPackage("io.vidocq.vauban.core.impl"));
        assertFalse(VaubanClassLoader.excludesPackage("acme.app"));
        assertFalse(VaubanClassLoader.excludesPackage("javaxx.app"), "a prefix match stops at the dot");
    }
}
