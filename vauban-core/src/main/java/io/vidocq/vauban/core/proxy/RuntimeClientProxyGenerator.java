package io.vidocq.vauban.core.proxy;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Generates CDI client proxies at runtime using the JDK 25 Class-File API.
 *
 * <p>A client proxy is a subclass of the bean that delegates every public
 * non-final non-static method to the contextual instance obtained from a
 * {@link java.util.function.Supplier}.
 *
 * <p><b>Protected method handling (§4.10.1.9 workaround) :</b> the JVM
 * bytecode verifier refuses {@code invokevirtual} on a {@code protected}
 * method of a superclass when the receiver type on the stack is not
 * assignable to the current class. Since our proxy {@code _ClientProxy}
 * extends {@code Bean}, invoking a protected bean method with receiver
 * {@code Bean} (the delegate) would be rejected. For these methods we
 * instead emit a {@link java.lang.invoke.MethodHandle#invokeExact} call
 * against a static-final {@code MethodHandle} initialized in {@code <clinit>}
 * via {@link java.lang.invoke.MethodHandles.Lookup#findVirtual}.
 *
 * <p>This differs from the compile-time {@code ClientProxyGenerator} in
 * {@code vauban-processor} — this version works with {@code Class<?>}
 * (reflection) instead of {@code ClassInfo} (bytecode index).
 */
public final class RuntimeClientProxyGenerator {

    private static final ClassDesc CD_Supplier = ClassDesc.of("java.util.function.Supplier");
    private static final ClassDesc CD_Object = ConstantDescs.CD_Object;
    private static final ClassDesc CD_MethodHandle = ClassDesc.of("java.lang.invoke.MethodHandle");
    private static final ClassDesc CD_MethodHandles = ClassDesc.of("java.lang.invoke.MethodHandles");
    private static final ClassDesc CD_MethodHandlesLookup = ClassDesc.of("java.lang.invoke.MethodHandles$Lookup");
    private static final ClassDesc CD_MethodType = ClassDesc.of("java.lang.invoke.MethodType");
    private static final ClassDesc CD_Class = ConstantDescs.CD_Class;
    private static final ClassDesc CD_Throwable = ConstantDescs.CD_Throwable;
    private static final ClassDesc CD_ExceptionInInitializerError = ClassDesc.of("java.lang.ExceptionInInitializerError");
    private static final String FIELD_DELEGATE = "$$delegate";
    private static final String MH_FIELD_PREFIX = "$$mh_";
    private static final String PROXY_SUFFIX = "_ClientProxy";

    private RuntimeClientProxyGenerator() {}

    /**
     * Returns the deterministic proxy class name for a given bean class.
     * Useful for build-time pre-generation: the runtime will look for this exact name.
     */
    public static String proxyClassName(Class<?> beanClass) {
        return beanClass.getName() + PROXY_SUFFIX;
    }

    /**
     * Generate a client proxy class for the given bean class.
     * The generated class name is deterministic: {@code BeanClass_ClientProxy}.
     *
     * @param beanClass the bean class to proxy
     * @return the generated class name and bytecode
     */
    public static GeneratedProxy generate(Class<?> beanClass) {
        String proxyClassName = proxyClassName(beanClass);
        ClassDesc proxyCD = ClassDesc.of(proxyClassName);
        ClassDesc beanCD = ClassDesc.of(beanClass.getName());

        // First pass : collect eligible methods and decide whether each needs MethodHandle.
        String proxyPackage = packageOf(proxyClassName);
        var proxiedSeen = new java.util.HashSet<String>();
        var methods = new java.util.ArrayList<ProxiedMethod>();
        var current = beanClass;
        int mhIndex = 0;
        while (current != null) {
            for (var method : current.getDeclaredMethods()) {
                var key = method.getName() + java.util.Arrays.toString(method.getParameterTypes());
                if (proxiedSeen.add(key) && shouldProxy(method)) {
                    boolean needsMh = needsMethodHandleDispatch(method, proxyPackage);
                    String mhField = needsMh ? (MH_FIELD_PREFIX + method.getName() + "_" + (mhIndex++)) : null;
                    methods.add(new ProxiedMethod(method, mhField));
                }
            }
            current = current.getSuperclass();
        }

        byte[] bytecode = ClassFile.of().build(proxyCD, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(beanCD);

            // Field: private Supplier delegate
            clb.withField(FIELD_DELEGATE, CD_Supplier, ClassFile.ACC_PRIVATE);

            // Static final MethodHandle fields for protected/cross-package methods.
            for (var pm : methods) {
                if (pm.mhField() != null) {
                    clb.withField(pm.mhField(), CD_MethodHandle,
                            ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
                }
            }

            // Generate no-arg constructor calling the simplest available super constructor
            // CDI 4.1: beans with only @Inject constructors (no no-arg) must still be proxyable
            var superCtor = findSimplestConstructor(beanClass);
            var superParamCDs = new ClassDesc[superCtor.getParameterCount()];
            for (int i = 0; i < superParamCDs.length; i++) {
                superParamCDs[i] = superCtor.getParameterTypes()[i].describeConstable()
                        .orElse(ClassDesc.of(superCtor.getParameterTypes()[i].getName()));
            }
            var superCtorType = MethodTypeDesc.of(ConstantDescs.CD_void, superParamCDs);
            clb.withMethodBody(
                    ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC,
                    cob -> {
                        cob.aload(0);
                        // Push default values for each super constructor parameter
                        for (var paramCD : superParamCDs) {
                            pushDefault(cob, paramCD);
                        }
                        cob.invokespecial(beanCD, ConstantDescs.INIT_NAME, superCtorType);
                        cob.return_();
                    });

            // Setter: public void $$setDelegate(Supplier)
            clb.withMethodBody(
                    "$$setDelegate",
                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_Supplier),
                    ClassFile.ACC_PUBLIC,
                    cob -> {
                        cob.aload(0);
                        cob.aload(1);
                        cob.putfield(proxyCD, FIELD_DELEGATE, CD_Supplier);
                        cob.return_();
                    });

            // <clinit> that initializes the MethodHandle fields (if any).
            boolean hasMhFields = methods.stream().anyMatch(m -> m.mhField() != null);
            if (hasMhFields) {
                generateStaticInitializer(clb, proxyCD, beanCD, methods);
            }

            // Override each eligible method.
            for (var pm : methods) {
                generateProxyMethod(clb, proxyCD, beanCD, pm);
            }
        });

        return new GeneratedProxy(proxyClassName, bytecode);
    }

    /**
     * Find the simplest non-private constructor (fewest parameters).
     * Prefers no-arg, then smallest parameter count.
     */
    private static java.lang.reflect.Constructor<?> findSimplestConstructor(Class<?> beanClass) {
        java.lang.reflect.Constructor<?> best = null;
        for (var ctor : beanClass.getDeclaredConstructors()) {
            if (java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())) continue;
            if (best == null || ctor.getParameterCount() < best.getParameterCount()) {
                best = ctor;
            }
        }
        if (best != null) return best;
        // All constructors are private — use the first one (proxy generation will still work
        // since the proxy class is in the same package)
        return beanClass.getDeclaredConstructors()[0];
    }

    /**
     * Push a default value for the given type onto the stack.
     * null for references, 0 for numerics, false for boolean.
     */
    private static void pushDefault(CodeBuilder cob, ClassDesc paramCD) {
        String desc = paramCD.descriptorString();
        switch (desc.charAt(0)) {
            case 'Z', 'B', 'C', 'S', 'I' -> cob.iconst_0();
            case 'J' -> cob.lconst_0();
            case 'F' -> cob.fconst_0();
            case 'D' -> cob.dconst_0();
            default -> cob.aconst_null();
        }
    }

    private static boolean shouldProxy(Method method) {
        if (Modifier.isStatic(method.getModifiers())) return false;
        if (Modifier.isPrivate(method.getModifiers())) return false;
        if (Modifier.isFinal(method.getModifiers())) return false;
        if (method.isSynthetic()) return false;
        if (method.isBridge()) return false;
        if (method.getName().startsWith("$$")) return false;
        if (method.getName().equals("finalize") && method.getParameterCount() == 0) return false;
        return !(method.getName().equals("clone") && method.getParameterCount() == 0);
    }

    /**
     * A protected method declared in a superclass in a different package cannot be
     * invoked via {@code invokevirtual} when the stack-top receiver type is not assignable
     * to the current class (JVMS §4.10.1.9). We route those through a {@link java.lang.invoke.MethodHandle}.
     * Package-private methods follow the same rule when crossing package boundaries.
     */
    private static boolean needsMethodHandleDispatch(Method method, String proxyPackage) {
        int mods = method.getModifiers();
        if (Modifier.isPublic(mods)) return false;
        String declaringPackage = packageOf(method.getDeclaringClass().getName());
        return !declaringPackage.equals(proxyPackage);
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static void generateProxyMethod(ClassBuilder clb,
                                            ClassDesc proxyCD, ClassDesc beanCD, ProxiedMethod pm) {
        Method method = pm.method();
        var returnCD = method.getReturnType().describeConstable()
                .orElse(ClassDesc.of(method.getReturnType().getName()));
        var paramCDs = new ClassDesc[method.getParameterCount()];
        for (int i = 0; i < paramCDs.length; i++) {
            paramCDs[i] = method.getParameterTypes()[i].describeConstable()
                    .orElse(ClassDesc.of(method.getParameterTypes()[i].getName()));
        }
        var methodType = MethodTypeDesc.of(returnCD, paramCDs);

        clb.withMethodBody(
                method.getName(),
                methodType,
                ClassFile.ACC_PUBLIC,
                cob -> {
                    if (pm.mhField() != null) {
                        emitMethodHandleInvocation(cob, proxyCD, beanCD, pm, methodType);
                    } else {
                        emitInvokevirtualInvocation(cob, proxyCD, beanCD, method, methodType, paramCDs);
                    }
                    emitReturn(cob, returnCD);
                });
    }

    private static void emitInvokevirtualInvocation(CodeBuilder cob, ClassDesc proxyCD,
                                                    ClassDesc beanCD, Method method,
                                                    MethodTypeDesc methodType, ClassDesc[] paramCDs) {
        // ((BeanClass) this.$$delegate.get()).method(params);
        cob.aload(0);
        cob.getfield(proxyCD, FIELD_DELEGATE, CD_Supplier);
        cob.invokeinterface(CD_Supplier, "get", MethodTypeDesc.of(CD_Object));
        cob.checkcast(beanCD);
        int slot = 1;
        for (var paramCD : paramCDs) slot = loadParam(cob, paramCD, slot);
        cob.invokevirtual(beanCD, method.getName(), methodType);
    }

    private static void emitMethodHandleInvocation(CodeBuilder cob, ClassDesc proxyCD,
                                                   ClassDesc beanCD, ProxiedMethod pm,
                                                   MethodTypeDesc methodType) {
        // MethodHandle mh = $$mh_name;
        cob.getstatic(proxyCD, pm.mhField(), CD_MethodHandle);
        // (BeanClass) this.$$delegate.get()
        cob.aload(0);
        cob.getfield(proxyCD, FIELD_DELEGATE, CD_Supplier);
        cob.invokeinterface(CD_Supplier, "get", MethodTypeDesc.of(CD_Object));
        cob.checkcast(beanCD);
        // load params
        int slot = 1;
        for (var paramCD : methodType.parameterArray()) {
            slot = loadParam(cob, paramCD, slot);
        }
        // invokeExact(delegate, params...) : returnType
        // signature-polymorphic: descriptor must include the delegate type as first param.
        var invokeExactType = methodType.insertParameterTypes(0, beanCD);
        cob.invokevirtual(CD_MethodHandle, "invokeExact", invokeExactType);
    }

    private static void generateStaticInitializer(ClassBuilder clb, ClassDesc proxyCD,
                                                  ClassDesc beanCD,
                                                  java.util.List<ProxiedMethod> methods) {
        clb.withMethodBody(
                ConstantDescs.CLASS_INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void),
                ClassFile.ACC_STATIC,
                cob -> {
                    var tryStart = cob.newBoundLabel();
                    for (var pm : methods) {
                        if (pm.mhField() == null) continue;
                        emitMethodHandleLookup(cob, proxyCD, beanCD, pm);
                    }
                    var tryEnd = cob.newBoundLabel();
                    cob.return_();

                    var handler = cob.newBoundLabel();
                    cob.exceptionCatch(tryStart, tryEnd, handler, CD_Throwable);
                    // catch Throwable t: throw new ExceptionInInitializerError(t)
                    cob.new_(CD_ExceptionInInitializerError);
                    cob.dup_x1();
                    cob.swap();
                    cob.invokespecial(CD_ExceptionInInitializerError, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void, CD_Throwable));
                    cob.athrow();
                });
    }

    private static void emitMethodHandleLookup(CodeBuilder cob, ClassDesc proxyCD,
                                               ClassDesc beanCD, ProxiedMethod pm) {
        Method method = pm.method();
        // Lookup lookup = MethodHandles.privateLookupIn(beanClass, MethodHandles.lookup());
        cob.ldc(beanCD);
        cob.invokestatic(CD_MethodHandles, "lookup",
                MethodTypeDesc.of(CD_MethodHandlesLookup));
        cob.invokestatic(CD_MethodHandles, "privateLookupIn",
                MethodTypeDesc.of(CD_MethodHandlesLookup, CD_Class, CD_MethodHandlesLookup));
        // target class
        cob.ldc(beanCD);
        // method name
        cob.ldc(method.getName());
        // MethodType: return type + param types
        var returnCD = method.getReturnType().describeConstable()
                .orElse(ClassDesc.of(method.getReturnType().getName()));
        var paramCDs = new ClassDesc[method.getParameterCount()];
        for (int i = 0; i < paramCDs.length; i++) {
            paramCDs[i] = method.getParameterTypes()[i].describeConstable()
                    .orElse(ClassDesc.of(method.getParameterTypes()[i].getName()));
        }
        // MethodType.methodType(returnType) — build via methodType(Class,Class...) if params, else methodType(Class)
        cob.ldc(returnCD);
        if (paramCDs.length == 0) {
            cob.invokestatic(CD_MethodType, "methodType",
                    MethodTypeDesc.of(CD_MethodType, CD_Class));
        } else {
            // new Class[n]
            cob.ldc(paramCDs.length);
            cob.anewarray(CD_Class);
            for (int i = 0; i < paramCDs.length; i++) {
                cob.dup();
                cob.ldc(i);
                cob.ldc(paramCDs[i]);
                cob.aastore();
            }
            cob.invokestatic(CD_MethodType, "methodType",
                    MethodTypeDesc.of(CD_MethodType, CD_Class, CD_Class.arrayType()));
        }
        // findVirtual(beanClass, name, methodType)
        cob.invokevirtual(CD_MethodHandlesLookup, "findVirtual",
                MethodTypeDesc.of(CD_MethodHandle, CD_Class,
                        ClassDesc.of("java.lang.String"), CD_MethodType));
        cob.putstatic(proxyCD, pm.mhField(), CD_MethodHandle);
    }

    private static int loadParam(CodeBuilder cob, ClassDesc paramCD, int slot) {
        String desc = paramCD.descriptorString();
        return switch (desc.charAt(0)) {
            case 'Z', 'B', 'C', 'S', 'I' -> { cob.iload(slot); yield slot + 1; }
            case 'J' -> { cob.lload(slot); yield slot + 2; }
            case 'F' -> { cob.fload(slot); yield slot + 1; }
            case 'D' -> { cob.dload(slot); yield slot + 2; }
            default -> { cob.aload(slot); yield slot + 1; }
        };
    }

    private static void emitReturn(CodeBuilder cob, ClassDesc returnCD) {
        String desc = returnCD.descriptorString();
        switch (desc.charAt(0)) {
            case 'V' -> cob.return_();
            case 'Z', 'B', 'C', 'S', 'I' -> cob.ireturn();
            case 'J' -> cob.lreturn();
            case 'F' -> cob.freturn();
            case 'D' -> cob.dreturn();
            default -> cob.areturn();
        }
    }

    private record ProxiedMethod(Method method, String mhField) {}

    public record GeneratedProxy(String className, byte[] bytecode) {
        @Override public boolean equals(Object o) {
            return o instanceof GeneratedProxy g
                    && className.equals(g.className)
                    && java.util.Arrays.equals(bytecode, g.bytecode);
        }
        @Override public int hashCode() {
            return className.hashCode() ^ java.util.Arrays.hashCode(bytecode);
        }
        @Override public String toString() {
            return "GeneratedProxy[" + className + ", " + bytecode.length + " bytes]";
        }
    }
}
