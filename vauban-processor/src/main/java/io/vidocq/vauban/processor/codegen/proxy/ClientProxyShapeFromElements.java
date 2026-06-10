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
 *   <li>{@code needsMethodHandle} is always {@code false}: declared methods share the
 *       proxy's package, so plain forwarding always compiles.</li>
 * </ul>
 */
public final class ClientProxyShapeFromElements {

    private ClientProxyShapeFromElements() {}

    /** Build the neutral shape for {@code bean}. */
    public static ClientProxyShape from(TypeElement bean, Elements elements, Types types) {
        String beanBinaryName = elements.getBinaryName(bean).toString();

        var methods = new ArrayList<ProxyMethodShape>();
        for (ExecutableElement m : ElementFilter.methodsIn(bean.getEnclosedElements())) {
            if (!shouldProxy(m)) continue;
            methods.add(new ProxyMethodShape(
                    m.getSimpleName().toString(),
                    typeRef(m.getReturnType(), elements, types),
                    typeRefs(m.getParameters(), elements, types),
                    thrownTypeRefs(m.getThrownTypes(), elements, types),
                    false));
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
     * Parameter types of the simplest non-private declared constructor — empty for a no-arg
     * (or absent) constructor, meaning a plain {@code super()} call.
     */
    private static List<TypeRef> superCtorParams(TypeElement bean, Elements elements, Types types) {
        ExecutableElement simplest = null;
        for (ExecutableElement c : ElementFilter.constructorsIn(bean.getEnclosedElements())) {
            if (c.getModifiers().contains(Modifier.PRIVATE)) continue;
            if (simplest == null || c.getParameters().size() < simplest.getParameters().size()) {
                simplest = c;
            }
        }
        if (simplest == null) return List.of();
        return typeRefs(simplest.getParameters(), elements, types);
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
}
