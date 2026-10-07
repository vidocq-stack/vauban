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
import java.util.function.Consumer;

/**
 * Builds a {@link ClientProxyShape} from a {@link TypeElement} — the APT
 * ({@code javax.lang.model}) front-end of the client-proxy generation, feeding the source
 * renderer ({@link ClientProxySourceRenderer}).
 *
 * <p>Front-end specifics, mirrored from the historical source renderer:
 * <ul>
 *   <li><em>declared</em>, non-static, non-private, non-final, non-abstract instance
 *       methods, the inherited ones each front-end allows, and the interface default methods the
 *       bean inherits; erased signatures — for {@link #from}, rendered as source, the signature as a
 *       member of the bean, its type variables bound, which may differ from the bytecode
 *       descriptor (BUG-20261004-06);</li>
 *   <li>the super constructor is the simplest non-private declared one — its parameter
 *       types become {@link ClientProxyShape#superCtorParams()} (default-value call);</li>
 *   <li>thrown types are kept (erased) so the rendered overrides preserve the bean
 *       method's checked-exception contract;</li>
 *   <li>{@link #from}: {@code needsMethodHandle} is always {@code false}: a forwarded method is
 *       either declared by the bean itself (same package as the proxy) or public, so plain
 *       forwarding always compiles — the bytecode path's MethodHandle fallback has no source
 *       equivalent;</li>
 *   <li>{@link #fromColocated}: the shape of a proxy placed in the bean's own package, emitted as
 *       bytecode — inherited non-public members are forwarded too, through a MethodHandle when
 *       they are protected members of a superclass in another package.</li>
 * </ul>
 */
public final class ClientProxyShapeFromElements {

    private ClientProxyShapeFromElements() {}

    /** Build the neutral shape for {@code bean}. */
    public static ClientProxyShape from(TypeElement bean, Elements elements, Types types) {
        return from(bean, elements.getPackageOf(bean), elements, types);
    }

    /** Build the neutral shape for {@code bean}, the proxy being rendered in {@code proxyPackage}. */
    public static ClientProxyShape from(TypeElement bean, javax.lang.model.element.PackageElement proxyPackage,
            Elements elements, Types types) {
        return from(bean, proxyPackage, elements, types, omitted -> {});
    }

    /**
     * The same, {@code omitted} receiving one message per inherited default method the proxy does
     * not forward because Java source in {@code proxyPackage} cannot write its override, or no
     * interface carrying a shadowed one can be named there: for the processor to report, since a
     * call of such a method on the proxy does not reach the contextual instance.
     */
    public static ClientProxyShape from(TypeElement bean, javax.lang.model.element.PackageElement proxyPackage,
            Elements elements, Types types, Consumer<String> omitted) {
        return from(bean, proxyPackage, elements, types, omitted, Subject.BEAN, true);
    }

    /**
     * The methods a proxy of {@code bean} forwards whose override Java source in {@code proxyPackage}
     * cannot declare, as {@code name(member parameter types)}: their signature as a member of
     * {@code bean} names a type that package cannot name — a package-private type of another
     * package, a private nested type of its own (BUG-20261004-09). The class methods {@link #from}
     * forwards, and the non-shadowed defaults, which {@link #from} would leave out (n3c, n11a).
     * Empty when the proxy can be rendered as source; otherwise the processor emits {@link
     * #fromErased} as bytecode, which carries those types in method descriptors only and forwards
     * those defaults as the bytecode generators do. A shadowed default is not counted: it is
     * forwarded through an interface the source must name, and keeps the rules of
     * BUG-20261004-08.
     */
    public static List<String> unnameableForwards(TypeElement bean,
            javax.lang.model.element.PackageElement proxyPackage, Elements elements, Types types) {
        var unnameable = new ArrayList<String>();
        var seen = new java.util.HashSet<String>();
        boolean declaring = true;
        for (TypeElement c = bean; c != null && !isObject(c); c = superclassOf(c), declaring = false) {
            for (ExecutableElement m : ElementFilter.methodsIn(c.getEnclosedElements())) {
                if (!seen.add(memberKey(bean, m, types))) continue;
                if (!shouldProxy(m)) continue;
                if (!declaring && !m.getModifiers().contains(Modifier.PUBLIC)) continue;
                if (!InterceptedShapeFromElements.memberSignatureNameableFrom(bean, m, proxyPackage, elements, types,
                        false)) {
                    unnameable.add(InterceptedShapeFromElements.memberSignature(bean, m, types));
                }
            }
        }
        // The defaults addDefaultMethods would forward through the bean class: not shadowed, and no
        // class method with the same member signature.
        for (ExecutableElement m : ElementFilter.methodsIn(elements.getAllMembers(bean))) {
            if (!m.getModifiers().contains(Modifier.DEFAULT) || !shouldProxy(m)) continue;
            if (InterceptedShapeFromElements.isShadowedDefault(bean, m, types)) continue;
            if (!seen.add(memberKey(bean, m, types))) continue;
            if (!InterceptedShapeFromElements.memberSignatureNameableFrom(bean, m, proxyPackage, elements, types,
                    false)) {
                unnameable.add(InterceptedShapeFromElements.memberSignature(bean, m, types));
            }
        }
        return unnameable;
    }

    /**
     * The shape of {@link #from} with erased declarations — the descriptors a bytecode override must
     * have — for a proxy the processor emits as bytecode because {@link #unnameableForwards} is not
     * empty. It forwards what the source shape forwards, and also the inherited defaults whose member
     * signature only the source cannot write.
     */
    public static ClientProxyShape fromErased(TypeElement bean, javax.lang.model.element.PackageElement proxyPackage,
            Elements elements, Types types, Consumer<String> omitted) {
        return from(bean, proxyPackage, elements, types, omitted, Subject.BEAN, false);
    }

    /** {@link #fromErased} for the proxy of a normal-scoped producer, emitted in the producer's package. */
    public static ClientProxyShape fromProducedErased(TypeElement producedType,
            javax.lang.model.element.PackageElement producerPackage, Elements elements, Types types,
            Consumer<String> omitted) {
        return from(producedType, producerPackage, elements, types, omitted, Subject.PRODUCED_TYPE, false);
    }

    /**
     * The same for the proxy a normal-scoped producer gets for {@code producedType}, rendered in the
     * producer's package (#42): the reports name the type as the produced type and the proxy as the
     * producer's.
     */
    public static ClientProxyShape fromProduced(TypeElement producedType,
            javax.lang.model.element.PackageElement producerPackage, Elements elements, Types types,
            Consumer<String> omitted) {
        return from(producedType, producerPackage, elements, types, omitted, Subject.PRODUCED_TYPE, true);
    }

    /** Whose proxy a shape is built for, as the processor's reports name the proxy and the type. */
    private enum Subject {
        BEAN("the client proxy", "the bean"),
        PRODUCED_TYPE("the producer's client proxy", "the produced type");

        private final String proxy;
        private final String type;

        Subject(String proxy, String type) {
            this.proxy = proxy;
            this.type = type;
        }
    }

    private static ClientProxyShape from(TypeElement bean, javax.lang.model.element.PackageElement proxyPackage,
            Elements elements, Types types, Consumer<String> omitted, Subject subject, boolean asMember) {
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
        //
        // Each override is declared with the signature the bean sees (its type variables bound),
        // the only one Java source can override (BUG-20261004-06); javac adds the bridge. The walk
        // is keyed on that signature too: echo(String) overriding Base<String>.echo(T) is one
        // member, and the most derived declaration decides, a non-proxyable one included.
        var seen = new java.util.HashSet<String>();
        boolean declaring = true;
        for (TypeElement c = bean; c != null && !isObject(c); c = superclassOf(c), declaring = false) {
            for (ExecutableElement m : ElementFilter.methodsIn(c.getEnclosedElements())) {
                if (!seen.add(memberKey(bean, m, types))) continue;
                if (!shouldProxy(m)) continue;
                if (!declaring && !m.getModifiers().contains(Modifier.PUBLIC)) continue;
                methods.add(methodShape(bean, m, asMember, false, elements, types));
            }
        }
        addDefaultMethods(bean, asMember, proxyPackage, elements, types, seen, methods, omitted, subject);

        return new ClientProxyShape(beanBinaryName, superCtorParams(bean, elements, types), methods);
    }

    /**
     * The interface default methods {@code bean} inherits — those no class of the bean overrides,
     * which {@link Elements#getAllMembers} already leaves out — forwarded like any public method.
     * Left out, a call on the proxy runs the default body on the proxy itself, bypassing the
     * contextual instance and its interceptors (BUG-20261004-02). As in
     * {@code RuntimeClientProxyGenerator.shapeOf}, and {@link InterfaceProxySourceRenderer} for an
     * interface-typed proxy.
     */
    private static void addDefaultMethods(TypeElement bean, boolean asMember,
            javax.lang.model.element.PackageElement proxyPackage, Elements elements,
            Types types, java.util.Set<String> seen, List<ProxyMethodShape> methods, Consumer<String> omitted,
            Subject subject) {
        // What the proxy already declares: whatever path finds a method, it is declared once.
        var declared = new java.util.HashSet<String>();
        for (var m : methods) declared.add(declaredSignature(m));
        for (ExecutableElement m : ElementFilter.methodsIn(elements.getAllMembers(bean))) {
            if (!m.getModifiers().contains(Modifier.DEFAULT) || !shouldProxy(m)) continue;
            if (InterceptedShapeFromElements.isShadowedDefault(bean, m, types)) {
                // A declaration the bean does not inherit (a private superclass method) holds the
                // key, and a call typed by the bean class would resolve to it and be refused: forward
                // through an interface instead (BUG-20261004-08), or not at all when none qualifies.
                // The proxy must name the interface where it is rendered: the bean's package, or a
                // producer's (#42) — where a package-private one of the bean's package is out of reach.
                var owner = InterceptedShapeFromElements.accessibleDefaultOwner(bean, m, proxyPackage, elements, types);
                if (owner == null) {
                    omitted.accept(notForwarded(bean, m, subject, "no interface carrying it can be named from package "
                            + proxyPackage.getQualifiedName(), SHADOWED_CALL));
                    continue;
                }
                // Rendered as source, the override declares the member signature: when that names
                // a type the proxy's package cannot (a package-private type argument of another
                // package), the method is not forwarded either, rather than breaking the build.
                if (asMember && !InterceptedShapeFromElements.memberSignatureNameableFrom(
                        bean, m, proxyPackage, elements, types)) {
                    omitted.accept(notForwarded(bean, m, subject,
                            unnameableMember(bean, m, proxyPackage, types, subject), SHADOWED_CALL));
                    continue;
                }
                var shape = methodShape(bean, m, asMember, false, elements, types);
                if (!declared.add(declaredSignature(shape))) continue;
                methods.add(new ProxyMethodShape(shape.name(), shape.returnType(), shape.params(),
                        shape.thrownTypes(), false,
                        TypeRef.ofReference(elements.getBinaryName(owner).toString(), 0,
                                owner.getQualifiedName().toString())));
                continue;
            }
            // A class method that is the same member (PlainBase.tag(String) implementing
            // Tagged<String>.tag(T)) was seen first and is the one the bean runs.
            if (!seen.add(memberKey(bean, m, types))) continue;
            // Rendered as source, the override declares the member signature, which may name a
            // type the proxy's package cannot (label(Hidden) from Labeled<Hidden> bound in another
            // package): not forwarded then — what a proxy did before defaults were forwarded at
            // all (BUG-20261004-02) — rather than breaking the build.
            if (asMember && !InterceptedShapeFromElements.memberSignatureNameableFrom(
                    bean, m, proxyPackage, elements, types)) {
                omitted.accept(notForwarded(bean, m, subject,
                        unnameableMember(bean, m, proxyPackage, types, subject), UNSHADOWED_CALL));
                continue;
            }
            var shape = methodShape(bean, m, asMember, false, elements, types); // interface methods are public
            if (declared.add(declaredSignature(shape))) methods.add(shape);
        }
    }

    /** The name and parameter types the proxy declares a method with. */
    private static String declaredSignature(ProxyMethodShape m) {
        return m.name() + m.params();
    }

    /**
     * What a call of an omitted default does on the proxy when nothing shadows it: the proxy, a
     * subclass, inherits it and runs it on itself.
     */
    private static final String UNSHADOWED_CALL =
            "a call on the proxy runs the default body on the proxy instance, not on the contextual instance.";

    /**
     * What a call of an omitted shadowed default does on the proxy: the JVM resolves it through the
     * shadowing declaration first, and HotSpot 25 does not select the default on a class that
     * inherits its interface only through a superclass (BUG-20261004-08).
     */
    private static final String SHADOWED_CALL = "a call on the proxy does not reach the contextual instance and"
            + " may fail, as it does on any subclass under such a shadow (AbstractMethodError on HotSpot 25,"
            + " BUG-20261004-08).";

    /** The processor's report of a default method the proxy does not forward, why, and what a call does. */
    private static String notForwarded(TypeElement type, ExecutableElement m, Subject subject, String why,
            String consequence) {
        return type.getQualifiedName() + ": " + subject.proxy + " does not forward the inherited default method "
                + ((TypeElement) m.getEnclosingElement()).getQualifiedName() + "." + m + " — " + why
                + "; " + consequence;
    }

    /** Why the member signature of {@code m} in {@code bean} cannot be written in {@code pkg}. */
    private static String unnameableMember(TypeElement bean, ExecutableElement m,
            javax.lang.model.element.PackageElement pkg, Types types, Subject subject) {
        var member = InterceptedShapeFromElements.memberType(bean, m, types);
        var sb = new StringBuilder("its signature as a member of ").append(subject.type).append(", ")
                .append(m.getSimpleName()).append('(');
        for (int i = 0; i < member.getParameterTypes().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(types.erasure(member.getParameterTypes().get(i)));
        }
        return sb.append("), names a type package ").append(pkg.getQualifiedName()).append(" cannot").toString();
    }

    /**
     * The member signature of {@code m} in {@code bean}, which every walk is keyed on: two
     * declarations with the same key are one method of the bean, forwarded once.
     */
    private static String memberKey(TypeElement bean, ExecutableElement m, Types types) {
        return InterceptedShapeFromElements.memberSignatureKey(bean, m, types);
    }

    /**
     * {@code m} forwarded by the proxy of {@code bean}. {@code asMember}: with the signature
     * {@code bean} sees, its type variables bound — what a source override must declare; else the
     * erased declaration — the descriptor a bytecode override must have.
     */
    private static ProxyMethodShape methodShape(TypeElement bean, ExecutableElement m, boolean asMember,
            boolean needsMethodHandle, Elements elements, Types types) {
        if (asMember) {
            var member = InterceptedShapeFromElements.memberType(bean, m, types);
            return new ProxyMethodShape(
                    m.getSimpleName().toString(),
                    typeRef(member.getReturnType(), elements, types),
                    mirrorRefs(member.getParameterTypes(), elements, types),
                    mirrorRefs(member.getThrownTypes(), elements, types),
                    needsMethodHandle);
        }
        return new ProxyMethodShape(
                m.getSimpleName().toString(),
                typeRef(m.getReturnType(), elements, types),
                typeRefs(m.getParameters(), elements, types),
                mirrorRefs(m.getThrownTypes(), elements, types),
                needsMethodHandle);
    }

    /**
     * The shape of a proxy <em>placed</em> in {@code bean}'s own package by the Vauban class loader
     * (issue #42, Stage 4). Placement is what makes the non-public surface reachable, so this shape
     * mirrors {@code RuntimeClientProxyGenerator.shapeOf} — the proxy it replaces — rather than
     * {@link #from}:
     * <ul>
     *   <li>declared members, as in {@link #from};</li>
     *   <li>inherited public members, and inherited protected or package-private members of a
     *       superclass in the <em>same</em> package: plain forwarding, the proxy shares that
     *       runtime package;</li>
     *   <li>inherited protected members of a superclass in <em>another</em> package: forwarded
     *       through a MethodHandle ({@code needsMethodHandle}), since {@code delegate.m()} on an
     *       instance other than {@code this} is forbidden there (JLS 6.6.2);</li>
     *   <li>inherited package-private members of a superclass in another package are left out: no
     *       class outside that runtime package can override them, so no proxy can intercept them
     *       wherever it lives, and the MethodHandle lookup would be refused.</li>
     * </ul>
     * The most-derived declaration of a signature decides, as in the bytecode generator.
     */
    public static ClientProxyShape fromColocated(TypeElement bean, Elements elements, Types types) {
        String beanBinaryName = elements.getBinaryName(bean).toString();
        var beanPackage = elements.getPackageOf(bean).getQualifiedName().toString();
        var methods = new ArrayList<ProxyMethodShape>();
        var seen = new java.util.HashSet<String>();
        boolean declaring = true;
        for (TypeElement c = bean; c != null && !isObject(c); c = superclassOf(c), declaring = false) {
            boolean samePackage = elements.getPackageOf(c).getQualifiedName().contentEquals(beanPackage);
            for (ExecutableElement m : ElementFilter.methodsIn(c.getEnclosedElements())) {
                if (!seen.add(memberKey(bean, m, types))) continue;
                if (!shouldProxy(m)) continue;
                var mods = m.getModifiers();
                boolean isPublic = mods.contains(Modifier.PUBLIC);
                if (!declaring && !isPublic && !mods.contains(Modifier.PROTECTED) && !samePackage) {
                    continue; // package-private in another runtime package: not overridable from here
                }
                methods.add(methodShape(bean, m, false, !isPublic && !samePackage, elements, types));
            }
        }
        addDefaultMethods(bean, false, elements.getPackageOf(bean), elements, types, seen, methods, omitted -> {},
                Subject.BEAN);
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

    private static List<TypeRef> mirrorRefs(List<? extends TypeMirror> mirrors,
            Elements elements, Types types) {
        var refs = new ArrayList<TypeRef>(mirrors.size());
        for (TypeMirror t : mirrors) refs.add(typeRef(t, elements, types));
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
}
