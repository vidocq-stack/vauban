package fr.vidocq.vauban.indexer;

import fr.vidocq.vauban.indexer.model.ClassInfo;
import fr.vidocq.vauban.indexer.model.DotName;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public final class VaubanIndex {

    private final Map<DotName, ClassInfo> classes;

    VaubanIndex(Map<DotName, ClassInfo> classes) {
        this.classes = Map.copyOf(classes);
    }

    public Optional<ClassInfo> getClassByName(DotName name) {
        return Optional.ofNullable(classes.get(name));
    }

    public Collection<ClassInfo> getKnownClasses() {
        return classes.values();
    }

    public Collection<ClassInfo> getClassesWithAnnotation(DotName annotationName) {
        return classes.values().stream().filter(c -> c.hasAnnotation(annotationName)).toList();
    }

    public Collection<ClassInfo> getImplementors(DotName interfaceName) {
        return classes.values().stream().filter(c -> c.interfaces().contains(interfaceName)).toList();
    }

    public Collection<ClassInfo> getSubclasses(DotName className) {
        return classes.values().stream().filter(c -> className.equals(c.superName())).toList();
    }

    public boolean containsClass(DotName name) {
        return classes.containsKey(name);
    }

    public int size() {
        return classes.size();
    }
}
