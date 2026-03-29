package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.bean.model.QualifierInstance;
import fr.vidocq.vauban.indexer.model.AnnotationValue;

import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Utility methods for converting {@link QualifierInstance} descriptors
 * to runtime {@link Annotation} instances via dynamic proxies.
 */
public final class QualifierUtils {

    private QualifierUtils() {}

    /**
     * Converts a set of {@link QualifierInstance} to runtime {@link Annotation} instances.
     * Uses CDI Literal instances for built-in qualifiers (@Default, @Any, @Named).
     *
     * @param qualifiers the qualifier descriptors
     * @param beanName   the bean name (used for @Named value), may be null
     */
    public static Set<Annotation> toAnnotations(Set<QualifierInstance> qualifiers, String beanName) {
        var result = new LinkedHashSet<Annotation>();
        for (var qi : qualifiers) {
            var ann = toAnnotation(qi, beanName);
            if (ann != null) result.add(ann);
        }
        return result;
    }

    /**
     * Converts a single {@link QualifierInstance} to a runtime {@link Annotation}.
     */
    public static Annotation toAnnotation(QualifierInstance qi, String beanName) {
        var annName = qi.annotationName().value();
        return switch (annName) {
            case "jakarta.enterprise.inject.Default" -> jakarta.enterprise.inject.Default.Literal.INSTANCE;
            case "jakarta.enterprise.inject.Any" -> jakarta.enterprise.inject.Any.Literal.INSTANCE;
            case "jakarta.inject.Named" -> {
                var members = new java.util.LinkedHashMap<>(qi.members());
                var nameVal = members.get("value");
                if ((nameVal == null || (nameVal instanceof AnnotationValue.StringVal sv && sv.value().isEmpty()))
                        && beanName != null) {
                    members.put("value", new AnnotationValue.StringVal(beanName));
                }
                yield createAnnotationInstance(jakarta.inject.Named.class, members);
            }
            default -> {
                try {
                    @SuppressWarnings("unchecked")
                    var annClass = (Class<? extends Annotation>) Class.forName(annName);
                    yield createAnnotationInstance(annClass, qi.members());
                } catch (ClassNotFoundException e) {
                    yield null;
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static Annotation createAnnotationInstance(Class<? extends Annotation> annType,
            Map<String, AnnotationValue> members) {
        return (Annotation) java.lang.reflect.Proxy.newProxyInstance(
            annType.getClassLoader(),
            new Class<?>[]{annType},
            (proxy, method, args) -> {
                var methodName = method.getName();
                return switch (methodName) {
                    case "annotationType" -> annType;
                    case "hashCode" -> computeAnnotationHashCode(annType, members);
                    case "equals" -> {
                        if (args[0] instanceof Annotation other) {
                            yield annType.equals(other.annotationType())
                                && membersEqual(annType, members, other);
                        }
                        yield false;
                    }
                    case "toString" -> "@" + annType.getName();
                    default -> {
                        var memberValue = members.get(methodName);
                        if (memberValue != null) {
                            yield convertAnnotationValue(memberValue);
                        }
                        var defaultValue = method.getDefaultValue();
                        if (defaultValue != null) yield defaultValue;
                        yield null;
                    }
                };
            });
    }

    static int computeAnnotationHashCode(Class<? extends Annotation> annType,
            Map<String, AnnotationValue> members) {
        int hash = 0;
        for (var m : annType.getDeclaredMethods()) {
            if (m.isAnnotationPresent(jakarta.enterprise.util.Nonbinding.class)) continue;
            var memberValue = members.get(m.getName());
            Object value;
            if (memberValue != null) {
                value = convertAnnotationValue(memberValue);
            } else {
                value = m.getDefaultValue();
            }
            if (value != null) {
                hash += (127 * m.getName().hashCode()) ^ value.hashCode();
            }
        }
        return hash;
    }

    static boolean membersEqual(Class<? extends Annotation> annType,
            Map<String, AnnotationValue> members, Annotation other) {
        try {
            for (var m : annType.getDeclaredMethods()) {
                if (m.isAnnotationPresent(jakarta.enterprise.util.Nonbinding.class)) continue;
                var memberValue = members.get(m.getName());
                Object thisVal;
                if (memberValue != null) {
                    thisVal = convertAnnotationValue(memberValue);
                } else {
                    thisVal = m.getDefaultValue();
                }
                Object otherVal = m.invoke(other);
                if (!Objects.deepEquals(thisVal, otherVal)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Object convertAnnotationValue(AnnotationValue value) {
        return switch (value) {
            case AnnotationValue.StringVal v -> v.value();
            case AnnotationValue.IntVal v -> v.value();
            case AnnotationValue.BooleanVal v -> v.value();
            case AnnotationValue.LongVal v -> v.value();
            case AnnotationValue.FloatVal v -> v.value();
            case AnnotationValue.DoubleVal v -> v.value();
            case AnnotationValue.ByteVal v -> v.value();
            case AnnotationValue.CharVal v -> v.value();
            case AnnotationValue.ShortVal v -> v.value();
            case AnnotationValue.ClassVal v -> {
                try { yield Class.forName(v.className().value()); }
                catch (ClassNotFoundException e) { yield Object.class; }
            }
            case AnnotationValue.EnumVal v -> {
                try {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    var enumVal = Enum.valueOf((Class) Class.forName(v.enumType().value()), v.constantName());
                    yield enumVal;
                } catch (Exception e) { yield null; }
            }
            case AnnotationValue.AnnotationVal v -> null;
            case AnnotationValue.ArrayVal v -> v.values().stream()
                    .map(QualifierUtils::convertAnnotationValue)
                    .toArray();
        };
    }
}
