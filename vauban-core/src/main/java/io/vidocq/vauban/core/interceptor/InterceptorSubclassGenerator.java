package io.vidocq.vauban.core.interceptor;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Generates intercepted bean subclasses using the JDK 25 Class-File API.
 *
 * <p>For a bean class {@code MyService} with interceptor bindings, generates
 * {@code MyService$$Intercepted extends MyService} where each non-private,
 * non-final, non-static method is overridden to invoke the interceptor chain
 * via {@link VaubanInvocationContext}.
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class InterceptorSubclassGenerator {

    private static final ClassDesc CD_InterceptorManager =
            ClassDesc.of("io.vidocq.vauban.core.interceptor.InterceptorManager");
    private static final ClassDesc CD_VaubanInvocationContext =
            ClassDesc.of("io.vidocq.vauban.core.interceptor.VaubanInvocationContext");
    private static final ClassDesc CD_Set = ClassDesc.of("java.util.Set");
    private static final ClassDesc CD_Object = ConstantDescs.CD_Object;
    private static final ClassDesc CD_Method = ClassDesc.of("java.lang.reflect.Method");
    private static final ClassDesc CD_Constructor = ClassDesc.of("java.lang.reflect.Constructor");
    private static final ClassDesc CD_Class = ClassDesc.of("java.lang.Class");
    private static final ClassDesc CD_String = ClassDesc.of("java.lang.String");
    private static final ClassDesc CD_List = ClassDesc.of("java.util.List");
    private static final ClassDesc CD_CreationalContext = ClassDesc.of("jakarta.enterprise.context.spi.CreationalContext");
    private static final ClassDesc CD_TargetInvoker = ClassDesc.of("io.vidocq.vauban.core.interceptor.VaubanInvocationContext$TargetInvoker");

    private static final String FIELD_MANAGER = "$$manager";
    private static final String FIELD_BINDINGS = "$$bindings";
    private static final String FIELD_CONTEXT = "$$context";
    private static final String METHOD_VALUEOF = "valueOf";

    private InterceptorSubclassGenerator() {}

    /**
     * Generate an intercepted subclass for the given bean class.
     *
     * @param beanClass the bean class to intercept
     * @param bindings the interceptor bindings on this bean
     * @return the generated class name and bytecode
     */
    @SuppressWarnings("java:S135")
    public static GeneratedInterceptedClass generate(Class<?> beanClass) {
        String beanClassName = beanClass.getName();
        String subclassName = beanClassName + "$$Intercepted";
        ClassDesc subclassCD = classDescOf(subclassName);
        ClassDesc beanCD = classDescOf(beanClass);

        byte[] bytecode = ClassFile.of().build(subclassCD, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(beanCD);

            // Fields
            clb.withField(FIELD_MANAGER, CD_InterceptorManager, ClassFile.ACC_PRIVATE);
            clb.withField(FIELD_BINDINGS, CD_Set, ClassFile.ACC_PRIVATE);
            clb.withField("$$constructorBindings", CD_Set, ClassFile.ACC_PRIVATE);
            clb.withField(FIELD_CONTEXT, CD_CreationalContext, ClassFile.ACC_PRIVATE);

            // Constructors: for each non-private constructor in super class, generate one here
            for (var constructor : beanClass.getDeclaredConstructors()) {
                if (Modifier.isPrivate(constructor.getModifiers())) continue;

                var paramCDs = new ClassDesc[constructor.getParameterCount()];
                for (int i = 0; i < paramCDs.length; i++) {
                    paramCDs[i] = classDescOf(constructor.getParameterTypes()[i]);
                }
                var mtd = MethodTypeDesc.of(ConstantDescs.CD_void, paramCDs);

                clb.withMethodBody(
                        ConstantDescs.INIT_NAME,
                        mtd,
                        ClassFile.ACC_PUBLIC,
                        cob -> {
                            cob.aload(0);
                            for (int i = 0; i < constructor.getParameterCount(); i++) {
                                cob.loadLocal(java.lang.classfile.TypeKind.from(constructor.getParameterTypes()[i]), cob.parameterSlot(i));
                            }
                            cob.invokespecial(beanCD, ConstantDescs.INIT_NAME, mtd);
                            cob.return_();
                        });
            }

            // Setter: public void $$init(InterceptorManager, Set<DotName>, Set<DotName>, CreationalContext)
            clb.withMethodBody(
                    "$$init",
                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_InterceptorManager, CD_Set, CD_Set, CD_CreationalContext),
                    ClassFile.ACC_PUBLIC,
                    cob -> {
                        cob.aload(0);
                        cob.aload(1);
                        cob.putfield(subclassCD, FIELD_MANAGER, CD_InterceptorManager);
                        cob.aload(0);
                        cob.aload(2);
                        cob.putfield(subclassCD, FIELD_BINDINGS, CD_Set);
                        cob.aload(0);
                        cob.aload(3);
                        cob.putfield(subclassCD, "$$constructorBindings", CD_Set);
                        cob.aload(0);
                        cob.aload(4);
                        cob.putfield(subclassCD, FIELD_CONTEXT, CD_CreationalContext);
                        
                        // If there are @PostConstruct methods, call them via interceptor manager?
                        // Actually lifecycle methods are handled by VaubanContainer separately.
                        cob.return_();
                    });

            // Override each interceptable method + generate $$super$ bridge
            // Include declared AND inherited methods (for inherited interceptor bindings)
            var interceptedMethods = new java.util.LinkedHashSet<String>();
            for (var method : beanClass.getDeclaredMethods()) {
                if (shouldIntercept(method)) {
                    generateSuperBridge(clb, beanCD, method);
                    generateInterceptedMethod(clb, subclassCD, beanCD, method);
                    interceptedMethods.add(method.getName() + java.util.Arrays.toString(method.getParameterTypes()));
                }
            }
            // Also intercept inherited public methods (from superclasses)
            for (var method : beanClass.getMethods()) {
                if (method.getDeclaringClass() == beanClass) continue; // already handled
                if (method.getDeclaringClass() == Object.class) continue;
                var key = method.getName() + java.util.Arrays.toString(method.getParameterTypes());
                if (interceptedMethods.contains(key)) continue; // already intercepted
                if (shouldIntercept(method)) {
                    generateSuperBridge(clb, beanCD, method);
                    generateInterceptedMethod(clb, subclassCD, beanCD, method);
                    interceptedMethods.add(key);
                }
            }
        });

        return new GeneratedInterceptedClass(subclassName, bytecode);
    }

    /**
     * Generate $$super$methodName that calls super.methodName() — used by the interceptor chain
     * to invoke the original method without infinite recursion.
     */
    private static void generateSuperBridge(java.lang.classfile.ClassBuilder clb,
            ClassDesc beanCD, Method method) {
        var returnCD = classDescOf(method.getReturnType());
        var paramCDs = new ClassDesc[method.getParameterCount()];
        for (int i = 0; i < paramCDs.length; i++) {
            paramCDs[i] = classDescOf(method.getParameterTypes()[i]);
        }
        var methodType = MethodTypeDesc.of(returnCD, paramCDs);

        clb.withMethodBody(
                "$$super$" + method.getName(),
                methodType,
                ClassFile.ACC_PUBLIC,
                cob -> {
                    cob.aload(0);
                    int s = 1;
                    for (var pcd : paramCDs) { s = loadParam(cob, pcd, s); }
                    cob.invokespecial(beanCD, method.getName(), methodType);
                    emitReturn(cob, returnCD);
                });
    }

    private static boolean shouldIntercept(Method method) {
        if (Modifier.isStatic(method.getModifiers())) return false;
        if (Modifier.isPrivate(method.getModifiers())) return false;
        if (Modifier.isFinal(method.getModifiers())) return false;
        if (method.isSynthetic()) return false;
        if (method.isBridge()) return false;
        if (method.getName().startsWith("$$")) return false;
        // CDI spec: @Inject initializer methods are NOT intercepted
        if (method.isAnnotationPresent(jakarta.inject.Inject.class)) return false;
        // Target class interceptor methods (@AroundInvoke etc.) are not business methods
        return !method.isAnnotationPresent(jakarta.interceptor.AroundInvoke.class);
    }

    private static void generateInterceptedMethod(
            java.lang.classfile.ClassBuilder clb,
            ClassDesc subclassCD, ClassDesc beanCD, Method method) {

        var returnCD = classDescOf(method.getReturnType());
        var paramCDs = new ClassDesc[method.getParameterCount()];
        for (int i = 0; i < paramCDs.length; i++) {
            paramCDs[i] = classDescOf(method.getParameterTypes()[i]);
        }
        var methodType = MethodTypeDesc.of(returnCD, paramCDs);

        clb.withMethodBody(
                method.getName(),
                methodType,
                ClassFile.ACC_PUBLIC,
                cob -> {
                    // If manager is null (pre-init), call super directly
                    cob.aload(0);
                    cob.getfield(subclassCD, FIELD_MANAGER, CD_InterceptorManager);
                    var intercepted = cob.newLabel();
                    cob.ifnonnull(intercepted);

                    // Super call (manager not yet set)
                    cob.aload(0);
                    int s = 1;
                    for (var pcd : paramCDs) { s = loadParam(cob, pcd, s); }
                    cob.invokespecial(beanCD, method.getName(), methodType);
                    emitReturn(cob, returnCD);

                    // Intercepted path
                    cob.labelBinding(intercepted);

                    // Get $$super$ Method for target invocation (avoids infinite recursion)
                    // this.getClass().getDeclaredMethod("$$super$name", paramTypes...)
                    cob.aload(0);
                    cob.invokevirtual(CD_Object, "getClass", MethodTypeDesc.of(CD_Class));
                    cob.ldc("$$super$" + method.getName());
                    cob.loadConstant(paramCDs.length);
                    cob.anewarray(CD_Class);
                    for (int i = 0; i < paramCDs.length; i++) {
                        cob.dup();
                        cob.loadConstant(i);
                        if (method.getParameterTypes()[i].isPrimitive()) {
                            var wcd = classDescOf(wrapperOf(method.getParameterTypes()[i]));
                            cob.getstatic(wcd, "TYPE", CD_Class);
                        } else {
                            cob.ldc(paramCDs[i]);
                        }
                        cob.aastore();
                    }
                    cob.invokevirtual(CD_Class, "getDeclaredMethod",
                            MethodTypeDesc.of(CD_Method, CD_String, CD_Class.arrayType()));
                    int mSlot = method.getParameterCount() + 1;
                    cob.astore(mSlot);

                    // Build Object[] args
                    cob.loadConstant(paramCDs.length);
                    cob.anewarray(CD_Object);
                    int slot = 1;
                    for (int i = 0; i < paramCDs.length; i++) {
                        cob.dup();
                        cob.loadConstant(i);
                        slot = boxAndLoad(cob, method.getParameterTypes()[i], slot);
                        cob.aastore();
                    }
                    int aSlot = mSlot + 1;
                    cob.astore(aSlot);

                    // Resolve chain (with method-level bindings + target class @AroundInvoke)
                    cob.aload(0);
                    cob.getfield(subclassCD, FIELD_MANAGER, CD_InterceptorManager);
                    cob.aload(0);
                    cob.getfield(subclassCD, FIELD_BINDINGS, CD_Set);
                    cob.aload(mSlot); // pass the $$super$ Method for binding resolution
                    cob.aload(0);     // pass this for target class @AroundInvoke
                    cob.aload(0);
                    cob.getfield(subclassCD, FIELD_CONTEXT, CD_CreationalContext);
                    cob.invokevirtual(CD_InterceptorManager, "resolveChainForMethod",
                            MethodTypeDesc.of(CD_List, CD_Set, CD_Method, CD_Object, CD_CreationalContext));
                    int cSlot = aSlot + 1;
                    cob.astore(cSlot);

                    // new VaubanInvocationContext(this, method, constructor, args, chain, targetInvoker)
                    cob.new_(CD_VaubanInvocationContext);
                    cob.dup();
                    cob.aload(0);
                    cob.aload(mSlot);
                    cob.aconst_null(); // constructor
                    cob.aload(aSlot);
                    cob.aload(cSlot);
                    cob.aconst_null(); // targetInvoker
                    cob.invokespecial(CD_VaubanInvocationContext, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void,
                                    CD_Object, CD_Method, CD_Constructor, CD_Object.arrayType(), CD_List, CD_TargetInvoker));

                    // ctx.proceed()
                    cob.invokevirtual(CD_VaubanInvocationContext, "proceed",
                            MethodTypeDesc.of(CD_Object));

                    // Cache any interceptor instances created during AroundConstruct
                    cob.aload(0);
                    cob.getfield(subclassCD, FIELD_MANAGER, CD_InterceptorManager);
                    cob.invokestatic(CD_InterceptorManager, "$$getAroundConstructContext", MethodTypeDesc.of(CD_CreationalContext));
                    cob.invokevirtual(CD_InterceptorManager, "shareInstances", MethodTypeDesc.of(ConstantDescs.CD_void, CD_CreationalContext));

                    // Return
                    if (method.getReturnType() == void.class) {
                        cob.pop();
                        cob.return_();
                    } else if (method.getReturnType().isPrimitive()) {
                        unboxReturn(cob, method.getReturnType());
                    } else {
                        cob.checkcast(returnCD);
                        cob.areturn();
                    }
                });
    }

    private static int boxAndLoad(CodeBuilder cob, Class<?> type, int slot) {
        if (!type.isPrimitive()) {
            cob.aload(slot);
            return slot + 1;
        }
        var wrapperCD = classDescOf(wrapperOf(type));
        return switch (type.getName()) {
            case "boolean", "byte", "char", "short", "int" -> {
                cob.iload(slot);
                cob.invokestatic(wrapperCD, METHOD_VALUEOF,
                        MethodTypeDesc.of(wrapperCD, classDescOf(type)));
                yield slot + 1;
            }
            case "long" -> {
                cob.lload(slot);
                cob.invokestatic(wrapperCD, METHOD_VALUEOF,
                        MethodTypeDesc.of(wrapperCD, classDescOf(type)));
                yield slot + 2;
            }
            case "float" -> {
                cob.fload(slot);
                cob.invokestatic(wrapperCD, METHOD_VALUEOF,
                        MethodTypeDesc.of(wrapperCD, classDescOf(type)));
                yield slot + 1;
            }
            case "double" -> {
                cob.dload(slot);
                cob.invokestatic(wrapperCD, METHOD_VALUEOF,
                        MethodTypeDesc.of(wrapperCD, classDescOf(type)));
                yield slot + 2;
            }
            default -> { cob.aload(slot); yield slot + 1; }
        };
    }

    private static void unboxReturn(CodeBuilder cob, Class<?> type) {
        var wrapperCD = classDescOf(wrapperOf(type));
        cob.checkcast(wrapperCD);
        switch (type.getName()) {
            case "boolean" -> { cob.invokevirtual(wrapperCD, "booleanValue", MethodTypeDesc.of(ConstantDescs.CD_boolean)); cob.ireturn(); }
            case "byte" -> { cob.invokevirtual(wrapperCD, "byteValue", MethodTypeDesc.of(ConstantDescs.CD_byte)); cob.ireturn(); }
            case "char" -> { cob.invokevirtual(wrapperCD, "charValue", MethodTypeDesc.of(ConstantDescs.CD_char)); cob.ireturn(); }
            case "short" -> { cob.invokevirtual(wrapperCD, "shortValue", MethodTypeDesc.of(ConstantDescs.CD_short)); cob.ireturn(); }
            case "int" -> { cob.invokevirtual(wrapperCD, "intValue", MethodTypeDesc.of(ConstantDescs.CD_int)); cob.ireturn(); }
            case "long" -> { cob.invokevirtual(wrapperCD, "longValue", MethodTypeDesc.of(ConstantDescs.CD_long)); cob.lreturn(); }
            case "float" -> { cob.invokevirtual(wrapperCD, "floatValue", MethodTypeDesc.of(ConstantDescs.CD_float)); cob.freturn(); }
            case "double" -> { cob.invokevirtual(wrapperCD, "doubleValue", MethodTypeDesc.of(ConstantDescs.CD_double)); cob.dreturn(); }
            default -> cob.areturn();
        }
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

    private static ClassDesc classDescOf(String className) {
        if (className.contains(".")) {
            int lastDot = className.lastIndexOf('.');
            return ClassDesc.of(className.substring(0, lastDot), className.substring(lastDot + 1));
        }
        return ClassDesc.of(className);
    }

    private static ClassDesc classDescOf(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == void.class) return ConstantDescs.CD_void;
            if (type == boolean.class) return ConstantDescs.CD_boolean;
            if (type == byte.class) return ConstantDescs.CD_byte;
            if (type == char.class) return ConstantDescs.CD_char;
            if (type == short.class) return ConstantDescs.CD_short;
            if (type == int.class) return ConstantDescs.CD_int;
            if (type == long.class) return ConstantDescs.CD_long;
            if (type == float.class) return ConstantDescs.CD_float;
            if (type == double.class) return ConstantDescs.CD_double;
        }
        if (type.isArray()) {
            return classDescOf(type.getComponentType()).arrayType();
        }
        String name = type.getName();
        if (name.contains(".")) {
            int lastDot = name.lastIndexOf('.');
            return ClassDesc.of(name.substring(0, lastDot), name.substring(lastDot + 1));
        }
        return ClassDesc.of(name);
    }

    private static Class<?> wrapperOf(Class<?> primitive) {
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == char.class) return Character.class;
        if (primitive == short.class) return Short.class;
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == float.class) return Float.class;
        if (primitive == double.class) return Double.class;
        return primitive;
    }

    public record GeneratedInterceptedClass(String className, byte[] bytecode) {
        @Override public boolean equals(Object o) {
            return o instanceof GeneratedInterceptedClass g
                    && className.equals(g.className)
                    && java.util.Arrays.equals(bytecode, g.bytecode);
        }
        @Override public int hashCode() {
            return className.hashCode() ^ java.util.Arrays.hashCode(bytecode);
        }
        @Override public String toString() {
            return "GeneratedInterceptedClass[" + className + ", " + bytecode.length + " bytes]";
        }
    }
}
