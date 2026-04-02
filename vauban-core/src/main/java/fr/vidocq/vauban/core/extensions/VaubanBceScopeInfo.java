package fr.vidocq.vauban.core.extensions;

import fr.vidocq.vauban.core.langmodel.IndexLookup;
import fr.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import fr.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.declarations.ClassInfo;

/**
 * BCE ScopeInfo adapter for Vauban's internal ScopeInfo.
 */
public final class VaubanBceScopeInfo implements jakarta.enterprise.inject.build.compatible.spi.ScopeInfo {

    private final fr.vidocq.vauban.core.bean.model.ScopeInfo scope;
    private final IndexLookup lookup;

    public VaubanBceScopeInfo(fr.vidocq.vauban.core.bean.model.ScopeInfo scope, IndexLookup lookup) {
        this.scope = scope;
        this.lookup = lookup;
    }

    @Override
    public ClassInfo annotation() {
        var indexClass = lookup.getClass(scope.annotationName()).orElse(null);
        if (indexClass != null) {
            return new VaubanClassInfo(indexClass, lookup);
        }
        // Fallback: create a minimal ClassInfo from the annotation name
        return null;
    }

    @Override
    public boolean isNormal() {
        return scope.isNormal();
    }
}
