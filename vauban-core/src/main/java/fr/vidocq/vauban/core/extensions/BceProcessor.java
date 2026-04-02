package fr.vidocq.vauban.core.extensions;

import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.langmodel.IndexLookup;
import fr.vidocq.vauban.indexer.VaubanIndex;
import jakarta.enterprise.inject.build.compatible.spi.*;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

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
            List<String> deploymentErrors
    ) {}

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

        for (var bceClass : bceClasses) {
            try {
                // Validate method signatures before processing
                validateExtensionMethods(bceClass, definitionErrors);
                if (!definitionErrors.isEmpty()) continue;

                var bce = instantiateBce(bceClass);

                // Phase: @Enhancement
                processEnhancement(bce, bceClass, beans, lookup, classLoader, deploymentErrors);

                // Phase: @Registration
                processRegistration(bce, bceClass, beans, lookup, classLoader, deploymentErrors);

                // Phase: @Synthesis
                var syntheticBeans = processSynthesis(bce, bceClass, deploymentErrors);
                allSyntheticBeans.addAll(syntheticBeans);

            } catch (Exception e) {
                deploymentErrors.add("BCE processing failed for " + bceClass.getName() + ": " + e.getMessage());
            }
        }

        return new Result(allSyntheticBeans, definitionErrors, deploymentErrors);
    }

    private static Object instantiateBce(Class<?> bceClass) throws Exception {
        var ctor = bceClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    /**
     * Process @Enhancement methods — minimal impl for MethodFromDifferentClassInvokerTest.
     */
    private static void processEnhancement(Object bce, Class<?> bceClass,
                                           List<BeanDescriptor> beans,
                                           IndexLookup lookup, ClassLoader classLoader,
                                           List<String> errors) {
        for (var method : getDeclaredMethodsSafe(bceClass)) {
            var enhancement = method.getAnnotation(Enhancement.class);
            if (enhancement == null) continue;

            method.setAccessible(true);

            for (var bean : beans) {
                if (!matchesTypes(enhancement.types(), bean, classLoader)) continue;

                var indexClass = lookup.getClass(bean.beanClass()).orElse(null);
                if (indexClass == null) continue;

                var vaubanClassInfo = new fr.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo(indexClass, lookup);

                // Call with each method of the matching class
                for (var beanMethod : vaubanClassInfo.methods()) {
                    try {
                        invokeEnhancementMethod(method, bce, beanMethod);
                    } catch (Exception e) {
                        errors.add("@Enhancement error: " + e.getMessage());
                    }
                }
            }
        }
    }

    private static void invokeEnhancementMethod(Method extensionMethod, Object bce,
                                                jakarta.enterprise.lang.model.declarations.MethodInfo beanMethod) throws Exception {
        var params = extensionMethod.getParameters();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            var paramType = params[i].getType();
            if (jakarta.enterprise.lang.model.declarations.MethodInfo.class.isAssignableFrom(paramType)) {
                args[i] = beanMethod;
            } else if (MethodConfig.class.isAssignableFrom(paramType)) {
                args[i] = beanMethod; // MethodConfig extends MethodInfo
            }
        }
        extensionMethod.invoke(bce, args);
    }

    /**
     * Process @Registration methods — main phase for Invokers.
     */
    private static void processRegistration(Object bce, Class<?> bceClass,
                                            List<BeanDescriptor> beans,
                                            IndexLookup lookup, ClassLoader classLoader,
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

                var args = resolveRegistrationArgs(method, beanInfo, invokerFactory, messages);

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
                                                    VaubanMessages messages) {
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
            }
        }
        return args;
    }

    /**
     * Process @Synthesis methods — creates synthetic beans.
     */
    private static List<VaubanSyntheticBeanBuilder<?>> processSynthesis(Object bce, Class<?> bceClass,
                                                                        List<String> errors) {
        var components = new VaubanSyntheticComponents();

        for (var method : getDeclaredMethodsSafe(bceClass)) {
            var synthesis = method.getAnnotation(Synthesis.class);
            if (synthesis == null) continue;

            method.setAccessible(true);

            var args = resolveSynthesisArgs(method, components);

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

    private static Object[] resolveSynthesisArgs(Method method, VaubanSyntheticComponents components) {
        var params = method.getParameters();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            var paramType = params[i].getType();
            if (SyntheticComponents.class.isAssignableFrom(paramType)) {
                args[i] = components;
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
