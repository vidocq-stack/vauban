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

import io.vidocq.vauban.core.annotation.AnnotationTypes;
import io.vidocq.vauban.core.annotation.AnnotationValues;
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.bean.resolution.QualifierMatcher;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.core.types.TypeInfoUtils;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
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
import java.lang.reflect.Parameter;
import java.lang.reflect.TypeVariable;
import java.util.HashSet;
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

    /**
     * The argument lookups of the invokers {@code factories} built: each must identify one bean among
     * {@code allBeans}, every bean of the deployment (CDI 4.1, "Configuring lookups": an unsatisfied lookup, or an
     * ambiguous one that is not resolvable, is a deployment problem). The extensions run at build and at start alike,
     * so both refuse what the call would. The qualifiers are those the container knows: the annotations
     * {@code @Qualifier} declares and those {@code qualifiers} lists, compared without their non-binding members.
     */
    static void validateInvokerLookups(List<VaubanInvokerFactory> factories,
                                       List<BeanDescriptor> allBeans,
                                       VaubanIndex index,
                                       ClassLoader classLoader,
                                       BceProcessor.RegisteredQualifiers qualifiers,
                                       List<String> errors) {
        var builders = factories.stream().flatMap(factory -> factory.getBuilders().stream())
                .filter(builder -> !builder.getArgumentLookups().isEmpty())
                .toList();
        if (builders.isEmpty()) {
            return;
        }
        var resolver = new BeanResolver(allBeans, List.of(), new AssignabilityRules(index, classLoader),
                new QualifierMatcher(new AnnotationTypes(index, List.of(classLoader), qualifiers.nonbindingMembers())));
        for (var builder : builders) {
            var reflectMethod = builder.getMethod();
            for (int idx : builder.getArgumentLookups()) {
                validateArgumentLookup(reflectMethod, idx, resolver, qualifiers, errors);
            }
        }
    }

    /**
     * The argument {@code idx} of an invoker, looked up as the call looks it up ({@code VaubanInvoker}), by the rules
     * of an injection point: the declared type of the parameter, {@code Holder<String>}, and its qualifiers,
     * {@code @Default} when it has none. It counted the beans whose class is assignable to the erased class of the
     * parameter, with no qualifier, producers left out (BUG-20261117-27).
     *
     * <p>A parameter of a type variable, {@code T}, is refused: the required type is the declared type of the
     * parameter, and a type variable is no legal one (CDI 4.1, 5.2.2). The count looked its erasure up, the bound.
     */
    private static void validateArgumentLookup(Method reflectMethod, int idx, BeanResolver resolver,
                                               BceProcessor.RegisteredQualifiers qualifiers, List<String> errors) {
        var parameter = reflectMethod.getParameters()[idx];
        var where = parameter.getParameterizedType().getTypeName() + " at position " + idx
                + " of method " + reflectMethod.getDeclaringClass().getName() + "." + reflectMethod.getName();
        if (parameter.getParameterizedType() instanceof TypeVariable<?>) {
            errors.add("Invoker argument lookup of a type variable: parameter type " + where
                    + "; a type variable is not a legal required type");
            return;
        }
        if (SPECIAL_LOOKUP_TYPES.stream().anyMatch(t -> t.isAssignableFrom(parameter.getType()))) {
            return;
        }
        var lookup = new InjectionPointInfo(TypeInfoUtils.fromReflectType(parameter.getParameterizedType()),
                qualifiersOf(parameter, qualifiers), InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
                "invoker argument " + idx);
        var result = resolver.resolveInjectionPoint(lookup);
        if (result.status() == BeanResolver.ResolutionResult.Status.UNSATISFIED) {
            errors.add("Invoker argument lookup unsatisfied: no bean found for parameter type " + where);
        } else if (result.status() == BeanResolver.ResolutionResult.Status.AMBIGUOUS) {
            errors.add("Invoker argument lookup ambiguous: " + result.beans().size()
                    + " beans found for parameter type " + where);
        }
    }

    /**
     * The qualifiers of {@code parameter}, or {@code @Default} when it has none, as an injection point has them: the
     * annotations {@code @Qualifier} declares, and those an extension registered.
     */
    private static Set<QualifierInstance> qualifiersOf(Parameter parameter,
                                                       BceProcessor.RegisteredQualifiers registered) {
        var qualifiers = new HashSet<QualifierInstance>();
        for (var annotation : parameter.getAnnotations()) {
            var type = annotation.annotationType();
            if (type.isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || registered.types().contains(DotName.of(type.getName()))) {
                qualifiers.add(QualifierInstance.from(AnnotationValues.infoOf(annotation)));
            }
        }
        return qualifiers.isEmpty() ? Set.of(QualifierInstance.DEFAULT) : qualifiers;
    }
}
