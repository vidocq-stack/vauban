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

    private static final String INJECT = "jakarta.inject.Inject";
    private static final String AROUND_INVOKE = "jakarta.interceptor.AroundInvoke";
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
        for (boolean interfacePass : new boolean[] {false, true}) {
            for (ExecutableElement method : members) {
                if (declaredByInterface(method) != interfacePass) continue;
                if (!seen.add(memberSignatureKey(bean, method, types))) continue;
                if (!shouldIntercept(method, elements)) continue;
                var shape = methodShape(bean, method, elements, types);
                if (isShadowedDefault(bean, method, types)) {
                    // super.<name>() would resolve to the shadowing declaration: the bridge reaches
                    // the default through an interface the subclass lists (BUG-20261004-08), or,
                    // when no interface it may name carries it, the method is left alone.
                    var owner = accessibleDefaultOwner(bean, method, elements, types);
                    if (owner == null) continue;
                    shape = shape.withDefaultOwner(TypeRef.ofReference(elements.getBinaryName(owner).toString(), 0));
                }
                methods.add(shape);
            }
        }

        return new InterceptedShape(beanBinaryName, ctors, methods);
    }

    /**
     * Whether {@code method}, an interface default method {@code bean} inherits, is shadowed: a class
     * of the bean's superclass chain declares a method with its name and erased descriptor that is
     * <em>not</em> a member of the bean — private, or package-private in another package (JLS
     * 8.4.8) — to which the JVM resolves a call typed by the bean class, the generated subclass's
     * {@code super.<name>()} included, and which it then refuses. A declaration the bean inherits
     * is no shadow: it is the bean's member, and implements the default. As
     * {@code io.vidocq.vauban.core.interceptor.ShadowedDefaults#isShadowed} (BUG-20261004-08).
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
                    && nameableFrom(candidate, bean, elements)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean nameableFrom(TypeElement candidate, TypeElement bean, Elements elements) {
        if (elements.getPackageOf(candidate).equals(elements.getPackageOf(bean))) return true;
        for (Element e = candidate; e instanceof TypeElement t; e = t.getEnclosingElement()) {
            if (!t.getModifiers().contains(Modifier.PUBLIC)) return false;
        }
        var ownerModule = elements.getModuleOf(candidate);
        var beanModule = elements.getModuleOf(bean);
        if (ownerModule == null || ownerModule.isUnnamed() || ownerModule.equals(beanModule)) return true;
        var pkg = elements.getPackageOf(candidate);
        for (var directive : ElementFilter.exportsIn(ownerModule.getDirectives())) {
            if (directive.getPackage().equals(pkg)
                    && (directive.getTargetModules() == null || directive.getTargetModules().contains(beanModule))) {
                return true;
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

        // CDI spec: @Inject initializer methods are NOT intercepted
        if (hasAnnotation(method, INJECT)) return false;
        // Target class interceptor methods (@AroundInvoke) are not business methods
        return !hasAnnotation(method, AROUND_INVOKE);
    }

    private static boolean hasAnnotation(ExecutableElement method, String qualifiedName) {
        for (AnnotationMirror ann : method.getAnnotationMirrors()) {
            if (ann.getAnnotationType().asElement() instanceof TypeElement te) {
                if (qualifiedName.equals(te.getQualifiedName().toString())) return true;
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
     * {@code method} as a member of {@code type}: its type variables replaced by what {@code type}
     * binds them to through its supertypes ({@code label(T)} of {@code Labeled<T>} is
     * {@code label(String)} in a class implementing {@code Labeled<String>}).
     */
    public static ExecutableType memberType(TypeElement type, ExecutableElement method, Types types) {
        return (ExecutableType) types.asMemberOf((DeclaredType) type.asType(), method);
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
                    return TypeRef.ofReference(binaryName, dims);
                }
                // Fallback for unusual mirrors (intersection types, error types): treat as Object
                return TypeRef.ofReference("java.lang.Object", dims);
            }
        }
    }
}
