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

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.context.CreationalContextImpl;
import io.vidocq.vauban.core.event.EventImpl;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class BeanInjector {

    private static final System.Logger LOG = System.getLogger(BeanInjector.class.getName());

    private final VaubanContainer container;
    private final VaubanLookup vaubanLookup;

    BeanInjector(VaubanContainer container, VaubanLookup vaubanLookup) {
        this.container = container;
        this.vaubanLookup = vaubanLookup;
    }

    /**
     * Full JSR-330 / CDI member injection for a managed bean instance: {@code @Inject} fields and
     * initializer methods, injected in the order mandated by the Jakarta Dependency Injection spec:
     *
     * <ul>
     *   <li>supertype members are injected before subtype members;</li>
     *   <li>within a class, fields are injected before methods — so a supertype's initializer
     *       methods run before a subtype's fields;</li>
     *   <li>an {@code @Inject} method that is overridden by a subtype method is injected at most
     *       once (via the override, and only if the override is itself {@code @Inject}); qualifiers
     *       are taken from the overriding method, never inherited from the overridden one;</li>
     *   <li>static members are never injected (CDI does not support static injection).</li>
     * </ul>
     */
    void performInjection(Object instance, BeanDescriptor descriptor, CreationalContext<?> ctx) {
        var beanClass = unwrapInterceptedSubclass(instance.getClass());
        var ownerBean = container.findBeanForInstance(instance);
        var typeMapping = ManagedBean.buildTypeVariableMapping(beanClass);
        for (var member : injectionOrder(beanClass, descriptor)) {
            if (member instanceof Field field) {
                injectSingleField(instance, field, descriptor, ctx, typeMapping);
            } else {
                injectSingleMethod(instance, (Method) member, descriptor, ctx, ownerBean, typeMapping);
            }
        }
    }

    /**
     * Field-only injection (used to wire {@code @Inject} fields of interceptor instances, which have
     * no initializer-method phase). Injects supertype fields before subtype fields; skips statics.
     */
    void injectFieldsByReflection(Object instance, BeanDescriptor descriptor, CreationalContext<?> parentCtx) {
        var beanClass = unwrapInterceptedSubclass(instance.getClass());
        var typeMapping = ManagedBean.buildTypeVariableMapping(beanClass);
        for (var member : injectionOrder(beanClass, descriptor)) {
            if (!(member instanceof Field field)) continue;
            injectSingleField(instance, field, descriptor, parentCtx, typeMapping);
        }
    }

    /**
     * The members {@link #performInjection} injects, in its order, mandated by the Jakarta Dependency Injection
     * spec: supertype members before subtype members; within a class, the non-static {@code @Inject} fields, then
     * the non-static {@code @Inject} methods that no subtype overrides. {@link CodegenCoverage} reads the same list.
     */
    static List<java.lang.reflect.Member> injectionOrder(Class<?> beanClass) {
        return injectionOrder(beanClass, null);
    }

    static List<java.lang.reflect.Member> injectionOrder(Class<?> beanClass, BeanDescriptor descriptor) {
        var hierarchy = hierarchySuperFirst(unwrapInterceptedSubclass(beanClass));
        var members = new ArrayList<java.lang.reflect.Member>();
        for (int i = 0; i < hierarchy.size(); i++) {
            var clazz = hierarchy.get(i);
            for (var field : clazz.getDeclaredFields()) {
                if ((field.isAnnotationPresent(jakarta.inject.Inject.class) || isEnhancedField(descriptor, field))
                        && !Modifier.isStatic(field.getModifiers())) {
                    members.add(field);
                }
            }
            var subclasses = hierarchy.subList(i + 1, hierarchy.size());
            for (var method : clazz.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(jakarta.inject.Inject.class)
                        && !isEnhancedInitializer(descriptor, method)) continue;
                if (Modifier.isStatic(method.getModifiers())) continue;
                if (isOverriddenInSubclasses(method, subclasses)) continue;
                members.add(method);
            }
        }
        return members;
    }

    private static boolean isEnhancedField(BeanDescriptor descriptor, Field field) {
        return descriptor != null && QualifierHelper.fieldPoint(descriptor.injectionPoints(), field) != null;
    }

    private static boolean isEnhancedInitializer(BeanDescriptor descriptor, Method method) {
        if (descriptor == null) return false;
        var methodDescriptor = MethodType.methodType(void.class, method.getParameterTypes()).descriptorString();
        var key = InjectionPointInfo.enhancedInitializerMethod(
                method.getDeclaringClass().getName(), method.getName(), methodDescriptor);
        return descriptor.enhancedInjectionMethods().contains(key);
    }

    /** The fields of {@link #injectionOrder}, in its order: what {@link #injectFieldsByReflection} injects. */
    static List<Field> injectedFields(Class<?> beanClass) {
        return injectionOrder(beanClass).stream()
                .filter(Field.class::isInstance).map(Field.class::cast).toList();
    }

    @SuppressWarnings({"java:S3776", "java:S1181"})
    private void injectSingleField(Object instance, Field field, BeanDescriptor descriptor,
                                   CreationalContext<?> parentCtx,
                                   Map<java.lang.reflect.TypeVariable<?>, java.lang.reflect.Type> typeMapping) {
        try {
            if (field.getType() == InjectionPoint.class) {
                writeField(instance, field, VaubanContainer.getCurrentInjectionPoint());
                return;
            }

            // What the bean's descriptor records for this field, or null for a field it does not
            // describe — an interceptor's, injected without one (vauban#70).
            var describedQualifiers = QualifierHelper.fieldQualifiers(
                    descriptor == null ? null : descriptor.injectionPoints(), field,
                    container.qualifierMatcher().types());
            var describedSet = describedQualifiers == null ? null
                    : new java.util.LinkedHashSet<>(java.util.List.of(describedQualifiers));

            if (field.getType() == Instance.class
                    || field.getType() == jakarta.inject.Provider.class) {
                Class<?> instanceType = Object.class;
                java.lang.reflect.Type instanceLookupType = Object.class;
                var genericType = ManagedBean.resolveType(field.getGenericType(), typeMapping);
                if (genericType instanceof ParameterizedType pt) {
                    var typeArg = pt.getActualTypeArguments()[0];
                    if (typeArg instanceof Class<?> c) {
                        instanceType = c;
                        instanceLookupType = c;
                    } else if (typeArg instanceof ParameterizedType nestedPt) {
                        // e.g. Provider<Optional<String>>, Provider<Set<String>> — preserve the
                        // full parameterized type so getBeans() can match synthetic beans exactly.
                        instanceType = (nestedPt.getRawType() instanceof Class<?> raw) ? raw : Object.class;
                        instanceLookupType = nestedPt;
                    }
                }
                var fieldQualifiers = describedQualifiers != null
                        ? describedQualifiers : QualifierHelper.extractFieldQualifiers(field);
                var ownerBean = container.findBeanForInstance(instance);
                var ip = new VaubanInjectionPoint(field, ownerBean, describedSet);
                writeField(instance, field, new InstanceImpl<>(container, instanceType, instanceLookupType, fieldQualifiers, ip, null));
                return;
            }

            if (BeanManager.class.isAssignableFrom(field.getType())
                    || field.getType() == jakarta.enterprise.inject.spi.BeanContainer.class) {
                writeField(instance, field, container.getBeanManager());
                return;
            }

            if (field.getType() == Event.class) {
                var eventQualifiers = describedQualifiers != null
                        ? QualifierHelper.eventQualifiers(describedQualifiers)
                        : QualifierHelper.collectEventQualifiers(field.getAnnotations());
                var ownerBean = container.findBeanForInstance(instance);
                var eventIp = new VaubanInjectionPoint(field, ownerBean, describedSet);
                writeField(instance, field, new EventImpl<>(container.eventDispatcher(), eventQualifiers, eventIp));
                return;
            }

            var ownerBean = container.findBeanForInstance(instance);
            VaubanContainer.withInjectionPoint(new VaubanInjectionPoint(field, ownerBean, describedSet), () -> {
                var fieldKeys = fieldQualifierKeys(field, descriptor);
                Object value;
                var bm = container.getBeanManager();
                var fieldType = ManagedBean.resolveType(field.getGenericType(), typeMapping);
                var resolvedBeans = bm.getBeans(fieldType, fieldKeys);
                if (resolvedBeans.isEmpty()) {
                    value = container.select(field.getType());
                } else {
                    var resolved = bm.resolve(resolvedBeans);
                    boolean needsFreshCtx = resolved instanceof ManagedBean<?> mb
                            && resolved.getScope() == jakarta.enterprise.context.Dependent.class
                            && mb.descriptor().kind() == BeanDescriptor.BeanKind.MANAGED
                            && container.interceptorManager() != null && container.interceptorManager().hasInterceptors()
                            && container.hasMethodOrClassInterceptors(mb);
                    var ctx = (parentCtx != null
                            && resolved.getScope() == jakarta.enterprise.context.Dependent.class
                            && !needsFreshCtx)
                            ? parentCtx
                            : bm.createCreationalContext(resolved);
                    value = bm.getReference(resolved, fieldType, ctx);
                    if (needsFreshCtx
                            && parentCtx instanceof CreationalContextImpl<?> parentVCtx
                            && value != null) {
                        parentVCtx.addDependentInstance(resolved, value, ctx);
                    }
                }
                if (value != null || !field.getType().isPrimitive()) {
                    writeField(instance, field, value);
                }
            });
        } catch (RuntimeException e) {
            // An injection that fails must say so. Logging it and leaving the field null hands out a
            // bean that looks built, and the failure comes back later as a NullPointerException in
            // application code naming neither the field nor the cause (BUG-20260914-17).
            // The exception keeps its type: a caller catching UnsatisfiedResolutionException or
            // IllegalProductException must still see it.
            if (e.getCause() instanceof jakarta.enterprise.inject.IllegalProductException ipe) throw ipe;
            throw e;
        } catch (Exception e) {
            throw new jakarta.enterprise.inject.CreationException("Failed to inject "
                    + field.getDeclaringClass().getName() + "." + field.getName()
                    + " on " + instance.getClass().getName(), e);
        }
    }

    /**
     * The keys of a field's qualifiers, taken from the injection point the index built — which an
     * {@code @Enhancement} may have changed. Nothing is read from the field and no qualifier type is
     * loaded, so a member value cannot be lost (BUG-20260914-02) and a type only the application's
     * class loader can see resolves all the same (BUG-20260914-05). A field the descriptor does not
     * describe — an interceptor's, injected without one — falls back on what the field carries.
     */
    private java.util.Set<io.vidocq.vauban.core.annotation.AnnotationKey> fieldQualifierKeys(
            Field field, BeanDescriptor descriptor) {
        if (descriptor != null) {
            var point = QualifierHelper.fieldPoint(descriptor.injectionPoints(), field);
            if (point != null) {
                return container.qualifierMatcher().keys(point.qualifiers());
            }
        }
        return container.qualifierMatcher().types().keys(QualifierHelper.extractFieldQualifiers(field));
    }

    /**
     * An initializer method's parameters, resolved from the descriptor that describes them — reading
     * a parameter back is only for a method the descriptor does not describe (vauban#70).
     */
    private void injectSingleMethod(Object instance, Method method, BeanDescriptor descriptor,
                                    CreationalContext<?> ctx,
                                    jakarta.enterprise.inject.spi.Bean<?> ownerBean,
                                    Map<java.lang.reflect.TypeVariable<?>, java.lang.reflect.Type> typeMapping) {
        try {
            var paramTypes = method.getParameterTypes();
            var rawGenericParamTypes = method.getGenericParameterTypes();
            var genericParamTypes = new java.lang.reflect.Type[rawGenericParamTypes.length];
            for (int i = 0; i < rawGenericParamTypes.length; i++) {
                genericParamTypes[i] = ManagedBean.resolveType(rawGenericParamTypes[i], typeMapping);
            }
            var params = method.getParameters();
            var args = new Object[paramTypes.length];
            var transientContexts = new ArrayList<CreationalContextImpl<?>>();
            for (int i = 0; i < paramTypes.length; i++) {
                var described = descriptor == null ? null : QualifierHelper.parameterQualifiers(
                        descriptor.injectionPoints(), method, i, container.qualifierMatcher().types());
                var paramQuals = described != null
                        ? described : QualifierHelper.extractParamQualifiers(params[i]);
                if (params[i].isAnnotationPresent(jakarta.enterprise.inject.TransientReference.class)) {
                    var transientCtx = new CreationalContextImpl<>();
                    args[i] = container.resolveParameter(paramTypes[i], genericParamTypes[i], transientCtx, paramQuals, method, ownerBean, params[i], i);
                    transientContexts.add(transientCtx);
                } else {
                    args[i] = container.resolveParameter(paramTypes[i], genericParamTypes[i], ctx, paramQuals, method, ownerBean, params[i], i);
                }
            }
            vaubanLookup.invokeMethod(instance, method, args);
            for (var tc : transientContexts) {
                tc.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to call initializer method: " + method.getName(), e);
        }
    }

    /**
     * Writes a resolved value into an {@code @Inject} field, delegating to the module's generated
     * {@code VaubanComponentProvider} when one owns the field's declaring class (an in-module
     * {@code putfield} — no reflection, no {@code opens}); otherwise falls back to reflective
     * {@link VaubanLookup#setField} (which still needs the qualified {@code opens} on the module
     * path). The provider is keyed on the field's declaring class, so a field inherited from a
     * superclass is routed to that superclass's provider.
     */
    private void writeField(Object instance, Field field, Object value) {
        var declaringClass = field.getDeclaringClass().getName();
        if (container.componentProviders().injectField(instance, declaringClass, field.getName(), value)) {
            return;
        }
        vaubanLookup.setField(instance, field, value);
    }

    // ---- Hierarchy / override resolution (JSR-330 §Injectable methods) ----

    private static Class<?> unwrapInterceptedSubclass(Class<?> clazz) {
        return clazz.getName().contains("$$Intercepted") ? clazz.getSuperclass() : clazz;
    }

    /** Class hierarchy from the top-most non-{@code Object} supertype down to {@code leaf}. */
    private static List<Class<?>> hierarchySuperFirst(Class<?> leaf) {
        var list = new ArrayList<Class<?>>();
        for (var c = leaf; c != null && c != Object.class; c = c.getSuperclass()) {
            list.add(c);
        }
        Collections.reverse(list);
        return list;
    }

    private static boolean isOverriddenInSubclasses(Method superMethod, List<Class<?>> subclasses) {
        for (var subclass : subclasses) {
            for (var candidate : subclass.getDeclaredMethods()) {
                if (overrides(candidate, superMethod)) return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code sub} (declared in a subtype) overrides {@code sup} per the Jakarta Dependency
     * Injection override rules (JLS 8.4.8.1): same name and parameter types, neither static nor
     * private, and — for a package-private supertype method — declared in the same package.
     */
    private static boolean overrides(Method sub, Method sup) {
        if (!sub.getName().equals(sup.getName())) return false;
        if (!Arrays.equals(sub.getParameterTypes(), sup.getParameterTypes())) return false;
        int subMod = sub.getModifiers();
        int supMod = sup.getModifiers();
        if (Modifier.isStatic(subMod) || Modifier.isStatic(supMod)) return false;
        // A private method neither overrides nor is overridden.
        if (Modifier.isPrivate(subMod) || Modifier.isPrivate(supMod)) return false;
        boolean supPackagePrivate = !Modifier.isPublic(supMod) && !Modifier.isProtected(supMod);
        if (supPackagePrivate) {
            return sub.getDeclaringClass().getPackageName().equals(sup.getDeclaringClass().getPackageName());
        }
        return true;
    }
}
