package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.context.CreationalContextImpl;
import fr.vidocq.vauban.core.event.EventImpl;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.reflect.ParameterizedType;

final class BeanInjector {

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
                    vaubanLookup.setField(instance, field, VaubanContainer.getCurrentInjectionPoint());
                    continue;
                }

                if (field.getType() == Instance.class
                        || field.getType() == jakarta.inject.Provider.class) {
                    Class<?> instanceType = Object.class;
                    var genericType = ManagedBean.resolveType(field.getGenericType(), typeMapping);
                    if (genericType instanceof ParameterizedType pt) {
                        var typeArg = pt.getActualTypeArguments()[0];
                        if (typeArg instanceof Class<?> c) {
                            instanceType = c;
                        }
                    }
                    var fieldQualifiers = QualifierHelper.extractFieldQualifiers(field);
                    var ownerBean = container.findBeanForInstance(instance);
                    var ip = new VaubanInjectionPoint(field, ownerBean);
                    vaubanLookup.setField(instance, field, new InstanceImpl<>(container, instanceType, fieldQualifiers, ip));
                    continue;
                }

                if (BeanManager.class.isAssignableFrom(field.getType())
                        || field.getType() == jakarta.enterprise.inject.spi.BeanContainer.class) {
                    vaubanLookup.setField(instance, field, container.getBeanManager());
                    continue;
                }

                if (field.getType() == Event.class) {
                    var eventQualifiers = QualifierHelper.collectEventQualifiers(field.getAnnotations());
                    var ownerBean = container.findBeanForInstance(instance);
                    var eventIp = new VaubanInjectionPoint(field, ownerBean);
                    vaubanLookup.setField(instance, field, new EventImpl<>(container.eventDispatcher(), eventQualifiers, eventIp));
                    continue;
                }

                var previousIp = VaubanContainer.getCurrentInjectionPoint();
                var ownerBean = container.findBeanForInstance(instance);
                VaubanContainer.setInjectionPoint(new VaubanInjectionPoint(field, ownerBean));
                try {
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
                        vaubanLookup.setField(instance, field, value);
                    }
                } finally {
                    VaubanContainer.setInjectionPoint(previousIp);
                }
            } catch (jakarta.enterprise.inject.IllegalProductException | jakarta.enterprise.inject.UnproxyableResolutionException e) {
                throw e;
            } catch (Exception e) {
                if (e.getCause() instanceof jakarta.enterprise.inject.IllegalProductException ipe) throw ipe;
                System.err.println("INJECTION FAILED FOR " + field.getName() + " ON " + instance.getClass() + " : " + e.getMessage());
                e.printStackTrace();
            }
            }
            clazz = clazz.getSuperclass();
        }
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
