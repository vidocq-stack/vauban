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
import io.vidocq.vauban.core.proxy.ClientProxyShape;
import io.vidocq.vauban.core.proxy.ClientProxyShape.ProxyMethodShape;
import io.vidocq.vauban.processor.codegen.interceptor.InterceptedShapeFromElements;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link ClientProxyShape} from a {@link TypeElement} — the APT
 * ({@code javax.lang.model}) front-end of the client-proxy generation, feeding the source
 * renderer ({@link ClientProxySourceRenderer}).
 *
 * <p>Front-end specifics, mirrored from the historical source renderer:
 * <ul>
 *   <li><em>declared</em>, non-static, non-private, non-final, non-abstract instance
 *       methods only (erased signatures);</li>
 *   <li>the super constructor is the simplest non-private declared one — its parameter
 *       types become {@link ClientProxyShape#superCtorParams()} (default-value call);</li>
 *   <li>thrown types are kept (erased) so the rendered overrides preserve the bean
 *       method's checked-exception contract;</li>
 *   <li>{@code needsMethodHandle} is always {@code false}: a forwarded method is either
 *       declared by the bean itself (same package as the proxy) or public, so plain forwarding
 *       always compiles — the bytecode path's MethodHandle fallback has no source equivalent.</li>
 * </ul>
 */
public final class ClientProxyShapeFromElements {

    private ClientProxyShapeFromElements() {}

    /** Build the neutral shape for {@code bean}. */
    public static ClientProxyShape from(TypeElement bean, Elements elements, Types types) {
        String beanBinaryName = elements.getBinaryName(bean).toString();

        var methods = new ArrayList<ProxyMethodShape>();
        // Walk the hierarchy, exactly like RuntimeClientProxyGenerator.shapeOf: a method inherited
        // from a superclass is not declared here, and the proxy does not override it either — so
        // leaving it out means invoking it on the proxy runs the superclass body against the
        // proxy's own default-initialised state instead of the contextual instance.
        //
        // Inherited members are only forwarded when they are public: forwarding compiles to
        // `delegate.m()` on an instance other than `this`, which JLS 6.6.2 forbids outside the
        // declaring package for protected members, and which package-private members do not allow
        // either. ProducerProxyEligibility rejects a produced type in exactly those cases, so an
        // eligible cross-package proxy never loses a method here.
        var seen = new java.util.HashSet<String>();
        boolean declaring = true;
        for (TypeElement c = bean; c != null && !isObject(c); c = superclassOf(c), declaring = false) {
            for (ExecutableElement m : ElementFilter.methodsIn(c.getEnclosedElements())) {
                if (!shouldProxy(m)) continue;
                if (!declaring && !m.getModifiers().contains(Modifier.PUBLIC)) continue;
                if (!seen.add(signatureKey(m, types))) continue;
                methods.add(new ProxyMethodShape(
                        m.getSimpleName().toString(),
                        typeRef(m.getReturnType(), elements, types),
                        typeRefs(m.getParameters(), elements, types),
                        thrownTypeRefs(m.getThrownTypes(), elements, types),
                        false));
            }
        }

        return new ClientProxyShape(beanBinaryName, superCtorParams(bean, elements, types), methods);
    }

    /** Mirrors the bytecode generators: skip static/private/final/abstract/{@code $$}. */
    private static boolean shouldProxy(ExecutableElement m) {
        var mods = m.getModifiers();
        if (mods.contains(Modifier.STATIC)) return false;
        if (mods.contains(Modifier.PRIVATE)) return false;
        if (mods.contains(Modifier.FINAL)) return false;
        if (mods.contains(Modifier.ABSTRACT)) return false;
        return !m.getSimpleName().toString().startsWith("$$");
    }

    /**
     * Parameter types of the constructor the proxy chains to: the opt-in {@code (ProxyLink)}
     * entry constructor when the bean declares one (side-effect-free by contract —
     * Vidocq/vauban#24), else the simplest non-private declared constructor — empty for a
     * no-arg (or absent) constructor, meaning a plain {@code super()} call.
     */
    private static List<TypeRef> superCtorParams(TypeElement bean, Elements elements, Types types) {
        ExecutableElement simplest = null;
        for (ExecutableElement c : ElementFilter.constructorsIn(bean.getEnclosedElements())) {
            if (c.getModifiers().contains(Modifier.PRIVATE)) continue;
            if (isProxyLinkConstructor(c)) {
                simplest = c;
                break;
            }
            if (simplest == null || c.getParameters().size() < simplest.getParameters().size()) {
                simplest = c;
            }
        }
        if (simplest == null) return List.of();
        return typeRefs(simplest.getParameters(), elements, types);
    }

    /** True when {@code c} is the opt-in {@code (ProxyLink)} client-proxy entry constructor. */
    public static boolean isProxyLinkConstructor(ExecutableElement c) {
        return c.getParameters().size() == 1
                && c.getParameters().getFirst().asType() instanceof javax.lang.model.type.DeclaredType dt
                && dt.asElement() instanceof TypeElement te
                && te.getQualifiedName().contentEquals(ClientProxyShape.PROXY_LINK_CLASS);
    }

    private static List<TypeRef> typeRefs(List<? extends VariableElement> params,
            Elements elements, Types types) {
        var refs = new ArrayList<TypeRef>(params.size());
        for (VariableElement p : params) refs.add(typeRef(p.asType(), elements, types));
        return refs;
    }

    private static List<TypeRef> thrownTypeRefs(List<? extends TypeMirror> thrown,
            Elements elements, Types types) {
        var refs = new ArrayList<TypeRef>(thrown.size());
        for (TypeMirror t : thrown) refs.add(typeRef(t, elements, types));
        return refs;
    }

    private static TypeRef typeRef(TypeMirror tm, Elements elements, Types types) {
        return InterceptedShapeFromElements.typeRefOf(tm, elements, types, 0);
    }

    /** {@code true} when {@code t} is {@code java.lang.Object} — the walk stops there. */
    private static boolean isObject(TypeElement t) {
        return t.getQualifiedName().contentEquals("java.lang.Object");
    }

    /** The superclass element, or {@code null} at the top of the hierarchy. */
    private static TypeElement superclassOf(TypeElement t) {
        return t.getSuperclass() instanceof javax.lang.model.type.DeclaredType dt
                && dt.asElement() instanceof TypeElement se ? se : null;
    }

    /** Erased name-and-parameters key: spots an override so a superclass copy is not emitted twice. */
    private static String signatureKey(ExecutableElement m, Types types) {
        var sb = new StringBuilder(m.getSimpleName().toString()).append('(');
        for (var p : m.getParameters()) {
            sb.append(types.erasure(p.asType()).toString()).append(',');
        }
        return sb.append(')').toString();
    }
}
