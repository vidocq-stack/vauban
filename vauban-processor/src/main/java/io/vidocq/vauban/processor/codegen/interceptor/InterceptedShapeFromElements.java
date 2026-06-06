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
 *   <li>Deduplication by {@code name + '[' + erased-param-binary-names + ']'}.</li>
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

        // Constructors
        var ctors = new ArrayList<CtorShape>();
        for (ExecutableElement ctor : ElementFilter.constructorsIn(bean.getEnclosedElements())) {
            if (ctor.getModifiers().contains(Modifier.PRIVATE)) continue;
            ctors.add(new CtorShape(paramShapes(ctor, elements, types)));
        }

        // Methods — all members including inherited, deduped
        var methods = new ArrayList<MethodShape>();
        var seen = new LinkedHashSet<String>();

        for (Element member : elements.getAllMembers(bean)) {
            if (member.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) member;
            if (!shouldIntercept(method, elements)) continue;

            String key = dedupeKey(method, elements, types);
            if (!seen.add(key)) continue;

            methods.add(methodShape(method, elements, types));
        }

        return new InterceptedShape(beanBinaryName, ctors, methods);
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

    private static String dedupeKey(ExecutableElement method, Elements elements, Types types) {
        var sb = new StringBuilder(method.getSimpleName().toString());
        sb.append('[');
        var params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(erasedBinaryName(params.get(i).asType(), elements, types));
        }
        sb.append(']');
        return sb.toString();
    }

    private static MethodShape methodShape(ExecutableElement method, Elements elements, Types types) {
        TypeRef returnType = typeRefOf(method.getReturnType(), elements, types, 0);
        var params = new ArrayList<TypeRef>();
        for (VariableElement p : method.getParameters()) {
            params.add(typeRefOf(p.asType(), elements, types, 0));
        }
        return new MethodShape(method.getSimpleName().toString(), returnType, params);
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
    static TypeRef typeRefOf(TypeMirror tm, Elements elements, Types types, int dims) {
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

    /** Erased binary name for dedup key computation. */
    private static String erasedBinaryName(TypeMirror tm, Elements elements, Types types) {
        if (tm.getKind().isPrimitive()) return tm.getKind().name().toLowerCase();
        if (tm.getKind() == TypeKind.ARRAY) {
            ArrayType at = (ArrayType) tm;
            return erasedBinaryName(at.getComponentType(), elements, types) + "[]";
        }
        TypeMirror erased = types.erasure(tm);
        if (erased instanceof DeclaredType dt) {
            return elements.getBinaryName((TypeElement) dt.asElement()).toString();
        }
        return erased.toString();
    }
}
