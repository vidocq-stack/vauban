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
import io.vidocq.vauban.core.bean.model.DisposerDescriptor;
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.resolution.BeanResolver;
import io.vidocq.vauban.core.bean.resolution.QualifierMatcher;
import io.vidocq.vauban.core.context.CreationalContextImpl;
import io.vidocq.vauban.core.event.EventImpl;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;

import java.lang.annotation.Annotation;

import java.lang.reflect.ParameterizedType;
import java.util.List;

final class DisposerInvoker {

    private final VaubanContainer container;
    private final VaubanLookup vaubanLookup;

    DisposerInvoker(VaubanContainer container, VaubanLookup vaubanLookup) {
        this.container = container;
        this.vaubanLookup = vaubanLookup;
    }

    static void validateDisposerParameters(
            List<DisposerDescriptor> disposers,
            List<BeanDescriptor> descriptors,
            VaubanIndex index,
            QualifierMatcher qualifierMatcher) {
        var assignability = new AssignabilityRules(index);
        var tempResolver = new BeanResolver(
                descriptors, List.of(), assignability, qualifierMatcher);

        for (var disposer : disposers) {
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var clazz = Class.forName(disposer.declaringClass().value(), false, cl);
                for (var method : clazz.getDeclaredMethods()) {
                    if (!method.getName().equals(disposer.methodName())) continue;
                    for (int pi = 0; pi < method.getParameterCount(); pi++) {
                        var param = method.getParameters()[pi];
                        if (param.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) continue;
                        var paramType = param.getType();
                        var paramTypeName = paramType.getName();
                        if (paramTypeName.equals("jakarta.enterprise.inject.spi.Bean")) {
                            var genericType = method.getGenericParameterTypes()[pi];
                            if (genericType instanceof java.lang.reflect.ParameterizedType pt
                                    && pt.getActualTypeArguments().length > 0
                                    && !(pt.getActualTypeArguments()[0] instanceof java.lang.reflect.WildcardType)) {
                                throw new jakarta.enterprise.inject.spi.DefinitionException(
                                    "Disposer method " + disposer.declaringClass().simpleName() + "." + disposer.methodName()
                                    + "(): Bean parameter must use wildcard type (Bean<?>), not concrete type");
                            }
                            continue;
                        }
                        if (paramTypeName.equals("jakarta.enterprise.inject.spi.BeanManager")
                                || paramTypeName.equals("jakarta.enterprise.inject.spi.BeanContainer")
                                || paramTypeName.equals("jakarta.enterprise.inject.spi.InjectionPoint")
                                || paramTypeName.equals("jakarta.enterprise.inject.Instance")
                                || paramTypeName.equals("jakarta.inject.Provider")
                                || paramTypeName.equals("jakarta.enterprise.event.Event")) continue;
                        // What the parameter declares, not @Default assumed for it: a qualified
                        // parameter used to read as unsatisfied against beans that carry its
                        // qualifier (vauban#89).
                        var described = describedParameter(disposer, pi);
                        var ip = described != null ? described : new InjectionPointInfo(
                                new TypeInfo.ClassType(DotName.of(paramType.getName())),
                                java.util.Set.of(new QualifierInstance(
                                        DotName.of("jakarta.enterprise.inject.Default"),
                                        java.util.Map.of()),
                                        new QualifierInstance(
                                        DotName.of("jakarta.enterprise.inject.Any"),
                                        java.util.Map.of())),
                                InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
                                "disposer parameter " + param.getName());
                        var result = tempResolver.resolveInjectionPoint(ip);
                        if (result.status() == BeanResolver.ResolutionResult.Status.UNSATISFIED) {
                            throw new jakarta.enterprise.inject.spi.DeploymentException(
                                    "Disposer method " + disposer.declaringClass().simpleName() + "." + disposer.methodName()
                                            + "(): unsatisfied dependency for parameter of type " + paramType.getName());
                        } else if (result.status() == BeanResolver.ResolutionResult.Status.AMBIGUOUS) {
                            throw new jakarta.enterprise.inject.spi.DeploymentException(
                                    "Disposer method " + disposer.declaringClass().simpleName() + "." + disposer.methodName()
                                            + "(): ambiguous dependency for parameter of type " + paramType.getName());
                        }
                    }
                    break;
                }
            } catch (jakarta.enterprise.inject.spi.DeploymentException | jakarta.enterprise.inject.spi.DefinitionException e) {
                throw e;
            } catch (Exception e) {
                // Skip
            }
        }
    }

    static void validateDisposerMethodDefinitions(
            List<DisposerDescriptor> disposers,
            List<BeanDescriptor> descriptors) {
        var disposersByKey = new java.util.HashMap<String, List<DisposerDescriptor>>();
        for (var disposer : disposers) {
            var key = disposer.declaringClass().value() + "#" + disposer.disposedType();
            disposersByKey.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(disposer);
        }
        for (var entry : disposersByKey.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw new jakarta.enterprise.inject.spi.DefinitionException(
                        "Multiple disposer methods for the same producer type: " + entry.getKey());
            }
        }
    }

    void wireDisposers(List<BeanDescriptor> descriptors, List<DisposerDescriptor> disposers) {
        for (var descriptor : descriptors) {
            if (descriptor.kind() != BeanDescriptor.BeanKind.PRODUCER_METHOD
                    && descriptor.kind() != BeanDescriptor.BeanKind.PRODUCER_FIELD) {
                continue;
            }

            var bean = container.beans.get(descriptor.id());
            if (bean == null) continue;

            for (var disposer : disposers) {
                if (!disposer.declaringClass().equals(descriptor.beanClass())) continue;

                boolean typeMatches = false;
                for (var bt : descriptor.types()) {
                    if (bt.equals(disposer.disposedType())) {
                        typeMatches = true;
                        break;
                    }
                    if (bt instanceof TypeInfo.ClassType btCt
                            && disposer.disposedType() instanceof TypeInfo.ClassType dCt
                            && btCt.name().equals(dCt.name())) {
                        typeMatches = true;
                        break;
                    }
                }
                if (!typeMatches && disposer.disposedType() instanceof TypeInfo.ClassType dCt) {
                    try {
                        var cl = Thread.currentThread().getContextClassLoader();
                        var disposedClass = Class.forName(dCt.name().value(), false, cl);
                        for (var bt : descriptor.types()) {
                            String btName = switch (bt) {
                                case TypeInfo.ClassType btCt -> btCt.name().value();
                                case TypeInfo.ParameterizedType pt -> pt.rawType().value();
                                case null, default -> null;
                            };
                            if (btName != null) {
                                var beanTypeClass = Class.forName(btName, false, cl);
                                if (disposedClass.isAssignableFrom(beanTypeClass)) {
                                    typeMatches = true;
                                    break;
                                }
                            }
                        }
                    } catch (ClassNotFoundException e) { /* skip */ }
                }
                if (!typeMatches) continue;

                boolean disposerHasExplicitAny = hasExplicitAnyOnDisposerParam(disposer);
                boolean qualifiersMatch;
                if (disposerHasExplicitAny) {
                    qualifiersMatch = true;
                } else {
                    var disposerQualNames = disposer.qualifiers().stream()
                            .filter(q -> !q.isDefault() && !q.isAny())
                            .map(q -> q.annotationName())
                            .collect(java.util.stream.Collectors.toSet());
                    var producerQualNames = descriptor.qualifiers().stream()
                            .filter(q -> !q.isDefault() && !q.isAny())
                            .map(q -> q.annotationName())
                            .collect(java.util.stream.Collectors.toSet());
                    if (disposerQualNames.isEmpty()) {
                        qualifiersMatch = producerQualNames.isEmpty();
                    } else if (!producerQualNames.containsAll(disposerQualNames)) {
                        qualifiersMatch = false;
                    } else {
                        qualifiersMatch = verifyDisposerQualifiersViaReflection(descriptor, disposer);
                    }
                }
                if (!qualifiersMatch) continue;

                if (bean.getDestroyer() != null) {
                    throw new jakarta.enterprise.inject.spi.DefinitionException(
                        "Multiple disposer methods for producer " + descriptor.id() + " in " + descriptor.beanClass());
                }
                bean.setDestroyer((instance, ctx) -> callDisposer(instance, disposer, ctx));
            }
        }
    }

    boolean matchesDisposerType(BeanDescriptor descriptor, DisposerDescriptor disposer) {
        for (var bt : descriptor.types()) {
            if (bt.equals(disposer.disposedType())) return true;
            if (bt instanceof TypeInfo.ClassType btCt
                    && disposer.disposedType() instanceof TypeInfo.ClassType dCt
                    && btCt.name().equals(dCt.name())) {
                return true;
            }
        }
        if (disposer.disposedType() instanceof TypeInfo.ClassType dCt) {
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var disposedClass = Class.forName(dCt.name().value(), false, cl);
                for (var bt : descriptor.types()) {
                    String btName = switch (bt) {
                        case TypeInfo.ClassType btCt2 -> btCt2.name().value();
                        case TypeInfo.ParameterizedType pt -> pt.rawType().value();
                        case null, default -> null;
                    };
                    if (btName != null) {
                        var beanTypeClass = Class.forName(btName, false, cl);
                        if (disposedClass.isAssignableFrom(beanTypeClass)) return true;
                    }
                }
            } catch (ClassNotFoundException e) { /* skip */ }
        }
        return false;
    }

    boolean hasExplicitAnyOnDisposerParam(DisposerDescriptor disposer) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var declaringClass = Class.forName(disposer.declaringClass().value(), false, cl);
            for (var method : declaringClass.getDeclaredMethods()) {
                if (method.getName().equals(disposer.methodName())
                        && method.getParameterCount() > disposer.parameterIndex()) {
                    for (var ann : method.getParameters()[disposer.parameterIndex()].getAnnotations()) {
                        if (ann.annotationType() == jakarta.enterprise.inject.Any.class) return true;
                    }
                    return false;
                }
            }
        } catch (Exception e) { /* ignore */ }
        return false;
    }

    boolean verifyDisposerQualifiersViaReflection(BeanDescriptor producer, DisposerDescriptor disposer) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var declaringClass = Class.forName(producer.beanClass().value(), false, cl);

            java.lang.annotation.Annotation[] producerAnnotations = null;
            var producerId = producer.id().value();
            if (producer.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD) {
                var methodName = producerId.contains("#") ? producerId.substring(producerId.indexOf('#') + 1) : null;
                if (methodName != null) {
                    for (var m : declaringClass.getDeclaredMethods()) {
                        if (m.getName().equals(methodName) && m.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                            producerAnnotations = m.getAnnotations();
                            break;
                        }
                    }
                }
            } else if (producer.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD) {
                var fieldName = producerId.contains(".") ? producerId.substring(producerId.lastIndexOf('.') + 1) : null;
                if (fieldName != null) {
                    try {
                        var f = declaringClass.getDeclaredField(fieldName);
                        producerAnnotations = f.getAnnotations();
                    } catch (NoSuchFieldException e) { /* skip */ }
                }
            }

            if (producerAnnotations == null) return true;

            java.lang.annotation.Annotation[] disposerParamAnnotations = null;
            for (var m : declaringClass.getDeclaredMethods()) {
                if (m.getName().equals(disposer.methodName()) && m.getParameterCount() > disposer.parameterIndex()) {
                    disposerParamAnnotations = m.getParameterAnnotations()[disposer.parameterIndex()];
                    break;
                }
            }

            if (disposerParamAnnotations == null) return true;

            for (var dAnn : disposerParamAnnotations) {
                if (!dAnn.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                        && dAnn.annotationType() != jakarta.enterprise.inject.Default.class
                        && dAnn.annotationType() != jakarta.enterprise.inject.Any.class) continue;
                if (dAnn.annotationType() == jakarta.enterprise.inject.Default.class
                        || dAnn.annotationType() == jakarta.enterprise.inject.Any.class
                        || dAnn.annotationType() == jakarta.enterprise.inject.Disposes.class) continue;

                boolean found = false;
                for (var pAnn : producerAnnotations) {
                    if (pAnn.annotationType() == dAnn.annotationType() && pAnn.equals(dAnn)) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /** The injection point the descriptor records for parameter {@code index}, or {@code null}. */
    private static InjectionPointInfo describedParameter(DisposerDescriptor disposer, int index) {
        var wanted = InjectionPointInfo.parameterDescription(
                disposer.declaringClass().simpleName(), disposer.methodName(), index);
        for (var point : disposer.injectionPoints()) {
            if (point.description().equals(wanted)) return point;
        }
        return null;
    }

    void callDisposer(Object producedInstance, DisposerDescriptor disposer, CreationalContext<?> creationalContext) {
        try {
            var declaringClass = container.loadClass(disposer.declaringClass().value());

            for (var method : declaringClass.getDeclaredMethods()) {
                if (method.getName().equals(disposer.methodName())
                        && method.getParameterCount() > disposer.parameterIndex()) {
                    var bm = container.getBeanManager();
                    var ctx = creationalContext != null ? creationalContext : new CreationalContextImpl<>();
                    try {
                        var declBeans = bm.getBeans(declaringClass);
                        var declBean = declBeans.isEmpty() ? null : bm.resolve(declBeans);
                        var isStatic = java.lang.reflect.Modifier.isStatic(method.getModifiers());
                        var declaringInstance = isStatic
                                ? null : (declBean != null
                                        ? bm.getReference(declBean, declaringClass, ctx)
                                        : container.selectByBeanClass(declaringClass));
                        var paramTypes = method.getParameterTypes();
                        var args = new Object[method.getParameterCount()];
                        args[disposer.parameterIndex()] = producedInstance;
                        for (int i = 0; i < paramTypes.length; i++) {
                            if (i == disposer.parameterIndex()) continue;
                            // What the parameter declares. Resolving on its type alone handed a
                            // qualified parameter the @Default bean (vauban#89); a parameter the
                            // descriptor does not describe still falls back on reading the method.
                            var described = describedParameter(disposer, i);
                            var qualifiers = described != null
                                    ? QualifierUtils.toAnnotations(described.declaredQualifiers(), null, null,
                                            container.qualifierMatcher().types()).toArray(new Annotation[0])
                                    : QualifierHelper.extractParamQualifiers(method.getParameters()[i]);
                            if (paramTypes[i] == BeanManager.class) {
                                args[i] = container.getBeanManager();
                            } else if (paramTypes[i] == Event.class) {
                                var eventIp = new VaubanInjectionPoint(method.getGenericParameterTypes()[i],
                                        java.util.Set.of(qualifiers), null, method);
                                args[i] = new EventImpl<>(container.eventDispatcher(),
                                        QualifierHelper.eventQualifiers(qualifiers), eventIp);
                            } else if (paramTypes[i] == Instance.class) {
                                Class<?> instanceType = Object.class;
                                var genericType = method.getGenericParameterTypes()[i];
                                if (genericType instanceof ParameterizedType pt) {
                                    var typeArg = pt.getActualTypeArguments()[0];
                                    if (typeArg instanceof Class<?> c) instanceType = c;
                                }
                                var ip = new VaubanInjectionPoint(genericType, java.util.Set.of(qualifiers), null, method);
                                args[i] = new InstanceImpl<>(container, instanceType, ip);
                            } else {
                                var beans = bm.getBeans(paramTypes[i], qualifiers);
                                var bean = beans.isEmpty() ? null : bm.resolve(beans);
                                args[i] = bean != null ? bm.getReference(bean, paramTypes[i], ctx) : container.select(paramTypes[i]);
                            }
                        }
                        if (isStatic) {
                            vaubanLookup.invokeStaticMethod(method, args);
                        } else {
                            vaubanLookup.invokeMethod(declaringInstance, method, args);
                        }
                    } finally {
                        ctx.release();
                    }
                    return;
                }
            }
        } catch (Exception e) {
            // CDI spec: exceptions in disposer methods are suppressed
        }
    }

    static void validateDisposerDefinitions(
            List<DisposerDescriptor> disposers,
            List<BeanDescriptor> descriptors) {
        var producerBeans = descriptors.stream()
                .filter(d -> d.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD
                        || d.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD)
                .toList();

        for (var disposer : disposers) {
            boolean found = false;
            for (var producer : producerBeans) {
                if (!producer.beanClass().equals(disposer.declaringClass())
                        && !producerDeclaredIn(producer, disposer.declaringClass())) {
                    continue;
                }
                if (disposerMatchesProducerType(disposer, producer)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                boolean isInBeanClass = descriptors.stream()
                        .anyMatch(d -> d.beanClass().equals(disposer.declaringClass())
                                && d.kind() == BeanDescriptor.BeanKind.MANAGED);
                if (isInBeanClass) {
                    throw new jakarta.enterprise.inject.spi.DefinitionException(
                            "Disposer method " + disposer.declaringClass().value() + "." + disposer.methodName()
                                    + "(): no matching producer found for disposed type " + disposer.disposedType());
                }
            }
        }
    }

    private static boolean producerDeclaredIn(BeanDescriptor producer, DotName declaringClass) {
        return producer.id().value().startsWith(declaringClass.value());
    }

    static boolean disposerMatchesProducerType(DisposerDescriptor disposer, BeanDescriptor producer) {
        for (var producerType : producer.types()) {
            if (producerType instanceof TypeInfo.ClassType ct
                    && disposer.disposedType() instanceof TypeInfo.ClassType dt
                    && ct.name().equals(dt.name())) {
                return true;
            }
            if (producerType.equals(disposer.disposedType())) {
                return true;
            }
        }
        return false;
    }
}
