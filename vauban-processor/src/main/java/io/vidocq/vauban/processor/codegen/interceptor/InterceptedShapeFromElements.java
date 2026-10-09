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
package io.vidocq.vauban.processor.codegen.interceptor;

import io.vidocq.vauban.core.codegen.BeanMembers;
import io.vidocq.vauban.core.interceptor.CtorShape;
import io.vidocq.vauban.core.interceptor.InterceptedShape;
import io.vidocq.vauban.core.interceptor.MethodShape;
import io.vidocq.vauban.core.interceptor.TypeRef;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds an {@link InterceptedShape} from a {@link TypeElement} using the
 * {@code javax.lang.model} API (APT / javac Elements).
 *
 * <p>The method-selection logic mirrors
 * {@link io.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator#fromClass(Class)}
 * exactly, so that both front-ends produce the same override set:
 * <ol>
 *   <li>Constructors from {@link ElementFilter#constructorsIn}, skipping {@code PRIVATE}.</li>
 *   <li>Methods from {@link Elements#getAllMembers(TypeElement)}, skipping:
 *     <ul>
 *       <li>declaring element is {@code java.lang.Object}</li>
 *       <li>modifiers: STATIC, PRIVATE, FINAL</li>
 *       <li>name starts with {@code $$}</li>
 *       <li>annotated with {@code @jakarta.inject.Inject} or {@code @jakarta.interceptor.AroundInvoke}</li>
 *     </ul>
 *   </li>
 *   <li>One method per signature as a member of the bean ({@link #memberSignatureKey}), class
 *       methods before interface default methods.</li>
 * </ol>
 *
 * <p>Bridge/synthetic methods do not appear in the Elements model so no special handling
 * is needed for those.
 */
public final class InterceptedShapeFromElements {

    private static final String JAVA_LANG_OBJECT = "java.lang.Object";

    private InterceptedShapeFromElements() {}

    /**
     * Build an {@link InterceptedShape} for {@code bean}.
     *
     * @param bean     the bean's {@link TypeElement}
     * @param elements the {@link Elements} utility from the processing environment
     * @param types    the {@link Types} utility from the processing environment
     * @return the neutral shape ready for
     *         {@link io.vidocq.vauban.core.interceptor.InterceptedEmitter#emit}
     */
    public static InterceptedShape from(TypeElement bean, Elements elements, Types types) {
        return from(bean, elements, types, omitted -> {});
    }

    /**
     * The same, {@code omitted} receiving one message per inherited default method the subclass
     * leaves alone — not intercepted — because no interface carrying it can be named from the
     * bean's package, or because its override would name a type that package cannot: for the
     * processor to report (BUG-20261004-08).
     */
    public static InterceptedShape from(TypeElement bean, Elements elements, Types types,
            java.util.function.Consumer<String> omitted) {
        return from(bean, elements, types, omitted, unnameable -> {});
    }

    /**
     * The same, {@code unnameable} receiving one {@code name(member parameter types)} per method of
     * the shape whose override Java source in the bean's package cannot declare: its signature as a
     * member of the bean names a type that package cannot name — a package-private type of another
     * package, a private nested type of its own (BUG-20261004-09). The source renderer cannot render
     * such a shape; the processor emits it as bytecode, which overrides by descriptor.
     */
    public static InterceptedShape from(TypeElement bean, Elements elements, Types types,
            java.util.function.Consumer<String> omitted, java.util.function.Consumer<String> unnameable) {
        String beanBinaryName = elements.getBinaryName(bean).toString();

        // Constructors — the (ProxyLink) client-proxy entry constructor is not mirrored:
        // the $$Intercepted subclass IS the contextual instance and must run business
        // constructors only (Vidocq/vauban#24).
        var ctors = new ArrayList<CtorShape>();
        for (ExecutableElement ctor : ElementFilter.constructorsIn(bean.getEnclosedElements())) {
            if (ctor.getModifiers().contains(Modifier.PRIVATE)) continue;
            if (io.vidocq.vauban.processor.codegen.proxy.ClientProxyShapeFromElements
                    .isProxyLinkConstructor(ctor)) continue;
            ctors.add(new CtorShape(paramShapes(ctor, elements, types)));
        }

        // Methods — all members including inherited, one per member signature. getAllMembers drops
        // an overridden method, but not a class method that implements an interface method for the
        // bean (PlainBase.label(String) for Labeled<String>.label(T)): both are the member
        // label(String), which the source renderer would declare twice (BUG-20261004-06). Class
        // methods come first, so the one the bean runs wins; a final one still hides the other.
        var methods = new ArrayList<MethodShape>();
        var seen = new LinkedHashSet<String>();
        var members = ElementFilter.methodsIn(elements.getAllMembers(bean));
        var beanPackage = elements.getPackageOf(bean);
        for (boolean interfacePass : new boolean[] {false, true}) {
            for (ExecutableElement method : members) {
                if (declaredByInterface(method) != interfacePass) continue;
                if (!seen.add(memberSignatureKey(bean, method, types))) continue;
                if (!shouldIntercept(method, elements)) continue;
                // As InterceptorSubclassGenerator#methodShapeOf: a return type the subclass may not
                // access cannot be typed from what the chain returns; a parameter is loaded by name.
                var returnType = types.erasure(method.getReturnType());
                if (!resolvableFrom(returnType, beanPackage, elements)) {
                    omitted.accept(bean.getQualifiedName() + ": the generated subclass does not intercept the inherited method "
                            + ((TypeElement) method.getEnclosingElement()).getQualifiedName() + "." + method
                            + " — its return type " + returnType + " is a class package " + beanPackage.getQualifiedName()
                            + " may not access, so no subclass there can type the value an interceptor chain returns;"
                            + " a call runs it as on a plain instance of the bean. The bytecode generators (run-time"
                            + " fallback, Maven plugin) leave it out too (BUG-20261004-09).");
                    continue;
                }
                var shape = methodShape(bean, method, elements, types);
                if (!descriptorResolvableFrom(method, beanPackage, elements, types)) {
                    shape = shape.withInaccessibleTypes();
                }
                if (isShadowedDefault(bean, method, types)) {
                    // super.<name>() would resolve to the shadowing declaration: the bridge reaches
                    // the default through an interface the subclass lists (BUG-20261004-08), or,
                    // when no interface it may name carries it — or the override and the listed
                    // interface would name a type this package cannot — the method is left alone.
                    var owner = accessibleDefaultOwner(bean, method, beanPackage, elements, types);
                    if (owner == null) {
                        // InterceptorSubclassGenerator decides by class-file access instead
                        // (ShadowedDefaults#accessibleOwner): it leaves out a package-private interface
                        // of another package too, but lists a private nested interface of the bean's
                        // package, which Java source cannot name (BUG-20261004-09).
                        omitted.accept(notIntercepted(bean, method, "no interface carrying it can be named from package "
                                + beanPackage.getQualifiedName(),
                                "Whether the bytecode generators (run-time fallback, Maven plugin) intercept it"
                                        + " depends on class-file access, a different rule (BUG-20261004-09)."));
                        continue;
                    }
                    var ownerType = asTheBeanParameterisesIt(bean, owner, types);
                    if (!memberSignatureNameableFrom(bean, method, beanPackage, elements, types)
                            || !nameableFrom(ownerType, beanPackage, elements)) {
                        omitted.accept(notIntercepted(bean, method, "its override, or the interface "
                                + ownerType + " the subclass would list, names a type package "
                                + beanPackage.getQualifiedName() + " cannot",
                                "The bytecode generators (run-time fallback, Maven plugin) override by descriptor"
                                        + " and keep it, so a subclass they generate differs from this one."));
                        continue;
                    }
                    shape = shape.withDefaultOwner(TypeRef.ofReference(elements.getBinaryName(owner).toString(), 0,
                                    owner.getQualifiedName().toString()),
                            ownerType.toString());
                } else if (!memberSignatureNameableFrom(bean, method, beanPackage, elements, types, false)) {
                    unnameable.accept(memberSignature(bean, method, types));
                }
                methods.add(shape);
            }
        }

        return new InterceptedShape(beanBinaryName, ctors, methods);
    }

    /**
     * The processor's report of a default method the generated subclass leaves alone, why, what a
     * call does, and how the bytecode generators treat it. The subclass leaves out a shadowed default
     * only, and a subclass that inherits the carrying interface only through its superclass may not
     * reach the default under the shadow, even where a plain instance of the bean does
     * (BUG-20261004-08). The class the processor generates is used wherever it is found, on the class
     * path and the module path alike.
     */
    private static String notIntercepted(TypeElement bean, ExecutableElement method, String why,
            String bytecodeGenerators) {
        return bean.getQualifiedName() + ": the generated subclass does not intercept the inherited default method "
                + ((TypeElement) method.getEnclosingElement()).getQualifiedName() + "." + method + " — " + why
                + "; a call is not intercepted and may fail, as it does on any subclass under such a shadow"
                + " (AbstractMethodError on HotSpot 25, BUG-20261004-08). " + bytecodeGenerators;
    }

    /**
     * Whether {@code method}, an interface default method {@code bean} inherits, is shadowed: a class
     * of the bean's superclass chain declares a method with its name and erased descriptor that is
     * <em>not</em> a member of the bean — private, or package-private in another package (JLS
     * 8.4.8) — to which the JVM resolves a call typed by the bean class, the generated subclass's
     * {@code super.<name>()} included, and which it then refuses. A declaration the bean inherits
     * is no shadow: it is the bean's member, and implements the default. As
     * {@code io.vidocq.vauban.core.codegen.ShadowedDefaults#isShadowed} (BUG-20261004-08).
     */
    public static boolean isShadowedDefault(TypeElement bean, ExecutableElement method, Types types) {
        if (!method.getModifiers().contains(Modifier.DEFAULT)) return false;
        var descriptor = erasedDescriptor(method, types);
        boolean shadowed = false;
        for (TypeElement c = bean; c != null && !isObject(c); c = superclassOf(c)) {
            for (ExecutableElement declared : ElementFilter.methodsIn(c.getEnclosedElements())) {
                if (!erasedDescriptor(declared, types).equals(descriptor)) continue;
                if (isMemberOf(bean, declared, c)) return false;
                shadowed = true;
            }
        }
        return shadowed;
    }

    /**
     * Whether {@code declared}, a method of {@code owner} — {@code bean} or one of its superclasses —
     * is a member of {@code bean}: not private, and when package-private, inherited through classes
     * of {@code owner}'s package only (JLS 8.4.8).
     */
    private static boolean isMemberOf(TypeElement bean, ExecutableElement declared, TypeElement owner) {
        var modifiers = declared.getModifiers();
        if (modifiers.contains(Modifier.PRIVATE)) return owner.equals(bean);
        if (modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED)) return true;
        var ownerPackage = packageOf(owner);
        for (TypeElement c = bean; c != null && !c.equals(owner); c = superclassOf(c)) {
            if (!packageOf(c).equals(ownerPackage)) return false;
        }
        return true;
    }

    private static Element packageOf(TypeElement type) {
        Element e = type;
        while (e != null && e.getKind() != ElementKind.PACKAGE) e = e.getEnclosingElement();
        return e;
    }

    /**
     * The interface through which a class generated in {@code bean}'s package can reach the shadowed
     * default {@code method} explicitly: its declaring interface when the generated class can name
     * it, else an interface among the bean's supertypes that inherits it and can be named; {@code
     * null} when there is none. As {@code ShadowedDefaults#accessibleOwner} at run time.
     */
    public static TypeElement accessibleDefaultOwner(TypeElement bean, ExecutableElement method,
            Elements elements, Types types) {
        return accessibleDefaultOwner(bean, method, elements.getPackageOf(bean), elements, types);
    }

    /**
     * The same, for a class generated in {@code generatedIn} — a producer's package for a producer's
     * proxy (#42), from where a package-private interface of the bean's package cannot be named:
     * there, a method whose interfaces are all out of reach gets {@code null}, and is not forwarded.
     */
    public static TypeElement accessibleDefaultOwner(TypeElement bean, ExecutableElement method,
            PackageElement generatedIn, Elements elements, Types types) {
        var declaring = (TypeElement) method.getEnclosingElement();
        var candidates = new java.util.LinkedHashSet<TypeElement>();
        candidates.add(declaring);
        var queue = new java.util.ArrayDeque<TypeElement>();
        for (TypeElement c = bean; c != null; c = superclassOf(c)) {
            queue.addAll(interfacesOf(c));
        }
        while (!queue.isEmpty()) {
            var next = queue.poll();
            if (types.isSubtype(types.erasure(next.asType()), types.erasure(declaring.asType()))
                    && candidates.add(next)) {
                queue.addAll(interfacesOf(next));
            }
        }
        for (var candidate : candidates) {
            if (ElementFilter.methodsIn(elements.getAllMembers(candidate)).contains(method)
                    && nameableFrom(candidate, generatedIn, elements)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * {@code owner}, a superinterface of {@code bean}, as the bean parameterises it
     * ({@code p.Labeled<java.lang.String>}), so a subclass may list it next to the bean: a raw
     * {@code p.Labeled} is "inherited with different arguments". A generic bean is extended raw by
     * its generated subclass, so its supertypes are taken erased too.
     */
    private static DeclaredType asTheBeanParameterisesIt(TypeElement bean, TypeElement owner, Types types) {
        TypeMirror start = bean.getTypeParameters().isEmpty() ? bean.asType() : types.erasure(bean.asType());
        var queue = new java.util.ArrayDeque<TypeMirror>();
        queue.add(start);
        while (!queue.isEmpty()) {
            for (TypeMirror supertype : types.directSupertypes(queue.poll())) {
                if (supertype instanceof DeclaredType dt && dt.asElement().equals(owner)) {
                    return dt;
                }
                queue.add(supertype);
            }
        }
        return (DeclaredType) types.erasure(owner.asType());
    }

    /**
     * Whether Java source in {@code generatedIn} can declare {@code method} with its signature as a
     * member of {@code bean} — {@link #memberType}, erased — the override a rendered subclass or
     * proxy writes: every parameter and return type nameable from there. {@code label(Hidden)}, the
     * member a bean gets from {@code implements Labeled<Hidden>} with {@code Hidden} package-private
     * in another package, is not (BUG-20261004-08).
     */
    public static boolean memberSignatureNameableFrom(TypeElement bean, ExecutableElement method,
            PackageElement generatedIn, Elements elements, Types types) {
        return memberSignatureNameableFrom(bean, method, generatedIn, elements, types, true);
    }

    /**
     * The same, {@code readability} false leaving out whether the generated class's module reads the
     * type's module. That check reads the {@code requires} of the module being compiled, which
     * completes it: javac then resolves its {@code provides} before the last round has written the
     * {@code _VaubanComponents} it names ("cannot find symbol"). It is fine for the rare shadowed
     * default, not for every forwarded method of every bean (BUG-20261004-09).
     */
    public static boolean memberSignatureNameableFrom(TypeElement bean, ExecutableElement method,
            PackageElement generatedIn, Elements elements, Types types, boolean readability) {
        var member = memberType(bean, method, types);
        if (!nameableFrom(types.erasure(member.getReturnType()), generatedIn, elements, readability)) return false;
        for (TypeMirror p : member.getParameterTypes()) {
            if (!nameableFrom(types.erasure(p), generatedIn, elements, readability)) return false;
        }
        return true;
    }

    /**
     * {@code name(p1,p2)}: {@code method}'s signature as a member of {@code bean}, erased — what a
     * rendered override declares.
     */
    public static String memberSignature(TypeElement bean, ExecutableElement method, Types types) {
        var joiner = new java.util.StringJoiner(",", method.getSimpleName() + "(", ")");
        for (TypeMirror p : memberType(bean, method, types).getParameterTypes()) {
            joiner.add(types.erasure(p).toString());
        }
        return joiner.toString();
    }

    /** Whether every parameter class of {@code method}'s descriptor is {@link #resolvableFrom} {@code from}. */
    private static boolean descriptorResolvableFrom(ExecutableElement method, PackageElement from,
            Elements elements, Types types) {
        for (VariableElement p : method.getParameters()) {
            if (!resolvableFrom(types.erasure(p.asType()), from, elements)) return false;
        }
        return true;
    }

    /**
     * Whether a class generated in {@code from} may access {@code type} — a descriptor type, erased
     * — at run time (JVMS 5.4.4), as {@code InterceptorSubclassGenerator#accessibleFrom}: a primitive;
     * a class of {@code from}, whatever its modifiers — a private nested class has package access in
     * its class file; else a class public in its class file — declared public, or a protected member
     * class, whatever its enclosing types — in a package exported to {@code from}'s module. Unlike
     * the run-time front-end, which also asks {@code Module#canRead}, it does not check that this
     * module reads the type's module: that would complete the module being compiled (see {@link
     * #memberSignatureNameableFrom(TypeElement, ExecutableElement, PackageElement, Elements, Types,
     * boolean)}). An array, as its element type. Wider than {@link #nameableFrom(TypeMirror,
     * PackageElement, Elements)}, which is what Java source may write (BUG-20261004-09).
     */
    public static boolean resolvableFrom(TypeMirror type, PackageElement from, Elements elements) {
        return switch (type.getKind()) {
            case ARRAY -> resolvableFrom(((ArrayType) type).getComponentType(), from, elements);
            case DECLARED -> {
                if (!(((DeclaredType) type).asElement() instanceof TypeElement te)) yield true;
                if (elements.getPackageOf(te).equals(from)) yield true;
                var modifiers = te.getModifiers();
                yield (modifiers.contains(Modifier.PUBLIC)
                                || (te.getNestingKind() == NestingKind.MEMBER && modifiers.contains(Modifier.PROTECTED)))
                        && exportedTo(te, from, elements, false);
            }
            default -> true;
        };
    }

    /**
     * Whether code in {@code from} can write {@code type}: its class and every type argument,
     * bound and component nameable from there ({@link #nameableFrom(TypeElement, PackageElement,
     * Elements)}). A type variable needs no name.
     */
    public static boolean nameableFrom(TypeMirror type, PackageElement from, Elements elements) {
        return nameableFrom(type, from, elements, true);
    }

    /**
     * The same, {@code readability} false leaving out whether {@code from}'s module reads the type's
     * module: that check completes the module being compiled, whose {@code provides} javac then
     * resolves before the last round has written {@code _VaubanComponents} (see
     * {@link #memberSignatureNameableFrom(TypeElement, ExecutableElement, PackageElement, Elements,
     * Types, boolean)}). Sound only where a declaration of {@code from}'s module already names
     * {@code type}, which javac checks for readability itself.
     */
    public static boolean nameableFrom(TypeMirror type, PackageElement from, Elements elements, boolean readability) {
        return switch (type.getKind()) {
            case DECLARED -> {
                var dt = (DeclaredType) type;
                if (!(dt.asElement() instanceof TypeElement te) || !nameableFrom(te, from, elements, readability)) {
                    yield false;
                }
                for (TypeMirror argument : dt.getTypeArguments()) {
                    if (!nameableFrom(argument, from, elements, readability)) yield false;
                }
                yield true;
            }
            case ARRAY -> nameableFrom(((ArrayType) type).getComponentType(), from, elements, readability);
            case WILDCARD -> {
                var wt = (WildcardType) type;
                yield (wt.getExtendsBound() == null || nameableFrom(wt.getExtendsBound(), from, elements, readability))
                        && (wt.getSuperBound() == null || nameableFrom(wt.getSuperBound(), from, elements, readability));
            }
            default -> true;
        };
    }

    /**
     * Whether code in {@code from} — a generated top-level class there — can name {@code candidate}:
     * same package and neither it nor an enclosing type private, or public through every enclosing
     * type, in a module {@code from}'s module reads, and exported to it. A {@code null} {@code from}
     * — a package not known — asks for public and exported to all.
     */
    private static boolean nameableFrom(TypeElement candidate, PackageElement from, Elements elements) {
        return nameableFrom(candidate, from, elements, true);
    }

    private static boolean nameableFrom(TypeElement candidate, PackageElement from, Elements elements,
            boolean readability) {
        if (from != null && elements.getPackageOf(candidate).equals(from)) {
            // A private type, or a type nested in one, can be named only in the body of its
            // top-level class (JLS 6.6.1), which a generated class never is (BUG-20261004-09, n11a).
            for (Element e = candidate; e instanceof TypeElement t; e = t.getEnclosingElement()) {
                if (t.getModifiers().contains(Modifier.PRIVATE)) return false;
            }
            return true;
        }
        for (Element e = candidate; e instanceof TypeElement t; e = t.getEnclosingElement()) {
            if (!t.getModifiers().contains(Modifier.PUBLIC)) return false;
        }
        return exportedTo(candidate, from, elements, readability);
    }

    /**
     * Whether {@code candidate}'s module lets code in {@code from} reach its package: the same or an
     * unnamed module, or one {@code from}'s module reads and that exports the package to it. A
     * {@code null} {@code from} asks for an export to all. {@code readability} false: the export
     * only (see {@link #memberSignatureNameableFrom(TypeElement, ExecutableElement, PackageElement,
     * Elements, Types, boolean)}).
     */
    private static boolean exportedTo(TypeElement candidate, PackageElement from, Elements elements,
            boolean readability) {
        var ownerModule = elements.getModuleOf(candidate);
        var fromModule = from != null ? elements.getModuleOf(from) : null;
        if (ownerModule == null || ownerModule.isUnnamed()
                || (fromModule != null && ownerModule.equals(fromModule))) return true;
        if (readability && fromModule != null && !reads(fromModule, ownerModule)) return false;
        var pkg = elements.getPackageOf(candidate);
        for (var directive : ElementFilter.exportsIn(ownerModule.getDirectives())) {
            if (directive.getPackage().equals(pkg) && (directive.getTargetModules() == null
                    || (fromModule != null && directive.getTargetModules().contains(fromModule)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code module} reads {@code target} (JLS 7.7.1): {@code java.base} and every module
     * it requires, directly or through a chain of {@code requires transitive}. The unnamed module
     * reads every module. A bean that inherits an interface through a superclass of another module
     * may not read the interface's module, and then cannot name it.
     */
    private static boolean reads(ModuleElement module, ModuleElement target) {
        if (module.isUnnamed() || target.getQualifiedName().contentEquals("java.base")) return true;
        var visited = new java.util.HashSet<ModuleElement>();
        var queue = new java.util.ArrayDeque<ModuleElement>();
        for (var requires : ElementFilter.requiresIn(module.getDirectives())) {
            queue.add(requires.getDependency());
        }
        while (!queue.isEmpty()) {
            var read = queue.poll();
            if (read.equals(target)) return true;
            if (!visited.add(read)) continue;
            for (var requires : ElementFilter.requiresIn(read.getDirectives())) {
                if (requires.isTransitive()) queue.add(requires.getDependency());
            }
        }
        return false;
    }

    private static java.util.List<TypeElement> interfacesOf(TypeElement type) {
        var interfaces = new ArrayList<TypeElement>();
        for (TypeMirror i : type.getInterfaces()) {
            if (i instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te) interfaces.add(te);
        }
        return interfaces;
    }

    /** Name, erased parameter types and erased return type: what the JVM resolves a call by. */
    private static String erasedDescriptor(ExecutableElement method, Types types) {
        var sb = new StringBuilder(method.getSimpleName()).append('(');
        for (VariableElement p : method.getParameters()) {
            sb.append(types.erasure(p.asType())).append(',');
        }
        return sb.append(')').append(types.erasure(method.getReturnType())).toString();
    }

    private static boolean isObject(TypeElement t) {
        return t.getQualifiedName().contentEquals(JAVA_LANG_OBJECT);
    }

    private static TypeElement superclassOf(TypeElement t) {
        return t.getSuperclass() instanceof DeclaredType dt && dt.asElement() instanceof TypeElement se ? se : null;
    }

    private static boolean declaredByInterface(ExecutableElement method) {
        return method.getEnclosingElement().getKind().isInterface();
    }

    /**
     * {@code method}'s name and erased parameter types as a member of {@code bean}: two declarations
     * with the same key are one method of the bean, which a subclass or proxy overrides once.
     */
    public static String memberSignatureKey(TypeElement bean, ExecutableElement method, Types types) {
        var sb = new StringBuilder(method.getSimpleName()).append('(');
        for (TypeMirror p : memberType(bean, method, types).getParameterTypes()) {
            sb.append(types.erasure(p)).append(',');
        }
        return sb.append(')').toString();
    }

    // ---- predicate ----

    private static boolean shouldIntercept(ExecutableElement method, Elements elements) {
        var mods = method.getModifiers();
        if (mods.contains(Modifier.STATIC)) return false;
        if (mods.contains(Modifier.PRIVATE)) return false;
        if (mods.contains(Modifier.FINAL)) return false;
        if (method.getSimpleName().toString().startsWith("$$")) return false;

        // Skip methods declared on java.lang.Object
        Element enclosing = method.getEnclosingElement();
        if (enclosing instanceof TypeElement te) {
            if (JAVA_LANG_OBJECT.equals(te.getQualifiedName().toString())) return false;
        }

        // @Inject initializers, the target class's interceptor methods and lifecycle callbacks are not
        // business methods: the run-time generator's list, matched by name
        return !hasNonBusinessMethodAnnotation(method);
    }

    private static boolean hasNonBusinessMethodAnnotation(ExecutableElement method) {
        for (AnnotationMirror ann : method.getAnnotationMirrors()) {
            if (ann.getAnnotationType().asElement() instanceof TypeElement te
                    && BeanMembers.NON_BUSINESS_METHOD_ANNOTATIONS.contains(te.getQualifiedName().toString())) {
                return true;
            }
        }
        return false;
    }

    // ---- shape builders ----

    /**
     * The erased declaration — the descriptor the run-time front-end sees — and the signature as a
     * member of {@code bean}, which differs when the bean binds a type variable of the declaring
     * supertype: the source renderer can only override that one (BUG-20261004-06).
     */
    private static MethodShape methodShape(TypeElement bean, ExecutableElement method,
            Elements elements, Types types) {
        TypeRef returnType = typeRefOf(method.getReturnType(), elements, types, 0);
        var params = new ArrayList<TypeRef>();
        for (VariableElement p : method.getParameters()) {
            params.add(typeRefOf(p.asType(), elements, types, 0));
        }
        var member = memberType(bean, method, types);
        TypeRef memberReturnType = typeRefOf(member.getReturnType(), elements, types, 0);
        var memberParams = new ArrayList<TypeRef>();
        for (TypeMirror p : member.getParameterTypes()) {
            memberParams.add(typeRefOf(p, elements, types, 0));
        }
        return new MethodShape(method.getSimpleName().toString(), returnType, params,
                memberReturnType, memberParams);
    }

    /**
     * {@code method} as a member of {@code type}, as the generated subclass or proxy sees it: its
     * type variables replaced by what {@code type} binds them to through its supertypes
     * ({@code label(T)} of {@code Labeled<T>} is {@code label(String)} in a class implementing
     * {@code Labeled<String>}). A generic {@code type} is extended raw by the generated class, and
     * every member of a raw type is erased (JLS 4.8): {@code label(Object)} there, whatever the
     * bean binds — a {@code label(String)} override would not override anything.
     */
    public static ExecutableType memberType(TypeElement type, ExecutableElement method, Types types) {
        var seenBy = type.getTypeParameters().isEmpty() ? type.asType() : types.erasure(type.asType());
        return (ExecutableType) types.asMemberOf((DeclaredType) seenBy, method);
    }

    private static List<TypeRef> paramShapes(ExecutableElement method, Elements elements, Types types) {
        var params = new ArrayList<TypeRef>();
        for (VariableElement p : method.getParameters()) {
            params.add(typeRefOf(p.asType(), elements, types, 0));
        }
        return params;
    }

    // ---- TypeMirror → TypeRef ----

    /**
     * Convert a {@link TypeMirror} to a {@link TypeRef}.
     *
     * <ul>
     *   <li>Primitive/void: via {@link TypeKind}.</li>
     *   <li>Array: recurse on component type, accumulate dimensions.</li>
     *   <li>Declared: erase via {@link Types#erasure}, extract binary name via
     *       {@link Elements#getBinaryName} (inner classes use {@code $},
     *       matching {@link Class#getName()}).</li>
     * </ul>
     */
    public static TypeRef typeRefOf(TypeMirror tm, Elements elements, Types types, int dims) {
        switch (tm.getKind()) {
            case VOID -> { return TypeRef.ofVoid(); }
            case BOOLEAN -> { return TypeRef.ofPrimitive(TypeRef.Primitive.BOOLEAN, dims); }
            case BYTE    -> { return TypeRef.ofPrimitive(TypeRef.Primitive.BYTE, dims); }
            case CHAR    -> { return TypeRef.ofPrimitive(TypeRef.Primitive.CHAR, dims); }
            case SHORT   -> { return TypeRef.ofPrimitive(TypeRef.Primitive.SHORT, dims); }
            case INT     -> { return TypeRef.ofPrimitive(TypeRef.Primitive.INT, dims); }
            case LONG    -> { return TypeRef.ofPrimitive(TypeRef.Primitive.LONG, dims); }
            case FLOAT   -> { return TypeRef.ofPrimitive(TypeRef.Primitive.FLOAT, dims); }
            case DOUBLE  -> { return TypeRef.ofPrimitive(TypeRef.Primitive.DOUBLE, dims); }
            case ARRAY   -> {
                ArrayType at = (ArrayType) tm;
                return typeRefOf(at.getComponentType(), elements, types, dims + 1);
            }
            default -> {
                // Declared, type-var, wildcard, etc. — erase and treat as a reference type
                TypeMirror erased = types.erasure(tm);
                if (erased instanceof DeclaredType dt) {
                    TypeElement te = (TypeElement) dt.asElement();
                    // getBinaryName gives "Outer$Inner" for nested classes, matching Class.getName()
                    String binaryName = elements.getBinaryName(te).toString();
                    // The canonical name too, for source: a '$' may be part of a type's own name.
                    return TypeRef.ofReference(binaryName, dims, te.getQualifiedName().toString());
                }
                // Fallback for unusual mirrors (intersection types, error types): treat as Object
                return TypeRef.ofReference("java.lang.Object", dims);
            }
        }
    }
}
