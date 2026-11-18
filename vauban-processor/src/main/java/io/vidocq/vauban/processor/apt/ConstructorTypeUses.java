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
package io.vidocq.vauban.processor.apt;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.AnnotationValueVisitor;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads, from the source of a constructor, the annotations written on it that apply to a type use only: the class
 * file records them on what the constructor builds, its return type, and the compiler's model has them neither among
 * the constructor's annotations nor on its {@code void} return type (BUG-20261118-06). Each is read from its tree, its
 * members evaluated as the compiler evaluates them: a literal, a sign before a number, a cast, a constant or an enum
 * constant named by its field, a class literal, an array, a nested annotation. An annotation with a member written
 * otherwise, a constant expression of several operands such as {@code NAME + "!"}, is left out. Only a compiler
 * that has the {@code com.sun.source} trees, javac, gives them.
 */
final class ConstructorTypeUses {

    private final Trees trees;

    private ConstructorTypeUses(Trees trees) {
        this.trees = trees;
    }

    /** What the processor says once when the compiler has no trees to read. */
    static final String UNREAD = "[Vauban] This compiler gives no com.sun.source trees: an annotation that targets"
            + " TYPE_USE only, written on a constructor, is not read at build, where the start reads it on the"
            + " constructor's return type (BUG-20261118-06).";

    /**
     * Says, in a note, that the annotations a constructor's source alone gives are not read, when the compiler of
     * {@code environment} has no trees: ECJ, say (BUG-20261118-06).
     */
    static void noteWhenUnavailable(ProcessingEnvironment environment) {
        if (of(environment).isEmpty()) {
            environment.getMessager().printMessage(Diagnostic.Kind.NOTE, UNREAD);
        }
    }

    /** The reader of the compiler of {@code environment}, when it has trees. */
    static Optional<ConstructorTypeUses> of(ProcessingEnvironment environment) {
        try {
            return Optional.of(new ConstructorTypeUses(Trees.instance(environment)));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    /**
     * The annotations written on {@code constructor} whose type applies to a type use and not to a constructor, in
     * the order written; none for a constructor without source, read from a class file.
     */
    List<AnnotationMirror> typeUseOnly(ExecutableElement constructor) {
        var path = trees.getPath(constructor);
        if (path == null || !(path.getLeaf() instanceof MethodTree method)) {
            return List.of();
        }
        var modifiers = new TreePath(path, method.getModifiers());
        var result = new ArrayList<AnnotationMirror>();
        for (var annotation : method.getModifiers().getAnnotations()) {
            var annotationPath = new TreePath(modifiers, annotation);
            if (type(annotationPath, annotation) instanceof TypeElement type
                    && MirrorTypeAnnotations.appliesTo(type, "TYPE_USE")
                    && !MirrorTypeAnnotations.appliesTo(type, "CONSTRUCTOR")) {
                mirror(annotationPath, annotation, type).ifPresent(result::add);
            }
        }
        return result;
    }

    private Object type(TreePath annotationPath, AnnotationTree annotation) {
        return trees.getElement(new TreePath(annotationPath, annotation.getAnnotationType()));
    }

    private Optional<AnnotationMirror> mirror(TreePath path, AnnotationTree annotation, TypeElement type) {
        var values = new LinkedHashMap<ExecutableElement, AnnotationValue>();
        for (var argument : annotation.getArguments()) {
            var argumentPath = new TreePath(path, argument);
            var assignment = argument instanceof AssignmentTree written
                    && written.getVariable() instanceof IdentifierTree ? written : null;
            var name = assignment == null ? "value" : ((IdentifierTree) assignment.getVariable()).getName().toString();
            var expression = assignment == null ? argumentPath
                    : new TreePath(argumentPath, assignment.getExpression());
            var member = member(type, name);
            var value = member.flatMap(found -> value(expression, found.getReturnType()));
            if (value.isEmpty()) {
                return Optional.empty();
            }
            values.put(member.get(), value.get());
        }
        return Optional.of(new Mirror((DeclaredType) type.asType(), values));
    }

    private static Optional<ExecutableElement> member(TypeElement type, String name) {
        return ElementFilter.methodsIn(type.getEnclosedElements()).stream()
                .filter(method -> method.getSimpleName().contentEquals(name)).findFirst();
    }

    /** The value of the expression at {@code path} for a member of type {@code expected}; empty when not read. */
    private Optional<AnnotationValue> value(TreePath path, TypeMirror expected) {
        var expression = path.getLeaf();
        if (expected instanceof ArrayType array) {
            var elements = expression instanceof NewArrayTree written ? written.getInitializers()
                    : List.of((ExpressionTree) expression);
            var values = new ArrayList<AnnotationValue>();
            for (var element : elements) {
                var read = value(written(path, expression, element), array.getComponentType());
                if (read.isEmpty()) {
                    return Optional.empty();
                }
                values.add(read.get());
            }
            return Optional.of(new Value(List.copyOf(values)));
        }
        return single(path, expected).map(Value::new);
    }

    /** The path of {@code element} of an array: under it when written as {@code {...}}, the expression itself else. */
    private static TreePath written(TreePath path, Tree expression, Tree element) {
        return expression == element ? path : new TreePath(path, element);
    }

    private Optional<Object> single(TreePath path, TypeMirror expected) {
        return switch (path.getLeaf()) {
            case ParenthesizedTree parenthesized -> single(new TreePath(path, parenthesized.getExpression()), expected);
            case TypeCastTree cast -> single(new TreePath(path, cast.getExpression()), expected)
                    .map(found -> coerced(found, expected));
            case LiteralTree literal -> Optional.ofNullable(literal.getValue()).map(found -> coerced(found, expected));
            case UnaryTree unary when unary.getKind() == Tree.Kind.UNARY_MINUS
                    || unary.getKind() == Tree.Kind.UNARY_PLUS ->
                    single(new TreePath(path, unary.getExpression()), expected)
                            .filter(Number.class::isInstance)
                            .map(found -> unary.getKind() == Tree.Kind.UNARY_MINUS ? negated((Number) found) : found);
            case AnnotationTree nested when type(path, nested) instanceof TypeElement type ->
                    mirror(path, nested, type).map(Object.class::cast);
            case MemberSelectTree select when select.getIdentifier().contentEquals("class") ->
                    Optional.ofNullable(trees.getTypeMirror(new TreePath(path, select.getExpression())));
            case IdentifierTree _, MemberSelectTree _ -> constant(path, expected);
            default -> Optional.empty();
        };
    }

    private Optional<Object> constant(TreePath path, TypeMirror expected) {
        if (!(trees.getElement(path) instanceof VariableElement field)) {
            return Optional.empty();
        }
        return field.getKind() == ElementKind.ENUM_CONSTANT ? Optional.of(field)
                : Optional.ofNullable(field.getConstantValue()).map(found -> coerced(found, expected));
    }

    private static Number negated(Number number) {
        return switch (number) {
            case Integer i -> -i;
            case Long l -> -l;
            case Float f -> -f;
            case Double d -> -d;
            case Short s -> (short) -s;
            case Byte b -> (byte) -b;
            default -> number;
        };
    }

    /** {@code value} as the member's type holds it: a {@code long} member written {@code 2} holds {@code 2L}. */
    private static Object coerced(Object value, TypeMirror expected) {
        if (!(value instanceof Number || value instanceof Character)) {
            return value;
        }
        var number = value instanceof Character c ? Integer.valueOf(c) : (Number) value;
        return switch (expected.getKind()) {
            case LONG -> number.longValue();
            case INT -> number.intValue();
            case SHORT -> number.shortValue();
            case BYTE -> number.byteValue();
            case FLOAT -> number.floatValue();
            case DOUBLE -> number.doubleValue();
            case CHAR -> (char) number.intValue();
            default -> value;
        };
    }

    /** An annotation read from its tree. */
    private record Mirror(DeclaredType annotationType, Map<ExecutableElement, AnnotationValue> values)
            implements AnnotationMirror {

        @Override
        public DeclaredType getAnnotationType() {
            return annotationType;
        }

        @Override
        public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues() {
            return values;
        }
    }

    /** A member's value read from its tree, held as the compiler's model holds it. */
    record Value(Object value) implements AnnotationValue {

        @Override
        public Object getValue() {
            return value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R, P> R accept(AnnotationValueVisitor<R, P> visitor, P parameter) {
            return switch (value) {
                case Boolean b -> visitor.visitBoolean(b, parameter);
                case Byte b -> visitor.visitByte(b, parameter);
                case Character c -> visitor.visitChar(c, parameter);
                case Double d -> visitor.visitDouble(d, parameter);
                case Float f -> visitor.visitFloat(f, parameter);
                case Integer i -> visitor.visitInt(i, parameter);
                case Long l -> visitor.visitLong(l, parameter);
                case Short s -> visitor.visitShort(s, parameter);
                case String s -> visitor.visitString(s, parameter);
                case TypeMirror t -> visitor.visitType(t, parameter);
                case VariableElement e -> visitor.visitEnumConstant(e, parameter);
                case AnnotationMirror a -> visitor.visitAnnotation(a, parameter);
                case List<?> l -> visitor.visitArray((List<? extends AnnotationValue>) l, parameter);
                default -> visitor.visitUnknown(this, parameter);
            };
        }
    }
}
