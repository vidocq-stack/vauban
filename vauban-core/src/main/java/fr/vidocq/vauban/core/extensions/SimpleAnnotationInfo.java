package fr.vidocq.vauban.core.extensions;

import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.lang.annotation.Annotation;
import java.util.Map;

final class SimpleAnnotationInfo implements AnnotationInfo {

    private final String annotationName;
    private final Map<String, AnnotationMember> memberMap;

    SimpleAnnotationInfo(String annotationName, Map<String, AnnotationMember> memberMap) {
        this.annotationName = annotationName;
        this.memberMap = Map.copyOf(memberMap);
    }

    @Override
    public ClassInfo declaration() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String name() {
        return annotationName;
    }

    @Override
    public boolean hasMember(String name) {
        return memberMap.containsKey(name);
    }

    @Override
    public AnnotationMember member(String name) {
        return memberMap.get(name);
    }

    @Override
    public Map<String, AnnotationMember> members() {
        return memberMap;
    }

    @Override
    public String toString() {
        return "@" + annotationName;
    }
}
