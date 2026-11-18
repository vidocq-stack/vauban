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
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.bean.resolution.QualifierMatcher;
import io.vidocq.vauban.core.bean.validation.DeploymentValidator;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.VaubanIndex;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.FieldConfig;
import jakarta.enterprise.inject.build.compatible.spi.InterceptorInfo;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.MethodConfig;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.ScannedClasses;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Validation;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validation of BCE method signatures (a method without its one target parameter → DefinitionException, a parameter
 * of a type its phase does not list → DeploymentException) and of registered invoker argument lookups
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

    /** The parameter types of a {@code @Discovery} method (the Javadoc of {@code Discovery}). */
    private static final Set<Class<?>> DISCOVERY_TYPES = Set.of(
            ScannedClasses.class, MetaAnnotations.class, Messages.class
    );
    /** The parameter types of a {@code @Synthesis} method (the Javadoc of {@code Synthesis}). */
    private static final Set<Class<?>> SYNTHESIS_TYPES = Set.of(
            SyntheticComponents.class, Messages.class, jakarta.enterprise.inject.build.compatible.spi.Types.class
    );
    /** The parameter types of a {@code @Validation} method (the Javadoc of {@code Validation}). */
    private static final Set<Class<?>> VALIDATION_TYPES = Set.of(
            Messages.class, jakarta.enterprise.inject.build.compatible.spi.Types.class
    );

    /**
     * Validate BCE method signatures. A method without its one target parameter joins {@code definitionErrors}, as
     * the CDI TCK expects. A parameter of a type the phase does not list joins {@code deploymentErrors}, as the Javadoc
     * of {@code BuildCompatibleExtension} says (BUG-20261118-58). A parameter type matches exactly one the Javadoc of
     * the phase lists: an extension's own subtype of one is not one (BUG-20261118-22).
     */
    static void validateExtensionMethods(Class<?> bceClass, List<String> definitionErrors,
                                         List<String> deploymentErrors) {
        for (var method : BceProcessor.getDeclaredMethodsSafe(bceClass)) {
            if (method.getAnnotation(Enhancement.class) != null) {
                validateEnhancementMethod(bceClass, method, definitionErrors, deploymentErrors);
            }
            if (method.getAnnotation(Registration.class) != null) {
                validateCountedMethod(bceClass, method, "@Registration", REGISTRATION_PRIMARY_TYPES,
                        REGISTRATION_OPTIONAL_TYPES, "BeanInfo/InterceptorInfo/ObserverInfo", definitionErrors,
                        deploymentErrors);
            }
            if (method.getAnnotation(Synthesis.class) != null) {
                validParameters(bceClass, method, "@Synthesis", SYNTHESIS_TYPES, deploymentErrors);
            }
            if (method.getAnnotation(Validation.class) != null) {
                validParameters(bceClass, method, "@Validation", VALIDATION_TYPES, deploymentErrors);
            }
        }
    }

    /**
     * Checks the {@code @Enhancement} methods of {@code bceClass}, as {@link #validateExtensionMethods} does. For the
     * paths that run {@code @Enhancement} alone, the enhancement of an archive
     * the processor did not see and the replay for a pre-processed jar, which check as the full cycle does.
     */
    static void validateEnhancementMethods(Class<?> bceClass, List<String> definitionErrors,
                                           List<String> deploymentErrors) {
        for (var method : BceProcessor.getDeclaredMethodsSafe(bceClass)) {
            if (method.getAnnotation(Enhancement.class) != null) {
                validateEnhancementMethod(bceClass, method, definitionErrors, deploymentErrors);
            }
        }
    }

    private static void validateEnhancementMethod(Class<?> bceClass, Method method, List<String> definitionErrors,
                                                  List<String> deploymentErrors) {
        validateCountedMethod(bceClass, method, "@Enhancement", ENHANCEMENT_CONFIG_TYPES, ENHANCEMENT_OPTIONAL_TYPES,
                "ClassConfig/ClassInfo, MethodConfig/MethodInfo or FieldConfig/FieldInfo", definitionErrors,
                deploymentErrors);
    }

    /**
     * Whether every parameter of the {@code @Discovery} {@code method} of {@code bceClass} has a type the phase
     * supports; the first one that does not joins {@code errors}, so that the method is not called with {@code null}
     * (BUG-20261118-22). A deployment problem, as in the other phases (BUG-20261118-58).
     */
    static boolean validDiscoveryMethod(Class<?> bceClass, Method method, List<String> errors) {
        return validParameters(bceClass, method, "@Discovery", DISCOVERY_TYPES, errors);
    }

    /**
     * Whether every parameter type of {@code method} is one of {@code supported}; the first one that is not joins
     * {@code errors}, naming the phase, the extension, the method and the type.
     */
    private static boolean validParameters(Class<?> bceClass, Method method, String phase, Set<Class<?>> supported,
                                           List<String> errors) {
        for (var p : method.getParameterTypes()) {
            if (!supported.contains(p)) {
                errors.add(invalidParameter(bceClass, method, phase, p));
                return false;
            }
        }
        return true;
    }

    /**
     * Checks a method that takes exactly one parameter of a {@code primary} type, its target, and any of the
     * {@code optional} types: each parameter of another type joins {@code deploymentErrors}, and a count other than
     * one {@code definitionErrors}, both when the method has both faults.
     */
    @SuppressWarnings("java:S107") // the method, its phase, its types and the two lists it reports to
    private static void validateCountedMethod(Class<?> bceClass, Method method, String phase, Set<Class<?>> primary,
                                              Set<Class<?>> optional, String primaryNames,
                                              List<String> definitionErrors, List<String> deploymentErrors) {
        int primaryCount = 0;
        for (var p : method.getParameterTypes()) {
            if (primary.contains(p)) {
                primaryCount++;
            } else if (!optional.contains(p)) {
                // Reported, and the count is still checked: the one does not hide the other (BUG-20261118-58)
                deploymentErrors.add(invalidParameter(bceClass, method, phase, p));
            }
        }
        if (primaryCount != 1) {
            definitionErrors.add(phase + " method " + bceClass.getName() + "." + method.getName() + " must have exactly one "
                    + primaryNames + " parameter, found " + primaryCount);
        }
    }

    private static String invalidParameter(Class<?> bceClass, Method method, String phase, Class<?> type) {
        return phase + " method " + bceClass.getName() + "." + method.getName() + " has invalid parameter type: "
                + type.getName();
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
            for (int idx : builder.getArgumentLookups()) {
                validateArgumentLookup(builder, idx, resolver, new LookupQualifiers(qualifiers, index), errors);
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
     *
     * <p>The parameter is read from the language model, which describes a class the build compiles as one it loads:
     * the build checks the lookups of an invoker of a bean it compiles too (BUG-20261117-48).
     */
    private static void validateArgumentLookup(VaubanInvokerBuilder builder, int idx, BeanResolver resolver,
                                               LookupQualifiers qualifiers, List<String> errors) {
        var method = builder.getMethodInfo();
        var parameter = method.parameters().get(idx);
        var type = parameter.type();
        var where = (type.isTypeVariable() ? type.asTypeVariable().name() : type) + " at position " + idx
                + " of method " + method.declaringClass().name() + "." + method.name();
        if (type.isTypeVariable()) {
            errors.add("Invoker argument lookup of a type variable: parameter type " + where
                    + "; a type variable is not a legal required type");
            return;
        }
        var required = LangModelTypeMapper.toIndexType(type);
        if (DeploymentValidator.isBuiltInBeanType(required)) {
            // The container serves a built-in bean, as at an injection point (BUG-20261117-44)
            return;
        }
        var lookup = new InjectionPointInfo(required,
                qualifiers.of(builder.parameterAnnotations(idx)), InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
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
     * The qualifiers among a looked-up parameter's annotations, or {@code @Default} when it has none, as an injection
     * point has them: those whose type {@code @Qualifier} declares, and those an extension registered. The annotations are the invoker's ({@code VaubanInvokerBuilder}), which a method
     * the bean class inherits takes from the bean class's configs (BUG-20261118-55).
     */
    private record LookupQualifiers(BceProcessor.RegisteredQualifiers registered, VaubanIndex index) {

        Set<QualifierInstance> of(List<io.vidocq.vauban.indexer.model.AnnotationInfo> annotations) {
            var lookup = new io.vidocq.vauban.core.langmodel.IndexLookup(index);
            var qualifiers = new HashSet<QualifierInstance>();
            for (var annotation : annotations) {
                if (new io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo(annotation, lookup).declaration()
                        .hasAnnotation(jakarta.inject.Qualifier.class)
                        || registered.types().contains(annotation.name())) {
                    qualifiers.add(QualifierInstance.from(annotation));
                }
            }
            return qualifiers.isEmpty() ? Set.of(QualifierInstance.DEFAULT) : qualifiers;
        }
    }
}
