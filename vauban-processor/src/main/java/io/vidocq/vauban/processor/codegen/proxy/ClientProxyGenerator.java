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

import io.vidocq.vauban.core.interceptor.TypeRef;
import io.vidocq.vauban.core.proxy.ClientProxyEmitter;
import io.vidocq.vauban.core.proxy.ClientProxyShape;
import io.vidocq.vauban.core.proxy.ClientProxyShape.ProxyMethodShape;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.processor.codegen.GeneratedClass;

import java.util.ArrayList;
import java.util.List;

/**
 * Compile-time ({@code ClassInfo}-driven) front-end of the client-proxy generation: builds
 * the neutral {@link ClientProxyShape} from the bytecode index and delegates the emission
 * to the shared {@link ClientProxyEmitter} — the same emitter the runtime
 * {@code RuntimeClientProxyGenerator} uses from {@code Class<?>}.
 *
 * <p>Front-end specifics (captured in the shape DATA, not in a duplicated emitter):
 * <ul>
 *   <li>only methods <em>declared</em> by the bean class are overridden
 *       ({@link ClassInfo#methods()} exposes no inherited members — no hierarchy walk);</li>
 *   <li>the proxy constructor is a plain {@code super()} ({@code superCtorParams} empty);
 *       beans without a no-arg constructor fall back to the runtime generator;</li>
 *   <li>the {@code MethodHandle} dispatch decision (JVMS §4.10.1.9) is kept aligned with
 *       the runtime front-end but is essentially dormant: declared methods always share the
 *       proxy's package, so it only matters once hierarchy traversal is introduced.</li>
 * </ul>
 */
public final class ClientProxyGenerator {

    private ClientProxyGenerator() {}

    /**
     * Generate a client proxy class for the given bean.
     *
     * @param beanClass the indexed class information of the bean
     * @return a {@link GeneratedClass} containing the proxy class name and bytecode
     */
    public static GeneratedClass generate(ClassInfo beanClass) {
        ClientProxyShape shape = shapeOf(beanClass);
        return new GeneratedClass(shape.proxyClassName(), ClientProxyEmitter.emit(shape));
    }

    private static ClientProxyShape shapeOf(ClassInfo beanClass) {
        String beanClassName = beanClass.name().value();
        String proxyPackage = packageOf(beanClassName);

        var methods = new ArrayList<ProxyMethodShape>();
        for (var method : beanClass.methods()) {
            if (!shouldProxy(method)) continue;
            methods.add(new ProxyMethodShape(
                    method.name(),
                    TypeRef.fromTypeInfo(method.returnType()),
                    paramTypeRefs(method),
                    List.of(),
                    needsMethodHandleDispatch(method, beanClassName, proxyPackage)));
        }

        // Chain to the opt-in (ProxyLink) entry constructor when the bean declares one
        // (side-effect-free by contract — Vidocq/vauban#24); else plain super().
        var superCtorParams = beanClass.methods().stream()
                .filter(m -> !m.isPrivate() && ClientProxyShape.isProxyLinkConstructor(m))
                .findFirst()
                .map(m -> List.of(TypeRef.fromTypeInfo(m.parameters().getFirst().type())))
                .orElse(List.of());
        return new ClientProxyShape(beanClassName, superCtorParams, methods);
    }

    private static List<TypeRef> paramTypeRefs(MethodInfo method) {
        var refs = new ArrayList<TypeRef>(method.parameters().size());
        for (var p : method.parameters()) refs.add(TypeRef.fromTypeInfo(p.type()));
        return refs;
    }

    private static boolean shouldProxy(MethodInfo method) {
        if (method.isConstructor() || method.isStaticInitializer()) return false;
        if (method.isStatic()) return false;
        if (method.isPrivate()) return false;
        if ((method.accessFlags() & 0x0010) != 0) return false; // final
        return !method.isSynthetic();
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
}
