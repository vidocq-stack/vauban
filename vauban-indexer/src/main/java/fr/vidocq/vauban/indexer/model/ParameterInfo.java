package fr.vidocq.vauban.indexer.model;

import java.util.List;

public record ParameterInfo(String name, TypeInfo type, List<AnnotationInfo> annotations) {

    public ParameterInfo {
        annotations = List.copyOf(annotations);
    }
}
