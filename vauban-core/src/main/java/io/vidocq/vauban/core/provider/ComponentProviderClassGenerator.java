package io.vidocq.vauban.core.provider;

import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Label;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

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
     * @param providerClassName fully-qualified name of the provider to generate (in a package of
     *                          the current module, e.g. {@code app._VaubanComponents})
     * @param noArgBeanFqns     fully-qualified names of public no-arg beans to instantiate
     */
    public static Generated generate(String providerClassName, List<String> noArgBeanFqns) {
        return generate(providerClassName, noArgBeanFqns, List.of());
    }

    /**
     * @param providerClassName fully-qualified name of the provider to generate
     * @param noArgBeanFqns     fully-qualified names of public no-arg beans to instantiate
     * @param fieldInjects      field injection descriptors for in-package, non-private fields
     */
    public static Generated generate(String providerClassName, List<String> noArgBeanFqns,
            List<FieldInject> fieldInjects) {
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
        });
        return new Generated(providerClassName, bytecode);
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
