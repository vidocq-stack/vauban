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
package io.vidocq.vauban.core.interceptor;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;

/**
 * Emits the bytecode for an intercepted bean subclass from a neutral {@link InterceptedShape}.
 *
 * <p>This class owns the entire {@link ClassFile} builder body — field names, method shapes,
 * label order, slot arithmetic — so that both the runtime {@link InterceptorSubclassGenerator}
 * (driven from {@link Class} objects) and the compile-time APT front-end (driven from
 * {@code javax.lang.model.element.TypeElement}) produce byte-for-byte identical output.
 *
 * <p><strong>Field names generated:</strong>
 * {@code $$manager}, {@code $$bindings}, {@code $$constructorBindings}, {@code $$context}.
 * <strong>Special methods generated:</strong> {@code $$init}, one {@code $$super$name} bridge
 * and one override per interceptable method.
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class InterceptedEmitter {

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
    private static final ClassDesc CD_CreationalContext =
            ClassDesc.of("jakarta.enterprise.context.spi.CreationalContext");
    private static final ClassDesc CD_TargetInvoker =
            ClassDesc.of("io.vidocq.vauban.core.interceptor.VaubanInvocationContext$TargetInvoker");

    private static final String FIELD_MANAGER = "$$manager";
    private static final String FIELD_BINDINGS = "$$bindings";
    private static final String FIELD_CONTEXT = "$$context";
    private static final String METHOD_VALUEOF = "valueOf";

    /** Erased signature of {@code TargetInvoker.invoke} and of every generated {@code $$ti$<name>}. */
    private static final MethodTypeDesc MTD_TARGET_INVOKER =
            MethodTypeDesc.of(CD_Object, CD_Object, CD_Object.arrayType());

    /**
     * Bootstrap handle for {@code LambdaMetafactory.metafactory} — turns a generated
     * {@code $$ti$<name>} static method into a {@code TargetInvoker} instance at link time, so the
     * interceptor chain invokes the original method through a plain (non-reflective) lambda call.
     */
    private static final DirectMethodHandleDesc BSM_LAMBDA_METAFACTORY = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC,
            ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
            "metafactory",
            MethodTypeDesc.of(
                    ConstantDescs.CD_CallSite,
                    ConstantDescs.CD_MethodHandles_Lookup,
                    ConstantDescs.CD_String,
                    ConstantDescs.CD_MethodType,
                    ConstantDescs.CD_MethodType,
                    ConstantDescs.CD_MethodHandle,
                    ConstantDescs.CD_MethodType));

    private InterceptedEmitter() {}

    /**
     * Emit the bytecode for {@code shape.beanBinaryName() + "$$Intercepted"}.
     *
     * @param shape the neutral description of the bean to intercept
     * @return raw bytecode of the generated subclass
     */
    public static byte[] emit(InterceptedShape shape) {
        String subclassName = shape.beanBinaryName() + "$$Intercepted";
        ClassDesc subclassCD = classDescOf(subclassName);
        ClassDesc beanCD = classDescOf(shape.beanBinaryName());

        return ClassFile.of().build(subclassCD, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(beanCD);

            // Fields
            clb.withField(FIELD_MANAGER, CD_InterceptorManager, ClassFile.ACC_PRIVATE);
            clb.withField(FIELD_BINDINGS, CD_Set, ClassFile.ACC_PRIVATE);
            clb.withField("$$constructorBindings", CD_Set, ClassFile.ACC_PRIVATE);
            clb.withField(FIELD_CONTEXT, CD_CreationalContext, ClassFile.ACC_PRIVATE);

            // Constructors: for each non-private constructor in super class, generate one here
            for (CtorShape ctor : shape.constructors()) {
                var paramCDs = ctor.params().stream()
                        .map(TypeRef::classDesc)
                        .toArray(ClassDesc[]::new);
                var mtd = MethodTypeDesc.of(ConstantDescs.CD_void, paramCDs);

                clb.withMethodBody(
                        ConstantDescs.INIT_NAME,
                        mtd,
                        ClassFile.ACC_PUBLIC,
                        cob -> {
                            cob.aload(0);
                            int slot = 0;
                            for (TypeRef p : ctor.params()) {
                                slot = loadParamBySlot(cob, p, slot);
                            }
                            cob.invokespecial(beanCD, ConstantDescs.INIT_NAME, mtd);
                            cob.return_();
                        });
            }

            // Setter: public void $$init(InterceptorManager, Set<DotName>, Set<DotName>, CreationalContext)
            clb.withMethodBody(
                    "$$init",
                    MethodTypeDesc.of(ConstantDescs.CD_void,
                            CD_InterceptorManager, CD_Set, CD_Set, CD_CreationalContext),
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
                        cob.return_();
                    });

            // Override each interceptable method + generate $$super$ bridge + $$ti$ glue. The $$ti$
            // glue erases to (Object,Object[])Object, so two overloaded methods would collide on the
            // bare $$ti$<name>; give each overload a unique name (the $$super$<name> bridges keep
            // distinct descriptors and need no suffix).
            var methods = shape.methods();
            var tiNames = targetInvokerNames(methods);
            for (int i = 0; i < methods.size(); i++) {
                MethodShape method = methods.get(i);
                generateSuperBridge(clb, beanCD, method);
                generateInterceptedMethod(clb, subclassCD, beanCD, method, tiNames.get(i));
                generateTargetInvokerGlue(clb, subclassCD, method, tiNames.get(i));
            }
        });
    }

    /**
     * Unique {@code $$ti$} glue method name per method. Non-overloaded names stay {@code $$ti$<name>}
     * (byte-for-byte stable); overloaded names get a {@code $<occurrence>} suffix so the erased
     * {@code (Object,Object[])Object} glues do not collide.
     */
    private static java.util.List<String> targetInvokerNames(java.util.List<MethodShape> methods) {
        var total = new java.util.HashMap<String, Integer>();
        for (MethodShape m : methods) total.merge(m.name(), 1, Integer::sum);
        var seen = new java.util.HashMap<String, Integer>();
        var names = new java.util.ArrayList<String>(methods.size());
        for (MethodShape m : methods) {
            int occ = seen.merge(m.name(), 1, Integer::sum) - 1;
            String base = "$$ti$" + m.name();
            names.add(total.get(m.name()) > 1 ? base + "$" + occ : base);
        }
        return names;
    }

    /**
     * Generate {@code $$super$methodName} — calls {@code super.methodName()} directly,
     * used by the interceptor chain to invoke the original method without infinite recursion.
     */
    private static void generateSuperBridge(java.lang.classfile.ClassBuilder clb,
            ClassDesc beanCD, MethodShape method) {
        ClassDesc returnCD = method.returnType().classDesc();
        var paramCDs = method.params().stream()
                .map(TypeRef::classDesc)
                .toArray(ClassDesc[]::new);
        var methodType = MethodTypeDesc.of(returnCD, paramCDs);

        clb.withMethodBody(
                "$$super$" + method.name(),
                methodType,
                ClassFile.ACC_PUBLIC,
                cob -> {
                    cob.aload(0);
                    int s = 1;
                    for (TypeRef p : method.params()) {
                        s = loadParam(cob, p.classDesc(), s);
                    }
                    cob.invokespecial(beanCD, method.name(), methodType);
                    emitReturn(cob, returnCD);
                });
    }

    private static void generateInterceptedMethod(
            java.lang.classfile.ClassBuilder clb,
            ClassDesc subclassCD, ClassDesc beanCD, MethodShape method, String tiName) {

        ClassDesc returnCD = method.returnType().classDesc();
        var paramCDs = method.params().stream()
                .map(TypeRef::classDesc)
                .toArray(ClassDesc[]::new);
        var methodType = MethodTypeDesc.of(returnCD, paramCDs);

        clb.withMethodBody(
                method.name(),
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
                    for (TypeRef p : method.params()) {
                        s = loadParam(cob, p.classDesc(), s);
                    }
                    cob.invokespecial(beanCD, method.name(), methodType);
                    emitReturn(cob, returnCD);

                    // Intercepted path
                    cob.labelBinding(intercepted);

                    // Get $$super$ Method for target invocation (avoids infinite recursion)
                    // this.getClass().getDeclaredMethod("$$super$name", paramTypes...)
                    cob.aload(0);
                    cob.invokevirtual(CD_Object, "getClass", MethodTypeDesc.of(CD_Class));
                    cob.ldc("$$super$" + method.name());
                    cob.loadConstant(paramCDs.length);
                    cob.anewarray(CD_Class);
                    for (int i = 0; i < paramCDs.length; i++) {
                        cob.dup();
                        cob.loadConstant(i);
                        TypeRef p = method.params().get(i);
                        if (p.isPrimitive()) {
                            cob.getstatic(p.wrapperClassDesc(), "TYPE", CD_Class);
                        } else {
                            cob.ldc(paramCDs[i]);
                        }
                        cob.aastore();
                    }
                    cob.invokevirtual(CD_Class, "getDeclaredMethod",
                            MethodTypeDesc.of(CD_Method, CD_String, CD_Class.arrayType()));
                    // Compute the local-slot offset just past the parameter list. Each long/double
                    // parameter occupies TWO slots (JVMS §2.6.1), so a naive parameterCount + 1
                    // count under-allocates and lands the synthetic Method/args/chain locals on
                    // top of the long's high half — surfacing as a VerifyError "Bad local
                    // variable type" the first time the method body loads its long/double param.
                    int mSlot = 1; // skip 'this' at slot 0
                    for (TypeRef p : method.params()) {
                        mSlot += p.isCategory2() ? 2 : 1;
                    }
                    cob.astore(mSlot);

                    // Build Object[] args
                    cob.loadConstant(paramCDs.length);
                    cob.anewarray(CD_Object);
                    int slot = 1;
                    for (int i = 0; i < paramCDs.length; i++) {
                        cob.dup();
                        cob.loadConstant(i);
                        slot = boxAndLoad(cob, method.params().get(i), slot);
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
                    // targetInvoker: an in-module lambda (no captures) calling $$ti$<name> →
                    // $$super$<name> → super.<name>(…). So the chain end invokes the original method
                    // with no reflection (the $$super$ Method above is still used only for binding
                    // resolution / ctx.getMethod()). LambdaMetafactory spins the lambda in the bean's
                    // own module — AOT-friendly, and needs no opens.
                    cob.invokedynamic(DynamicCallSiteDesc.of(
                            BSM_LAMBDA_METAFACTORY,
                            "invoke",
                            MethodTypeDesc.of(CD_TargetInvoker),
                            MTD_TARGET_INVOKER,
                            MethodHandleDesc.ofMethod(
                                    DirectMethodHandleDesc.Kind.STATIC,
                                    subclassCD, tiName, MTD_TARGET_INVOKER),
                            MTD_TARGET_INVOKER));
                    cob.invokespecial(CD_VaubanInvocationContext, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void,
                                    CD_Object, CD_Method, CD_Constructor, CD_Object.arrayType(), CD_List,
                                    CD_TargetInvoker));

                    // ctx.proceed()
                    cob.invokevirtual(CD_VaubanInvocationContext, "proceed",
                            MethodTypeDesc.of(CD_Object));

                    // Cache any interceptor instances created during AroundConstruct
                    cob.aload(0);
                    cob.getfield(subclassCD, FIELD_MANAGER, CD_InterceptorManager);
                    cob.invokestatic(CD_InterceptorManager, "$$getAroundConstructContext",
                            MethodTypeDesc.of(CD_CreationalContext));
                    cob.invokevirtual(CD_InterceptorManager, "shareInstances",
                            MethodTypeDesc.of(ConstantDescs.CD_void, CD_CreationalContext));

                    // Return
                    TypeRef ret = method.returnType();
                    if (ret.isVoid()) {
                        cob.pop();
                        cob.return_();
                    } else if (ret.isPrimitive()) {
                        unboxReturn(cob, ret);
                    } else {
                        cob.checkcast(returnCD);
                        cob.areturn();
                    }
                });
    }

    /**
     * Generate the {@code private static Object $$ti$<name>(Object target, Object[] params)} glue
     * that the {@link DynamicCallSiteDesc} above lifts into a {@code TargetInvoker}. It casts the
     * target to the subclass, unboxes each argument from {@code params}, calls the public
     * {@code $$super$<name>} bridge, and boxes the result (or returns {@code null} for {@code void}).
     */
    private static void generateTargetInvokerGlue(ClassBuilder clb, ClassDesc subclassCD,
            MethodShape method, String tiName) {
        ClassDesc returnCD = method.returnType().classDesc();
        var paramCDs = method.params().stream()
                .map(TypeRef::classDesc)
                .toArray(ClassDesc[]::new);
        var superType = MethodTypeDesc.of(returnCD, paramCDs);

        clb.withMethodBody(
                tiName,
                MTD_TARGET_INVOKER,
                ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                cob -> {
                    cob.aload(0);              // target
                    cob.checkcast(subclassCD); // -> <bean>$$Intercepted
                    var params = method.params();
                    for (int i = 0; i < params.size(); i++) {
                        cob.aload(1);          // Object[] params
                        cob.loadConstant(i);
                        cob.aaload();
                        castOrUnboxParam(cob, params.get(i));
                    }
                    cob.invokevirtual(subclassCD, "$$super$" + method.name(), superType);
                    boxResultAndReturn(cob, method.returnType());
                });
    }

    /** Stack: an {@code Object} (a {@code params[i]} element) → the typed/unboxed argument. */
    private static void castOrUnboxParam(CodeBuilder cob, TypeRef p) {
        if (!p.isPrimitive()) {
            if (!p.classDesc().equals(CD_Object)) {
                cob.checkcast(p.classDesc());
            }
            return;
        }
        ClassDesc wrapperCD = p.wrapperClassDesc();
        ClassDesc primCD = p.classDesc();
        cob.checkcast(wrapperCD);
        switch (p.primitiveKind()) {
            case BOOLEAN -> cob.invokevirtual(wrapperCD, "booleanValue", MethodTypeDesc.of(primCD));
            case BYTE -> cob.invokevirtual(wrapperCD, "byteValue", MethodTypeDesc.of(primCD));
            case CHAR -> cob.invokevirtual(wrapperCD, "charValue", MethodTypeDesc.of(primCD));
            case SHORT -> cob.invokevirtual(wrapperCD, "shortValue", MethodTypeDesc.of(primCD));
            case INT -> cob.invokevirtual(wrapperCD, "intValue", MethodTypeDesc.of(primCD));
            case LONG -> cob.invokevirtual(wrapperCD, "longValue", MethodTypeDesc.of(primCD));
            case FLOAT -> cob.invokevirtual(wrapperCD, "floatValue", MethodTypeDesc.of(primCD));
            case DOUBLE -> cob.invokevirtual(wrapperCD, "doubleValue", MethodTypeDesc.of(primCD));
            default -> { /* not reached */ }
        }
    }

    /** Stack: the {@code $$super$} return value → boxed {@code Object} ({@code null} for void), then areturn. */
    private static void boxResultAndReturn(CodeBuilder cob, TypeRef ret) {
        if (ret.isVoid()) {
            cob.aconst_null();
        } else if (ret.isPrimitive()) {
            ClassDesc wrapperCD = ret.wrapperClassDesc();
            cob.invokestatic(wrapperCD, METHOD_VALUEOF,
                    MethodTypeDesc.of(wrapperCD, ret.classDesc()));
        }
        cob.areturn();
    }

    private static int boxAndLoad(CodeBuilder cob, TypeRef type, int slot) {
        if (!type.isPrimitive()) {
            cob.aload(slot);
            return slot + (type.isCategory2() ? 2 : 1);
        }
        ClassDesc wrapperCD = type.wrapperClassDesc();
        ClassDesc primCD = type.classDesc();
        return switch (type.primitiveKind()) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT -> {
                cob.iload(slot);
                cob.invokestatic(wrapperCD, METHOD_VALUEOF, MethodTypeDesc.of(wrapperCD, primCD));
                yield slot + 1;
            }
            case LONG -> {
                cob.lload(slot);
                cob.invokestatic(wrapperCD, METHOD_VALUEOF, MethodTypeDesc.of(wrapperCD, primCD));
                yield slot + 2;
            }
            case FLOAT -> {
                cob.fload(slot);
                cob.invokestatic(wrapperCD, METHOD_VALUEOF, MethodTypeDesc.of(wrapperCD, primCD));
                yield slot + 1;
            }
            case DOUBLE -> {
                cob.dload(slot);
                cob.invokestatic(wrapperCD, METHOD_VALUEOF, MethodTypeDesc.of(wrapperCD, primCD));
                yield slot + 2;
            }
            default -> { cob.aload(slot); yield slot + 1; }
        };
    }

    private static void unboxReturn(CodeBuilder cob, TypeRef type) {
        ClassDesc wrapperCD = type.wrapperClassDesc();
        cob.checkcast(wrapperCD);
        switch (type.primitiveKind()) {
            case BOOLEAN -> { cob.invokevirtual(wrapperCD, "booleanValue", MethodTypeDesc.of(ConstantDescs.CD_boolean)); cob.ireturn(); }
            case BYTE -> { cob.invokevirtual(wrapperCD, "byteValue", MethodTypeDesc.of(ConstantDescs.CD_byte)); cob.ireturn(); }
            case CHAR -> { cob.invokevirtual(wrapperCD, "charValue", MethodTypeDesc.of(ConstantDescs.CD_char)); cob.ireturn(); }
            case SHORT -> { cob.invokevirtual(wrapperCD, "shortValue", MethodTypeDesc.of(ConstantDescs.CD_short)); cob.ireturn(); }
            case INT -> { cob.invokevirtual(wrapperCD, "intValue", MethodTypeDesc.of(ConstantDescs.CD_int)); cob.ireturn(); }
            case LONG -> { cob.invokevirtual(wrapperCD, "longValue", MethodTypeDesc.of(ConstantDescs.CD_long)); cob.lreturn(); }
            case FLOAT -> { cob.invokevirtual(wrapperCD, "floatValue", MethodTypeDesc.of(ConstantDescs.CD_float)); cob.freturn(); }
            case DOUBLE -> { cob.invokevirtual(wrapperCD, "doubleValue", MethodTypeDesc.of(ConstantDescs.CD_double)); cob.dreturn(); }
            default -> cob.areturn();
        }
    }

    /**
     * Load a parameter from a local slot given its {@link ClassDesc}; returns next slot.
     * Mirrors the original descriptor-string-based dispatch in {@code InterceptorSubclassGenerator}.
     */
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

    /**
     * Load a constructor parameter, accounting for the fact that slot 0 is {@code this}
     * — so we start the caller at slot 0 and add 1 for {@code this} internally.
     */
    private static int loadParamBySlot(CodeBuilder cob, TypeRef p, int paramIndex) {
        // cob.parameterSlot(i) gives 0-based param slot (no this)
        // We use parameterSlot to stay consistent with the original constructor gen
        cob.loadLocal(java.lang.classfile.TypeKind.from(p.classDesc()), cob.parameterSlot(paramIndex));
        return paramIndex + 1;
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

    /** Binary-name → ClassDesc (handles no-package, and the last-dot split for packages). */
    static ClassDesc classDescOf(String binaryName) {
        int lastDot = binaryName.lastIndexOf('.');
        if (lastDot >= 0) {
            return ClassDesc.of(binaryName.substring(0, lastDot), binaryName.substring(lastDot + 1));
        }
        return ClassDesc.of(binaryName);
    }
}
