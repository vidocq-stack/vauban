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
import io.vidocq.vauban.core.context.CreationalContextImpl;
import io.vidocq.vauban.core.event.EventImpl;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.reflect.ParameterizedType;

final class BeanInjector {

    private static final System.Logger LOG = System.getLogger(BeanInjector.class.getName());

    private final VaubanContainer container;
    private final VaubanLookup vaubanLookup;

    BeanInjector(VaubanContainer container, VaubanLookup vaubanLookup) {
        this.container = container;
        this.vaubanLookup = vaubanLookup;
    }

    void injectFieldsByReflection(Object instance, BeanDescriptor descriptor, CreationalContext<?> parentCtx) {
        var beanClass = instance.getClass();
        var typeMapping = ManagedBean.buildTypeVariableMapping(beanClass);
        var clazz = beanClass;
        while (clazz != null && clazz != Object.class) {
            for (var field : clazz.getDeclaredFields()) {
                if (!field.isAnnotationPresent(jakarta.inject.Inject.class)) continue;
                try {

                if (field.getType() == InjectionPoint.class) {
                    writeField(instance, field,VaubanContainer.getCurrentInjectionPoint());
                    continue;
                }

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
                    var fieldQualifiers = QualifierHelper.extractFieldQualifiers(field);
                    var ownerBean = container.findBeanForInstance(instance);
                    var ip = new VaubanInjectionPoint(field, ownerBean);
                    writeField(instance, field,new InstanceImpl<>(container, instanceType, instanceLookupType, fieldQualifiers, ip, null));
                    continue;
                }

                if (BeanManager.class.isAssignableFrom(field.getType())
                        || field.getType() == jakarta.enterprise.inject.spi.BeanContainer.class) {
                    writeField(instance, field,container.getBeanManager());
                    continue;
                }

                if (field.getType() == Event.class) {
                    var eventQualifiers = QualifierHelper.collectEventQualifiers(field.getAnnotations());
                    var ownerBean = container.findBeanForInstance(instance);
                    var eventIp = new VaubanInjectionPoint(field, ownerBean);
                    writeField(instance, field,new EventImpl<>(container.eventDispatcher(), eventQualifiers, eventIp));
                    continue;
                }

                var ownerBean = container.findBeanForInstance(instance);
                VaubanContainer.withInjectionPoint(new VaubanInjectionPoint(field, ownerBean), () -> {
                    var fieldQuals = QualifierHelper.extractFieldQualifiersWithEnhancement(field, descriptor);
                    Object value;
                    var bm = container.getBeanManager();
                    var fieldType = ManagedBean.resolveType(field.getGenericType(), typeMapping);
                    var resolvedBeans = bm.getBeans(fieldType, fieldQuals);
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
                        writeField(instance, field,value);
                    }
                });
            } catch (jakarta.enterprise.inject.IllegalProductException | jakarta.enterprise.inject.UnproxyableResolutionException e) {
                throw e;
            } catch (Exception e) {
                if (e.getCause() instanceof jakarta.enterprise.inject.IllegalProductException ipe) throw ipe;
                LOG.log(System.Logger.Level.ERROR,
                        "Injection failed for " + field.getName() + " on " + instance.getClass(), e);
            }
            }
            clazz = clazz.getSuperclass();
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
    private void writeField(Object instance, java.lang.reflect.Field field, Object value) {
        var declaringClass = field.getDeclaringClass().getName();
        if (container.componentProviders().injectField(instance, declaringClass, field.getName(), value)) {
            return;
        }
        vaubanLookup.setField(instance, field, value);
    }

    void callInitializerMethods(Object instance, CreationalContext<?> ctx) {
        var clazz = instance.getClass();
        if (clazz.getName().contains("$$Intercepted")) {
            clazz = clazz.getSuperclass();
        }
        var ownerBean = container.findBeanForInstance(instance);
        var typeMapping = ManagedBean.buildTypeVariableMapping(clazz);
        var current = clazz;
        while (current != null && current != Object.class) {
            for (var method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                try {
                    var paramTypes = method.getParameterTypes();
                    var rawGenericParamTypes = method.getGenericParameterTypes();
                    var genericParamTypes = new java.lang.reflect.Type[rawGenericParamTypes.length];
                    for (int i = 0; i < rawGenericParamTypes.length; i++) {
                        genericParamTypes[i] = ManagedBean.resolveType(rawGenericParamTypes[i], typeMapping);
                    }
                    var params = method.getParameters();
                    var args = new Object[paramTypes.length];
                    var transientContexts = new java.util.ArrayList<CreationalContextImpl<?>>();
                    for (int i = 0; i < paramTypes.length; i++) {
                        var paramQuals = QualifierHelper.extractParamQualifiers(params[i]);
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
            }
            current = current.getSuperclass();
        }
    }
}
