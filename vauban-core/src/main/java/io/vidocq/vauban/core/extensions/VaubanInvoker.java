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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.invoke.Invoker;
import io.vidocq.vauban.core.container.InstanceImpl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SuppressWarnings({"unchecked", "java:S3011", "java:S3776"}) // CDI spec requires reflective access
public final class VaubanInvoker implements Invoker<Object, Object>, InvokerInfo {

    private final Method method;
    private final Class<?> beanClass;
    private final boolean isStatic;
    private final boolean instanceLookup;
    private final Set<Integer> argumentLookups;

    public VaubanInvoker(Method method, Class<?> beanClass,
                         boolean instanceLookup, Set<Integer> argumentLookups) {
        this.method = method;
        this.beanClass = beanClass;
        this.isStatic = Modifier.isStatic(method.getModifiers());
        this.instanceLookup = instanceLookup;
        this.argumentLookups = Set.copyOf(argumentLookups);
        makeAccessibleSafe(method);
    }

    private static void makeAccessibleSafe(java.lang.reflect.AccessibleObject member) {
        member.trySetAccessible();
    }

    @Override
    @SuppressWarnings("java:S112") // CDI spec: container exceptions propagate as RuntimeException
    public Object invoke(Object instance, Object[] arguments) throws Exception {
        var cdi = CDI.current();
        List<Runnable> cleanups = new ArrayList<>();

        try {
            if (instanceLookup) {
                var inst = cdi.select(beanClass);
                var handle = inst.getHandle();
                instance = handle.get();
                if (handle.getBean() != null && handle.getBean().getScope() == Dependent.class) {
                    cleanups.add(handle::destroy);
                }
            }

            if (!argumentLookups.isEmpty() && arguments != null) {
                var paramTypes = method.getParameterTypes();
                var genParamTypes = method.getGenericParameterTypes();
                arguments = arguments.clone();
                for (int idx : argumentLookups) {
                    var paramType = paramTypes[idx];
                    var genType = genParamTypes[idx];

                    if (jakarta.enterprise.inject.Instance.class.isAssignableFrom(paramType)) {
                        var resolved = resolveInstance(cdi, genType);
                        arguments[idx] = resolved;
                        if (resolved instanceof InstanceImpl<?> instanceImpl) {
                            cleanups.add(instanceImpl::releaseAllDependents);
                        }
                    } else if (jakarta.enterprise.event.Event.class.isAssignableFrom(paramType)) {
                        arguments[idx] = resolveEvent(cdi, genType);
                    } else if (BeanManager.class.isAssignableFrom(paramType)) {
                        arguments[idx] = cdi.getBeanManager();
                    } else {
                        var inst = cdi.select(paramType);
                        var handle = inst.getHandle();
                        arguments[idx] = handle.get();
                        if (handle.getBean() != null && handle.getBean().getScope() == Dependent.class) {
                            cleanups.add(handle::destroy);
                        }
                    }
                }
            }

            var paramCount = method.getParameterCount();
            if (arguments != null && arguments.length > paramCount) {
                var trimmed = new Object[paramCount];
                System.arraycopy(arguments, 0, trimmed, 0, paramCount);
                arguments = trimmed;
            }

            try {
                if (isStatic) {
                    return method.invoke(null, arguments);
                }
                if (instance == null) {
                    throw new RuntimeException("Cannot invoke instance method " + method.getName()
                            + " with null instance");
                }
                return method.invoke(instance, arguments);
            } catch (InvocationTargetException e) {
                var cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                if (cause instanceof Error err) throw err;
                throw e;
            }
        } finally {
            for (var cleanup : cleanups) {
                try {
                    cleanup.run();
                } catch (Exception ignored) {
                    // intentionally empty
                }
            }
        }
    }

    private Object resolveInstance(CDI<Object> cdi, java.lang.reflect.Type genType) {
        if (genType instanceof java.lang.reflect.ParameterizedType pt) {
            var actualType = pt.getActualTypeArguments()[0];
            if (actualType instanceof Class<?> cls) {
                return cdi.select(cls);
            }
        }
        return cdi.select(Object.class);
    }

    private Object resolveEvent(CDI<Object> cdi, java.lang.reflect.Type genType) {
        var bm = cdi.getBeanManager();
        if (genType instanceof java.lang.reflect.ParameterizedType pt
                && pt.getActualTypeArguments().length > 0) {
            var eventTypeArg = pt.getActualTypeArguments()[0];
            var dispatcher = ((io.vidocq.vauban.core.container.VaubanBeanManager) bm).getEventDispatcher();
            return new io.vidocq.vauban.core.event.EventImpl<>(dispatcher, new java.lang.annotation.Annotation[0], null, eventTypeArg);
        }
        return bm.getEvent();
    }
}
