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
package io.vidocq.vauban.maven.generate;

import io.vidocq.vauban.maven.generate.intercepted.ClassBound;
import io.vidocq.vauban.maven.generate.intercepted.ConstructorBound;
import io.vidocq.vauban.maven.generate.intercepted.DefaultMethodBound;
import io.vidocq.vauban.maven.generate.intercepted.InheritedBound;
import io.vidocq.vauban.maven.generate.intercepted.LoggedInterceptor;
import io.vidocq.vauban.maven.generate.intercepted.MethodBound;
import io.vidocq.vauban.maven.generate.intercepted.OverridingBound;
import io.vidocq.vauban.maven.generate.intercepted.OwnAroundInvoke;
import io.vidocq.vauban.maven.generate.intercepted.Plain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vauban:generate} pre-generates {@code <Bean>$$Intercepted} for every managed bean the
 * container would wrap, not only for one bound at the class level (BUG-20261004-10, vauban#121):
 * on the strict module path the container cannot define the subclass itself without an
 * {@code opens} of the bean's package.
 */
@DisplayName("VaubanGenerator — intercepted subclass pre-generation")
class InterceptedSubclassGenerationTest {

    @TempDir
    Path tempDir;

    private GenerationResult result;
    private Path outputDir;

    @BeforeEach
    void generate() throws IOException, URISyntaxException {
        var fixtures = Path.of(ClassBound.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve(ClassBound.class.getPackageName().replace('.', '/'));
        var classesDir = tempDir.resolve("classes");
        var target = classesDir.resolve(ClassBound.class.getPackageName().replace('.', '/'));
        Files.createDirectories(target);
        try (Stream<Path> files = Files.list(fixtures)) {
            for (var file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
        outputDir = tempDir.resolve("output");
        result = VaubanGenerator.generate(new VaubanGenerator.Config(List.of(), classesDir, outputDir,
                InterceptedSubclassGenerationTest.class.getClassLoader()));
    }

    @Test
    @DisplayName("a bean bound at the class level gets one, as before")
    void classLevelBinding() {
        assertPreGenerated(ClassBound.class);
    }

    @Test
    @DisplayName("a bean bound only through a method it declares gets one")
    void declaredMethodBinding() {
        assertPreGenerated(MethodBound.class);
    }

    @Test
    @DisplayName("a bean bound only through a superclass method it inherits gets one")
    void inheritedMethodBinding() {
        assertPreGenerated(InheritedBound.class);
    }

    @Test
    @DisplayName("a bean bound only through an interface default method it inherits gets one")
    void defaultMethodBinding() {
        assertPreGenerated(DefaultMethodBound.class);
    }

    @Test
    @DisplayName("a bean bound only through its constructor gets one")
    void constructorBinding() {
        assertPreGenerated(ConstructorBound.class);
    }

    @Test
    @DisplayName("a bean with an @AroundInvoke method of its own gets one")
    void ownAroundInvoke() {
        assertPreGenerated(OwnAroundInvoke.class);
    }

    @Test
    @DisplayName("a bean that overrides the bound method without the binding gets none (CDI 4.1 §4.2)")
    void overriddenBindingDoesNotCount() {
        assertNotPreGenerated(OverridingBound.class);
    }

    @Test
    @DisplayName("a bean that is not intercepted, or an interceptor, gets none")
    void notIntercepted() {
        assertNotPreGenerated(Plain.class);
        assertNotPreGenerated(LoggedInterceptor.class);
    }

    @Test
    @DisplayName("the package's _VaubanComponents creates every subclass it pre-generated (BUG-20261007-05)")
    void providerListsThePreGeneratedSubclasses() throws IOException {
        var provider = classFile(ClassBound.class.getPackageName() + "._VaubanComponents");
        var strings = new java.util.HashSet<String>();
        for (var entry : java.lang.classfile.ClassFile.of().parse(Files.readAllBytes(provider)).constantPool()) {
            if (entry instanceof java.lang.classfile.constantpool.StringEntry s) strings.add(s.stringValue());
        }
        for (var bean : List.of(ClassBound.class, MethodBound.class, InheritedBound.class,
                DefaultMethodBound.class, ConstructorBound.class, OwnAroundInvoke.class)) {
            var name = bean.getName() + "$$Intercepted";
            assertTrue(strings.contains(name), "the provider must create " + name + "; it knows " + strings);
        }
        assertFalse(strings.contains(Plain.class.getName() + "$$Intercepted"));
    }

    private void assertPreGenerated(Class<?> bean) {
        var name = bean.getName() + "$$Intercepted";
        assertTrue(result.generatedInterceptors().contains(name),
                name + " must be pre-generated; generated: " + result.generatedInterceptors()
                        + ", warnings: " + result.warnings());
        assertTrue(Files.isRegularFile(classFile(name)), name + ".class must be written");
    }

    private void assertNotPreGenerated(Class<?> bean) {
        var name = bean.getName() + "$$Intercepted";
        assertFalse(result.generatedInterceptors().contains(name),
                name + " must not be pre-generated; generated: " + result.generatedInterceptors());
        assertFalse(Files.exists(classFile(name)), name + ".class must not be written");
    }

    private Path classFile(String binaryName) {
        return outputDir.resolve(binaryName.replace('.', '/') + ".class");
    }
}
