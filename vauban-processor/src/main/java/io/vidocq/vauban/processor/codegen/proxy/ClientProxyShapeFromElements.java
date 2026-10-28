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
import java.util.stream.Stream;

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
        for (ExecutableElement m : forwardedClassMethods(bean, types, seen)) {
            if (!InterceptedShapeFromElements.memberSignatureNameableFrom(bean, m, proxyPackage, elements, types,
                    false)) {
                unnameable.add(InterceptedShapeFromElements.memberSignature(bean, m, types));
            }
        }
        // The defaults addDefaultMethods would forward through the bean class: not shadowed, and no
        // class method with the same member signature.
        for (ExecutableElement m : ElementFilter.methodsIn(elements.getAllMembers(bean))) {
            if (isProxiedDefault(m)
                    && !InterceptedShapeFromElements.isShadowedDefault(bean, m, types)
                    && seen.add(memberKey(bean, m, types))
                    && !InterceptedShapeFromElements.memberSignatureNameableFrom(bean, m, proxyPackage, elements,
                            types, false)) {
                unnameable.add(InterceptedShapeFromElements.memberSignature(bean, m, types));
            }
        }
        return unnameable;
    }

    /**
     * The class methods a source proxy of {@code bean} forwards: the proxyable members of the bean
     * and its superclasses (below {@code java.lang.Object}), inherited ones only when public, the
     * most derived declaration of a member signature deciding. {@code seen} receives every member
     * signature met, forwarded or not.
     */
    private static List<ExecutableElement> forwardedClassMethods(TypeElement bean, Types types,
            java.util.Set<String> seen) {
        var forwarded = new ArrayList<ExecutableElement>();
        boolean declaring = true;
        for (TypeElement c : classesBelowObject(bean)) {
            for (ExecutableElement m : ElementFilter.methodsIn(c.getEnclosedElements())) {
                if (seen.add(memberKey(bean, m, types)) && shouldProxy(m)
                        && (declaring || m.getModifiers().contains(Modifier.PUBLIC))) {
                    forwarded.add(m);
                }
            }
            declaring = false;
        }
        return forwarded;
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

    /** What a shape is built from: the type proxied, where the proxy is rendered, and how. */
    private record ShapeRequest(TypeElement bean, javax.lang.model.element.PackageElement proxyPackage,
            Elements elements, Types types, Consumer<String> omitted, Subject subject, boolean asMember) {}

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
        for (ExecutableElement m : forwardedClassMethods(bean, types, seen)) {
            methods.add(methodShape(bean, m, asMember, false, elements, types));
        }
        addDefaultMethods(new ShapeRequest(bean, proxyPackage, elements, types, omitted, subject, asMember),
                seen, methods);

        return new ClientProxyShape(beanBinaryName, superCtorParams(bean, elements, types), methods,
                superCtorThrown(bean, elements, types));
    }

    /**
     * The interface default methods {@code bean} inherits — those no class of the bean overrides,
     * which {@link Elements#getAllMembers} already leaves out — forwarded like any public method.
     * Left out, a call on the proxy runs the default body on the proxy itself, bypassing the
     * contextual instance and its interceptors (BUG-20261004-02). As in
     * {@code RuntimeClientProxyGenerator.shapeOf}, and {@link InterfaceProxySourceRenderer} for an
     * interface-typed proxy.
     */
    private static void addDefaultMethods(ShapeRequest request, java.util.Set<String> seen,
            List<ProxyMethodShape> methods) {
        // What the proxy already declares: whatever path finds a method, it is declared once.
        var declared = new java.util.HashSet<String>();
        for (var m : methods) declared.add(declaredSignature(m));
        for (ExecutableElement m : ElementFilter.methodsIn(request.elements().getAllMembers(request.bean()))) {
            if (isProxiedDefault(m)) {
                ProxyMethodShape shape = InterceptedShapeFromElements.isShadowedDefault(request.bean(), m,
                        request.types())
                        ? shadowedDefaultShape(request, m)
                        : unshadowedDefaultShape(request, m, seen);
                if (shape != null && declared.add(declaredSignature(shape))) {
                    methods.add(shape);
                }
            }
        }
    }

    /** A {@code default} method the proxy may forward, by the same rules as a class method. */
    private static boolean isProxiedDefault(ExecutableElement m) {
        return m.getModifiers().contains(Modifier.DEFAULT) && shouldProxy(m);
    }

    /**
     * The forward of a shadowed default {@code m}, or {@code null} (reported to the request's
     * {@code omitted}) when the proxy cannot forward it.
     */
    private static ProxyMethodShape shadowedDefaultShape(ShapeRequest request, ExecutableElement m) {
        var bean = request.bean();
        var proxyPackage = request.proxyPackage();
        // A declaration the bean does not inherit (a private superclass method) holds the
        // key, and a call typed by the bean class would resolve to it and be refused: forward
        // through an interface instead (BUG-20261004-08), or not at all when none qualifies.
        // The proxy must name the interface where it is rendered: the bean's package, or a
        // producer's (#42) — where a package-private one of the bean's package is out of reach.
        var owner = InterceptedShapeFromElements.accessibleDefaultOwner(bean, m, proxyPackage, request.elements(),
                request.types());
        if (owner == null) {
            request.omitted().accept(notForwarded(bean, m, request.subject(),
                    "no interface carrying it can be named from package " + proxyPackage.getQualifiedName(),
                    SHADOWED_CALL));
            return null;
        }
        // Rendered as source, the override declares the member signature: when that names
        // a type the proxy's package cannot (a package-private type argument of another
        // package), the method is not forwarded either, rather than breaking the build.
        if (!memberSignatureWritable(request, m)) {
            request.omitted().accept(notForwarded(bean, m, request.subject(),
                    unnameableMember(bean, m, proxyPackage, request.types(), request.subject()), SHADOWED_CALL));
            return null;
        }
        var shape = methodShape(bean, m, request.asMember(), false, request.elements(), request.types());
        return new ProxyMethodShape(shape.name(), shape.returnType(), shape.params(),
                shape.thrownTypes(), false,
                TypeRef.ofReference(request.elements().getBinaryName(owner).toString(), 0,
                        owner.getQualifiedName().toString()));
    }

    /**
     * The forward of a default {@code m} nothing shadows, or {@code null} when a class method is the
     * same member or when the proxy cannot forward it (then reported to the request's {@code omitted}).
     */
    private static ProxyMethodShape unshadowedDefaultShape(ShapeRequest request, ExecutableElement m,
            java.util.Set<String> seen) {
        var bean = request.bean();
        // A class method that is the same member (PlainBase.tag(String) implementing
        // Tagged<String>.tag(T)) was seen first and is the one the bean runs.
        if (!seen.add(memberKey(bean, m, request.types()))) {
            return null;
        }
        // Rendered as source, the override declares the member signature, which may name a
        // type the proxy's package cannot (label(Hidden) from Labeled<Hidden> bound in another
        // package): not forwarded then — what a proxy did before defaults were forwarded at
        // all (BUG-20261004-02) — rather than breaking the build.
        if (!memberSignatureWritable(request, m)) {
            request.omitted().accept(notForwarded(bean, m, request.subject(),
                    unnameableMember(bean, m, request.proxyPackage(), request.types(), request.subject()),
                    UNSHADOWED_CALL));
            return null;
        }
        // Interface methods are public: plain forwarding.
        return methodShape(bean, m, request.asMember(), false, request.elements(), request.types());
    }

    /** {@code true} unless the proxy is rendered as source and cannot write the member signature of {@code m}. */
    private static boolean memberSignatureWritable(ShapeRequest request, ExecutableElement m) {
        return !request.asMember() || InterceptedShapeFromElements.memberSignatureNameableFrom(
                request.bean(), m, request.proxyPackage(), request.elements(), request.types());
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
        for (TypeElement c : classesBelowObject(bean)) {
            boolean samePackage = elements.getPackageOf(c).getQualifiedName().contentEquals(beanPackage);
            for (ExecutableElement m : ElementFilter.methodsIn(c.getEnclosedElements())) {
                if (seen.add(memberKey(bean, m, types)) && shouldProxy(m)
                        && colocatedForwardable(m, declaring, samePackage)) {
                    boolean isPublic = m.getModifiers().contains(Modifier.PUBLIC);
                    methods.add(methodShape(bean, m, false, !isPublic && !samePackage, elements, types));
                }
            }
            declaring = false;
        }
        addDefaultMethods(new ShapeRequest(bean, elements.getPackageOf(bean), elements, types, omitted -> {},
                Subject.BEAN, false), seen, methods);
        return new ClientProxyShape(beanBinaryName, superCtorParams(bean, elements, types), methods,
                superCtorThrown(bean, elements, types));
    }

    /**
     * Whether a placed proxy forwards {@code m}: every member except an inherited package-private one
     * of another runtime package, which is not overridable from the bean's package.
     */
    private static boolean colocatedForwardable(ExecutableElement m, boolean declaring, boolean samePackage) {
        var mods = m.getModifiers();
        return declaring || samePackage || mods.contains(Modifier.PUBLIC) || mods.contains(Modifier.PROTECTED);
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
        ExecutableElement chosen = superConstructor(bean);
        if (chosen == null) return List.of();
        return typeRefs(chosen.getParameters(), elements, types);
    }

    /** The exceptions the constructor of {@link #superCtorParams} declares: the proxy constructor declares them too. */
    private static List<TypeRef> superCtorThrown(TypeElement bean, Elements elements, Types types) {
        ExecutableElement chosen = superConstructor(bean);
        if (chosen == null) return List.of();
        return mirrorRefs(chosen.getThrownTypes(), elements, types);
    }

    /** The {@code (ProxyLink)} constructor if non-private, else the non-private one with the fewest parameters. */
    private static ExecutableElement superConstructor(TypeElement bean) {
        ExecutableElement simplest = null;
        for (ExecutableElement c : ElementFilter.constructorsIn(bean.getEnclosedElements())) {
            boolean accessible = !c.getModifiers().contains(Modifier.PRIVATE);
            if (accessible && isProxyLinkConstructor(c)) {
                return c;
            }
            if (accessible && (simplest == null || c.getParameters().size() < simplest.getParameters().size())) {
                simplest = c;
            }
        }
        return simplest;
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

    /** {@code type} and its superclasses, most derived first, up to but excluding {@code java.lang.Object}. */
    private static List<TypeElement> classesBelowObject(TypeElement type) {
        return Stream.iterate(type, c -> c != null && !isObject(c), ClientProxyShapeFromElements::superclassOf)
                .toList();
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
