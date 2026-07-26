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
package io.vidocq.vauban.core.container;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reflective validation must never be the thing that brings a deployment down.
 *
 * <p>A bean class can legitimately carry a member whose type is not resolvable in the
 * current context — an optional dependency declared {@code requires static} and absent at
 * runtime, or a stale {@code .class} left behind by an interrupted build. In that
 * situation the JVM throws a {@link LinkageError} (typically {@code NoClassDefFoundError})
 * from {@code Class.getDeclaredFields()} / {@code getDeclaredMethods()}, and the class
 * simply cannot be inspected member-by-member.</p>
 *
 * <p>Every other reflective consumer in Vauban already guards against this —
 * {@code BceTypeMatcher}, {@code BceProcessor.getDeclaredMethodsSafe}, {@code ContainerScanner},
 * {@code VaubanContainerBuilder}. {@code ReflectionValidator} was the last one that did not,
 * which turned an uninspectable class into a hard boot failure (Vidocq/vidocq#38: 93 TCK
 * deployments aborted by a single unresolvable field type).</p>
 */
class ReflectionValidatorLinkageErrorTest {

    /**
     * Builds a normal-scoped bean class whose only field has a descriptor pointing at a type
     * that does not exist, then loads it. Any reflective inspection of its members throws
     * {@code NoClassDefFoundError} — reproducing vidocq#38 exactly.
     */
    private static Class<?> beanWithUnresolvableFieldType() {
        var className = "io.vidocq.vauban.core.container.GeneratedBeanWithMissingFieldType";
        var bytes = ClassFile.of().build(ClassDesc.of(className), clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC);
            // Field type deliberately references an absent class.
            clb.withField("missing", ClassDesc.of("com.acme.AbsentAtRuntime"), ClassFile.ACC_PUBLIC);
            clb.withMethodBody("<init>", java.lang.constant.MethodTypeDesc.of(ClassDesc.ofDescriptor("V")),
                    ClassFile.ACC_PUBLIC,
                    cob -> cob.aload(0)
                            .invokespecial(ClassDesc.of("java.lang.Object"), "<init>",
                                    java.lang.constant.MethodTypeDesc.of(ClassDesc.ofDescriptor("V")))
                            .return_());
        });

        var loader = new ClassLoader(ReflectionValidatorLinkageErrorTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(className, bytes, 0, bytes.length);
            }
        };
        return loader.define();
    }

    @Test
    @DisplayName("the generated fixture really is uninspectable (guards the test itself)")
    void fixture_throwsLinkageError_onMemberInspection() {
        var clazz = beanWithUnresolvableFieldType();
        assertNotNull(clazz);
        assertTrue(
                org.junit.jupiter.api.Assertions.assertThrows(LinkageError.class, clazz::getDeclaredFields)
                        .getMessage().contains("AbsentAtRuntime"),
                "fixture should fail to resolve com.acme.AbsentAtRuntime");
    }

    @Test
    @DisplayName("validateWithReflection skips a class it cannot inspect instead of failing the boot")
    void validateWithReflection_skipsUninspectableClass() {
        var clazz = beanWithUnresolvableFieldType();

        var errors = assertDoesNotThrow(
                () -> ReflectionValidator.validateWithReflection(List.of(clazz)),
                "an uninspectable bean class must not abort validation");

        assertNotNull(errors);
    }

    @Test
    @DisplayName("an uninspectable class does not stop validation of the classes after it")
    void validateWithReflection_keepsValidatingRemainingClasses() {
        var broken = beanWithUnresolvableFieldType();

        var errors = assertDoesNotThrow(
                () -> ReflectionValidator.validateWithReflection(List.of(broken, PublicFieldBean.class)));

        assertTrue(
                errors.stream().anyMatch(e -> e.contains(PublicFieldBean.class.getName())),
                "the valid-but-offending bean after the broken one must still be reported, got: " + errors);
    }

    /** Normal-scoped bean with a non-static public field — a violation the validator must report. */
    @jakarta.enterprise.context.ApplicationScoped
    static class PublicFieldBean {
        public String oops;
    }
}
