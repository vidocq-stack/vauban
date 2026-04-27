package io.vidocq.vauban.indexer.model;

import java.util.Map;

public record AnnotationInfo(DotName name, Map<String, AnnotationValue> members) {

    public AnnotationInfo {
        members = Map.copyOf(members);
    }

    public AnnotationValue member(String name) {
        return members.get(name);
    }

    public boolean hasMember(String name) {
        return members.containsKey(name);
    }
}
