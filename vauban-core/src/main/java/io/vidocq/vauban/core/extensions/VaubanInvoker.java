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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * An invoker of a method of a bean (CDI 4.1, "Invokers"). Built at start, or read from the metadata of a build that
 * did not generate its class, it calls the method through a method handle, or reflectively when it may have none. Read
 * from the metadata of a build that generated its class ({@link InvokerClasses}), it calls the method through that
 * class, which calls it by name, and resolves no {@link Method} (BUG-20261118-37).
 */
@SuppressWarnings({"unchecked", "java:S3011"}) // CDI spec requires reflective access
public final class VaubanInvoker implements Invoker<Object, Object>, InvokerInfo {

    /** The order in which a primitive widens to another (JLS 5.1.2); {@code char} widens from none but itself. */
    private static final Map<Class<?>, Integer> WIDTHS = Map.of(Byte.class, 1, Short.class, 2, Character.class, 2,
            Integer.class, 3, Long.class, 4, Float.class, 5, Double.class, 6);
    private static final String SHORT = "short";
    private static final Map<String, Integer> WIDTHS_BY_NAME = Map.of(SHORT, 2, "int", 3, "long", 4, "float", 5,
            "double", 6);

    private final InvokerTarget target;
    private final Class<?> beanClass;
    private final Class<?> declaringClass;
    private final boolean isStatic;
    private final boolean instanceLookup;
    private final Set<Integer> argumentLookups;
    /** How a generated class calls the method, when the language model built this invoker; else {@code null}. */
    private final InvokerShape shape;
    /** The method, {@code null} for an invoker that calls through its generated class. */
    private final Method method;
    /** The generated class, {@code null} for an invoker that calls the method itself. */
    private final Class<?> generatedClass;
    /** By looked-up position, what the build recorded of the parameter, for an invoker with a generated class. */
    private final Map<Integer, LookupPoint> lookupPoints;
    /** How the method is called; resolved on the first call for an invoker with a generated class. */
    private final AtomicReference<Call> call = new AtomicReference<>();

    /**
     * What the call of the method does with the target instance and the arguments. A checked exception of the method
     * comes wrapped in an {@link InvocationTargetException} from the reflective calls, and as it is from a generated
     * class, which declares none.
     */
    @FunctionalInterface
    private interface Call {
        Object call(Object target, Object[] arguments) throws InvocationTargetException;
    }

    /**
     * A looked-up parameter, as the build recorded it for an invoker with a generated class.
     *
     * @param type        its declared type
     * @param annotations its annotations, its qualifiers among them
     */
    record LookupPoint(Type type, List<Annotation> annotations) {

        LookupPoint {
            annotations = List.copyOf(annotations);
        }
    }

    public VaubanInvoker(Method method, Class<?> beanClass,
                         boolean instanceLookup, Set<Integer> argumentLookups) {
        this(method, beanClass, instanceLookup, argumentLookups, null);
    }

    /** An invoker that calls {@code method} itself; {@code shape} is how a generated class would call it. */
    VaubanInvoker(Method method, Class<?> beanClass, boolean instanceLookup, Set<Integer> argumentLookups,
                  InvokerShape shape) {
        this.target = new InvokerTarget(beanClass.getName(), method.getDeclaringClass().getName(), method.getName(),
                Arrays.stream(method.getParameterTypes()).map(Class::getName).toList());
        this.beanClass = beanClass;
        this.declaringClass = method.getDeclaringClass();
        this.isStatic = Modifier.isStatic(method.getModifiers());
        this.instanceLookup = instanceLookup;
        this.argumentLookups = Set.copyOf(argumentLookups);
        this.shape = shape;
        this.method = method;
        this.generatedClass = null;
        this.lookupPoints = Map.of();
        this.call.set(reflectiveCall(method, isStatic));
    }

    /**
     * An invoker that calls the method of {@code target} through {@code generatedClass}, the class the build generated
     * for it, with the looked-up parameters {@code lookupPoints} recorded.
     */
    VaubanInvoker(InvokerTarget target, Class<?> beanClass, Class<?> declaringClass, boolean isStatic,
                  boolean instanceLookup, Map<Integer, LookupPoint> lookupPoints, Class<?> generatedClass) {
        this.target = target;
        this.beanClass = beanClass;
        this.declaringClass = declaringClass;
        this.isStatic = isStatic;
        this.instanceLookup = instanceLookup;
        this.argumentLookups = Set.copyOf(lookupPoints.keySet());
        this.shape = null;
        this.method = null;
        this.generatedClass = generatedClass;
        this.lookupPoints = Map.copyOf(lookupPoints);
    }

    /** The invoked method; {@code null} for an invoker that calls it through the class the build generated. */
    public Method method() {
        return method;
    }

    /** The class the build generated to call the method, {@code null} when this invoker calls it itself. */
    public Class<?> generatedClass() {
        return generatedClass;
    }

    /** What the build recorded of the looked-up parameter at {@code position}, for a generated invoker. */
    LookupPoint lookupPoint(int position) {
        return lookupPoints.get(position);
    }

    /** The invoked method, by names. */
    public InvokerTarget target() {
        return target;
    }

    /** How a generated class calls the method, {@code null} when the language model did not build this invoker. */
    InvokerShape shape() {
        return shape;
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
     * The method of {@code target}, its classes loaded through {@code loader}.
     *
     * @throws IllegalArgumentException when a class cannot be loaded, or the class declares no such method
     */
    static Method resolve(InvokerTarget target, ClassLoader loader) {
        var declaring = SyntheticParamCodec.load(target.declaringClass(), loader);
        var parameters = target.parameterTypes().stream().map(type -> SyntheticParamCodec.load(type, loader))
                .toArray(Class<?>[]::new);
        try {
            return declaring.getDeclaredMethod(target.name(), parameters);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("the invoked method " + target.declaringClass() + "." + target.name()
                    + target.parameterTypes() + " no longer exists", e);
        }
    }

    /**
     * The call of {@code method}: through a method handle when this module may have one, reflectively otherwise.
     *
     * <p>The handle is preferred over {@link Method#invoke}: the access check happens once, here, instead of on every
     * call. It is not a way around the module system — {@code unreflect} needs the same consent
     * {@code setAccessible} would — so a method this module cannot reach yields no handle and the reflective path
     * takes over. That path needs the member accessible: {@code trySetAccessible}, never {@code setAccessible}, which
     * throws {@code InaccessibleObjectException} on a module that opens nothing.
     *
     * <p>The handle has a fixed arity. The {@code Invoker} contract passes a variadic parameter as the array itself,
     * and {@code invokeWithArguments} on a variable-arity handle would collect that array into a new one, as
     * {@code Method#invoke} never does.
     */
    private static Call reflectiveCall(Method method, boolean isStatic) {
        try {
            var handle = java.lang.invoke.MethodHandles.lookup().unreflect(method).asFixedArity();
            return (target, arguments) -> invokeHandle(handle, isStatic, target, arguments);
        } catch (IllegalAccessException _) {
            method.trySetAccessible();
            return (target, arguments) -> invokeReflectively(method, isStatic, target, arguments);
        }
    }

    /**
     * The call through the generated class: an instance of it, made through a public lookup when its package is
     * exported, through the lookup the generated provider of the bean's package grants otherwise (the package needs
     * no {@code opens}). With neither, the method is resolved and called as an invoker built at start calls it.
     */
    @SuppressWarnings("java:S1181") // a constructor handle throws Throwable; an Error is rethrown as it is
    private Call generatedCall() {
        var lookup = generatedClass.getModule().isExported(generatedClass.getPackageName())
                ? java.lang.invoke.MethodHandles.publicLookup()
                : io.vidocq.vauban.core.access.ModuleLookups.lookupFor(beanClass).orElse(null);
        if (lookup == null) {
            return reflectiveCall(resolve(target, beanClass.getClassLoader()), isStatic);
        }
        try {
            var constructor = lookup.findConstructor(generatedClass,
                    java.lang.invoke.MethodType.methodType(void.class));
            var function = (BiFunction<Object, Object[], Object>) constructor.invoke();
            return (instance, arguments) -> applyGenerated(function, instance, widened(arguments));
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("cannot create " + generatedClass.getName(), e);
        }
    }

    /**
     * Calls the generated class, with the contract of the reflective calls: an unchecked throwable as it is, a checked
     * exception of the method wrapped, so that {@code invoke} rethrows it as it is, an
     * {@code InvocationTargetException} the method throws included.
     */
    @SuppressWarnings("java:S1181") // the generated class throws the method's checked exceptions, undeclared
    private static Object applyGenerated(BiFunction<Object, Object[], Object> function, Object instance,
                                         Object[] arguments) throws InvocationTargetException {
        try {
            return function.apply(instance, arguments);
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Throwable checked) {
            throw new InvocationTargetException(checked);
        }
    }

    /**
     * {@code arguments} with each value a primitive parameter takes by widening widened to its type (JLS 5.1.2), as
     * a method handle converts it: the generated class unboxes the wrapper of the parameter's own type only.
     */
    private Object[] widened(Object[] arguments) {
        if (arguments == null) {
            return arguments;
        }
        Object[] widened = null;
        var types = target.parameterTypes();
        for (int i = 0; i < Math.min(arguments.length, types.size()); i++) {
            var value = widen(arguments[i], types.get(i));
            if (value != null) {
                if (widened == null) {
                    widened = arguments.clone();
                }
                widened[i] = value;
            }
        }
        return widened == null ? arguments : widened;
    }

    /** {@code value} widened to {@code primitive}, or {@code null} when it takes no widening to it. */
    private static Object widen(Object value, String primitive) {
        var from = value == null ? null : WIDTHS.get(value.getClass());
        var to = WIDTHS_BY_NAME.get(primitive);
        if (from == null || to == null || from >= to || (value instanceof Character && SHORT.equals(primitive))) {
            return null;
        }
        var number = value instanceof Character character ? Integer.valueOf(character) : (Number) value;
        return switch (primitive) {
            case SHORT -> number.shortValue();
            case "int" -> number.intValue();
            case "long" -> number.longValue();
            case "float" -> number.floatValue();
            default -> number.doubleValue();
        };
    }

    private Call call() {
        var resolved = call.get();
        if (resolved == null) {
            resolved = generatedCall();
            call.compareAndSet(null, resolved);
        }
        return resolved;
    }

    @Override
    public Object invoke(Object instance, Object[] arguments) throws Exception {
        List<Runnable> cleanups = new ArrayList<>();

        try {
            var beanManager = instanceLookup || !argumentLookups.isEmpty() ? CDI.current().getBeanManager() : null;
            var instanceOrLookup = instanceLookup ? lookupTarget(beanManager, cleanups) : instance;
            var callArguments = trimToParameterCount(lookupArguments(beanManager, arguments, cleanups));

            if (!isStatic && instanceOrLookup == null) {
                throw new IllegalArgumentException("Cannot invoke instance method " + target.name()
                        + " with null instance");
            }
            return call().call(instanceOrLookup, callArguments);
        } catch (InvocationTargetException e) {
            throw thrownBy(e);
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
                    + " for the invoker of " + target.name());
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
        for (var type : bean.getTypes()) {
            if (type == declaringClass || (type instanceof ParameterizedType parameterized
                    && parameterized.getRawType() == declaringClass)) {
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
        var injectionPoint = injectionPoint(beanManager, position);
        var value = beanManager.getInjectableReference(injectionPoint,
                contextOf(beanManager, injectionPoint, cleanups));
        if (value instanceof InstanceImpl<?> instanceImpl) {
            cleanups.add(instanceImpl::releaseAllDependents);
        }
        return value;
    }

    /**
     * The injection point of the parameter at {@code position}: read from the method, or from what the build recorded
     * of it when the invoker calls through a generated class.
     */
    private VaubanInjectionPoint injectionPoint(BeanManager beanManager, int position) {
        if (method == null) {
            var point = lookupPoints.get(position);
            return new VaubanInjectionPoint(point.type(), qualifiers(beanManager, point.annotations()), null, null);
        }
        var parameter = method.getParameters()[position];
        return new VaubanInjectionPoint(parameter, position, method, parameter.getParameterizedType(),
                qualifiers(beanManager, List.of(parameter.getAnnotations())), null);
    }

    private static Set<Annotation> qualifiers(BeanManager beanManager, List<Annotation> annotations) {
        var qualifiers = new HashSet<Annotation>();
        for (var annotation : annotations) {
            if (beanManager.isQualifier(annotation.annotationType())) {
                qualifiers.add(annotation);
            }
        }
        return qualifiers;
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
        var paramCount = target.parameterTypes().size();
        if (arguments == null || arguments.length <= paramCount) {
            return arguments;
        }
        var trimmed = new Object[paramCount];
        System.arraycopy(arguments, 0, trimmed, 0, paramCount);
        return trimmed;
    }

    /** What the method threw, {@code e}'s cause: an exception is returned for the caller to throw, an error thrown. */
    private static Exception thrownBy(InvocationTargetException e) {
        return switch (e.getCause()) {
            case Exception exception -> exception;
            case Error error -> throw error;
            case null, default -> e;
        };
    }

    @SuppressWarnings("java:S1181") // a handle throws Throwable: an unchecked one as it is, a checked one wrapped
    private static Object invokeHandle(java.lang.invoke.MethodHandle handle, boolean isStatic, Object target,
                                       Object[] arguments) throws InvocationTargetException {
        var callArgs = new ArrayList<Object>();
        if (!isStatic) {
            callArgs.add(target);
        }
        if (arguments != null) {
            java.util.Collections.addAll(callArgs, arguments);
        }
        try {
            return handle.invokeWithArguments(callArgs);
        } catch (RuntimeException | Error direct) {
            // A handle throws the target's exception as-is, with no wrapper to unpack.
            throw direct;
        } catch (Throwable checked) {
            throw new InvocationTargetException(checked);
        }
    }

    /** The reflective path, when no method handle could be obtained; the target's exception is unwrapped. */
    private static Object invokeReflectively(Method method, boolean isStatic, Object target, Object[] arguments)
            throws InvocationTargetException {
        try {
            return method.invoke(isStatic ? null : target, arguments);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot call " + method + ": " + e.getMessage(), e);
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
