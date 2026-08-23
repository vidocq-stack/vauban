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
package io.vidocq.vauban.weaver;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;

/**
 * Class-File transformations behind phase 2 of Vidocq/vauban#24: make a compiled
 * normal-scoped bean proxyable <strong>without touching its source</strong>.
 *
 * <p>The JVM forces the client proxy's {@code <init>} to chain to a constructor of the bean
 * (its direct superclass), so the side-effect-free entry point must exist in the bean class
 * itself — and annotation processing cannot add it (JSR 269 only creates new files). The
 * compiled classes are therefore rewritten after javac: by the auto-started javac plugin
 * (Maven/Gradle/CLI builds), by the {@code vauban-maven-plugin} at {@code process-classes}
 * (ecosystem jars), or at class load time by {@link WeavingAgent} when the build did not
 * weave (IDE builds):
 *
 * <ul>
 *   <li>{@link #addMarkerConstructor(byte[], SuperChain)} weaves a synthetic
 *       {@code protected <init>(ProxyLink)} with an empty body into the bean;</li>
 *   <li>{@link #retargetProxyConstructor(byte[])} rewrites the {@code <init>} of an
 *       already-generated {@code <Bean>_ClientProxy} (APT-emitted before the marker existed)
 *       to chain to that marker instead of a business constructor.</li>
 * </ul>
 *
 * <p>At the bytecode level the woven constructor legitimately leaves blank {@code final}
 * fields unassigned (definite assignment is a javac rule, not a verifier one), so no
 * per-field default assignments are needed. Beans that declare a manual {@code (ProxyLink)}
 * constructor (phase 1) are left untouched.
 *
 * <p>This module is dependency-free on purpose (it doubles as a self-contained agent jar),
 * so the marker type is referenced by name only — kept in sync with
 * {@code io.vidocq.vauban.api.ProxyLink.CLASS_NAME}.
 */
public final class ProxyLinkWeaver {

    /** Binary name of the marker type — mirror of {@code io.vidocq.vauban.api.ProxyLink.CLASS_NAME}. */
    public static final String PROXY_LINK_CLASS = "io.vidocq.vauban.api.ProxyLink";

    private static final ClassDesc CD_PROXY_LINK = ClassDesc.of(PROXY_LINK_CLASS);
    private static final MethodTypeDesc MTD_MARKER =
            MethodTypeDesc.of(ConstantDescs.CD_void, CD_PROXY_LINK);
    private static final MethodTypeDesc MTD_VOID = MethodTypeDesc.of(ConstantDescs.CD_void);

    /** How the woven marker chains to the bean's superclass. */
    public enum SuperChain {
        /** {@code super()} — the superclass is {@code Object} or offers a no-arg constructor. */
        NO_ARG,
        /** {@code super((ProxyLink) null)} — the superclass carries a marker itself. */
        MARKER
    }

    private ProxyLinkWeaver() {}

    /** {@code true} when the class already declares a {@code (ProxyLink)} constructor. */
    public static boolean hasMarkerConstructor(byte[] classBytes) {
        for (var method : ClassFile.of().parse(classBytes).methods()) {
            if (isMarkerCtor(method)) return true;
        }
        return false;
    }

    /** {@code true} when the class declares a non-private no-arg constructor. */
    public static boolean hasNonPrivateNoArgConstructor(byte[] classBytes) {
        for (var method : ClassFile.of().parse(classBytes).methods()) {
            if (method.methodName().equalsString(ConstantDescs.INIT_NAME)
                    && method.methodTypeSymbol().parameterCount() == 0
                    && !method.flags().has(AccessFlag.PRIVATE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Weaves the synthetic {@code protected <init>(ProxyLink)} entry constructor into
     * {@code beanClass}, chaining to the superclass per {@code chain}.
     *
     * @return the patched bytecode, or {@code null} when the class already declares the
     *         marker (manual phase-1 constructor, or a previous weaving run)
     */
    public static byte[] addMarkerConstructor(byte[] beanClass, SuperChain chain) {
        var cf = ClassFile.of();
        var model = cf.parse(beanClass);
        for (var method : model.methods()) {
            if (isMarkerCtor(method)) return null;
        }
        var superDesc = model.superclass().orElseThrow().asSymbol();
        return cf.transformClass(model, ClassTransform.ACCEPT_ALL.andThen(
                ClassTransform.endHandler(clb ->
                        clb.withMethodBody(ConstantDescs.INIT_NAME, MTD_MARKER,
                                ClassFile.ACC_PROTECTED | ClassFile.ACC_SYNTHETIC, cob -> {
                                    cob.aload(0);
                                    if (chain == SuperChain.MARKER) cob.aconst_null();
                                    cob.invokespecial(superDesc, ConstantDescs.INIT_NAME,
                                            chain == SuperChain.MARKER ? MTD_MARKER : MTD_VOID);
                                    cob.return_();
                                }))));
    }

    /**
     * Rewrites the no-arg {@code <init>} of a generated client proxy to
     * {@code super((ProxyLink) null)}. Idempotent: retargeting a proxy that already chains
     * to the marker produces the same body. The bean class must carry the marker
     * constructor (woven or manual) before the retargeted proxy is loaded.
     */
    public static byte[] retargetProxyConstructor(byte[] proxyClass) {
        var cf = ClassFile.of();
        var model = cf.parse(proxyClass);
        var superDesc = model.superclass().orElseThrow().asSymbol();
        return cf.transformClass(model, (clb, ce) -> {
            if (ce instanceof MethodModel mm && mm.methodName().equalsString("<init>")) {
                clb.withMethodBody("<init>", mm.methodTypeSymbol(), mm.flags().flagsMask(), cob -> {
                    cob.aload(0);
                    cob.aconst_null();
                    cob.invokespecial(superDesc, ConstantDescs.INIT_NAME, MTD_MARKER);
                    cob.return_();
                });
            } else {
                clb.with(ce);
            }
        });
    }

    private static boolean isMarkerCtor(MethodModel method) {
        return method.methodName().equalsString("<init>")
                && method.methodTypeSymbol().parameterCount() == 1
                && method.methodTypeSymbol().parameterType(0).equals(CD_PROXY_LINK);
    }
}
