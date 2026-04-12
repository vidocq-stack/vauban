package fr.vidocq.vauban.core.proxy;

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
 * <p>This differs from the compile-time {@code ClientProxyGenerator} in
 * {@code vauban-processor} — this version works with {@code Class<?>}
 * (reflection) instead of {@code ClassInfo} (bytecode index).
 */
public final class RuntimeClientProxyGenerator {

    private static final ClassDesc CD_Supplier = ClassDesc.of("java.util.function.Supplier");
    private static final ClassDesc CD_Object = ConstantDescs.CD_Object;
    private static final String FIELD_DELEGATE = "$$delegate";
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

        byte[] bytecode = ClassFile.of().build(proxyCD, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(beanCD);

            // Field: private Supplier delegate
            clb.withField(FIELD_DELEGATE, CD_Supplier, ClassFile.ACC_PRIVATE);

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

            // Override each eligible method (including inherited, package-private, and Object methods)
            var proxiedMethods = new java.util.HashSet<String>();
            var current = beanClass;
            while (current != null) {
                for (var method : current.getDeclaredMethods()) {
                    var key = method.getName() + java.util.Arrays.toString(method.getParameterTypes());
                    if (proxiedMethods.add(key) && shouldProxy(method)) {
                        generateProxyMethod(clb, proxyCD, beanCD, method);
                    }
                }
                current = current.getSuperclass();
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

    private static void generateProxyMethod(java.lang.classfile.ClassBuilder clb,
            ClassDesc proxyCD, ClassDesc beanCD, Method method) {
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
                    // ((BeanClass) this.$$delegate.get())
                    cob.aload(0);
                    cob.getfield(proxyCD, FIELD_DELEGATE, CD_Supplier);
                    cob.invokeinterface(CD_Supplier, "get",
                            MethodTypeDesc.of(CD_Object));
                    cob.checkcast(beanCD);

                    // Load all parameters
                    int slot = 1;
                    for (var paramCD : paramCDs) {
                        slot = loadParam(cob, paramCD, slot);
                    }

                    // Invoke the real method on the delegate
                    cob.invokevirtual(beanCD, method.getName(), methodType);

                    // Return
                    emitReturn(cob, returnCD);
                });
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
