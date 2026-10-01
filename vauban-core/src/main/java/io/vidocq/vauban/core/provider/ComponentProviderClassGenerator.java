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
package io.vidocq.vauban.core.provider;

import io.vidocq.vauban.indexer.codegen.Component;
import io.vidocq.vauban.indexer.codegen.FieldInject;
import io.vidocq.vauban.indexer.codegen.MethodInvoke;

import java.lang.classfile.Annotation;
import io.vidocq.vauban.core.codegen.GeneratedClassFile;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Label;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
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
 * {@code create(String)} / {@code create(String, Object[])} are chains of {@code className.equals}
 * tests returning a fresh instance ({@code new X()} or {@code new X((Dep) args[0], …)}), mirroring
 * the source switch. Unlike the source generator, the bytecode references each bean by its
 * <em>binary</em> name, so it can also instantiate a Filer-generated sibling such as
 * {@code <bean>$$Intercepted} — which a generated source could not (javac cannot resolve a
 * round-generated class symbol). Only public, top-level beans whose constructor parameters are all
 * nameable reference types are listed; anything else is left to the reflective fallback.
 *
 * <p>The descriptor records ({@link FieldInject}, {@link MethodInvoke}) are defined in
 * {@code io.vidocq.vauban.indexer.codegen} and shared with the APT source generator
 * ({@code ComponentProviderGenerator}) via the common {@code vauban-indexer} dependency.
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
    private static final MethodTypeDesc MTD_create2 =
            MethodTypeDesc.of(CD_Object, CD_String, CD_Object.arrayType());
    private static final MethodTypeDesc MTD_injectField =
            MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_Object, CD_String, CD_String, CD_Object);
    private static final ClassDesc CD_Supplier = ClassDesc.of("java.util.function.Supplier");
    private static final MethodTypeDesc MTD_createClientProxy =
            MethodTypeDesc.of(CD_Object, CD_String, CD_Supplier);
    private static final MethodTypeDesc MTD_setDelegate =
            MethodTypeDesc.of(ConstantDescs.CD_void, CD_Supplier);
    private static final ClassDesc CD_Coverage = ClassDesc.of("io.vidocq.vauban.api.GeneratedCoverage");
    private static final ClassDesc CD_Generator = ClassDesc.of("io.vidocq.vauban.api.GeneratedCoverage$Generator");
    private static final MethodTypeDesc MTD_coverage = MethodTypeDesc.of(CD_Coverage);
    private static final MethodTypeDesc MTD_coverageOf = MethodTypeDesc.of(CD_Coverage, CD_Generator,
            CD_String.arrayType(), CD_String.arrayType(), CD_String.arrayType(), CD_String.arrayType());

    /**
     * The most keys one {@code coverage()} method holds: each costs 8 bytes of bytecode ({@code dup}, index,
     * {@code ldc_w}, {@code aastore}), and a method body stops at 64 KiB. A provider with more emits no
     * {@code coverage()} — the diagnostics report it unknown — and keeps its dispatch: a diagnostic never costs the
     * container its generated code.
     */
    public static final int MAX_COVERAGE_KEYS = 7000;

    private ComponentProviderClassGenerator() {}

    /**
     * A build-time client proxy for a normal-scoped producer of a fully-public external class
     * (issue #42), whose container lookup {@code key} ({@code <producedType>_ClientProxy}) differs
     * from {@code proxyFqn}, the proxy class actually instantiated (in the producer's package).
     * Bytecode parity with {@code ComponentProviderGenerator.ProducerProxy} (APT source path).
     */
    public record ProducerProxy(String key, String proxyFqn) {}

    /** A generated class: its fully-qualified name and its bytecode. */
    public record Generated(String className, byte[] bytecode) {}

    /**
     * @param providerClassName fully-qualified name of the provider to generate (in a package of
     *                          the current module, e.g. {@code app._VaubanComponents})
     * @param components        public beans to instantiate in-module: no-arg ({@code new X()}) and
     *                          injected-constructor ({@code new X((Dep) args[0], …)})
     */
    public static Generated generate(String providerClassName, List<Component> components) {
        return generate(providerClassName, components, List.of(), List.of());
    }

    /**
     * @param providerClassName fully-qualified name of the provider to generate
     * @param components        public beans to instantiate in-module (no-arg and injected-ctor)
     * @param fieldInjects      field injection descriptors for in-package, non-private fields
     */
    public static Generated generate(String providerClassName, List<Component> components,
            List<FieldInject> fieldInjects) {
        return generate(providerClassName, components, fieldInjects, List.of());
    }

    /**
     * @param providerClassName fully-qualified name of the provider to generate
     * @param components        public beans to instantiate in-module (no-arg and injected-ctor)
     * @param fieldInjects      field injection descriptors for in-package, non-private fields
     * @param methodInvokes     method invocation descriptors for in-package methods
     */
    public static Generated generate(String providerClassName, List<Component> components,
            List<FieldInject> fieldInjects, List<MethodInvoke> methodInvokes) {
        return generate(providerClassName, components, fieldInjects, methodInvokes, List.of());
    }

    /**
     * @param providerClassName fully-qualified name of the provider to generate
     * @param components        public beans to instantiate in-module (no-arg and injected-ctor)
     * @param fieldInjects      field injection descriptors for in-package, non-private fields
     * @param methodInvokes     method invocation descriptors for in-package methods
     * @param clientProxyFqns   fully-qualified {@code <Bean>_ClientProxy} names (normal-scoped beans
     *                          of this package) the provider instantiates in-module — {@code new
     *                          <Bean>_ClientProxy()} + {@code $$setDelegate(delegate)} — so the
     *                          container creates the proxy without reflection and without exporting
     *                          the package (parity with the APT source generator)
     */
    public static Generated generate(String providerClassName, List<Component> components,
            List<FieldInject> fieldInjects, List<MethodInvoke> methodInvokes,
            List<String> clientProxyFqns) {
        return generate(providerClassName, components, fieldInjects, methodInvokes,
                clientProxyFqns, List.of());
    }

    /**
     * Full overload also emitting {@link ProducerProxy} cases (issue #42, Stage 1.6): build-time
     * proxies for normal-scoped producers of fully-public external classes, keyed by the produced
     * type but instantiated from the producer's own package.
     */
    public static Generated generate(String providerClassName, List<Component> components,
            List<FieldInject> fieldInjects, List<MethodInvoke> methodInvokes,
            List<String> clientProxyFqns, List<ProducerProxy> producerProxies) {
        return generate(providerClassName, components, fieldInjects, methodInvokes, clientProxyFqns, producerProxies,
                MAX_COVERAGE_KEYS);
    }

    /** The full overload, with the most keys {@code coverage()} may declare before it is left out. */
    static Generated generate(String providerClassName, List<Component> components,
            List<FieldInject> fieldInjects, List<MethodInvoke> methodInvokes,
            List<String> clientProxyFqns, List<ProducerProxy> producerProxies, int maxCoverageKeys) {
        var instantiatedKeys = components.stream().map(Component::fqn).toList();
        var fieldKeys = fieldInjects.stream().map(fi -> fi.declaringClassFqn() + "#" + fi.fieldName()).toList();
        var methodKeys = methodInvokes.stream().map(mi -> mi.declaringClassFqn() + "#" + mi.methodId()).toList();
        var proxyKeys = new java.util.ArrayList<>(clientProxyFqns);
        producerProxies.forEach(pp -> proxyKeys.add(pp.key()));
        boolean declaresCoverage = instantiatedKeys.size() + fieldKeys.size() + methodKeys.size() + proxyKeys.size()
                <= maxCoverageKeys;
        var providerCD = ClassDesc.of(providerClassName);
        var noArg = components.stream().filter(Component::noArg).toList();
        var withArgs = components.stream().filter(c -> !c.noArg()).toList();
        byte[] bytecode = GeneratedClassFile.build(providerCD, clb -> {
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
                for (var c : noArg) {
                    var beanCD = ClassDesc.of(c.fqn());
                    var next = cob.newLabel();
                    cob.aload(1);
                    cob.ldc(c.fqn());
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

            // Emit create(String, Object[]) only when there are injected-constructor beans, so the
            // SPI default (which delegates to create(String)) stays in force otherwise.
            //
            // public Object create(String className, Object[] args) {
            //     if (args == null || args.length == 0) return create(className);
            //     if (className.equals("a.Svc")) return new a.Svc((Dep) args[0], …);
            //     ...
            //     return null;
            // }
            // slots: 0=this, 1=className, 2=args
            if (!withArgs.isEmpty()) {
                clb.withMethodBody("create", MTD_create2, ClassFile.ACC_PUBLIC, cob -> {
                    var delegate = cob.newLabel();
                    // if (args == null || args.length == 0) return create(className);
                    cob.aload(2);
                    cob.ifnull(delegate);
                    cob.aload(2);
                    cob.arraylength();
                    cob.ifeq(delegate);
                    for (var c : withArgs) {
                        var beanCD = ClassDesc.of(c.fqn());
                        var next = cob.newLabel();
                        cob.aload(1);
                        cob.ldc(c.fqn());
                        cob.invokevirtual(CD_String, "equals", MTD_String_equals);
                        cob.ifeq(next);
                        cob.new_(beanCD);
                        cob.dup();
                        var params = c.ctorParamTypes();
                        var paramDescs = new ClassDesc[params.size()];
                        for (int i = 0; i < params.size(); i++) {
                            cob.aload(2);
                            loadIntConstant(cob, i);
                            cob.aaload();
                            var paramCD = resolveFieldTypeDesc(params.get(i));
                            paramDescs[i] = paramCD;
                            if (!paramCD.equals(CD_Object)) {
                                cob.checkcast(paramCD);
                            }
                        }
                        cob.invokespecial(beanCD, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, paramDescs));
                        cob.areturn();
                        cob.labelBinding(next);
                    }
                    cob.aconst_null();
                    cob.areturn();
                    // delegate: return create(className);
                    cob.labelBinding(delegate);
                    cob.aload(0);
                    cob.aload(1);
                    cob.invokevirtual(providerCD, "create", MTD_create);
                    cob.areturn();
                });
            }

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
                    var fieldTypeCD = resolveFieldTypeDesc(fi.fieldTypeErasure());
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

            // public Object createClientProxy(String proxyClassName, Supplier delegate) {
            //     if (proxyClassName.equals("a.B_ClientProxy")) {
            //         var p = new a.B_ClientProxy(); p.$$setDelegate(delegate); return p;
            //     }
            //     ...
            //     return null;
            // }
            // The bytecode references the (bytecode) <Bean>_ClientProxy by binary name — no javac
            // wall here, so the plugin path needs no source proxy (unlike the APT). slots: 0=this,
            // 1=proxyClassName, 2=delegate.
            if (!clientProxyFqns.isEmpty() || !producerProxies.isEmpty()) {
                clb.withMethodBody("createClientProxy", MTD_createClientProxy, ClassFile.ACC_PUBLIC, cob -> {
                    for (var proxyFqn : clientProxyFqns) {
                        var proxyCD = ClassDesc.of(proxyFqn);
                        var next = cob.newLabel();
                        cob.aload(1);
                        cob.ldc(proxyFqn);
                        cob.invokevirtual(CD_String, "equals", MTD_String_equals);
                        cob.ifeq(next);
                        cob.new_(proxyCD);
                        cob.dup();
                        cob.invokespecial(proxyCD, ConstantDescs.INIT_NAME, MTD_void); // new proxy()
                        cob.dup();
                        cob.aload(2);                                                  // delegate
                        cob.invokevirtual(proxyCD, "$$setDelegate", MTD_setDelegate);
                        cob.areturn();
                        cob.labelBinding(next);
                    }
                    // Producer proxies (issue #42): the equals-key is the produced type, the
                    // instantiated class is the proxy in the producer's package.
                    for (var pp : producerProxies) {
                        var proxyCD = ClassDesc.of(pp.proxyFqn());
                        var next = cob.newLabel();
                        cob.aload(1);
                        cob.ldc(pp.key());
                        cob.invokevirtual(CD_String, "equals", MTD_String_equals);
                        cob.ifeq(next);
                        cob.new_(proxyCD);
                        cob.dup();
                        cob.invokespecial(proxyCD, ConstantDescs.INIT_NAME, MTD_void);
                        cob.dup();
                        cob.aload(2);
                        cob.invokevirtual(proxyCD, "$$setDelegate", MTD_setDelegate);
                        cob.areturn();
                        cob.labelBinding(next);
                    }
                    cob.aconst_null();
                    cob.areturn();
                });
            }

            // public GeneratedCoverage coverage() {
            //     return GeneratedCoverage.of(Generator.CLASS_FILE, new String[] {…}, …);
            // }
            // From the same lists as the switches above, so the declaration cannot diverge from the dispatch.
            if (declaresCoverage) {
                clb.withMethodBody("coverage", MTD_coverage, ClassFile.ACC_PUBLIC, cob -> {
                    cob.getstatic(CD_Generator, "CLASS_FILE", CD_Generator);
                    pushStringArray(cob, instantiatedKeys);
                    pushStringArray(cob, fieldKeys);
                    pushStringArray(cob, methodKeys);
                    pushStringArray(cob, proxyKeys);
                    cob.invokestatic(CD_Coverage, "of", MTD_coverageOf);
                    cob.areturn();
                });
            }
        });
        return new Generated(providerClassName, bytecode);
    }

    /** Pushes {@code new String[] {values…}} on the stack. */
    private static void pushStringArray(java.lang.classfile.CodeBuilder cob, List<String> values) {
        loadIntConstant(cob, values.size());
        cob.anewarray(CD_String);
        for (int i = 0; i < values.size(); i++) {
            cob.dup();
            loadIntConstant(cob, i);
            cob.ldc(values.get(i));
            cob.aastore();
        }
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
     * Resolves a field type erasure (as stored in {@link FieldInject#fieldTypeErasure()}) to a
     * {@link ClassDesc}. Handles simple array types expressed as {@code "ComponentType[]"}.
     */
    private static ClassDesc resolveFieldTypeDesc(String fqn) {
        if (fqn.endsWith("[]")) {
            return ClassDesc.of(fqn.substring(0, fqn.length() - 2)).arrayType();
        }
        return ClassDesc.of(fqn);
    }
}
