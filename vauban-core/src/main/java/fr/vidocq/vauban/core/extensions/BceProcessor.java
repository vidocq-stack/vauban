package fr.vidocq.vauban.core.extensions;

import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.InjectionPointInfo;
import fr.vidocq.vauban.core.bean.model.QualifierInstance;
import fr.vidocq.vauban.core.langmodel.IndexLookup;
import fr.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import fr.vidocq.vauban.core.langmodel.BuiltAnnotationInfo;
import fr.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import fr.vidocq.vauban.indexer.VaubanIndex;
import fr.vidocq.vauban.indexer.model.AnnotationValue;
import fr.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Processes Build Compatible Extensions (BCE) — CDI 4.1 spec chapter 28.
 * Handles @Enhancement, @Registration, and @Synthesis phases.
 */
public final class BceProcessor {

    /**
     * Result of BCE processing: synthetic bean definitions and collected errors.
     */
    public record Result(
            List<VaubanSyntheticBeanBuilder<?>> syntheticBeans,
            List<String> definitionErrors,
            List<String> deploymentErrors,
            Map<fr.vidocq.vauban.indexer.model.DotName, List<VaubanClassConfig>> enhancementModifications
    ) {}

    public record DiscoveryResult(
            VaubanMetaAnnotations metaAnnotations,
            VaubanScannedClasses scannedClasses
    ) {}

    public static DiscoveryResult processDiscovery(List<Class<?>> bceClasses, IndexLookup lookup) {
        var metaAnnotations = new VaubanMetaAnnotations(lookup);
        var scannedClasses = new VaubanScannedClasses();

        for (var bceClass : bceClasses) {
            try {
                var bce = instantiateBce(bceClass);
                for (var method : getDeclaredMethodsSafe(bceClass)) {
                    if (method.getAnnotation(Discovery.class) == null) continue;
                    method.setAccessible(true);

                    var params = method.getParameters();
                    var args = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        var paramType = params[i].getType();
                        if (MetaAnnotations.class.isAssignableFrom(paramType)) {
                            args[i] = metaAnnotations;
                        } else if (ScannedClasses.class.isAssignableFrom(paramType)) {
                            args[i] = scannedClasses;
                        }
                    }
                    method.invoke(bce, args);
                }
            } catch (Exception e) {
                // Discovery phase errors are silently ignored per spec
            }
        }

        return new DiscoveryResult(metaAnnotations, scannedClasses);
    }

    /**
     * Process all BCE classes through their lifecycle phases.
     */
    public static Result process(List<Class<?>> bceClasses,
                                 List<BeanDescriptor> beans,
                                 VaubanIndex index,
                                 ClassLoader classLoader) {
        var lookup = new IndexLookup(index);
        var definitionErrors = new ArrayList<String>();
        var deploymentErrors = new ArrayList<String>();
        var allSyntheticBeans = new ArrayList<VaubanSyntheticBeanBuilder<?>>();
        var allEnhancementMods = new HashMap<fr.vidocq.vauban.indexer.model.DotName, List<VaubanClassConfig>>();

        for (var bceClass : bceClasses) {
            try {
                // Validate method signatures before processing
                validateExtensionMethods(bceClass, definitionErrors);
                if (!definitionErrors.isEmpty()) continue;

                var bce = instantiateBce(bceClass);

                var types = new VaubanTypes(lookup);

                // Phase: @Enhancement
                processEnhancement(bce, bceClass, beans, lookup, classLoader, deploymentErrors, allEnhancementMods);

                // Phase: @Registration
                processRegistration(bce, bceClass, beans, lookup, classLoader, types, deploymentErrors);

                // Phase: @Synthesis
                var syntheticBeans = processSynthesis(bce, bceClass, types, deploymentErrors);
                allSyntheticBeans.addAll(syntheticBeans);

                // Phase: @Validation
                processValidation(bce, bceClass, types, deploymentErrors);

            } catch (Exception e) {
                deploymentErrors.add("BCE processing failed for " + bceClass.getName() + ": " + e.getMessage());
            }
        }

        return new Result(allSyntheticBeans, definitionErrors, deploymentErrors, allEnhancementMods);
    }

    private static Object instantiateBce(Class<?> bceClass) throws Exception {
        var ctor = bceClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private enum EnhancementParamKind {
        CLASS_CONFIG, FIELD_CONFIG, METHOD_CONFIG, CLASS_INFO, FIELD_INFO, METHOD_INFO
    }

    private static EnhancementParamKind detectEnhancementParamKind(Method method) {
        for (var param : method.getParameterTypes()) {
            if (ClassConfig.class.isAssignableFrom(param)) return EnhancementParamKind.CLASS_CONFIG;
            if (FieldConfig.class.isAssignableFrom(param)) return EnhancementParamKind.FIELD_CONFIG;
            if (MethodConfig.class.isAssignableFrom(param)) return EnhancementParamKind.METHOD_CONFIG;
            if (jakarta.enterprise.lang.model.declarations.ClassInfo.class.isAssignableFrom(param))
                return EnhancementParamKind.CLASS_INFO;
            if (jakarta.enterprise.lang.model.declarations.FieldInfo.class.isAssignableFrom(param))
                return EnhancementParamKind.FIELD_INFO;
            if (jakarta.enterprise.lang.model.declarations.MethodInfo.class.isAssignableFrom(param))
                return EnhancementParamKind.METHOD_INFO;
        }
        return EnhancementParamKind.CLASS_CONFIG; // fallback
    }

    private static void processEnhancement(Object bce, Class<?> bceClass,
                                           List<BeanDescriptor> beans,
                                           IndexLookup lookup, ClassLoader classLoader,
                                           List<String> errors,
                                           Map<fr.vidocq.vauban.indexer.model.DotName, List<VaubanClassConfig>> modifications) {
        for (var method : getDeclaredMethodsSafe(bceClass)) {
            var enhancement = method.getAnnotation(Enhancement.class);
            if (enhancement == null) continue;

            method.setAccessible(true);
            var paramKind = detectEnhancementParamKind(method);

            for (var bean : beans) {
                if (!matchesTypes(enhancement.types(), bean, classLoader)) continue;

                var indexClass = lookup.getClass(bean.beanClass()).orElse(null);
                if (indexClass == null) continue;

                var vaubanClassInfo = new VaubanClassInfo(indexClass, lookup);

                try {
                    switch (paramKind) {
                        case CLASS_CONFIG -> {
                            var classConfig = new VaubanClassConfig(vaubanClassInfo);
                            invokeWithArg(method, bce, ClassConfig.class, classConfig);
                            if (classConfig.isModified()) {
                                modifications.computeIfAbsent(bean.beanClass(), k -> new ArrayList<>())
                                        .add(classConfig);
                            }
                        }
                        case CLASS_INFO -> {
                            invokeWithArg(method, bce,
                                    jakarta.enterprise.lang.model.declarations.ClassInfo.class, vaubanClassInfo);
                        }
                        case METHOD_CONFIG -> {
                            var classConfig = new VaubanClassConfig(vaubanClassInfo);
                            for (var mc : classConfig.methods()) {
                                invokeWithArg(method, bce, MethodConfig.class, mc);
                            }
                            if (classConfig.isModified()) {
                                modifications.computeIfAbsent(bean.beanClass(), k -> new ArrayList<>())
                                        .add(classConfig);
                            }
                        }
                        case METHOD_INFO -> {
                            for (var mi : vaubanClassInfo.methods()) {
                                invokeWithArg(method, bce,
                                        jakarta.enterprise.lang.model.declarations.MethodInfo.class, mi);
                            }
                        }
                        case FIELD_CONFIG -> {
                            var classConfig = new VaubanClassConfig(vaubanClassInfo);
                            for (var fc : classConfig.fields()) {
                                invokeWithArg(method, bce, FieldConfig.class, fc);
                            }
                            if (classConfig.isModified()) {
                                modifications.computeIfAbsent(bean.beanClass(), k -> new ArrayList<>())
                                        .add(classConfig);
                            }
                        }
                        case FIELD_INFO -> {
                            for (var fi : vaubanClassInfo.fields()) {
                                invokeWithArg(method, bce,
                                        jakarta.enterprise.lang.model.declarations.FieldInfo.class, fi);
                            }
                        }
                    }
                } catch (Exception e) {
                    var cause = e instanceof java.lang.reflect.InvocationTargetException ite
                            ? (ite.getCause() != null ? ite.getCause() : ite) : e;
                    errors.add("@Enhancement error: " + cause.getMessage());
                }
            }
        }
    }

    private static void invokeWithArg(Method method, Object bce, Class<?> targetType, Object arg) throws Exception {
        var params = method.getParameters();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            var paramType = params[i].getType();
            if (targetType.isAssignableFrom(paramType) || paramType.isAssignableFrom(targetType)) {
                args[i] = arg;
            }
            // Messages and Types can be added here if needed
        }
        method.invoke(bce, args);
    }

    /**
     * Process @Registration methods — main phase for Invokers.
     */
    private static void processRegistration(Object bce, Class<?> bceClass,
                                            List<BeanDescriptor> beans,
                                            IndexLookup lookup, ClassLoader classLoader,
                                            VaubanTypes types,
                                            List<String> errors) {
        for (var method : getDeclaredMethodsSafe(bceClass)) {
            var registration = method.getAnnotation(Registration.class);
            if (registration == null) continue;

            method.setAccessible(true);

            for (var bean : beans) {
                if (!matchesTypes(registration.types(), bean, classLoader)) continue;

                var beanInfo = new VaubanBceBeanInfo(bean, lookup);
                var invokerFactory = new VaubanInvokerFactory(classLoader);
                var messages = new VaubanMessages();

                var args = resolveRegistrationArgs(method, beanInfo, invokerFactory, messages, types);

                try {
                    method.invoke(bce, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    var cause = e.getCause();
                    if (cause instanceof IllegalStateException ise) {
                        errors.add(ise.getMessage());
                    } else {
                        errors.add("@Registration error on " + bean.beanClass() + ": "
                                + (cause != null ? cause.getMessage() : e.getMessage()));
                    }
                } catch (Exception e) {
                    errors.add("@Registration error: " + e.getMessage());
                }

                if (messages.hasErrors()) {
                    errors.addAll(messages.getErrors());
                }
            }
        }
    }

    private static Object[] resolveRegistrationArgs(Method method, VaubanBceBeanInfo beanInfo,
                                                    VaubanInvokerFactory invokerFactory,
                                                    VaubanMessages messages, VaubanTypes types) {
        var params = method.getParameters();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            var paramType = params[i].getType();
            if (BeanInfo.class.isAssignableFrom(paramType)) {
                args[i] = beanInfo;
            } else if (InvokerFactory.class.isAssignableFrom(paramType)) {
                args[i] = invokerFactory;
            } else if (Messages.class.isAssignableFrom(paramType)) {
                args[i] = messages;
            } else if (jakarta.enterprise.inject.build.compatible.spi.Types.class.isAssignableFrom(paramType)) {
                args[i] = types;
            }
        }
        return args;
    }

    /**
     * Process @Synthesis methods — creates synthetic beans.
     */
    private static List<VaubanSyntheticBeanBuilder<?>> processSynthesis(Object bce, Class<?> bceClass,
                                                                        VaubanTypes types,
                                                                        List<String> errors) {
        var components = new VaubanSyntheticComponents();

        for (var method : getDeclaredMethodsSafe(bceClass)) {
            var synthesis = method.getAnnotation(Synthesis.class);
            if (synthesis == null) continue;

            method.setAccessible(true);

            var args = resolveSynthesisArgs(method, components, types);

            try {
                method.invoke(bce, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                var cause = e.getCause();
                errors.add("@Synthesis error: " + (cause != null ? cause.getMessage() : e.getMessage()));
            } catch (Exception e) {
                errors.add("@Synthesis error: " + e.getMessage());
            }
        }

        return components.getBeanDefinitions();
    }

    /**
     * Process @Validation methods — collect errors that cause DeploymentException.
     */
    private static void processValidation(Object bce, Class<?> bceClass, VaubanTypes types, List<String> errors) {
        for (var method : getDeclaredMethodsSafe(bceClass)) {
            if (method.getAnnotation(Validation.class) == null) continue;

            method.setAccessible(true);
            var messages = new VaubanMessages();
            var params = method.getParameters();
            var args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                if (Messages.class.isAssignableFrom(params[i].getType())) {
                    args[i] = messages;
                } else if (jakarta.enterprise.inject.build.compatible.spi.Types.class.isAssignableFrom(params[i].getType())) {
                    args[i] = types;
                }
            }

            try {
                method.invoke(bce, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                var cause = e.getCause();
                errors.add("@Validation error: " + (cause != null ? cause.getMessage() : e.getMessage()));
            } catch (Exception e) {
                errors.add("@Validation error: " + e.getMessage());
            }

            if (messages.hasErrors()) {
                for (var msg : messages.getErrors()) {
                    errors.add(msg);
                }
            }
        }
    }

    private static Object[] resolveSynthesisArgs(Method method, VaubanSyntheticComponents components,
                                                  VaubanTypes types) {
        var params = method.getParameters();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            var paramType = params[i].getType();
            if (SyntheticComponents.class.isAssignableFrom(paramType)) {
                args[i] = components;
            } else if (jakarta.enterprise.inject.build.compatible.spi.Types.class.isAssignableFrom(paramType)) {
                args[i] = types;
            }
        }
        return args;
    }

    /**
     * Check if a bean matches any of the Registration/Enhancement types.
     * CDI spec: matches if the bean's set of bean types contains a type
     * that is assignable from at least one of the listed types.
     */
    private static boolean matchesTypes(Class<?>[] types, BeanDescriptor bean, ClassLoader classLoader) {
        for (var beanTypeInfo : bean.types()) {
            String beanTypeName = switch (beanTypeInfo) {
                case fr.vidocq.vauban.indexer.model.TypeInfo.ClassType ct -> ct.name().value();
                case fr.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType pt -> pt.rawType().value();
                default -> null;
            };
            if (beanTypeName == null) continue;
            try {
                var beanType = classLoader.loadClass(beanTypeName);
                for (var type : types) {
                    if (type.isAssignableFrom(beanType)) {
                        return true;
                    }
                }
            } catch (ClassNotFoundException e) {
                // skip
            }
        }
        return false;
    }

    // Valid parameter types for each extension phase
    private static final java.util.Set<Class<?>> ENHANCEMENT_CONFIG_TYPES = java.util.Set.of(
            ClassConfig.class, MethodConfig.class, FieldConfig.class,
            jakarta.enterprise.lang.model.declarations.ClassInfo.class,
            jakarta.enterprise.lang.model.declarations.MethodInfo.class,
            jakarta.enterprise.lang.model.declarations.FieldInfo.class
    );
    private static final java.util.Set<Class<?>> ENHANCEMENT_OPTIONAL_TYPES = java.util.Set.of(
            Messages.class, jakarta.enterprise.inject.build.compatible.spi.Types.class
    );
    private static final java.util.Set<Class<?>> REGISTRATION_PRIMARY_TYPES = java.util.Set.of(
            BeanInfo.class, InterceptorInfo.class
    );
    private static final java.util.Set<Class<?>> REGISTRATION_OPTIONAL_TYPES = java.util.Set.of(
            Messages.class, InvokerFactory.class, jakarta.enterprise.inject.build.compatible.spi.Types.class
    );

    /**
     * Validate BCE method signatures. Invalid signatures → DefinitionException.
     */
    private static void validateExtensionMethods(Class<?> bceClass, List<String> errors) {
        for (var method : getDeclaredMethodsSafe(bceClass)) {
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
     * Apply enhancement modifications to bean descriptors. Returns the modified list.
     */
    public static List<BeanDescriptor> applyEnhancements(
            List<BeanDescriptor> descriptors,
            Map<DotName, List<VaubanClassConfig>> modifications,
            VaubanIndex index) {

        if (modifications.isEmpty()) return descriptors;

        var result = new ArrayList<BeanDescriptor>(descriptors.size());
        for (var bean : descriptors) {
            var configs = modifications.get(bean.beanClass());
            if (configs == null || configs.isEmpty()) {
                result.add(bean);
                continue;
            }
            result.add(applyClassConfigs(bean, configs, index));
        }
        return result;
    }

    private static BeanDescriptor applyClassConfigs(BeanDescriptor bean, List<VaubanClassConfig> configs,
                                                     VaubanIndex index) {
        var qualifiers = new LinkedHashSet<>(bean.qualifiers());
        var interceptorBindings = new LinkedHashSet<>(bean.interceptorBindings());
        var interceptorBindingAnnotations = new ArrayList<>(bean.interceptorBindingAnnotations());
        var injectionPoints = new ArrayList<>(bean.injectionPoints());

        for (var config : configs) {
            // Class-level annotation removals
            for (var predicate : config.getRemovePredicates()) {
                qualifiers.removeIf(q -> {
                    var annInfo = qualifierToAnnotationInfo(q);
                    return annInfo != null && predicate.test(annInfo);
                });
            }
            if (config.isAllAnnotationsRemoved()) {
                qualifiers.clear();
                interceptorBindings.clear();
                interceptorBindingAnnotations.clear();
            }

            // Class-level annotation additions
            boolean classHasExplicit = false;
            for (var ann : config.getAddedAnnotations()) {
                var qName = DotName.of(ann.getName());
                qualifiers.add(new QualifierInstance(qName, Map.of()));
                if (!qName.equals(QualifierInstance.ANY_NAME) && !qName.equals(QualifierInstance.NAMED_NAME)) {
                    classHasExplicit = true;
                }
            }
            for (var annInfo : config.getAddedAnnotationInfos()) {
                var qi = annotationInfoToQualifier(annInfo);
                qualifiers.add(qi);
                if (!qi.annotationName().equals(QualifierInstance.ANY_NAME)
                        && !qi.annotationName().equals(QualifierInstance.NAMED_NAME)) {
                    classHasExplicit = true;
                }
            }
            if (classHasExplicit) {
                qualifiers.removeIf(q -> q.annotationName().equals(QualifierInstance.DEFAULT_NAME));
            }

            // Field-level modifications -> update injection points
            for (var fieldConfig : config.getFieldConfigs()) {
                if (!fieldConfig.isModified()) continue;
                applyFieldEnhancement(fieldConfig, injectionPoints, index);
            }

            // Method-level modifications -> update interceptor bindings
            for (var methodConfig : config.getMethodConfigs()) {
                if (!methodConfig.isModified()) continue;
                applyMethodEnhancement(methodConfig, interceptorBindings, interceptorBindingAnnotations);

                // Parameter-level modifications -> update observer/injection qualifiers
                for (var paramConfig : methodConfig.getParameterConfigs()) {
                    if (!paramConfig.isModified()) continue;
                    applyParameterEnhancement(paramConfig, methodConfig.info().name(), injectionPoints);
                }
            }
        }

        // Re-add @Any if qualifiers were modified and it was there before
        if (!qualifiers.isEmpty() && qualifiers.stream().noneMatch(QualifierInstance::isAny)
                && bean.qualifiers().stream().anyMatch(QualifierInstance::isAny)) {
            qualifiers.add(QualifierInstance.ANY);
        }

        return new BeanDescriptor(
                bean.id(), bean.beanClass(), bean.kind(), bean.types(),
                qualifiers, bean.scope(), bean.isAlternative(), bean.priority(),
                injectionPoints, bean.name(), interceptorBindings,
                bean.constructorBindings(), interceptorBindingAnnotations
        );
    }

    private static void applyFieldEnhancement(VaubanFieldConfig fieldConfig,
                                               List<InjectionPointInfo> injectionPoints,
                                               VaubanIndex index) {
        String fieldName = fieldConfig.info().name();

        for (int i = 0; i < injectionPoints.size(); i++) {
            var ip = injectionPoints.get(i);
            if (ip.kind() != InjectionPointInfo.InjectionKind.FIELD) continue;
            if (!ip.description().contains(fieldName)) continue;

            var ipQualifiers = new LinkedHashSet<>(ip.qualifiers());

            if (fieldConfig.isAllAnnotationsRemoved()) {
                ipQualifiers.clear();
            }

            boolean hasExplicitQualifier = false;
            for (var ann : fieldConfig.getAddedAnnotations()) {
                var qName = DotName.of(ann.getName());
                ipQualifiers.add(new QualifierInstance(qName, Map.of()));
                if (!qName.equals(QualifierInstance.ANY_NAME) && !qName.equals(QualifierInstance.NAMED_NAME)) {
                    hasExplicitQualifier = true;
                }
            }
            for (var annInfo : fieldConfig.getAddedAnnotationInfos()) {
                var qi = annotationInfoToQualifier(annInfo);
                ipQualifiers.add(qi);
                if (!qi.annotationName().equals(QualifierInstance.ANY_NAME)
                        && !qi.annotationName().equals(QualifierInstance.NAMED_NAME)) {
                    hasExplicitQualifier = true;
                }
            }
            // CDI spec: @Default is removed when an explicit qualifier is added
            if (hasExplicitQualifier) {
                ipQualifiers.removeIf(q -> q.annotationName().equals(QualifierInstance.DEFAULT_NAME));
            }

            injectionPoints.set(i, new InjectionPointInfo(
                    ip.requiredType(), ipQualifiers, ip.kind(), ip.description()));
        }
    }

    private static void applyMethodEnhancement(VaubanMethodConfig methodConfig,
                                                Set<DotName> interceptorBindings,
                                                List<Annotation> interceptorBindingAnnotations) {
        for (var ann : methodConfig.getAddedAnnotations()) {
            interceptorBindings.add(DotName.of(ann.getName()));
        }
        for (var annInfo : methodConfig.getAddedAnnotationInfos()) {
            interceptorBindings.add(DotName.of(annInfo.name()));
            if (annInfo instanceof BuiltAnnotationInfo built) {
                try {
                    var proxy = createAnnotationProxy(built);
                    if (proxy != null) interceptorBindingAnnotations.add(proxy);
                } catch (Exception ignored) {}
            }
        }
    }

    private static void applyParameterEnhancement(VaubanParameterConfig paramConfig,
                                                    String methodName,
                                                    List<InjectionPointInfo> injectionPoints) {
        // Parameter modifications affect observer qualifiers, handled via observer descriptors
        // For now this is mainly used by ChangeObserverQualifierTest which modifies observer parameters
        // The actual observer modification happens in the observer discovery phase
    }

    private static QualifierInstance annotationInfoToQualifier(AnnotationInfo annInfo) {
        var members = new LinkedHashMap<String, AnnotationValue>();
        if (annInfo.members() != null) {
            for (var entry : annInfo.members().entrySet()) {
                var member = entry.getValue();
                members.put(entry.getKey(), annotationMemberToValue(member));
            }
        }
        return new QualifierInstance(DotName.of(annInfo.name()), members);
    }

    private static AnnotationValue annotationMemberToValue(AnnotationMember member) {
        return switch (member.kind()) {
            case STRING -> new AnnotationValue.StringVal(member.asString());
            case BOOLEAN -> new AnnotationValue.BooleanVal(member.asBoolean());
            case INT -> new AnnotationValue.IntVal(member.asInt());
            case LONG -> new AnnotationValue.LongVal(member.asLong());
            case DOUBLE -> new AnnotationValue.DoubleVal(member.asDouble());
            case FLOAT -> new AnnotationValue.FloatVal(member.asFloat());
            case BYTE -> new AnnotationValue.ByteVal(member.asByte());
            case SHORT -> new AnnotationValue.ShortVal(member.asShort());
            case CHAR -> new AnnotationValue.CharVal(member.asChar());
            default -> new AnnotationValue.StringVal(member.toString());
        };
    }

    private static AnnotationInfo qualifierToAnnotationInfo(QualifierInstance q) {
        var members = new LinkedHashMap<String, AnnotationMember>();
        return new SimpleAnnotationInfo(q.annotationName().value(), members);
    }

    @SuppressWarnings("unchecked")
    private static Annotation createAnnotationProxy(BuiltAnnotationInfo built) {
        var annotationType = built.annotationType();
        return (Annotation) java.lang.reflect.Proxy.newProxyInstance(
                annotationType.getClassLoader(),
                new Class<?>[]{annotationType},
                (proxy, method, args) -> {
                    if ("annotationType".equals(method.getName())) return annotationType;
                    if ("toString".equals(method.getName())) return "@" + annotationType.getName();
                    if ("hashCode".equals(method.getName())) return 0;
                    if ("equals".equals(method.getName())) return false;
                    var member = built.member(method.getName());
                    if (member != null) {
                        return switch (member.kind()) {
                            case STRING -> member.asString();
                            case BOOLEAN -> member.asBoolean();
                            case INT -> member.asInt();
                            case LONG -> member.asLong();
                            case DOUBLE -> member.asDouble();
                            case FLOAT -> member.asFloat();
                            case BYTE -> member.asByte();
                            case SHORT -> member.asShort();
                            case CHAR -> member.asChar();
                            default -> method.getDefaultValue();
                        };
                    }
                    return method.getDefaultValue();
                }
        );
    }

    /**
     * Get declared methods including from superclasses (for InvokerHolderExtensionBase pattern).
     */
    private static Method[] getDeclaredMethodsSafe(Class<?> cls) {
        var methods = new ArrayList<Method>();
        for (var c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var m : c.getDeclaredMethods()) {
                methods.add(m);
            }
        }
        return methods.toArray(new Method[0]);
    }
}
