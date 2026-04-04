package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.Parameters;

import java.lang.reflect.Array;
import java.util.Map;

/**
 * Simple implementation of {@link Parameters} backed by a Map.
 */
public final class VaubanParameters implements Parameters {

    private final Map<String, Object> params;

    public VaubanParameters(Map<String, Object> params) {
        this.params = Map.copyOf(params);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        var value = params.get(key);
        if (value == null) {
            return null;
        }
        return convertIfNeeded(value, type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type, T defaultValue) {
        var value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        return convertIfNeeded(value, type);
    }

    @SuppressWarnings("unchecked")
    private <T> T convertIfNeeded(Object value, Class<T> type) {
        // Handle array type mismatch (e.g., InvokerInfo[] → Invoker[])
        if (type.isArray() && value.getClass().isArray() && !type.isInstance(value)) {
            var componentType = type.getComponentType();
            int length = Array.getLength(value);
            var newArray = Array.newInstance(componentType, length);
            for (int i = 0; i < length; i++) {
                Array.set(newArray, i, convertIfNeeded(Array.get(value, i), componentType));
            }
            return (T) newArray;
        }
        // Convert AnnotationInfo to Annotation proxy when requested type is an annotation
        if (type.isAnnotation() && value instanceof jakarta.enterprise.lang.model.AnnotationInfo annInfo) {
            return (T) createAnnotationProxy(type, annInfo);
        }
        return (T) value;
    }

    @SuppressWarnings("unchecked")
    private static <A extends java.lang.annotation.Annotation> A createAnnotationProxy(Class<?> type, jakarta.enterprise.lang.model.AnnotationInfo annInfo) {
        return (A) java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if ("annotationType".equals(method.getName())) return type;
                    if ("hashCode".equals(method.getName())) return 0;
                    if ("equals".equals(method.getName())) return false;
                    if ("toString".equals(method.getName())) return annInfo.toString();

                    // Look up member value by method name
                    var member = annInfo.hasMember(method.getName()) ? annInfo.member(method.getName()) : null;
                    if (member != null) {
                        return convertMemberValue(member, method.getReturnType());
                    }
                    // Try default value
                    var defaultValue = method.getDefaultValue();
                    if (defaultValue != null) return defaultValue;
                    return null;
                });
    }

    private static Object convertMemberValue(jakarta.enterprise.lang.model.AnnotationMember member, Class<?> returnType) {
        if (member.isBoolean()) return member.asBoolean();
        if (member.isByte()) return member.asByte();
        if (member.isShort()) return member.asShort();
        if (member.isInt()) return member.asInt();
        if (member.isLong()) return member.asLong();
        if (member.isFloat()) return member.asFloat();
        if (member.isDouble()) return member.asDouble();
        if (member.isChar()) return member.asChar();
        if (returnType.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            var enumValue = member.asEnum((Class) returnType);
            return enumValue;
        }
        if (returnType == Class.class) {
            try {
                if (member.isClass()) {
                    var type = member.asType();
                    if (type instanceof jakarta.enterprise.lang.model.types.ClassType ct) {
                        return Class.forName(ct.declaration().name());
                    }
                } else if (member.isString()) {
                    return Class.forName(member.asString());
                }
                return Object.class;
            } catch (Exception e) {
                return Object.class;
            }
        }
        if (member.isString()) return member.asString();
        if (returnType.isAnnotation()) {
            return createAnnotationProxy(returnType, member.asNestedAnnotation());
        }
        if (member.isArray() && returnType.isArray()) {
            var elements = member.asArray();
            var componentType = returnType.getComponentType();
            var array = java.lang.reflect.Array.newInstance(componentType, elements.size());
            for (int i = 0; i < elements.size(); i++) {
                java.lang.reflect.Array.set(array, i, convertMemberValue(elements.get(i), componentType));
            }
            return array;
        }
        return null;
    }
}
