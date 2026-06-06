package io.vidocq.vauban.core.provider;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

/**
 * Generates the bytecode of a per-module {@code _VaubanComponents} class implementing
 * {@link io.vidocq.vauban.core.VaubanComponentProvider}, so the container can instantiate the
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
            ClassDesc.of("io.vidocq.vauban.core.VaubanComponentProvider");
    private static final MethodTypeDesc MTD_void = MethodTypeDesc.of(ConstantDescs.CD_void);
    private static final MethodTypeDesc MTD_String_equals =
            MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_Object);
    private static final MethodTypeDesc MTD_create =
            MethodTypeDesc.of(CD_Object, CD_String);

    private ComponentProviderClassGenerator() {}

    /** A generated class: its fully-qualified name and its bytecode. */
    public record Generated(String className, byte[] bytecode) {}

    /**
     * @param providerClassName fully-qualified name of the provider to generate (in a package of
     *                          the current module, e.g. {@code app._VaubanComponents})
     * @param noArgBeanFqns     fully-qualified names of public no-arg beans to instantiate
     */
    public static Generated generate(String providerClassName, List<String> noArgBeanFqns) {
        var providerCD = ClassDesc.of(providerClassName);
        byte[] bytecode = ClassFile.of().build(providerCD, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            clb.withSuperclass(CD_Object);
            clb.withInterfaceSymbols(CD_Provider);

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
        });
        return new Generated(providerClassName, bytecode);
    }
}
