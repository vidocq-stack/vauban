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
            throw new IllegalArgumentException("No parameter with key: " + key);
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
        // Java arrays are not covariant for casting, so we need to copy
        if (type.isArray() && value.getClass().isArray() && !type.isInstance(value)) {
            var componentType = type.getComponentType();
            int length = Array.getLength(value);
            var newArray = Array.newInstance(componentType, length);
            for (int i = 0; i < length; i++) {
                Array.set(newArray, i, Array.get(value, i));
            }
            return (T) newArray;
        }
        return (T) value;
    }
}
