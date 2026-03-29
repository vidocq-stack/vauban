package fr.vidocq.vauban.core.bean.model;

import fr.vidocq.vauban.indexer.model.AnnotationInfo;
import fr.vidocq.vauban.indexer.model.AnnotationValue;
import fr.vidocq.vauban.indexer.model.DotName;

import java.util.Map;

/**
 * Represents a qualifier annotation instance with its member values.
 */
public record QualifierInstance(DotName annotationName, Map<String, AnnotationValue> members) {

    public QualifierInstance {
        members = Map.copyOf(members);
    }

    public static final DotName DEFAULT_NAME = DotName.of("jakarta.enterprise.inject.Default");
    public static final DotName ANY_NAME = DotName.of("jakarta.enterprise.inject.Any");
    public static final DotName NAMED_NAME = DotName.of("jakarta.inject.Named");

    public static final QualifierInstance DEFAULT = new QualifierInstance(DEFAULT_NAME, Map.of());
    public static final QualifierInstance ANY = new QualifierInstance(ANY_NAME, Map.of());

    public static QualifierInstance from(AnnotationInfo annotation) {
        return new QualifierInstance(annotation.name(), annotation.members());
    }

    public boolean isDefault() {
        return annotationName.equals(DEFAULT_NAME);
    }

    public boolean isAny() {
        return annotationName.equals(ANY_NAME);
    }
}
