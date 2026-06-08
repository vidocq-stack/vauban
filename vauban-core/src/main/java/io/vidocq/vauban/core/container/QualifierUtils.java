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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.indexer.model.AnnotationValue;

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
     */
    public static Set<Annotation> toAnnotations(Set<QualifierInstance> qualifiers, String beanName) {
        return toAnnotations(qualifiers, beanName, null);
    }

    public static Set<Annotation> toAnnotations(Set<QualifierInstance> qualifiers, String beanName,
            ClassLoader cl) {
        var result = new LinkedHashSet<Annotation>();
        for (var qi : qualifiers) {
            var ann = toAnnotation(qi, beanName, cl);
            if (ann != null) result.add(ann);
        }
        return result;
    }

    public static Annotation toAnnotation(QualifierInstance qi, String beanName) {
        return toAnnotation(qi, beanName, null);
    }

    /**
     * Converts a single {@link QualifierInstance} to a runtime {@link Annotation}.
     */
    public static Annotation toAnnotation(QualifierInstance qi, String beanName, ClassLoader cl) {
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
                yield createAnnotationInstance(jakarta.inject.Named.class, members, cl);
            }
            default -> {
                try {
                    @SuppressWarnings("unchecked")
                    var annClass = (Class<? extends Annotation>) (cl != null
                            ? Class.forName(annName, true, cl) : Class.forName(annName));
                    yield createAnnotationInstance(annClass, qi.members(), cl);
                } catch (ClassNotFoundException e) {
                    yield null;
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static Annotation createAnnotationInstance(Class<? extends Annotation> annType,
            Map<String, AnnotationValue> members) {
        return createAnnotationInstance(annType, members, null);
    }

    @SuppressWarnings("unchecked")
    public static Annotation createAnnotationInstance(Class<? extends Annotation> annType,
            Map<String, AnnotationValue> members, ClassLoader cl) {
        return (Annotation) java.lang.reflect.Proxy.newProxyInstance(
            annType.getClassLoader(),
            new Class<?>[]{annType},
            (proxy, method, args) -> {
                var methodName = method.getName();
                return switch (methodName) {
                    case "annotationType" -> annType;
                    case "hashCode" -> computeAnnotationHashCode(annType, members, cl);
                    case "equals" -> {
                        if (args[0] instanceof Annotation other) {
                            yield annType.equals(other.annotationType())
                                && membersEqual(annType, members, other, cl);
                        }
                        yield false;
                    }
                    case "toString" -> "@" + annType.getName();
                    default -> {
                        var memberValue = members.get(methodName);
                        if (memberValue != null) {
                            yield convertAnnotationValue(memberValue, cl);
                        }
                        var defaultValue = method.getDefaultValue();
                        if (defaultValue != null) yield defaultValue;
                        yield null;
                    }
                };
            });
    }

    static int computeAnnotationHashCode(Class<? extends Annotation> annType,
            Map<String, AnnotationValue> members, ClassLoader cl) {
        int hash = 0;
        for (var m : annType.getDeclaredMethods()) {
            if (m.isAnnotationPresent(jakarta.enterprise.util.Nonbinding.class)) continue;
            var memberValue = members.get(m.getName());
            Object value;
            if (memberValue != null) {
                value = convertAnnotationValue(memberValue, cl);
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
            Map<String, AnnotationValue> members, Annotation other, ClassLoader cl) {
        try {
            for (var m : annType.getDeclaredMethods()) {
                if (m.isAnnotationPresent(jakarta.enterprise.util.Nonbinding.class)) continue;
                var memberValue = members.get(m.getName());
                Object thisVal;
                if (memberValue != null) {
                    thisVal = convertAnnotationValue(memberValue, cl);
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

    static Object convertAnnotationValue(AnnotationValue value, ClassLoader cl) {
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
                try {
                    yield cl != null ? Class.forName(v.className().value(), true, cl)
                            : Class.forName(v.className().value());
                } catch (ClassNotFoundException e) { yield Object.class; }
            }
            case AnnotationValue.EnumVal v -> {
                try {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    var enumVal = Enum.valueOf((Class) (cl != null
                            ? Class.forName(v.enumType().value(), true, cl)
                            : Class.forName(v.enumType().value())), v.constantName());
                    yield enumVal;
                } catch (Exception e) { yield null; }
            }
            case AnnotationValue.AnnotationVal v -> null;
            case AnnotationValue.ArrayVal v -> v.values().stream()
                    .map(av -> convertAnnotationValue(av, cl))
                    .toArray();
        };
    }
}
