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
package io.vidocq.vauban.processor.codegen.proxy;

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.processor.codegen.GeneratedClass;

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
 *
 * <p><b>Protected method handling :</b> mirrors the workaround implemented
 * in {@code RuntimeClientProxyGenerator}. When an override targets a
 * {@code protected} method declared in a superclass located in a different
 * package than the proxy, JVMS §4.10.1.9 rejects a naive {@code invokevirtual}.
 * The generator then emits a {@link java.lang.invoke.MethodHandle#invokeExact}
 * call backed by a static-final field initialized in {@code <clinit>} via
 * {@link java.lang.invoke.MethodHandles.Lookup#findVirtual}.
 *
 * <p>Currently {@link #generate(ClassInfo)} only overrides methods declared
 * directly by the bean class (no hierarchy walk), so the MethodHandle path is
 * essentially dormant. It is nevertheless wired up so that enabling hierarchy
 * traversal later doesn't reintroduce bug Vauban #6.
 */
public final class ClientProxyGenerator {

    private static final ClassDesc CD_Supplier = ClassDesc.of("java.util.function.Supplier");
    private static final ClassDesc CD_Object = ConstantDescs.CD_Object;
    private static final ClassDesc CD_MethodHandle = ClassDesc.of("java.lang.invoke.MethodHandle");
    private static final ClassDesc CD_MethodHandles = ClassDesc.of("java.lang.invoke.MethodHandles");
    private static final ClassDesc CD_MethodHandlesLookup = ClassDesc.of("java.lang.invoke.MethodHandles$Lookup");
    private static final ClassDesc CD_MethodType = ClassDesc.of("java.lang.invoke.MethodType");
    private static final ClassDesc CD_Class = ConstantDescs.CD_Class;
    private static final ClassDesc CD_Throwable = ConstantDescs.CD_Throwable;
    private static final ClassDesc CD_ExceptionInInitializerError = ClassDesc.of("java.lang.ExceptionInInitializerError");
    private static final String MH_FIELD_PREFIX = "$$mh_";

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

        String proxyPackage = packageOf(proxyClassName);
        // First pass : collect eligible methods and decide if each needs MethodHandle dispatch.
        var methods = new java.util.ArrayList<ProxiedMethod>();
        int mhIndex = 0;
        for (var method : beanClass.methods()) {
            if (!shouldProxy(method)) continue;
            boolean needsMh = needsMethodHandleDispatch(method, beanClassName, proxyPackage);
            String mhField = needsMh ? (MH_FIELD_PREFIX + method.name() + "_" + (mhIndex++)) : null;
            methods.add(new ProxiedMethod(method, mhField));
        }

        byte[] bytecode = ClassFile.of().build(proxyCD, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(beanCD);

            // Field: private Supplier $$delegate
            // Format aligned with RuntimeClientProxyGenerator (lazy-set via $$setDelegate)
            // so that InterceptorBeanWrapper.getOrCreateProxy can instantiate then inject.
            clb.withField("$$delegate", CD_Supplier, ClassFile.ACC_PRIVATE);

            // Static final MethodHandle fields (dormant until hierarchy traversal is added).
            for (var pm : methods) {
                if (pm.mhField() != null) {
                    clb.withField(pm.mhField(), CD_MethodHandle,
                            ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
                }
            }

            // Constructor: public Proxy() { super(); }
            // CDI 4.1: for beans without a no-arg ctor, the runtime falls back to RuntimeClientProxyGenerator.
            clb.withMethodBody(
                    ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC,
                    cob -> {
                        cob.aload(0);
                        cob.invokespecial(beanCD, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.return_();
                    });

            // Setter: public void $$setDelegate(Supplier d) { this.$$delegate = d; }
            clb.withMethodBody(
                    "$$setDelegate",
                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_Supplier),
                    ClassFile.ACC_PUBLIC,
                    cob -> {
                        cob.aload(0);
                        cob.aload(1);
                        cob.putfield(proxyCD, "$$delegate", CD_Supplier);
                        cob.return_();
                    });

            // <clinit> to populate MethodHandle fields.
            if (methods.stream().anyMatch(m -> m.mhField() != null)) {
                generateStaticInitializer(clb, proxyCD, beanCD, methods);
            }

            // Override each eligible method.
            for (var pm : methods) {
                generateProxyMethod(clb, proxyCD, beanCD, pm);
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

    /**
     * Decides whether the method override must dispatch via {@link java.lang.invoke.MethodHandle}
     * to avoid a {@code VerifyError} on {@code invokevirtual} of a protected/package-private
     * member declared in a different runtime package (JVMS §4.10.1.9).
     *
     * <p>For now, {@link ClassInfo#methods()} only exposes methods declared by {@code beanClass}
     * itself, so {@code declaringClassName == beanClassName} and the check is always false.
     * Kept aligned with the runtime generator for when hierarchy traversal is introduced.</p>
     */
    private static boolean needsMethodHandleDispatch(MethodInfo method, String declaringClassName,
                                                     String proxyPackage) {
        if (method.isPublic()) return false;
        String declaringPackage = packageOf(declaringClassName);
        return !declaringPackage.equals(proxyPackage);
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static void generateProxyMethod(ClassBuilder clb, ClassDesc proxyCD,
                                            ClassDesc beanCD, ProxiedMethod pm) {
        MethodInfo method = pm.method();
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
                    if (pm.mhField() != null) {
                        emitMethodHandleInvocation(cob, proxyCD, beanCD, pm, methodType);
                    } else {
                        emitInvokevirtualInvocation(cob, proxyCD, beanCD, method, methodType, paramCDs);
                    }
                    emitReturn(cob, returnCD);
                });
    }

    private static void emitInvokevirtualInvocation(CodeBuilder cob, ClassDesc proxyCD,
                                                    ClassDesc beanCD, MethodInfo method,
                                                    MethodTypeDesc methodType, ClassDesc[] paramCDs) {
        cob.aload(0);
        cob.getfield(proxyCD, "$$delegate", CD_Supplier);
        cob.invokeinterface(CD_Supplier, "get", MethodTypeDesc.of(CD_Object));
        cob.checkcast(beanCD);
        int slot = 1;
        for (var paramCD : paramCDs) slot = loadParam(cob, paramCD, slot);
        cob.invokevirtual(beanCD, method.name(), methodType);
    }

    private static void emitMethodHandleInvocation(CodeBuilder cob, ClassDesc proxyCD,
                                                   ClassDesc beanCD, ProxiedMethod pm,
                                                   MethodTypeDesc methodType) {
        cob.getstatic(proxyCD, pm.mhField(), CD_MethodHandle);
        cob.aload(0);
        cob.getfield(proxyCD, "$$delegate", CD_Supplier);
        cob.invokeinterface(CD_Supplier, "get", MethodTypeDesc.of(CD_Object));
        cob.checkcast(beanCD);
        int slot = 1;
        for (var paramCD : methodType.parameterArray()) {
            slot = loadParam(cob, paramCD, slot);
        }
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
        MethodInfo method = pm.method();
        cob.ldc(beanCD);
        cob.invokestatic(CD_MethodHandles, "lookup",
                MethodTypeDesc.of(CD_MethodHandlesLookup));
        cob.invokestatic(CD_MethodHandles, "privateLookupIn",
                MethodTypeDesc.of(CD_MethodHandlesLookup, CD_Class, CD_MethodHandlesLookup));
        cob.ldc(beanCD);
        cob.ldc(method.name());
        var returnCD = toClassDesc(method.returnType());
        var paramCDs = method.parameters().stream()
                .map(p -> toClassDesc(p.type()))
                .toArray(ClassDesc[]::new);
        cob.ldc(returnCD);
        if (paramCDs.length == 0) {
            cob.invokestatic(CD_MethodType, "methodType",
                    MethodTypeDesc.of(CD_MethodType, CD_Class));
        } else {
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
        cob.invokevirtual(CD_MethodHandlesLookup, "findVirtual",
                MethodTypeDesc.of(CD_MethodHandle, CD_Class,
                        ClassDesc.of("java.lang.String"), CD_MethodType));
        cob.putstatic(proxyCD, pm.mhField(), CD_MethodHandle);
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

    private record ProxiedMethod(MethodInfo method, String mhField) {}
}
