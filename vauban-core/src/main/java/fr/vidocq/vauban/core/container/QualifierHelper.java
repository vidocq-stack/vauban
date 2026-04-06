package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.QualifierInstance;
import fr.vidocq.vauban.indexer.model.AnnotationValue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class QualifierHelper {

    private QualifierHelper() {}

    static Annotation[] extractParamQualifiers(Parameter param) {
        var quals = new ArrayList<Annotation>();
        boolean hasAnyAnnotation = false;
        for (var ann : param.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || ann.annotationType() == jakarta.enterprise.inject.Default.class
                    || ann.annotationType() == jakarta.enterprise.inject.Any.class
                    || ann.annotationType() == jakarta.inject.Named.class) {
                quals.add(ann);
                hasAnyAnnotation = true;
            }
        }
        if (!hasAnyAnnotation) {
            quals.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return quals.toArray(new Annotation[0]);
    }

    static Set<Annotation> collectQualifierSet(Annotation[] annotations) {
        var quals = new LinkedHashSet<Annotation>();
        for (var ann : annotations) {
            if (ann.annotationType() == jakarta.inject.Inject.class) continue;
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || ann.annotationType() == jakarta.enterprise.inject.Default.class
                    || ann.annotationType() == jakarta.enterprise.inject.Any.class) {
                quals.add(ann);
            }
        }
        if (quals.isEmpty()) {
            quals.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return quals;
    }

    static Annotation[] collectEventQualifiers(Annotation[] annotations) {
        var quals = new ArrayList<Annotation>();
        boolean hasExplicitQualifier = false;
        for (var ann : annotations) {
            if (ann.annotationType() == jakarta.inject.Inject.class) continue;
            if (ann.annotationType() == jakarta.enterprise.inject.Default.class) {
                quals.add(ann);
                continue;
            }
            if (ann.annotationType() == jakarta.enterprise.inject.Any.class) {
                quals.add(ann);
                hasExplicitQualifier = true;
                continue;
            }
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)) {
                quals.add(ann);
                hasExplicitQualifier = true;
            }
        }
        if (!hasExplicitQualifier) {
            boolean hasDefault = quals.stream().anyMatch(q -> q.annotationType() == jakarta.enterprise.inject.Default.class);
            if (!hasDefault) {
                quals.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
            }
        }
        boolean hasAny = quals.stream().anyMatch(q -> q.annotationType() == jakarta.enterprise.inject.Any.class);
        if (!hasAny) {
            quals.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
        }
        return quals.toArray(new Annotation[0]);
    }

    static Annotation[] extractFieldQualifiersWithEnhancement(Field field, BeanDescriptor descriptor) {
        if (descriptor != null) {
            for (var ip : descriptor.injectionPoints()) {
                if (ip.kind() != fr.vidocq.vauban.core.bean.model.InjectionPointInfo.InjectionKind.FIELD) continue;
                if (!ip.description().endsWith("." + field.getName())) continue;
                return qualifierInstancesToAnnotations(ip.qualifiers());
            }
        }
        return extractFieldQualifiers(field);
    }

    static Annotation[] qualifierInstancesToAnnotations(Set<QualifierInstance> qualifierInstances) {
        var annotations = new ArrayList<Annotation>();
        for (var qi : qualifierInstances) {
            var name = qi.annotationName().value();
            if (name.equals("jakarta.enterprise.inject.Default")) {
                annotations.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
            } else if (name.equals("jakarta.enterprise.inject.Any")) {
                annotations.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
            } else if (name.equals("jakarta.inject.Named")) {
                var nameValue = qi.members().get("value");
                var strValue = nameValue instanceof AnnotationValue.StringVal sv
                        ? sv.value() : "";
                annotations.add(jakarta.enterprise.inject.literal.NamedLiteral.of(strValue));
            } else {
                try {
                    @SuppressWarnings("unchecked")
                    var annType = (Class<? extends Annotation>)
                            Thread.currentThread().getContextClassLoader().loadClass(name);
                    annotations.add(createQualifierAnnotation(annType, qi.members()));
                } catch (ClassNotFoundException e) {
                    // Skip unloadable qualifier
                }
            }
        }
        if (annotations.isEmpty()) {
            annotations.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return annotations.toArray(new Annotation[0]);
    }

    @SuppressWarnings("unchecked")
    static <A extends Annotation> A createQualifierAnnotation(
            Class<A> annType, Map<String, AnnotationValue> members) {
        return (A) java.lang.reflect.Proxy.newProxyInstance(
                annType.getClassLoader(),
                new Class<?>[]{annType},
                (proxy, method, args) -> {
                    if ("annotationType".equals(method.getName())) return annType;
                    if ("toString".equals(method.getName())) return "@" + annType.getName();
                    if ("hashCode".equals(method.getName())) return 0;
                    if ("equals".equals(method.getName())) {
                        if (args[0] == null) return false;
                        if (!annType.isInstance(args[0])) return false;
                        for (var m : annType.getDeclaredMethods()) {
                            var expected = members.get(m.getName());
                            var actual = m.invoke(args[0]);
                            if (expected != null) {
                                var expectedVal = annotationValueToObject(expected);
                                if (!Objects.deepEquals(expectedVal, actual)) return false;
                            }
                        }
                        return true;
                    }
                    var memberVal = members.get(method.getName());
                    if (memberVal != null) {
                        return annotationValueToObject(memberVal);
                    }
                    return method.getDefaultValue();
                });
    }

    static Object annotationValueToObject(AnnotationValue value) {
        return switch (value) {
            case AnnotationValue.StringVal sv -> sv.value();
            case AnnotationValue.BooleanVal bv -> bv.value();
            case AnnotationValue.IntVal iv -> iv.value();
            case AnnotationValue.LongVal lv -> lv.value();
            case AnnotationValue.DoubleVal dv -> dv.value();
            case AnnotationValue.FloatVal fv -> fv.value();
            default -> null;
        };
    }

    static Annotation[] extractFieldQualifiers(Field field) {
        var qualifiers = new ArrayList<Annotation>();
        boolean hasAnyAnnotation = false;
        for (var ann : field.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)
                    || ann.annotationType() == jakarta.enterprise.inject.Default.class
                    || ann.annotationType() == jakarta.enterprise.inject.Any.class
                    || ann.annotationType() == jakarta.inject.Named.class) {
                qualifiers.add(ann);
                hasAnyAnnotation = true;
            }
        }
        if (!hasAnyAnnotation) {
            qualifiers.add(jakarta.enterprise.inject.Default.Literal.INSTANCE);
        }
        return qualifiers.toArray(new Annotation[0]);
    }
}
