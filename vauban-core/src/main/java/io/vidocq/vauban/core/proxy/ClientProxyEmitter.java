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
package io.vidocq.vauban.core.proxy;

import io.vidocq.vauban.core.interceptor.TypeRef;
import io.vidocq.vauban.core.proxy.ClientProxyShape.ProxyMethodShape;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

/**
 * Emits the bytecode of a {@code <Bean>_ClientProxy} from a neutral {@link ClientProxyShape}
 * with the JDK 25 Class-File API — the single emitter behind both bytecode front-ends
 * (runtime {@code Class}-driven and APT {@code ClassInfo}-driven), exactly like
 * {@link io.vidocq.vauban.core.interceptor.InterceptedEmitter} for {@code $$Intercepted}.
 *
 * <p>A client proxy is a subclass of the bean that delegates every eligible method to the
 * contextual instance obtained from a {@link java.util.function.Supplier} held in
 * {@code $$delegate} (wired via {@code $$setDelegate}).
 *
 * <p><b>Protected method handling (JVMS §4.10.1.9):</b> the verifier refuses
 * {@code invokevirtual} on a protected (or package-private) method declared in another
 * runtime package when the stack-top receiver is the bean, not the proxy. Methods flagged
 * {@link ProxyMethodShape#needsMethodHandle()} dispatch through a static-final
 * {@code MethodHandle.invokeExact} initialized in {@code <clinit>} via
 * {@code MethodHandles.privateLookupIn(...).findVirtual(...)}.
 */
public final class ClientProxyEmitter {

    private static final ClassDesc CD_Supplier = ClassDesc.of("java.util.function.Supplier");
    private static final ClassDesc CD_Object = ConstantDescs.CD_Object;
    private static final ClassDesc CD_MethodHandle = ClassDesc.of("java.lang.invoke.MethodHandle");
    private static final ClassDesc CD_MethodHandles = ClassDesc.of("java.lang.invoke.MethodHandles");
    private static final ClassDesc CD_MethodHandlesLookup = ClassDesc.of("java.lang.invoke.MethodHandles$Lookup");
    private static final ClassDesc CD_MethodType = ClassDesc.of("java.lang.invoke.MethodType");
    private static final ClassDesc CD_Class = ConstantDescs.CD_Class;
    private static final ClassDesc CD_Throwable = ConstantDescs.CD_Throwable;
    private static final ClassDesc CD_ExceptionInInitializerError = ClassDesc.of("java.lang.ExceptionInInitializerError");

    private ClientProxyEmitter() {}

    /** Emit the bytecode of {@code shape.proxyClassName()} (proxy co-located with the bean). */
    public static byte[] emit(ClientProxyShape shape) {
        return emitAt(shape, shape.proxyClassName());
    }

    /**
     * Emit the bytecode at an explicit {@code proxyBinaryName} while still extending the shape's
     * {@code beanBinaryName} — the bytecode counterpart of
     * {@code ClientProxySourceRenderer.renderAt}. The producer-proxy path (issue #42, Stage 1.6)
     * passes a name in the producer's own package so the proxy of a fully-public produced type from
     * a non-opened module lands in-module; {@link #emit(ClientProxyShape)} passes the co-located
     * {@code shape.proxyClassName()} (behaviour-preserving for managed beans).
     */
    public static byte[] emitAt(ClientProxyShape shape, String proxyBinaryName) {
        ClassDesc proxyCD = ClassDesc.of(proxyBinaryName);
        ClassDesc beanCD = ClassDesc.of(shape.beanBinaryName());
        List<ProxyMethodShape> methods = shape.methods();
        List<String> mhFields = shape.methodHandleFieldNames();

        return ClassFile.of().build(proxyCD, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(beanCD);

            // Field: private Supplier $$delegate (lazy-set via $$setDelegate)
            clb.withField(ClientProxyShape.FIELD_DELEGATE, CD_Supplier, ClassFile.ACC_PRIVATE);

            // Static final MethodHandle fields for protected/cross-package methods.
            for (String mhField : mhFields) {
                if (mhField != null) {
                    clb.withField(mhField, CD_MethodHandle,
                            ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
                }
            }

            // No-arg constructor calling the chosen super constructor with default values
            // (null / 0 / false) — CDI 4.1: beans with only @Inject constructors must still
            // be proxyable. An empty superCtorParams list means plain super().
            var superParamCDs = shape.superCtorParams().stream()
                    .map(TypeRef::classDesc)
                    .toArray(ClassDesc[]::new);
            var superCtorType = MethodTypeDesc.of(ConstantDescs.CD_void, superParamCDs);
            clb.withMethodBody(
                    ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC,
                    cob -> {
                        cob.aload(0);
                        for (TypeRef param : shape.superCtorParams()) {
                            pushDefault(cob, param);
                        }
                        cob.invokespecial(beanCD, ConstantDescs.INIT_NAME, superCtorType);
                        cob.return_();
                    });

            // Setter: public void $$setDelegate(Supplier d) { this.$$delegate = d; }
            clb.withMethodBody(
                    ClientProxyShape.SET_DELEGATE_METHOD,
                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_Supplier),
                    ClassFile.ACC_PUBLIC,
                    cob -> {
                        cob.aload(0);
                        cob.aload(1);
                        cob.putfield(proxyCD, ClientProxyShape.FIELD_DELEGATE, CD_Supplier);
                        cob.return_();
                    });

            // <clinit> that initializes the MethodHandle fields (if any).
            if (shape.hasMethodHandles()) {
                generateStaticInitializer(clb, proxyCD, beanCD, methods, mhFields);
            }

            // Override each eligible method.
            for (int i = 0; i < methods.size(); i++) {
                generateProxyMethod(clb, proxyCD, beanCD, methods.get(i), mhFields.get(i));
            }
        });
    }

    /** Push a default value for the given type: 0/false for scalar primitives, null otherwise. */
    private static void pushDefault(CodeBuilder cob, TypeRef param) {
        if (!param.isPrimitive()) {
            cob.aconst_null();
            return;
        }
        switch (param.primitiveKind()) {
            case LONG -> cob.lconst_0();
            case FLOAT -> cob.fconst_0();
            case DOUBLE -> cob.dconst_0();
            default -> cob.iconst_0();
        }
    }

    private static void generateProxyMethod(ClassBuilder clb, ClassDesc proxyCD,
                                            ClassDesc beanCD, ProxyMethodShape method, String mhField) {
        var returnCD = method.returnType().classDesc();
        var paramCDs = method.params().stream()
                .map(TypeRef::classDesc)
                .toArray(ClassDesc[]::new);
        var methodType = MethodTypeDesc.of(returnCD, paramCDs);

        clb.withMethodBody(
                method.name(),
                methodType,
                ClassFile.ACC_PUBLIC,
                cob -> {
                    if (mhField != null) {
                        emitMethodHandleInvocation(cob, proxyCD, beanCD, mhField, methodType);
                    } else {
                        emitInvokevirtualInvocation(cob, proxyCD, beanCD, method, methodType, paramCDs);
                    }
                    emitReturn(cob, returnCD);
                });
    }

    private static void emitInvokevirtualInvocation(CodeBuilder cob, ClassDesc proxyCD,
                                                    ClassDesc beanCD, ProxyMethodShape method,
                                                    MethodTypeDesc methodType, ClassDesc[] paramCDs) {
        // ((BeanClass) this.$$delegate.get()).method(params);
        cob.aload(0);
        cob.getfield(proxyCD, ClientProxyShape.FIELD_DELEGATE, CD_Supplier);
        cob.invokeinterface(CD_Supplier, "get", MethodTypeDesc.of(CD_Object));
        cob.checkcast(beanCD);
        int slot = 1;
        for (var paramCD : paramCDs) slot = loadParam(cob, paramCD, slot);
        cob.invokevirtual(beanCD, method.name(), methodType);
    }

    private static void emitMethodHandleInvocation(CodeBuilder cob, ClassDesc proxyCD,
                                                   ClassDesc beanCD, String mhField,
                                                   MethodTypeDesc methodType) {
        cob.getstatic(proxyCD, mhField, CD_MethodHandle);
        cob.aload(0);
        cob.getfield(proxyCD, ClientProxyShape.FIELD_DELEGATE, CD_Supplier);
        cob.invokeinterface(CD_Supplier, "get", MethodTypeDesc.of(CD_Object));
        cob.checkcast(beanCD);
        int slot = 1;
        for (var paramCD : methodType.parameterArray()) {
            slot = loadParam(cob, paramCD, slot);
        }
        // invokeExact is signature-polymorphic: the descriptor must include the delegate
        // type as first parameter.
        var invokeExactType = methodType.insertParameterTypes(0, beanCD);
        cob.invokevirtual(CD_MethodHandle, "invokeExact", invokeExactType);
    }

    private static void generateStaticInitializer(ClassBuilder clb, ClassDesc proxyCD,
                                                  ClassDesc beanCD,
                                                  List<ProxyMethodShape> methods,
                                                  List<String> mhFields) {
        clb.withMethodBody(
                ConstantDescs.CLASS_INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void),
                ClassFile.ACC_STATIC,
                cob -> {
                    var tryStart = cob.newBoundLabel();
                    for (int i = 0; i < methods.size(); i++) {
                        if (mhFields.get(i) == null) continue;
                        emitMethodHandleLookup(cob, proxyCD, beanCD, methods.get(i), mhFields.get(i));
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
                                               ClassDesc beanCD, ProxyMethodShape method,
                                               String mhField) {
        // Lookup lookup = MethodHandles.privateLookupIn(beanClass, MethodHandles.lookup());
        cob.ldc(beanCD);
        cob.invokestatic(CD_MethodHandles, "lookup",
                MethodTypeDesc.of(CD_MethodHandlesLookup));
        cob.invokestatic(CD_MethodHandles, "privateLookupIn",
                MethodTypeDesc.of(CD_MethodHandlesLookup, CD_Class, CD_MethodHandlesLookup));
        cob.ldc(beanCD);
        cob.ldc(method.name());
        var returnCD = method.returnType().classDesc();
        var paramCDs = method.params().stream()
                .map(TypeRef::classDesc)
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
        cob.putstatic(proxyCD, mhField, CD_MethodHandle);
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
}
