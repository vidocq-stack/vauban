package fr.vidocq.vauban.core.bean.model;

import fr.vidocq.vauban.indexer.model.DotName;

/**
 * Unique bean identifier.
 */
public record BeanId(String value) {

    public static BeanId of(DotName className) {
        return new BeanId(className.value());
    }

    public static BeanId ofProducerMethod(DotName className, String methodName) {
        return new BeanId(className.value() + "#" + methodName);
    }

    public static BeanId ofProducerField(DotName className, String fieldName) {
        return new BeanId(className.value() + "." + fieldName);
    }
}
