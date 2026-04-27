package io.vidocq.vauban.indexer;

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class IndexBuilder {

    private final Map<DotName, ClassInfo> classes = new LinkedHashMap<>();

    public IndexBuilder add(ClassInfo classInfo) {
        Objects.requireNonNull(classInfo);
        classes.put(classInfo.name(), classInfo);
        return this;
    }

    public IndexBuilder addAll(Collection<ClassInfo> classInfos) {
        classInfos.forEach(this::add);
        return this;
    }

    public boolean contains(DotName name) {
        return classes.containsKey(name);
    }

    public int size() {
        return classes.size();
    }

    public VaubanIndex build() {
        return new VaubanIndex(classes);
    }
}
