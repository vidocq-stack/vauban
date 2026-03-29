package fr.vidocq.vauban.processor.codegen.proxy;

import fr.vidocq.vauban.indexer.model.ClassInfo;
import fr.vidocq.vauban.indexer.model.MethodInfo;
import fr.vidocq.vauban.indexer.model.TypeInfo;
import fr.vidocq.vauban.processor.codegen.GeneratedClass;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

/**
 * Generates CDI client proxies using the JDK 25 Class-File API.
 *
 * <p>A client proxy is a subclass of the bean that delegates every public
 * non-final non-static method to the contextual instance obtained from a
 * {@link java.util.function.Supplier}.
 */
public final class ClientProxyGenerator {

    private static final ClassDesc CD_Supplier = ClassDesc.of("java.util.function.Supplier");
    private static final ClassDesc CD_Object = ConstantDescs.CD_Object;

    private ClientProxyGenerator() {}

    /**
     * Generate a client proxy class for the given bean.
     *
     * @param beanClass the indexed class information of the bean
     * @return a {@link GeneratedClass} containing the proxy class name and bytecode
     */
    public static GeneratedClass generate(ClassInfo beanClass) {
        String beanClassName = beanClass.name().value();
        String proxyClassName = beanClassName + "_ClientProxy";

        ClassDesc proxyCD = ClassDesc.of(proxyClassName);
        ClassDesc beanCD = ClassDesc.of(beanClassName);

        byte[] bytecode = ClassFile.of().build(proxyCD, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(beanCD);

            // Field: private final Supplier delegate
            clb.withField("delegate", CD_Supplier,
                    ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);

            // Constructor: public Proxy(Supplier delegate) { super(); this.delegate = delegate; }
            clb.withMethodBody(
                    ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_Supplier),
                    ClassFile.ACC_PUBLIC,
                    cob -> {
                        cob.aload(0);
                        cob.invokespecial(beanCD, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.aload(0);
                        cob.aload(1);
                        cob.putfield(proxyCD, "delegate", CD_Supplier);
                        cob.return_();
                    });

            // Override each eligible method
            for (var method : beanClass.methods()) {
                if (shouldProxy(method)) {
                    generateProxyMethod(clb, proxyCD, beanCD, method);
                }
            }
        });

        return new GeneratedClass(proxyClassName, bytecode);
    }

    private static boolean shouldProxy(MethodInfo method) {
        if (method.isConstructor() || method.isStaticInitializer()) return false;
        if (method.isStatic()) return false;
        if (method.isPrivate()) return false;
        if ((method.accessFlags() & 0x0010) != 0) return false; // final
        if (method.isSynthetic()) return false;
        return true;
    }

    private static void generateProxyMethod(ClassBuilder clb, ClassDesc proxyCD,
                                            ClassDesc beanCD, MethodInfo method) {
        var returnCD = toClassDesc(method.returnType());
        var paramCDs = method.parameters().stream()
                .map(p -> toClassDesc(p.type()))
                .toArray(ClassDesc[]::new);
        var methodType = MethodTypeDesc.of(returnCD, paramCDs);

        clb.withMethodBody(
                method.name(),
                methodType,
                ClassFile.ACC_PUBLIC,
                cob -> {
                    // ((BeanClass) this.delegate.get())
                    cob.aload(0);
                    cob.getfield(proxyCD, "delegate", CD_Supplier);
                    cob.invokeinterface(CD_Supplier, "get",
                            MethodTypeDesc.of(CD_Object));
                    cob.checkcast(beanCD);

                    // Load all parameters
                    int slot = 1;
                    for (var paramCD : paramCDs) {
                        slot = loadParam(cob, paramCD, slot);
                    }

                    // Invoke the real method
                    cob.invokevirtual(beanCD, method.name(), methodType);

                    // Return
                    emitReturn(cob, returnCD);
                });
    }

    private static int loadParam(CodeBuilder cob, ClassDesc paramCD, int slot) {
        String desc = paramCD.descriptorString();
        switch (desc.charAt(0)) {
            case 'Z', 'B', 'C', 'S', 'I' -> { cob.iload(slot); return slot + 1; }
            case 'J'                       -> { cob.lload(slot); return slot + 2; }
            case 'F'                       -> { cob.fload(slot); return slot + 1; }
            case 'D'                       -> { cob.dload(slot); return slot + 2; }
            default                        -> { cob.aload(slot); return slot + 1; }
        }
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

    /**
     * Convert the indexer's {@link TypeInfo} model to a {@link ClassDesc}
     * suitable for the Class-File API. Generic types are erased.
     */
    static ClassDesc toClassDesc(TypeInfo typeInfo) {
        return switch (typeInfo) {
            case TypeInfo.VoidType v -> ConstantDescs.CD_void;
            case TypeInfo.PrimitiveType p -> switch (p.kind()) {
                case BOOLEAN -> ConstantDescs.CD_boolean;
                case BYTE    -> ConstantDescs.CD_byte;
                case CHAR    -> ConstantDescs.CD_char;
                case SHORT   -> ConstantDescs.CD_short;
                case INT     -> ConstantDescs.CD_int;
                case LONG    -> ConstantDescs.CD_long;
                case FLOAT   -> ConstantDescs.CD_float;
                case DOUBLE  -> ConstantDescs.CD_double;
            };
            case TypeInfo.ClassType c -> ClassDesc.of(c.name().value());
            case TypeInfo.ArrayType a -> toClassDesc(a.componentType()).arrayType(a.dimensions());
            case TypeInfo.ParameterizedType p -> ClassDesc.of(p.rawType().value());
            case TypeInfo.TypeVariable t -> CD_Object;
            case TypeInfo.WildcardType w -> CD_Object;
        };
    }
}
