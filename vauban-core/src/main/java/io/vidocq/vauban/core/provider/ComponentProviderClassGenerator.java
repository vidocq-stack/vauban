package io.vidocq.vauban.core.provider;

import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Label;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.ArrayList;

/**
 * Generates the bytecode of a per-module {@code _VaubanComponents} class implementing
 * {@link io.vidocq.vauban.api.VaubanComponentProvider}, so the container can instantiate the
 * module's no-arg beans in-module ({@code new X()}) without reflection and without
 * {@code opens … to io.vidocq.vauban.core}.
 *
 * <p>This is the build-plugin counterpart of the APT's source generator
 * ({@code ComponentProviderGenerator}): the {@code vauban-maven-plugin} runs after compilation and
 * has no {@code javac}, so it must emit bytecode directly via the Class-File API. The generated
 * {@code create(String)} is a chain of {@code className.equals("fqn")} tests returning a fresh
 * instance, mirroring the source switch. Only public, top-level, no-arg beans are listed; anything
 * else is left to the reflective fallback.
 */
public final class ComponentProviderClassGenerator {

    private static final ClassDesc CD_Object = ConstantDescs.CD_Object;
    private static final ClassDesc CD_String = ConstantDescs.CD_String;
    private static final ClassDesc CD_Provider =
            ClassDesc.of("io.vidocq.vauban.api.VaubanComponentProvider");
    private static final ClassDesc CD_Vetoed = ClassDesc.of("jakarta.enterprise.inject.Vetoed");
    private static final MethodTypeDesc MTD_void = MethodTypeDesc.of(ConstantDescs.CD_void);
    private static final MethodTypeDesc MTD_String_equals =
            MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_Object);
    private static final MethodTypeDesc MTD_create =
            MethodTypeDesc.of(CD_Object, CD_String);
    private static final MethodTypeDesc MTD_injectField =
            MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_Object, CD_String, CD_String, CD_Object);

    private ComponentProviderClassGenerator() {}

    /** A generated class: its fully-qualified name and its bytecode. */
    public record Generated(String className, byte[] bytecode) {}

    /**
     * Describes a field injection site: the declaring class, field name, and erased field type.
     * The field must be package-private (or wider) — private fields still require {@code opens}.
     *
     * @param declaringClassFqn fully-qualified name of the class declaring the field
     * @param fieldName         simple field name
     * @param fieldTypeFqn      fully-qualified name of the erased field type (e.g. {@code java.util.List},
     *                          or {@code java.lang.String[]} for array types)
     */
    public record FieldInject(String declaringClassFqn, String fieldName, String fieldTypeFqn) {}

    /**
     * Describes a method invocation site: the declaring class, method name, erased parameter types,
     * the erased return type (null for void), and whether the method is static.
     *
     * <p>The {@code methodId} key is {@code methodName + "(" + String.join(",", paramErasures) + ")"},
     * matching exactly the key produced by {@link io.vidocq.vauban.core.container.VaubanLookup#methodId}.
     *
     * @param declaringClassFqn fully-qualified name of the class declaring the method
     * @param methodName        simple method name
     * @param paramErasures     erased parameter type names (binary, e.g. {@code java.lang.String},
     *                          {@code java.lang.Object[]})
     * @param isStatic          whether the method is static
     * @param isVoid            whether the method return type is void
     * @param returnErasure     erased return type FQN, or {@code null} for void
     */
    public record MethodInvoke(
            String declaringClassFqn,
            String methodName,
            List<String> paramErasures,
            boolean isStatic,
            boolean isVoid,
            String returnErasure
    ) {
        public MethodInvoke {
            paramErasures = List.copyOf(paramErasures);
        }

        /** The lookup key used by the container to find this method. */
        public String methodId() {
            return methodName + "(" + String.join(",", paramErasures) + ")";
        }
    }

    /**
     * @param providerClassName fully-qualified name of the provider to generate (in a package of
     *                          the current module, e.g. {@code app._VaubanComponents})
     * @param noArgBeanFqns     fully-qualified names of public no-arg beans to instantiate
     */
    public static Generated generate(String providerClassName, List<String> noArgBeanFqns) {
        return generate(providerClassName, noArgBeanFqns, List.of(), List.of());
    }

    /**
     * @param providerClassName fully-qualified name of the provider to generate
     * @param noArgBeanFqns     fully-qualified names of public no-arg beans to instantiate
     * @param fieldInjects      field injection descriptors for in-package, non-private fields
     */
    public static Generated generate(String providerClassName, List<String> noArgBeanFqns,
            List<FieldInject> fieldInjects) {
        return generate(providerClassName, noArgBeanFqns, fieldInjects, List.of());
    }

    /**
     * @param providerClassName fully-qualified name of the provider to generate
     * @param noArgBeanFqns     fully-qualified names of public no-arg beans to instantiate
     * @param fieldInjects      field injection descriptors for in-package, non-private fields
     * @param methodInvokes     method invocation descriptors for in-package methods
     */
    public static Generated generate(String providerClassName, List<String> noArgBeanFqns,
            List<FieldInject> fieldInjects, List<MethodInvoke> methodInvokes) {
        var providerCD = ClassDesc.of(providerClassName);
        byte[] bytecode = ClassFile.of().build(providerCD, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            clb.withSuperclass(CD_Object);
            clb.withInterfaceSymbols(CD_Provider);
            // @Vetoed: never a CDI bean — so even Weld in bean-discovery-mode=all (which would try
            // to load every class) skips it, avoiding a NoClassDefFoundError when vauban-api is
            // absent at runtime. Neutral for Vauban, which loads it via ServiceLoader, not scanning.
            clb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(CD_Vetoed)));

            // public no-arg constructor (required so ServiceLoader can instantiate the provider)
            clb.withMethodBody(ConstantDescs.INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, cob -> {
                cob.aload(0);
                cob.invokespecial(CD_Object, ConstantDescs.INIT_NAME, MTD_void);
                cob.return_();
            });

            // public Object create(String className) {
            //     if (className.equals("a.B")) return new a.B();
            //     ...
            //     return null;
            // }
            clb.withMethodBody("create", MTD_create, ClassFile.ACC_PUBLIC, cob -> {
                for (var fqn : noArgBeanFqns) {
                    var beanCD = ClassDesc.of(fqn);
                    var next = cob.newLabel();
                    cob.aload(1);
                    cob.ldc(fqn);
                    cob.invokevirtual(CD_String, "equals", MTD_String_equals);
                    cob.ifeq(next);
                    cob.new_(beanCD);
                    cob.dup();
                    cob.invokespecial(beanCD, ConstantDescs.INIT_NAME, MTD_void);
                    cob.areturn();
                    cob.labelBinding(next);
                }
                cob.aconst_null();
                cob.areturn();
            });

            // public boolean injectField(Object bean, String className, String fieldName, Object value) {
            //     if (className.equals("a.B") && fieldName.equals("f")) {
            //         ((a.B) bean).f = (FieldType) value;
            //         return true;
            //     }
            //     ...
            //     return false;
            // }
            clb.withMethodBody("injectField", MTD_injectField, ClassFile.ACC_PUBLIC, cob -> {
                for (var fi : fieldInjects) {
                    var declCD = ClassDesc.of(fi.declaringClassFqn());
                    var fieldTypeCD = resolveFieldTypeDesc(fi.fieldTypeFqn());
                    Label next = cob.newLabel();
                    // if (!className.equals(declFqn)) goto next
                    cob.aload(2);
                    cob.ldc(fi.declaringClassFqn());
                    cob.invokevirtual(CD_String, "equals", MTD_String_equals);
                    cob.ifeq(next);
                    // if (!fieldName.equals(thatField)) goto next
                    cob.aload(3);
                    cob.ldc(fi.fieldName());
                    cob.invokevirtual(CD_String, "equals", MTD_String_equals);
                    cob.ifeq(next);
                    // ((Decl) bean).field = (FieldType) value
                    cob.aload(1);
                    cob.checkcast(declCD);
                    cob.aload(4);
                    if (!fieldTypeCD.equals(CD_Object)) {
                        cob.checkcast(fieldTypeCD);
                    }
                    cob.putfield(declCD, fi.fieldName(), fieldTypeCD);
                    cob.iconst_1();
                    cob.ireturn();
                    cob.labelBinding(next);
                }
                cob.iconst_0();
                cob.ireturn();
            });

            // Emit invoke() only when there are methods to dispatch, to keep the class minimal.
            //
            // public Object invoke(Object target, String className, String methodId, Object[] args) {
            //     if (className.equals("a.B")) {
            //         if (methodId.equals("m(java.lang.String)")) {
            //             ((a.B) target).m((String) args[0]);
            //             return null;           // void method — invoked, not NOT_INVOKED
            //         }
            //         if (methodId.equals("produce()")) {
            //             return ((a.B) target).produce();
            //         }
            //     }
            //     return VaubanComponentProvider.NOT_INVOKED;  // sentinel: not owned
            // }
            if (!methodInvokes.isEmpty()) {
                // Signature: (Object, String, String, Object[]) -> Object
                // slots: 0=this, 1=target, 2=className, 3=methodId, 4=args
                var MTD_invoke = MethodTypeDesc.of(CD_Object, CD_Object, CD_String, CD_String, CD_Object.arrayType());
                clb.withMethodBody("invoke", MTD_invoke, ClassFile.ACC_PUBLIC, cob -> {
                    // Group by declaring class for a two-level if-chain.
                    // Build an ordered list of distinct declaring classes (preserving first-seen order).
                    var classOrder = new ArrayList<String>();
                    for (var mi : methodInvokes) {
                        if (!classOrder.contains(mi.declaringClassFqn())) {
                            classOrder.add(mi.declaringClassFqn());
                        }
                    }

                    for (var declFqn : classOrder) {
                        var declCD = ClassDesc.of(declFqn);
                        var nextClass = cob.newLabel();
                        // if (!className.equals(declFqn)) goto nextClass
                        cob.aload(2);
                        cob.ldc(declFqn);
                        cob.invokevirtual(CD_String, "equals", MTD_String_equals);
                        cob.ifeq(nextClass);

                        for (var mi : methodInvokes) {
                            if (!mi.declaringClassFqn().equals(declFqn)) continue;
                            var nextMethod = cob.newLabel();
                            var key = mi.methodId();
                            // if (!methodId.equals(key)) goto nextMethod
                            cob.aload(3);
                            cob.ldc(key);
                            cob.invokevirtual(CD_String, "equals", MTD_String_equals);
                            cob.ifeq(nextMethod);

                            // Receiver for instance methods
                            if (!mi.isStatic()) {
                                cob.aload(1);
                                cob.checkcast(declCD);
                            }
                            // Load and cast each argument from the args array (slot 4)
                            var params = mi.paramErasures();
                            for (int i = 0; i < params.size(); i++) {
                                cob.aload(4);
                                loadIntConstant(cob, i);
                                cob.aaload();
                                var paramCD = resolveFieldTypeDesc(params.get(i));
                                if (!paramCD.equals(CD_Object)) {
                                    cob.checkcast(paramCD);
                                }
                            }
                            // Build the method descriptor: (P0,P1,...) -> ReturnType
                            var paramDescs = params.stream()
                                    .map(ComponentProviderClassGenerator::resolveFieldTypeDesc)
                                    .toArray(ClassDesc[]::new);
                            ClassDesc retCD = mi.isVoid() ? ConstantDescs.CD_void
                                    : (mi.returnErasure() == null ? CD_Object
                                            : resolveFieldTypeDesc(mi.returnErasure()));
                            var mtdMethod = MethodTypeDesc.of(retCD, paramDescs);

                            if (mi.isStatic()) {
                                cob.invokestatic(declCD, mi.methodName(), mtdMethod);
                            } else {
                                cob.invokevirtual(declCD, mi.methodName(), mtdMethod);
                            }

                            if (mi.isVoid()) {
                                // void method: successfully invoked — return null (not NOT_INVOKED)
                                cob.aconst_null();
                            }
                            cob.areturn();
                            cob.labelBinding(nextMethod);
                        }
                        cob.labelBinding(nextClass);
                    }
                    // No className/methodId matched — return the NOT_INVOKED sentinel
                    cob.getstatic(CD_Provider, "NOT_INVOKED", CD_Object);
                    cob.areturn();
                });
            }
        });
        return new Generated(providerClassName, bytecode);
    }

    /**
     * Emits the most compact bytecode instruction to push a small non-negative integer.
     * Uses {@code iconst_N} for 0–5, {@code bipush} for 6–127, {@code sipush} for 128–32767.
     */
    private static void loadIntConstant(java.lang.classfile.CodeBuilder cob, int value) {
        switch (value) {
            case 0 -> cob.iconst_0();
            case 1 -> cob.iconst_1();
            case 2 -> cob.iconst_2();
            case 3 -> cob.iconst_3();
            case 4 -> cob.iconst_4();
            case 5 -> cob.iconst_5();
            default -> {
                if (value <= 127) cob.bipush(value);
                else cob.sipush(value);
            }
        }
    }

    /**
     * Resolves a field type FQN (as stored in {@link FieldInject}) to a {@link ClassDesc}.
     * Handles simple array types expressed as {@code "ComponentType[]"}.
     */
    private static ClassDesc resolveFieldTypeDesc(String fqn) {
        if (fqn.endsWith("[]")) {
            return ClassDesc.of(fqn.substring(0, fqn.length() - 2)).arrayType();
        }
        return ClassDesc.of(fqn);
    }
}
