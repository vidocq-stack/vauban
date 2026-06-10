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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * Validation of BCE method signatures ({@code @Enhancement} / {@code @Registration}
 * parameter shapes → DefinitionException) and of registered invoker argument lookups
 * (unsatisfied / ambiguous → DeploymentException). Extracted from {@link BceProcessor}
 * (which stays the facade).
 */
final class ExtensionMethodValidator {

    private ExtensionMethodValidator() {
    }

    // Valid parameter types for each extension phase
    private static final Set<Class<?>> ENHANCEMENT_CONFIG_TYPES = Set.of(
            ClassConfig.class, MethodConfig.class, FieldConfig.class,
            jakarta.enterprise.lang.model.declarations.ClassInfo.class,
            jakarta.enterprise.lang.model.declarations.MethodInfo.class,
            jakarta.enterprise.lang.model.declarations.FieldInfo.class
    );
    private static final Set<Class<?>> ENHANCEMENT_OPTIONAL_TYPES = Set.of(
            Messages.class, jakarta.enterprise.inject.build.compatible.spi.Types.class
    );
    private static final Set<Class<?>> REGISTRATION_PRIMARY_TYPES = Set.of(
            BeanInfo.class, InterceptorInfo.class, ObserverInfo.class
    );
    private static final Set<Class<?>> REGISTRATION_OPTIONAL_TYPES = Set.of(
            Messages.class, InvokerFactory.class, jakarta.enterprise.inject.build.compatible.spi.Types.class
    );

    private static final Set<Class<?>> SPECIAL_LOOKUP_TYPES = Set.of(
            jakarta.enterprise.inject.Instance.class,
            jakarta.enterprise.event.Event.class,
            jakarta.enterprise.inject.spi.BeanManager.class
    );

    /**
     * Validate BCE method signatures. Invalid signatures → DefinitionException.
     */
    static void validateExtensionMethods(Class<?> bceClass, List<String> errors) {
        for (var method : BceProcessor.getDeclaredMethodsSafe(bceClass)) {
            if (method.getAnnotation(Enhancement.class) != null) {
                validateEnhancementMethod(method, errors);
            }
            if (method.getAnnotation(Registration.class) != null) {
                validateRegistrationMethod(method, errors);
            }
        }
    }

    private static void validateEnhancementMethod(Method method, List<String> errors) {
        var params = method.getParameterTypes();
        // Must have exactly 1 config/declaration parameter
        int configCount = 0;
        for (var p : params) {
            if (ENHANCEMENT_CONFIG_TYPES.stream().anyMatch(t -> t.isAssignableFrom(p))) {
                configCount++;
            } else if (ENHANCEMENT_OPTIONAL_TYPES.stream().noneMatch(t -> t.isAssignableFrom(p))) {
                errors.add("@Enhancement method " + method.getName() + " has invalid parameter type: " + p.getName());
                return;
            }
        }
        if (configCount != 1) {
            errors.add("@Enhancement method " + method.getName()
                    + " must have exactly one ClassConfig/MethodConfig/FieldConfig parameter, found " + configCount);
        }
    }

    private static void validateRegistrationMethod(Method method, List<String> errors) {
        var params = method.getParameterTypes();
        // Must have exactly 1 BeanInfo or InterceptorInfo parameter
        int primaryCount = 0;
        for (var p : params) {
            if (REGISTRATION_PRIMARY_TYPES.stream().anyMatch(t -> t.isAssignableFrom(p))) {
                primaryCount++;
            } else if (REGISTRATION_OPTIONAL_TYPES.stream().noneMatch(t -> t.isAssignableFrom(p))) {
                errors.add("@Registration method " + method.getName() + " has invalid parameter type: " + p.getName());
                return;
            }
        }
        if (primaryCount != 1) {
            errors.add("@Registration method " + method.getName()
                    + " must have exactly one BeanInfo/InterceptorInfo parameter, found " + primaryCount);
        }
    }

    static void validateInvokerLookups(VaubanInvokerFactory factory,
                                       List<BeanDescriptor> allBeans,
                                       ClassLoader classLoader,
                                       List<String> errors) {
        for (var builder : factory.getBuilders()) {
            var argLookups = builder.getArgumentLookups();
            if (argLookups.isEmpty()) continue;

            var reflectMethod = builder.getMethod();
            var paramTypes = reflectMethod.getParameterTypes();

            for (int idx : argLookups) {
                var paramType = paramTypes[idx];

                if (SPECIAL_LOOKUP_TYPES.stream().anyMatch(t -> t.isAssignableFrom(paramType))) {
                    continue;
                }

                int matchCount = 0;
                for (var bean : allBeans) {
                    try {
                        var beanClass = classLoader.loadClass(bean.beanClass().value());
                        if (paramType.isAssignableFrom(beanClass)) {
                            matchCount++;
                        }
                    } catch (ClassNotFoundException ignored) { // intentionally empty
                    }
                }

                if (matchCount == 0) {
                    errors.add("Invoker argument lookup unsatisfied: no bean found for parameter type "
                            + paramType.getName() + " at position " + idx
                            + " of method " + reflectMethod.getDeclaringClass().getName() + "." + reflectMethod.getName());
                } else if (matchCount > 1) {
                    errors.add("Invoker argument lookup ambiguous: " + matchCount + " beans found for parameter type "
                            + paramType.getName() + " at position " + idx
                            + " of method " + reflectMethod.getDeclaringClass().getName() + "." + reflectMethod.getName());
                }
            }
        }
    }
}
