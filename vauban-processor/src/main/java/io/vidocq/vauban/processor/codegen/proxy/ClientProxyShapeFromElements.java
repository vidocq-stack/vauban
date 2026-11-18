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

import io.vidocq.vauban.core.codegen.TypeView;
import io.vidocq.vauban.core.interceptor.TypeRef;
import io.vidocq.vauban.core.proxy.ClientProxyShape;
import io.vidocq.vauban.core.proxy.ClientProxyShape.ProxyMethodShape;
import io.vidocq.vauban.core.proxy.ForwardedMethods;
import io.vidocq.vauban.processor.codegen.ElementTypeView;
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
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Builds a {@link ClientProxyShape} from a {@link TypeElement} — the APT
 * ({@code javax.lang.model}) front-end of the client-proxy generation, feeding the source
 * renderer ({@link ClientProxySourceRenderer}).
 *
 * <p>Front-end specifics, mirrored from the historical source renderer:
 * <ul>
 *   <li><em>declared</em>, non-static, non-private, non-final instance methods, abstract ones
 *       included, the inherited ones each front-end allows, and the interface methods the bean
 *       inherits and no class of it implements; erased signatures — for {@link #from}, rendered as source, the signature as a
 *       member of the bean, its type variables bound, which may differ from the bytecode
 *       descriptor (BUG-20261004-06);</li>
 *   <li>the super constructor is the simplest non-private declared one — its parameter
 *       types become {@link ClientProxyShape#superCtorParams()} (default-value call);</li>
 *   <li>thrown types are kept (erased) so the rendered overrides preserve the bean
 *       method's checked-exception contract;</li>
 *   <li>the class methods forwarded are chosen by {@link ForwardedMethods}, the rule of every build path; a
 *       method forwarded through a {@code MethodHandle} has no source form, so the processor emits such a proxy
 *       as bytecode ({@link #methodHandleForwards});</li>
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
        for (var forward : forwardedClassMethods(bean, proxyPackage, elements, types, seen)) {
            var m = ElementTypeView.method(forward.method());
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
     * The class methods a proxy of {@code bean} rendered in {@code proxyPackage} forwards, by the rule of every build
     * path ({@link ForwardedMethods}). {@code seen} receives every member signature met, forwarded or not.
     */
    private static List<ForwardedMethods.Forward> forwardedClassMethods(TypeElement bean,
            javax.lang.model.element.PackageElement proxyPackage, Elements elements, Types types,
            java.util.Set<String> seen) {
        var selection = ForwardedMethods.select(ElementTypeView.of(bean, types),
                placement(bean, proxyPackage, elements));
        seen.addAll(selection.keys());
        return selection.forwards();
    }

    /**
     * The methods a proxy of {@code bean} rendered in {@code proxyPackage} forwards through a {@code MethodHandle},
     * as {@code name(member parameter types)}: protected methods of a superclass in another package, which Java
     * source cannot call on the delegate (JLS 6.6.2). Empty when the proxy can be rendered as source; otherwise the
     * processor emits {@link #fromErased} as bytecode.
     */
    public static List<String> methodHandleForwards(TypeElement bean,
            javax.lang.model.element.PackageElement proxyPackage, Elements elements, Types types) {
        return forwardedClassMethods(bean, proxyPackage, elements, types, new java.util.HashSet<>()).stream()
                .filter(ForwardedMethods.Forward::needsMethodHandle)
                .map(f -> InterceptedShapeFromElements.memberSignature(bean, ElementTypeView.method(f.method()), types))
                .toList();
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

        // The class methods of every build path (ForwardedMethods): a method inherited from a
        // superclass is not declared here, and the proxy does not override it either — so leaving it
        // out means invoking it on the proxy runs the superclass body against the proxy's own
        // default-initialised state instead of the contextual instance. Rendered in the bean's
        // package, the proxy forwards every member of the bean; in a producer's package, public
        // methods only, which ProducerProxyEligibility guarantees are all there is.
        //
        // Each override is declared with the signature the bean sees (its type variables bound),
        // the only one Java source can override (BUG-20261004-06); javac adds the bridge. The walk
        // is keyed on that signature too: echo(String) overriding Base<String>.echo(T) is one
        // member, and the most derived declaration decides, a non-proxyable one included.
        var request = new ShapeRequest(bean, proxyPackage, elements, types, omitted, subject, asMember);
        var methods = forwardedMethods(request);
        // Looked up once for the shape: what the proxy inherits decides both the report and its own writeReplace().
        var inherited = inheritedWriteReplace(bean, proxyPackage, elements, types);
        inherited.ifPresent(method -> reportUnforwardedWriteReplace(request, method));

        return new ClientProxyShape(beanBinaryName, superCtorParams(bean, elements, types), methods,
                superCtorThrown(bean, elements, types),
                inherited.filter(InheritedWriteReplace::isSerialHook).isPresent());
    }

    /**
     * The methods the proxy of the request forwards: the class methods, the interface methods no class method is, and,
     * in a bytecode proxy, the bridges a call typed by an interface goes through ({@link #addSuperCallingBridges}).
     */
    private static List<ProxyMethodShape> forwardedMethods(ShapeRequest request) {
        var methods = new ArrayList<ProxyMethodShape>();
        var seen = new java.util.HashSet<String>();
        var forwarded = new ArrayList<ExecutableElement>();
        for (var forward : forwardedClassMethods(request.bean(), request.proxyPackage(), request.elements(),
                request.types(), seen)) {
            forwarded.add(ElementTypeView.method(forward.method()));
            methods.add(methodShape(request.bean(), forwarded.getLast(), request.asMember(),
                    forward.needsMethodHandle(), request.elements(), request.types()));
        }
        addDefaultMethods(request, seen, methods);
        if (!request.asMember()) {
            addSuperCallingBridges(request, forwarded, methods);
        }
        return methods;
    }

    /**
     * Whether a proxy of {@code bean} rendered in {@code proxyPackage} inherits a no-arg {@code Object writeReplace()}:
     * from a class, by the rule of every build path ({@link ForwardedMethods#exposesWriteReplace}), or from an
     * interface, whose methods are public. The proxy's own {@code writeReplace()} then overrides it, so Java source
     * must declare it public, with no {@code throws} clause.
     */
    private static boolean exposesWriteReplace(TypeElement bean, javax.lang.model.element.PackageElement proxyPackage,
            Elements elements, Types types) {
        return inheritedWriteReplace(bean, proxyPackage, elements, types)
                .filter(InheritedWriteReplace::isSerialHook)
                .isPresent();
    }

    /**
     * The {@code writeReplace()} a proxy of {@code bean} rendered in {@code proxyPackage} inherits and next to which
     * Java source cannot declare the proxy's own {@code Object writeReplace()}, as
     * {@code static java.lang.Object writeReplace()}: a static one of a class, or one of a class or an interface
     * returning another type ({@link ForwardedMethods#inheritedWriteReplace}). Empty, or that one declaration: the
     * proxy is then emitted as bytecode, which has neither limit.
     */
    public static List<String> clashingWriteReplace(TypeElement bean,
            javax.lang.model.element.PackageElement proxyPackage, Elements elements, Types types) {
        return inheritedWriteReplace(bean, proxyPackage, elements, types)
                .filter(inherited -> !inherited.isSerialHook())
                .map(inherited -> List.of(inherited.declaration()))
                .orElse(List.of());
    }

    /**
     * A no-arg {@code writeReplace()} a proxy inherits.
     *
     * @param isStatic whether it is static: no proxy forwards it, and it needs none
     * @param returned its erased return type, as a canonical name
     */
    private record InheritedWriteReplace(boolean isStatic, String returned) {

        /** Whether serialization calls it: an instance method returning {@code Object}, which the proxy overrides. */
        boolean isSerialHook() {
            return !isStatic && ForwardedMethods.OBJECT.equals(returned);
        }

        /** As {@code static java.lang.Object writeReplace()}. */
        String declaration() {
            return (isStatic ? "static " : "") + returned + " " + ForwardedMethods.WRITE_REPLACE + "()";
        }
    }

    /**
     * The no-arg {@code writeReplace()} a proxy of {@code bean} rendered in {@code proxyPackage} inherits, if any:
     * from a class, by the rule of every build path, else from an interface, a default method no class above
     * overrides or an abstract one an abstract class leaves to its subclasses, public either way.
     */
    private static Optional<InheritedWriteReplace> inheritedWriteReplace(TypeElement bean,
            javax.lang.model.element.PackageElement proxyPackage, Elements elements, Types types) {
        var fromClass = ForwardedMethods.inheritedWriteReplace(ElementTypeView.of(bean, types),
                placement(bean, proxyPackage, elements));
        if (fromClass.isPresent()) {
            return Optional.of(new InheritedWriteReplace(fromClass.get().is(TypeView.Flag.STATIC),
                    fromClass.get().returnType()));
        }
        for (ExecutableElement m : ElementFilter.methodsIn(elements.getAllMembers(bean))) {
            if (m.getEnclosingElement().getKind().isInterface() && !m.getModifiers().contains(Modifier.STATIC)
                    && m.getSimpleName().contentEquals(ForwardedMethods.WRITE_REPLACE) && m.getParameters().isEmpty()) {
                return Optional.of(new InheritedWriteReplace(false, types.erasure(m.getReturnType()).toString()));
            }
        }
        return Optional.empty();
    }

    /**
     * Reports {@code inherited}, the {@code writeReplace()} the proxy of {@code request.bean()} inherits, when it is an
     * instance method returning another type than {@code Object}: a business method, which the proxy does not forward
     * ({@link ForwardedMethods#isSerialHook}), as its own {@code Object writeReplace()} takes the name.
     */
    private static void reportUnforwardedWriteReplace(ShapeRequest request, InheritedWriteReplace inherited) {
        if (!inherited.isStatic() && !inherited.isSerialHook()) {
            request.omitted().accept(request.bean().getQualifiedName() + ": " + request.subject().proxy
                    + " does not forward " + inherited.declaration() + " — its own java.lang.Object writeReplace()"
                    + " takes the name, so that a session holding it can move to another container (foy#114); "
                    + UNSHADOWED_CALL);
        }
    }

    /** Where a proxy rendered in {@code proxyPackage} lives, relative to {@code bean}. */
    private static ForwardedMethods.Placement placement(TypeElement bean,
            javax.lang.model.element.PackageElement proxyPackage, Elements elements) {
        return elements.getPackageOf(bean).equals(proxyPackage)
                ? ForwardedMethods.Placement.TYPE_PACKAGE
                : ForwardedMethods.Placement.OTHER_PACKAGE;
    }

    /**
     * The interface methods {@code bean} inherits — those no class of the bean overrides, which
     * {@link Elements#getAllMembers} already leaves out — forwarded like any public method. Left out,
     * a call on the proxy runs a default body on the proxy itself, bypassing the contextual instance
     * and its interceptors (BUG-20261004-02), and finds no body at all for an abstract one, which an
     * abstract proxied type leaves to its subclasses (BUG-20261114-02). As in
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

    /**
     * The interface methods a bytecode proxy forwards with their own erasure, next to the class method that implements
     * them: javac implements an interface method with a superclass method of another erasure,
     * {@code PlainTagBase.tag(String)} for {@code Tagged<String>.tag(T)}, through a bridge {@code tag(Object)} in the
     * class that brings them together, which calls the superclass method by {@code invokespecial}. An override of
     * {@code tag(String)} alone does not catch a call typed by the interface, which then ran on the proxy
     * (BUG-20261118-17). The run-time generator forwards the same bridges, which it sees. A source proxy needs none:
     * javac gives it the bridge.
     */
    private static void addSuperCallingBridges(ShapeRequest request, List<ExecutableElement> forwarded,
            List<ProxyMethodShape> methods) {
        var declared = new java.util.HashSet<String>();
        for (var m : methods) declared.add(descriptorKey(m));
        for (ExecutableElement m : ElementFilter.methodsIn(request.elements().getAllMembers(request.bean()))) {
            if (isProxiedDefault(m)) {
                var bridge = methodShape(request.bean(), m, false, false, request.elements(), request.types());
                if (InterceptedShapeFromElements.superCalledBy(request.bean(), m, forwarded, request.elements(),
                        request.types()).isPresent() && declared.add(descriptorKey(bridge))) {
                    methods.add(bridge);
                }
            }
        }
    }

    /**
     * The shape of the proxy of {@code bean} when its class is final or has a final method: the proxy extends the class
     * above it
     * that {@link ForwardedMethods#proxySuperclass} picks, as the run-time generator's does, and implements the
     * interfaces of {@code bean}, every method of which it forwards through its interface (BUG-20261118-20). Empty when
     * the proxy extends {@code bean}. Such a shape is emitted as bytecode, named after {@code bean}.
     */
    public static Optional<ClientProxyShape> beyondFinalMethod(TypeElement bean, Elements elements, Types types,
            Consumer<String> omitted) {
        var superclass = ((ElementTypeView) ForwardedMethods.proxySuperclass(ElementTypeView.of(bean, types), true))
                .type();
        if (superclass.equals(bean)) {
            return Optional.empty();
        }
        var proxyPackage = elements.getPackageOf(bean);
        var base = fromErased(superclass, proxyPackage, elements, types, omitted);
        var methods = new ArrayList<>(base.methods());
        var declared = new java.util.HashSet<String>();
        for (var m : methods) declared.add(descriptorKey(m));
        var interfaces = new ArrayList<TypeRef>();
        for (var implemented : proxiedInterfaces(bean, proxyPackage, elements)) {
            var owner = TypeRef.ofReference(elements.getBinaryName(implemented).toString());
            interfaces.add(owner);
            for (var method : ElementFilter.methodsIn(elements.getAllMembers(implemented))) {
                if (isForwardedInterfaceMethod(method)) {
                    var shape = new ProxyMethodShape(method.getSimpleName().toString(),
                            typeRef(method.getReturnType(), elements, types),
                            typeRefs(method.getParameters(), elements, types),
                            mirrorRefs(method.getThrownTypes(), elements, types), false, owner);
                    if (declared.add(descriptorKey(shape))) {
                        methods.add(shape);
                    }
                }
            }
        }
        return Optional.of(new ClientProxyShape(base.beanBinaryName(), base.superCtorParams(), methods,
                base.superCtorThrown(), base.overridesWriteReplace() || inheritsInterfaceWriteReplace(interfaces,
                        methods), interfaces));
    }

    /** Whether an interface's {@code Object writeReplace()} is among {@code methods}, forwarded through it. */
    private static boolean inheritsInterfaceWriteReplace(List<TypeRef> interfaces, List<ProxyMethodShape> methods) {
        return !interfaces.isEmpty() && methods.stream().anyMatch(m -> m.interfaceOwner() != null
                && m.name().equals(ForwardedMethods.WRITE_REPLACE) && m.params().isEmpty());
    }

    /** A method an interface declares, neither static nor private: its own, or one of a superinterface. */
    private static boolean isForwardedInterfaceMethod(ExecutableElement method) {
        return method.getEnclosingElement().getKind() == javax.lang.model.element.ElementKind.INTERFACE
                && !method.getModifiers().contains(Modifier.STATIC)
                && !method.getModifiers().contains(Modifier.PRIVATE);
    }

    /**
     * The interfaces of {@code bean} and of its superclasses, with theirs, in the order the classes declare them, that
     * a proxy in {@code proxyPackage} may implement, {@code java.io.Serializable} aside: the run-time generator's list.
     */
    private static List<TypeElement> proxiedInterfaces(TypeElement bean,
            javax.lang.model.element.PackageElement proxyPackage, Elements elements) {
        var found = new java.util.LinkedHashSet<TypeElement>();
        for (TypeElement c = bean; c != null && !c.getQualifiedName().contentEquals(ForwardedMethods.OBJECT);
                c = superclassOf(c)) {
            for (var implemented : c.getInterfaces()) {
                addWithSuperinterfaces(implemented, found);
            }
        }
        found.removeIf(implemented -> implemented.getQualifiedName().contentEquals("java.io.Serializable")
                || !accessibleFrom(implemented, proxyPackage, elements));
        return List.copyOf(found);
    }

    private static void addWithSuperinterfaces(TypeMirror implemented, java.util.Set<TypeElement> found) {
        if (implemented instanceof javax.lang.model.type.DeclaredType declared
                && declared.asElement() instanceof TypeElement element && found.add(element)) {
            for (var parent : element.getInterfaces()) {
                addWithSuperinterfaces(parent, found);
            }
        }
    }

    private static TypeElement superclassOf(TypeElement type) {
        return type.getSuperclass() instanceof javax.lang.model.type.DeclaredType declared
                && declared.asElement() instanceof TypeElement superclass ? superclass : null;
    }

    /** Whether code of {@code proxyPackage} may name {@code type}: public, enclosing types included, or its own. */
    private static boolean accessibleFrom(TypeElement type, javax.lang.model.element.PackageElement proxyPackage,
            Elements elements) {
        if (elements.getPackageOf(type).equals(proxyPackage)) {
            return true;
        }
        for (var e = (javax.lang.model.element.Element) type; e instanceof TypeElement;
                e = e.getEnclosingElement()) {
            if (!e.getModifiers().contains(Modifier.PUBLIC)) {
                return false;
            }
        }
        return true;
    }

    /** The name, parameter types and return type: what a method of a class file is told apart by. */
    private static String descriptorKey(ProxyMethodShape m) {
        return m.name() + m.params() + m.returnType();
    }

    /**
     * An interface method the proxy may forward, by the same rules as a class method: a {@code default} one, or an
     * abstract one an abstract proxied type leaves to its subclasses (BUG-20261114-02). As in
     * {@code RuntimeClientProxyGenerator.shapeOf}.
     */
    private static boolean isProxiedDefault(ExecutableElement m) {
        return m.getEnclosingElement().getKind().isInterface() && shouldProxy(m);
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
     * (issue #42, Stage 4), emitted as bytecode: every member of the bean is forwarded, as
     * {@link ForwardedMethods} decides for that placement — an inherited protected member of a
     * superclass in another package through a MethodHandle ({@code needsMethodHandle}), since
     * {@code delegate.m()} on an instance other than {@code this} is forbidden there (JLS 6.6.2), and
     * a package-private method only when the bean inherits it (JLS 8.4.8). The signatures are erased:
     * the descriptors the bytecode overrides must have.
     */
    public static ClientProxyShape fromColocated(TypeElement bean, Elements elements, Types types) {
        String beanBinaryName = elements.getBinaryName(bean).toString();
        var methods = forwardedMethods(new ShapeRequest(bean, elements.getPackageOf(bean), elements, types,
                omitted -> {}, Subject.BEAN, false));
        return new ClientProxyShape(beanBinaryName, superCtorParams(bean, elements, types), methods,
                superCtorThrown(bean, elements, types),
                exposesWriteReplace(bean, elements.getPackageOf(bean), elements, types));
    }

    /** Mirrors the bytecode generators: skip static/private/final/{@code $$} and the serialization hooks. */
    private static boolean shouldProxy(ExecutableElement m) {
        var mods = m.getModifiers();
        if (mods.contains(Modifier.STATIC)) return false;
        if (mods.contains(Modifier.PRIVATE)) return false;
        if (mods.contains(Modifier.FINAL)) return false;
        var name = m.getSimpleName().toString();
        return !name.startsWith("$$") && !ForwardedMethods.isSerialHook(name, m.getParameters().size());
    }

    /**
     * Parameter types of the constructor the proxy chains to: the opt-in {@code (ProxyLink)}
     * entry constructor when the bean declares one (side-effect-free by contract —
     * Vidocq/vauban#24), else the simplest non-private declared constructor — empty for a
     * no-arg (or absent) constructor, meaning a plain {@code super()} call.
     */
    private static List<TypeRef> superCtorParams(TypeElement bean, Elements elements, Types types) {
        ExecutableElement chosen = ElementTypeView.of(bean, types).superConstructor();
        if (chosen == null) return List.of();
        return typeRefs(chosen.getParameters(), elements, types);
    }

    /** The exceptions the constructor of {@link #superCtorParams} declares: the proxy constructor declares them too. */
    private static List<TypeRef> superCtorThrown(TypeElement bean, Elements elements, Types types) {
        ExecutableElement chosen = ElementTypeView.of(bean, types).superConstructor();
        if (chosen == null) return List.of();
        return mirrorRefs(chosen.getThrownTypes(), elements, types);
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
}
