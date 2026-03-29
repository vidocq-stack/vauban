package fr.vidocq.vauban.core.langmodel;

import fr.vidocq.vauban.indexer.VaubanIndex;
import fr.vidocq.vauban.indexer.model.ClassInfo;
import fr.vidocq.vauban.indexer.model.DotName;

import java.util.Objects;
import java.util.Optional;

/**
 * Provides index lookup for lang model implementations.
 */
public final class IndexLookup {

    private final VaubanIndex index;

    public IndexLookup(VaubanIndex index) {
        this.index = Objects.requireNonNull(index);
    }

    public VaubanIndex index() {
        return index;
    }

    public Optional<ClassInfo> getClass(DotName name) {
        return index.getClassByName(name);
    }

    public ClassInfo requireClass(DotName name) {
        return index.getClassByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Class not found in index: " + name));
    }
}
