package io.vidocq.vauban.core.container;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized reflective access utility using {@link MethodHandles.Lookup}.
 * <p>
 * All reflective access goes through {@link MethodHandles#privateLookupIn} —
 * no {@code setAccessible(true)} anywhere. Works for both JPMS modules and
 * classpath (unnamed modules).
 * <p>
 * The user's module consents to access either by:
 * <ul>
 *   <li>{@code opens my.package to io.vidocq.vauban.core;} in module-info.java</li>
 *   <li>Providing a {@code MethodHandles.Lookup} via {@code VaubanContainer.builder().lookup(...)}</li>
 * </ul>
 */
public final class VaubanLookup {

    private final MethodHandles.Lookup rootLookup;
    private final ConcurrentHashMap<Class<?>, MethodHandles.Lookup> lookupCache = new ConcurrentHashMap<>();

    /**
     * In-module providers consulted before reflective invocation; set once after the container is
     * wired (may stay {@code null} for stand-alone lookups, e.g. tests, in which case everything
     * goes through reflection).
     */
    private ComponentProviders componentProviders;

    void setComponentProviders(ComponentProviders componentProviders) {
        this.componentProviders = componentProviders;
    }

    /**
     * Create a VaubanLookup with the given root Lookup.
     * The root lookup should come from the user's module
     * (via {@code MethodHandles.lookup()} called in the user's code).
     */
    public VaubanLookup(MethodHandles.Lookup rootLookup) {
        this.rootLookup = rootLookup;
    }

    /**
     * Create a VaubanLookup with the default public lookup.
     * Only useful for unnamed modules (classpath mode).
     */
    public VaubanLookup() {
        this(MethodHandles.lookup());
    }

    /**
     * Create a new instance of the given class using its no-arg constructor.
     */
    @SuppressWarnings("unchecked")
    public <T> T newInstance(Class<T> clazz) {
        try {
            var mh = findConstructorHandle(clazz, MethodType.methodType(void.class));
            return (T) mh.invokeWithArguments();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new jakarta.enterprise.inject.CreationException("Failed to instantiate: " + clazz.getName(), e);
        }
    }

    /**
     * Instantiates via the public {@link MethodHandles#publicLookup()}. This works for a
     * <strong>public</strong> constructor of a public class in an <strong>unconditionally
     * exported</strong> package, with no {@code privateLookupIn} and hence no
     * {@code opens … to io.vidocq.vauban.core} on the strict module path. Used for the
     * statically generated {@code <bean>$$Intercepted} subclass (always public, in the bean's
     * exported package).
     *
     * <p>Returns {@code null} when public access is insufficient — a non-public member, or a
     * package that is only qualifiedly exported / not exported — so the caller can fall back to
     * {@link #newInstance(Constructor, Object...)} (the private-lookup path, which needs the
     * package {@code opens}-ed, or is on the class path where access is unrestricted).
     */
    public Object newInstancePublic(Constructor<?> constructor, Object... args) {
        MethodHandle mh;
        try {
            mh = MethodHandles.publicLookup().unreflectConstructor(constructor);
        } catch (IllegalAccessException publicAccessInsufficient) {
            return null;
        }
        try {
            return mh.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new jakarta.enterprise.inject.CreationException(
                    "Failed to invoke constructor: " + constructor, e);
        }
    }

    /**
     * Create a new instance using the given constructor and arguments.
     */
    public Object newInstance(Constructor<?> constructor, Object... args) {
        try {
            var lookup = lookupFor(constructor.getDeclaringClass());
            var mh = lookup.unreflectConstructor(constructor);
            return mh.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new jakarta.enterprise.inject.CreationException(
                    "Failed to invoke constructor: " + constructor, e);
        }
    }

    /**
     * Set a field value on an instance.
     */
    public void setField(Object instance, Field field, Object value) {
        try {
            var lookup = lookupFor(field.getDeclaringClass());
            var mh = lookup.unreflectSetter(field);
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                mh.invokeWithArguments(value);
            } else {
                mh.invokeWithArguments(instance, value);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set field: " + field, e);
        }
    }

    /**
     * Get a field value from an instance (or null for static fields).
     */
    public Object getField(Object instance, Field field) {
        try {
            var lookup = lookupFor(field.getDeclaringClass());
            var mh = lookup.unreflectGetter(field);
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                return mh.invokeWithArguments();
            }
            return mh.invokeWithArguments(instance);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get field: " + field, e);
        }
    }

    /**
     * Invoke a method on an instance with the given arguments.
     * Handles both instance and static methods, and primitive return types.
     */
    public Object invokeMethod(Object instance, Method method, Object... args) {
        // In-module call first: a generated provider that owns this method calls it directly (no
        // reflection, no opens). NOT_INVOKED means "not owned" — fall through to reflection.
        if (componentProviders != null) {
            var callArgs = (args == null) ? new Object[0] : args;
            var result = componentProviders.invoke(instance, method.getDeclaringClass().getName(),
                    methodId(method), callArgs);
            if (result != io.vidocq.vauban.api.VaubanComponentProvider.NOT_INVOKED) return result;
        }
        try {
            var lookup = lookupFor(method.getDeclaringClass());
            var mh = lookup.unreflect(method);
            // Build argument list: instance methods need receiver, static don't
            var allArgs = new java.util.ArrayList<>();
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                allArgs.add(instance);
            }
            if (args != null) {
                java.util.Collections.addAll(allArgs, args);
            }
            return mh.invokeWithArguments(allArgs);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            // Unwrap and propagate the target exception directly
            // CDI spec requires producer method exceptions to propagate as-is
            var cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            if (cause instanceof Exception ex) throw new jakarta.enterprise.inject.CreationException(ex);
            throw new jakarta.enterprise.inject.CreationException(e);
        }
    }

    /**
     * Invoke a static method with the given arguments.
     */
    public Object invokeStaticMethod(Method method, Object... args) {
        return invokeMethod(null, method, args);
    }

    /**
     * Builds the {@code name(paramErasure,…)} identity key matching the one a generated
     * {@code VaubanComponentProvider} emits, so the container can look an invocable method up by
     * value. Parameter erasures use the same form as the code generators (binary class names with
     * {@code $} for nested types, {@code []} suffix per array dimension), so the strings line up on
     * both sides for any method whose parameters are all nameable reference types.
     */
    static String methodId(Method method) {
        var sb = new StringBuilder(method.getName()).append('(');
        var params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(typeKey(params[i]));
        }
        return sb.append(')').toString();
    }

    private static String typeKey(Class<?> c) {
        if (c.isArray()) return typeKey(c.getComponentType()) + "[]";
        return c.getName();
    }

    /**
     * Make a member accessible for later invocation via {@code Method.invoke()}.
     * Uses {@link MethodHandles#privateLookupIn} to validate access, then sets accessible.
     * Prefer {@link #invokeMethod} for direct MethodHandle-based invocation.
     */
    public void makeAccessible(java.lang.reflect.AccessibleObject member) {
        Class<?> declaringClass = switch (member) {
            case Method m -> m.getDeclaringClass();
            case Field f -> f.getDeclaringClass();
            case Constructor<?> c -> c.getDeclaringClass();
            default -> null;
        };
        if (declaringClass != null) {
            lookupFor(declaringClass); // validate Lookup access
        }
        member.trySetAccessible();
    }

    /**
     * Get a private Lookup for the given target class.
     * Uses cache for performance. Works for both JPMS modules (with opens)
     * and unnamed modules (classpath).
     */
    MethodHandles.Lookup lookupFor(Class<?> targetClass) {
        return lookupCache.computeIfAbsent(targetClass, clazz -> {
            try {
                // Ensure vauban.core can read the target module (required for JPMS)
                Module vaubanModule = VaubanLookup.class.getModule();
                Module targetModule = clazz.getModule();
                if (!vaubanModule.canRead(targetModule)) {
                    vaubanModule.addReads(targetModule);
                }
                return MethodHandles.privateLookupIn(clazz, rootLookup);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot reflectively access " + clazz.getName()
                        + " on the module path. Either (preferred) provide a generated "
                        + "VaubanComponentProvider for its module — the APT or packaging plugin "
                        + "emits a `_VaubanComponents` and the module declares `provides "
                        + "io.vidocq.vauban.api.VaubanComponentProvider with …;` — so the container "
                        + "instantiates it in-module without reflection; or open the package: "
                        + "`opens " + clazz.getPackageName() + " to io.vidocq.vauban.core;`", e);
            }
        });
    }

    private MethodHandle findConstructorHandle(Class<?> clazz, MethodType type) {
        try {
            var lookup = lookupFor(clazz);
            return lookup.findConstructor(clazz, type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Cannot find constructor for " + clazz.getName(), e);
        }
    }
}
