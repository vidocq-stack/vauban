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
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.invoke.Invoker;
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.container.InstanceImpl;
import io.vidocq.vauban.core.container.ManagedBean;
import io.vidocq.vauban.core.container.VaubanInjectionPoint;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@SuppressWarnings({"unchecked", "java:S3011", "java:S3776"}) // CDI spec requires reflective access
public final class VaubanInvoker implements Invoker<Object, Object>, InvokerInfo {

    private final Method method;
    private final Class<?> beanClass;
    private final boolean isStatic;
    private final boolean instanceLookup;
    private final Set<Integer> argumentLookups;
    /** Resolved once, here rather than per call; {@code null} when the method is not reachable. */
    private final java.lang.invoke.MethodHandle handle;

    public VaubanInvoker(Method method, Class<?> beanClass,
                         boolean instanceLookup, Set<Integer> argumentLookups) {
        this.method = method;
        this.beanClass = beanClass;
        this.isStatic = Modifier.isStatic(method.getModifiers());
        this.instanceLookup = instanceLookup;
        this.argumentLookups = Set.copyOf(argumentLookups);
        this.handle = unreflect(method);
        if (this.handle == null) {
            // Only when no handle could be obtained: the reflective path still needs the member to
            // be accessible. trySetAccessible, never setAccessible — the latter throws
            // InaccessibleObjectException on a module that opens nothing.
            method.trySetAccessible();
        }
    }

    /** The invoked method. */
    public Method method() {
        return method;
    }

    /** The class of the bean whose method is invoked. */
    public Class<?> beanClass() {
        return beanClass;
    }

    /** Whether the target instance is looked up rather than passed. */
    public boolean instanceLookup() {
        return instanceLookup;
    }

    /** The positions of the arguments that are looked up rather than passed. */
    public Set<Integer> argumentLookups() {
        return argumentLookups;
    }

    /**
     * A method handle for {@code method}, or {@code null} when this module may not have one.
     *
     * <p>Preferred over {@link Method#invoke}: the access check happens once, here, instead of on
     * every call. It is not a way around the module system — {@code unreflect} needs the same
     * consent {@code setAccessible} would — so a method this module cannot reach yields
     * {@code null} and the reflective path takes over, exactly as before.
     *
     * <p>The handle has a fixed arity. The {@code Invoker} contract passes a variadic parameter as the
     * array itself, and {@code invokeWithArguments} on a variable-arity handle would collect that array
     * into a new one, as {@code Method#invoke} never does.
     */
    private static java.lang.invoke.MethodHandle unreflect(Method method) {
        try {
            return java.lang.invoke.MethodHandles.lookup().unreflect(method).asFixedArity();
        } catch (IllegalAccessException _) {
            return null;
        }
    }

    @Override
    @SuppressWarnings("java:S112") // CDI spec: container exceptions propagate as RuntimeException
    public Object invoke(Object instance, Object[] arguments) throws Exception {
        var cdi = CDI.current();
        List<Runnable> cleanups = new ArrayList<>();

        try {
            var target = instanceLookup ? lookupTarget(cdi.getBeanManager(), cleanups) : instance;
            var callArguments = trimToParameterCount(lookupArguments(cdi.getBeanManager(), arguments, cleanups));

            if (!isStatic && target == null) {
                throw new RuntimeException("Cannot invoke instance method " + method.getName()
                        + " with null instance");
            }
            if (handle != null) {
                return invokeHandle(target, callArguments);
            }
            return invokeReflectively(target, callArguments);
        } finally {
            runCleanups(cleanups);
        }
    }

    /**
     * A contextual reference for the target bean, of the bean type that declares the target method (CDI 4.1,
     * "Configuring lookups"): the managed bean of {@link #beanClass}, whatever its qualifiers, and found among the
     * beans of {@code Object} when a {@code @Typed} leaves its class out of its bean types. It selected the class
     * with {@code @Default}, which missed a bean with a qualifier, and one whose class is no bean type of its
     * (BUG-20261117-38). The creational context of a {@code @Dependent} target is released once the call is over,
     * which destroys it; that of any other is left alone, so a {@code @Singleton} the lookup creates keeps its
     * {@code @Dependent} objects.
     */
    private Object lookupTarget(BeanManager beanManager, List<Runnable> cleanups) {
        var bean = targetBeanAmong(beanManager, beanManager.getBeans(beanClass, Any.Literal.INSTANCE));
        if (bean == null) {
            bean = targetBeanAmong(beanManager, beanManager.getBeans(Object.class, Any.Literal.INSTANCE));
        }
        if (bean == null) {
            throw new UnsatisfiedResolutionException("No bean of class " + beanClass.getName()
                    + " for the invoker of " + method.getName());
        }
        var context = beanManager.createCreationalContext(bean);
        if (bean.getScope() == Dependent.class) {
            cleanups.add(context::release);
        }
        return beanManager.getReference(bean, declaringBeanType(bean), context);
    }

    /** The target bean among {@code beans}, or {@code null} when none of them is. */
    private Bean<?> targetBeanAmong(BeanManager beanManager, Set<Bean<?>> beans) {
        Set<Bean<?>> candidates = beans.stream().filter(this::isTargetBean).collect(Collectors.toSet());
        return beanManager.resolve(candidates);
    }

    /**
     * Whether {@code bean} is the managed bean of {@link #beanClass}, not a producer its class declares. The class is
     * compared by name, as the types of {@code getBeans} are: a deployment of several loaders may hold it twice.
     */
    @SuppressWarnings("java:S1872")
    private boolean isTargetBean(Bean<?> bean) {
        return bean.getBeanClass().getName().equals(beanClass.getName())
                && (!(bean instanceof ManagedBean<?> managed)
                    || managed.descriptor().kind() == BeanDescriptor.BeanKind.MANAGED);
    }

    /**
     * The bean type of {@code bean} that declares the target method, as the bean has it, {@code Base<String>} say,
     * or {@code Object}, a bean type of every bean, when a {@code @Typed} left that type out.
     */
    private Type declaringBeanType(Bean<?> bean) {
        var declaring = method.getDeclaringClass();
        for (var type : bean.getTypes()) {
            if (type == declaring || (type instanceof ParameterizedType parameterized
                    && parameterized.getRawType() == declaring)) {
                return type;
            }
        }
        return Object.class;
    }

    /**
     * A copy of {@code arguments} with the looked-up positions filled, or {@code arguments} when none is. The
     * {@code @Dependent} instances they created are destroyed once the call is over.
     */
    private Object[] lookupArguments(BeanManager beanManager, Object[] arguments, List<Runnable> cleanups) {
        if (argumentLookups.isEmpty() || arguments == null) {
            return arguments;
        }
        var lookedUp = arguments.clone();
        for (int position : argumentLookups) {
            lookedUp[position] = lookupArgument(beanManager, position, cleanups);
        }
        return lookedUp;
    }

    /**
     * The value of the argument at {@code position}: a contextual reference for the bean typesafe resolution
     * identifies, the required type the declared type of the parameter and the required qualifiers all the qualifiers
     * on it (CDI 4.1, "Configuring lookups"), as for an injection point, a built-in {@code Instance}, {@code Event}
     * or {@code BeanManager} among them. It resolved the erased class of the parameter with {@code @Default}: a
     * {@code Holder<String>} parameter found no bean of {@code Holder<String>} (BUG-20261117-27).
     */
    private Object lookupArgument(BeanManager beanManager, int position, List<Runnable> cleanups) {
        var parameter = method.getParameters()[position];
        var qualifiers = new HashSet<Annotation>();
        for (var annotation : parameter.getAnnotations()) {
            if (beanManager.isQualifier(annotation.annotationType())) {
                qualifiers.add(annotation);
            }
        }
        var injectionPoint = new VaubanInjectionPoint(parameter, position, method, parameter.getParameterizedType(),
                qualifiers, null);
        var value = beanManager.getInjectableReference(injectionPoint,
                contextOf(beanManager, injectionPoint, cleanups));
        if (value instanceof InstanceImpl<?> instanceImpl) {
            cleanups.add(instanceImpl::releaseAllDependents);
        }
        return value;
    }

    /**
     * A creational context of its own for the bean {@code injectionPoint} resolves to, as an injection gives each
     * instance: two intercepted {@code @Dependent} arguments have an interceptor instance each (Jakarta Interceptors
     * 2.1, 2.2), where one context for the whole call cached one for both (fix round 2 of BUG-20261117-27). The
     * context of a {@code @Dependent} bean is released once the call is over, which destroys the {@code @Dependent}
     * instances created for the lookups (CDI 4.1, "Configuring lookups"); that of any other bean is left alone, so a
     * {@code @Singleton} the lookup creates keeps its {@code @Dependent} objects (fix round 1). No bean:
     * {@code getInjectableReference} throws the lookup's exception; an ambiguous one throws here, the same.
     *
     * <p>The bean is resolved here and again in {@code getInjectableReference}, which takes no bean: it gives the
     * built-in beans and the {@code InjectionPoint} metadata as an injection does.
     */
    private static CreationalContext<?> contextOf(BeanManager beanManager, VaubanInjectionPoint injectionPoint,
                                                  List<Runnable> cleanups) {
        var bean = beanManager.resolve(beanManager.getBeans(injectionPoint.getType(),
                injectionPoint.getQualifiers().toArray(Annotation[]::new)));
        var context = beanManager.createCreationalContext(bean);
        if (bean == null || bean.getScope() == Dependent.class) {
            cleanups.add(context::release);
        }
        return context;
    }

    /** {@code arguments} without the trailing values the method has no parameter for. */
    private Object[] trimToParameterCount(Object[] arguments) {
        var paramCount = method.getParameterCount();
        if (arguments == null || arguments.length <= paramCount) {
            return arguments;
        }
        var trimmed = new Object[paramCount];
        System.arraycopy(arguments, 0, trimmed, 0, paramCount);
        return trimmed;
    }

    @SuppressWarnings("java:S112") // a Throwable that is neither an Exception nor an Error has no better type
    private Object invokeHandle(Object target, Object[] arguments) throws Exception {
        var callArgs = new ArrayList<Object>();
        if (!isStatic) {
            callArgs.add(target);
        }
        if (arguments != null) {
            java.util.Collections.addAll(callArgs, arguments);
        }
        try {
            return handle.invokeWithArguments(callArgs);
        } catch (Exception | Error direct) {
            // A handle throws the target's exception as-is, with no wrapper to unpack.
            throw direct;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** The reflective path, when no method handle could be obtained; the target's exception is unwrapped. */
    private Object invokeReflectively(Object target, Object[] arguments) throws Exception {
        try {
            if (isStatic) {
                return method.invoke(null, arguments);
            }
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    /** Runs every cleanup; one that fails does not keep the others from running. */
    private static void runCleanups(List<Runnable> cleanups) {
        for (var cleanup : cleanups) {
            try {
                cleanup.run();
            } catch (Exception _) {
                // intentionally empty
            }
        }
    }
}
