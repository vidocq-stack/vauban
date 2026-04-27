package io.vidocq.vauban.core.langmodel;

import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

import java.lang.annotation.Annotation;
import java.util.Map;

public final class BuiltAnnotationInfo implements AnnotationInfo {

    private final Class<? extends Annotation> annotationType;
    private final Map<String, AnnotationMember> memberMap;

    public BuiltAnnotationInfo(Class<? extends Annotation> annotationType, Map<String, AnnotationMember> memberMap) {
        this.annotationType = annotationType;
        this.memberMap = Map.copyOf(memberMap);
    }

    @Override
    public ClassInfo declaration() {
        throw new UnsupportedOperationException(
                "declaration() not supported on built annotations (no index available)");
    }

    @Override
    public String name() {
        return annotationType.getName();
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

    public Class<? extends Annotation> annotationType() {
        return annotationType;
    }

    @Override
    public String toString() {
        return "@" + annotationType.getName();
    }
}
