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
package io.vidocq.vauban.processor.codegen.provider;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Renders, for the annotation types a module declares, the three things the container would otherwise
 * have to work out by reading them: what each type declares, a reader that turns an instance into its
 * member values, and a literal class.
 *
 * <p>They land in the module's {@code _VaubanComponents}, which answers
 * {@link io.vidocq.vauban.api.VaubanComponentProvider#annotationMetadata},
 * {@code readAnnotation} and {@code annotationLiteral} for them. A type this module does not declare is
 * not rendered: its own module ships its artefacts, or the container reads its class file.
 *
 * <p>A type whose default values cannot all be written as Java expressions — a nested annotation of a
 * type rendered nowhere here — is left out entirely rather than described by halves.
 */
public final class AnnotationArtefacts {

    private static final DotName NONBINDING = DotName.of("jakarta.enterprise.util.Nonbinding");
    private static final DotName RETENTION = DotName.of("java.lang.annotation.Retention");
    private static final DotName RETENTION_POLICY = DotName.of("java.lang.annotation.RetentionPolicy");

    /**
     * The canonical name of a type given its binary name, or {@code null} when the compiler does not
     * know the type; then the binary name with each {@code $} turned into {@code .} is written, which
     * is right for a nested type only (BUG-20261007-01).
     */
    private final Function<String, String> canonicalNames;

    private AnnotationArtefacts(Function<String, String> canonicalNames) {
        this.canonicalNames = canonicalNames;
    }

    /** The member methods of an annotation type, in declaration order. */
    private List<MethodInfo> members(ClassInfo annotationType) {
        return annotationType.methods().stream()
                .filter(method -> method.parameters().isEmpty() && !method.isStatic()
                        && !method.isSynthetic() && !method.isConstructor() && !method.isStaticInitializer())
                .toList();
    }

    /**
     * The bodies to put in the provider and the literal classes to put beside it, for the types that
     * can be rendered whole.
     *
     * @param annotationTypes the annotation types this module declares, from the index
     */
    public static Rendered render(List<ClassInfo> annotationTypes) {
        return render(annotationTypes, binaryName -> null);
    }

    /**
     * The same, writing each type by the canonical name {@code canonicalNames} gives for its binary
     * name: a {@code $} may be part of a top-level type's own name, so the binary name alone does not
     * say how source names it (BUG-20261007-01).
     *
     * @param canonicalNames the canonical name of a type given its binary name, or {@code null} when
     *                       the type is not known
     */
    public static Rendered render(List<ClassInfo> annotationTypes, Function<String, String> canonicalNames) {
        return new AnnotationArtefacts(canonicalNames).renderAll(annotationTypes);
    }

    private Rendered renderAll(List<ClassInfo> annotationTypes) {
        var runtime = annotationTypes.stream().filter(this::keptAtRuntime).toList();
        var renderable = runtime.stream().filter(type -> canRender(type, runtime)).toList();
        if (renderable.isEmpty()) {
            return new Rendered("", "");
        }
        return new Rendered(methods(renderable), literals(renderable));
    }

    /** What the provider gains: the metadata, the reader and the literal factory. */
    public record Rendered(String methods, String literals) {

        public boolean isEmpty() {
            return methods.isEmpty();
        }
    }

    // ---- the provider's methods ----

    private String methods(List<ClassInfo> types) {
        var sb = new StringBuilder();
        // Nothing here names an annotation type: a type is mentioned only inside its own nested
        // class, which the JVM loads when an arm below runs. A type that is on the compile path and
        // absent at run time — an optional integration — therefore costs nothing, where a chain of
        // `instanceof` resolved every one of them on the first call (vauban#88, heisenberg).
        sb.append("\n    @Override\n");
        sb.append("    public io.vidocq.vauban.api.AnnotationTypeMetadata annotationMetadata(String annotationClassName) {\n");
        sb.append("        return switch (annotationClassName) {\n");
        for (var type : types) {
            sb.append("            case \"").append(type.name().value()).append("\" -> ")
                    .append(literalName(type.name())).append(".metadata();\n");
        }
        sb.append("            default -> null;\n");
        sb.append("        };\n");
        sb.append("    }\n");

        sb.append("\n    @Override\n");
        sb.append("    public java.util.Map<String, Object> readAnnotation(java.lang.annotation.Annotation annotation) {\n");
        sb.append("        return switch (annotation.annotationType().getName()) {\n");
        for (var type : types) {
            sb.append("            case \"").append(type.name().value()).append("\" -> ")
                    .append(literalName(type.name())).append(".read(annotation);\n");
        }
        sb.append("            default -> null;\n");
        sb.append("        };\n");
        sb.append("    }\n");

        sb.append("\n    @Override\n");
        sb.append("    public java.lang.annotation.Annotation annotationLiteral(String annotationClassName,\n");
        sb.append("            java.util.Map<String, Object> members) {\n");
        sb.append("        return switch (annotationClassName) {\n");
        for (var type : types) {
            sb.append("            case \"").append(type.name().value()).append("\" -> new ")
                    .append(literalName(type.name())).append("(members);\n");
        }
        sb.append("            default -> null;\n");
        sb.append("        };\n");
        sb.append("    }\n");
        return sb.toString();
    }

    private String metadataMethod(ClassInfo type, List<ClassInfo> rendered) {
        var members = members(type);
        var sb = new StringBuilder();
        sb.append("\n        /** What the declaration says, for the container to normalize keys with. */\n");
        sb.append("        static io.vidocq.vauban.api.AnnotationTypeMetadata metadata() {\n");
        sb.append("            var types = new java.util.LinkedHashMap<String, Class<?>>();\n");
        for (var member : members) {
            sb.append("            types.put(\"").append(member.name()).append("\", ")
                    .append(classLiteral(member.returnType())).append(");\n");
        }
        sb.append("            var defaults = new java.util.LinkedHashMap<String, Object>();\n");
        for (var member : members) {
            if (member.defaultValue() != null) {
                sb.append("            defaults.put(\"").append(member.name()).append("\", ")
                        .append(expression(member.defaultValue(), member.returnType(), rendered)).append(");\n");
            }
        }
        sb.append("            return new io.vidocq.vauban.api.AnnotationTypeMetadata(\n");
        sb.append("                    java.util.List.of(").append(members.stream()
                .map(member -> "\"" + member.name() + "\"").collect(Collectors.joining(", "))).append("),\n");
        sb.append("                    types, defaults, ").append(nonbindingSet(members)).append(");\n");
        sb.append("        }\n");
        return sb.toString();
    }

    /** The reader, as a static method of the type's own nested class. */
    private String readMethod(ClassInfo type) {
        var members = members(type);
        var source = sourceName(type.name());
        var sb = new StringBuilder();
        sb.append("\n        /** The member values of an instance, read by calling them. */\n");
        sb.append("        static java.util.Map<String, Object> read(java.lang.annotation.Annotation annotation) {\n");
        if (members.isEmpty()) {
            sb.append("            return java.util.Map.of();\n        }\n");
            return sb.toString();
        }
        sb.append("            var value = (").append(source).append(") annotation;\n");
        sb.append("            var members = new java.util.LinkedHashMap<String, Object>();\n");
        for (var member : members) {
            sb.append("            members.put(\"").append(member.name()).append("\", value.")
                    .append(member.name()).append("());\n");
        }
        sb.append("            return members;\n        }\n");
        return sb.toString();
    }

    private String nonbindingSet(List<MethodInfo> members) {
        var nonbinding = members.stream()
                .filter(member -> member.annotations().stream().anyMatch(a -> a.name().equals(NONBINDING)))
                .map(member -> "\"" + member.name() + "\"")
                .toList();
        return "java.util.Set.of(" + String.join(", ", nonbinding) + ")";
    }

    // ---- the literal classes ----

    private String literals(List<ClassInfo> types) {
        return types.stream().map(type -> literal(type, types)).collect(Collectors.joining());
    }

    private String literal(ClassInfo type, List<ClassInfo> rendered) {
        var members = members(type);
        var source = sourceName(type.name());
        var name = literalName(type.name());
        var sb = new StringBuilder();
        sb.append("\n    /** The instance of {@code @").append(type.name().simpleName())
                .append("} the container hands to application code. */\n");
        sb.append("    static final class ").append(name).append(" implements ").append(source).append(" {\n");
        for (var member : members) {
            if (member == members.get(0)) sb.append("\n");
            sb.append("        private final ").append(sourceName(member.returnType())).append(" ")
                    .append(field(member)).append(";\n");
        }
        if (members.stream().anyMatch(member -> isParameterized(member.returnType()))) {
            sb.append("\n        @SuppressWarnings(\"unchecked\")");
            sb.append("\n        ").append(name).append("(java.util.Map<String, Object> members) {\n");
        } else {
            sb.append("\n        ").append(name).append("(java.util.Map<String, Object> members) {\n");
        }
        for (var member : members) {
            var written = "(" + boxed(member.returnType()) + ") members.get(\"" + member.name() + "\")";
            sb.append("            this.").append(field(member)).append(" = members.containsKey(\"")
                    .append(member.name()).append("\") ? ")
                    .append(isArray(member.returnType()) ? "(" + written + ").clone()" : written);
            if (member.defaultValue() != null) {
                sb.append(" : ").append(expression(member.defaultValue(), member.returnType(), rendered));
            } else {
                sb.append(" : incomplete(\"").append(member.name()).append("\")");
            }
            sb.append(";\n");
        }
        sb.append("        }\n");
        for (var member : members) {
            sb.append("\n        @Override\n        public ").append(sourceName(member.returnType())).append(" ")
                    .append(member.name()).append("() {\n            return ")
                    .append(isArray(member.returnType()) ? field(member) + ".clone()" : field(member))
                    .append(";\n        }\n");
        }
        sb.append("\n        @Override\n");
        sb.append("        public Class<? extends java.lang.annotation.Annotation> annotationType() {\n");
        sb.append("            return ").append(source).append(".class;\n        }\n");
        sb.append(metadataMethod(type, rendered));
        sb.append(readMethod(type));
        sb.append(equalsMethod(source, members));
        sb.append(hashCodeMethod(members));
        sb.append(toStringMethod(type, members));
        if (members.stream().anyMatch(member -> member.defaultValue() == null)) {
            sb.append("\n        private static <T> T incomplete(String member) {\n");
            sb.append("            throw new java.lang.annotation.IncompleteAnnotationException(")
                    .append(source).append(".class, member);\n        }\n");
        }
        sb.append("    }\n");
        return sb.toString();
    }

    private String equalsMethod(String source, List<MethodInfo> members) {
        var sb = new StringBuilder();
        sb.append("\n        @Override\n        public boolean equals(Object other) {\n");
        if (members.isEmpty()) {
            sb.append("            return other instanceof ").append(source).append(";\n        }\n");
            return sb.toString();
        }
        sb.append("            if (!(other instanceof ").append(source).append(" that)) return false;\n");
        sb.append("            return ").append(members.stream()
                .map(member -> memberEquals(member)).collect(Collectors.joining("\n                    && ")));
        sb.append(";\n        }\n");
        return sb.toString();
    }

    /** {@code Annotation#equals}: arrays element by element, floats and doubles by their bits. */
    private String memberEquals(MethodInfo member) {
        var field = field(member);
        var call = "that." + member.name() + "()";
        if (isArray(member.returnType())) {
            return "java.util.Arrays.equals(" + field + ", " + call + ")";
        }
        if (member.returnType() instanceof TypeInfo.PrimitiveType primitive) {
            return switch (primitive.kind()) {
                case FLOAT -> "Float.valueOf(" + field + ").equals(" + call + ")";
                case DOUBLE -> "Double.valueOf(" + field + ").equals(" + call + ")";
                default -> field + " == " + call;
            };
        }
        return field + ".equals(" + call + ")";
    }

    private String hashCodeMethod(List<MethodInfo> members) {
        var sb = new StringBuilder();
        sb.append("\n        @Override\n        public int hashCode() {\n");
        if (members.isEmpty()) {
            sb.append("            return 0;\n        }\n");
            return sb.toString();
        }
        sb.append("            return ").append(members.stream()
                .map(this::memberHash).collect(Collectors.joining("\n                    + ")));
        sb.append(";\n        }\n");
        return sb.toString();
    }

    /** {@code Annotation#hashCode}: 127 times the member's name hash, exclusive-or its value's. */
    private String memberHash(MethodInfo member) {
        var field = field(member);
        String value;
        if (isArray(member.returnType())) {
            value = "java.util.Arrays.hashCode(" + field + ")";
        } else if (member.returnType() instanceof TypeInfo.PrimitiveType primitive) {
            value = switch (primitive.kind()) {
                case BOOLEAN -> "Boolean.hashCode(" + field + ")";
                case BYTE -> "Byte.hashCode(" + field + ")";
                case CHAR -> "Character.hashCode(" + field + ")";
                case SHORT -> "Short.hashCode(" + field + ")";
                case INT -> "Integer.hashCode(" + field + ")";
                case LONG -> "Long.hashCode(" + field + ")";
                case FLOAT -> "Float.hashCode(" + field + ")";
                case DOUBLE -> "Double.hashCode(" + field + ")";
                default -> field + ".hashCode()";
            };
        } else {
            value = field + ".hashCode()";
        }
        return "((127 * \"" + member.name() + "\".hashCode()) ^ " + value + ")";
    }

    /**
     * {@code Annotation#toString} as {@code AnnotationInstances} renders it, so a qualifier reads the
     * same in a message whether the module generated its literal or the container built the instance:
     * members sorted by name, strings quoted, a {@code Class} as {@code X.class}, an array in braces.
     */
    private String toStringMethod(ClassInfo type, List<MethodInfo> members) {
        var sorted = members.stream().sorted(Comparator.comparing(MethodInfo::name)).toList();
        var sb = new StringBuilder();
        sb.append("\n        @Override\n        public String toString() {\n");
        sb.append("            return \"@").append(sourceName(type.name())).append("(\"");
        for (int i = 0; i < sorted.size(); i++) {
            sb.append("\n                    + \"").append(i == 0 ? "" : ", ").append(sorted.get(i).name())
                    .append("=\" + ").append(rendered(sorted.get(i)));
        }
        sb.append("\n                    + \")\";\n        }\n");
        for (var member : sorted) {
            if (isArray(member.returnType())) {
                sb.append(arrayRenderer(member));
            }
        }
        return sb.toString();
    }

    /** The member's value as it appears in {@code toString}. */
    private String rendered(MethodInfo member) {
        var field = field(member);
        if (isArray(member.returnType())) {
            return renderer(member) + "(" + field + ")";
        }
        return scalarRendering(member.returnType(), field);
    }

    private String scalarRendering(TypeInfo type, String value) {
        if (type instanceof TypeInfo.ClassType classType) {
            return switch (classType.name().value()) {
                case "java.lang.String" -> "\"\\\"\" + " + value + " + \"\\\"\"";
                case "java.lang.Class" -> value + ".getName() + \".class\"";
                default -> "String.valueOf(" + value + ")";
            };
        }
        if (type instanceof TypeInfo.ParameterizedType parameterized
                && "java.lang.Class".equals(parameterized.rawType().value())) {
            return value + ".getName() + \".class\"";
        }
        return "String.valueOf(" + value + ")";
    }

    /** One renderer per array member: a plain loop, so nothing here reads a value reflectively. */
    private String arrayRenderer(MethodInfo member) {
        var component = member.returnType() instanceof TypeInfo.ArrayType array
                ? array.componentType() : member.returnType();
        return "\n        private static String " + renderer(member) + "("
                + sourceName(member.returnType()) + " values) {\n"
                + "            var items = new java.util.ArrayList<String>();\n"
                + "            for (var value : values) items.add(" + scalarRendering(component, "value") + ");\n"
                + "            return \"{\" + String.join(\", \", items) + \"}\";\n"
                + "        }\n";
    }

    private String renderer(MethodInfo member) {
        return "render$" + member.name();
    }

    /** Whether the declaration keeps the type at runtime — the only ones the container ever sees. */
    private boolean keptAtRuntime(ClassInfo type) {
        return type.annotation(RETENTION)
                .map(retention -> retention.members().get("value"))
                .filter(value -> value instanceof AnnotationValue.EnumVal enumValue
                        && enumValue.enumType().equals(RETENTION_POLICY)
                        && "RUNTIME".equals(enumValue.constantName()))
                .isPresent();
    }

    // ---- values as Java expressions ----

    /** Whether every default of {@code type} can be written as a Java expression. */
    private boolean canRender(ClassInfo type, List<ClassInfo> rendered) {
        return members(type).stream()
                .allMatch(member -> member.defaultValue() == null
                        || renderable(member.defaultValue(), rendered));
    }

    private boolean renderable(AnnotationValue value, List<ClassInfo> rendered) {
        return switch (value) {
            // A nested annotation needs a literal class, so its type has to be rendered here too.
            case AnnotationValue.AnnotationVal nested -> rendered.stream()
                    .anyMatch(type -> type.name().equals(nested.annotation().name()));
            case AnnotationValue.ArrayVal array -> array.values().stream().allMatch(item -> renderable(item, rendered));
            default -> true;
        };
    }

    private String expression(AnnotationValue value, TypeInfo type, List<ClassInfo> rendered) {
        return switch (value) {
            case AnnotationValue.StringVal v -> quote(v.value());
            case AnnotationValue.BooleanVal v -> String.valueOf(v.value());
            case AnnotationValue.ByteVal v -> "(byte) " + v.value();
            case AnnotationValue.CharVal v -> charLiteral(v.value());
            case AnnotationValue.ShortVal v -> "(short) " + v.value();
            case AnnotationValue.IntVal v -> String.valueOf(v.value());
            case AnnotationValue.LongVal v -> v.value() + "L";
            case AnnotationValue.FloatVal v -> floatLiteral(v.value());
            case AnnotationValue.DoubleVal v -> doubleLiteral(v.value());
            case AnnotationValue.ClassVal v -> sourceName(v.className()) + ".class";
            case AnnotationValue.EnumVal v -> sourceName(v.enumType()) + "." + v.constantName();
            case AnnotationValue.AnnotationVal v -> nestedLiteral(v.annotation(), rendered);
            case AnnotationValue.ArrayVal v -> arrayExpression(v, type, rendered);
        };
    }

    private String nestedLiteral(AnnotationInfo annotation, List<ClassInfo> rendered) {
        var type = rendered.stream().filter(candidate -> candidate.name().equals(annotation.name())).findFirst();
        var members = type.map(this::members).orElse(List.of());
        var written = annotation.members().entrySet().stream()
                .map(entry -> {
                    var member = members.stream().filter(m -> m.name().equals(entry.getKey())).findFirst();
                    return "\"" + entry.getKey() + "\", "
                            + expression(entry.getValue(),
                                    member.map(MethodInfo::returnType).orElse(new TypeInfo.ClassType(DotName.of("java.lang.Object"))),
                                    rendered);
                })
                .collect(Collectors.joining(", "));
        return "new " + literalName(annotation.name()) + "(java.util.Map.of(" + written + "))";
    }

    private String arrayExpression(AnnotationValue.ArrayVal array, TypeInfo type, List<ClassInfo> rendered) {
        var component = type instanceof TypeInfo.ArrayType arrayType ? arrayType.componentType() : type;
        var items = array.values().stream()
                .map(item -> expression(item, component, rendered))
                .collect(Collectors.joining(", "));
        return "new " + erasure(component) + "[] {" + items + "}";
    }

    // ---- names and literals ----

    /**
     * The name source writes for {@code name}: its canonical name, so {@code app.Holder$Inner} is
     * written {@code app.Holder.Inner} and a top-level {@code app.A$B} keeps its {@code $}.
     */
    String sourceName(DotName name) {
        var value = name.value();
        if (value.startsWith("[")) {
            return sourceNameOfDescriptor(value);
        }
        return canonicalName(value);
    }

    /** The canonical name of the type whose binary name is {@code binaryName}. */
    private String canonicalName(String binaryName) {
        var canonical = canonicalNames.apply(binaryName);
        return canonical != null ? canonical : binaryName.replace('$', '.');
    }

    private String sourceNameOfDescriptor(String descriptor) {
        int dimensions = 0;
        while (dimensions < descriptor.length() && descriptor.charAt(dimensions) == '[') {
            dimensions++;
        }
        var element = descriptor.substring(dimensions);
        var name = switch (element.charAt(0)) {
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L' -> canonicalName(element.substring(1, element.length() - 1));
            default -> "java.lang.Object";
        };
        return name + "[]".repeat(dimensions);
    }

    /** The type as the declaration writes it — what a field, a parameter or an override must say. */
    String sourceName(TypeInfo type) {
        return switch (type) {
            case TypeInfo.ClassType classType -> sourceName(classType.name());
            case TypeInfo.ParameterizedType parameterized -> sourceName(parameterized.rawType())
                    + parameterized.typeArguments().stream().map(this::sourceName)
                            .collect(Collectors.joining(", ", "<", ">"));
            case TypeInfo.ArrayType array -> sourceName(array.componentType()) + "[]".repeat(array.dimensions());
            case TypeInfo.PrimitiveType primitive -> primitive.kind().name().toLowerCase(java.util.Locale.ROOT);
            case TypeInfo.WildcardType wildcard -> {
                if (wildcard.upperBound() != null) yield "? extends " + sourceName(wildcard.upperBound());
                if (wildcard.lowerBound() != null) yield "? super " + sourceName(wildcard.lowerBound());
                yield "?";
            }
            case TypeInfo.VoidType _ -> "void";
            default -> "java.lang.Object";
        };
    }

    /** The type without its arguments — what a {@code .class} literal and an array creation need. */
    private String erasure(TypeInfo type) {
        return switch (type) {
            case TypeInfo.ParameterizedType parameterized -> sourceName(parameterized.rawType());
            case TypeInfo.ArrayType array -> erasure(array.componentType()) + "[]".repeat(array.dimensions());
            default -> sourceName(type);
        };
    }

    /** The boxed form, for the cast of a value taken out of a {@code Map<String, Object>}. */
    private String boxed(TypeInfo type) {
        if (type instanceof TypeInfo.PrimitiveType primitive) {
            return switch (primitive.kind()) {
                case BOOLEAN -> "Boolean";
                case BYTE -> "Byte";
                case CHAR -> "Character";
                case SHORT -> "Short";
                case INT -> "Integer";
                case LONG -> "Long";
                case FLOAT -> "Float";
                case DOUBLE -> "Double";
            };
        }
        return sourceName(type);
    }

    private String classLiteral(TypeInfo type) {
        return erasure(type) + ".class";
    }

    private boolean isArray(TypeInfo type) {
        return type instanceof TypeInfo.ArrayType;
    }

    private boolean isParameterized(TypeInfo type) {
        return type instanceof TypeInfo.ParameterizedType
                || (type instanceof TypeInfo.ArrayType array && isParameterized(array.componentType()));
    }

    private String field(MethodInfo member) {
        // A member named `other`, `that` or `members` would shadow a local of the methods below.
        return member.name() + "$";
    }

    private String literalName(DotName annotationType) {
        return annotationType.simpleName().replace('$', '_') + "_Literal";
    }

    private String quote(String value) {
        var escaped = new StringBuilder("\"");
        for (var c : value.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.append('"').toString();
    }

    private String charLiteral(char value) {
        return switch (value) {
            case '\'' -> "'\\''";
            case '\\' -> "'\\\\'";
            case '\n' -> "'\\n'";
            case '\r' -> "'\\r'";
            case '\t' -> "'\\t'";
            default -> "'" + value + "'";
        };
    }

    private String floatLiteral(float value) {
        if (Float.isNaN(value)) return "Float.NaN";
        if (value == Float.POSITIVE_INFINITY) return "Float.POSITIVE_INFINITY";
        if (value == Float.NEGATIVE_INFINITY) return "Float.NEGATIVE_INFINITY";
        return value + "f";
    }

    private String doubleLiteral(double value) {
        if (Double.isNaN(value)) return "Double.NaN";
        if (value == Double.POSITIVE_INFINITY) return "Double.POSITIVE_INFINITY";
        if (value == Double.NEGATIVE_INFINITY) return "Double.NEGATIVE_INFINITY";
        return String.valueOf(value);
    }

}
