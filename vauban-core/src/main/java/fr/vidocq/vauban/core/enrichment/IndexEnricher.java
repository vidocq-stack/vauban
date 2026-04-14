package fr.vidocq.vauban.core.enrichment;

import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.VaubanIndex;
import fr.vidocq.vauban.indexer.model.AnnotationInfo;
import fr.vidocq.vauban.indexer.model.ClassInfo;
import fr.vidocq.vauban.indexer.model.DotName;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * Enriches a {@link VaubanIndex} by injecting CDI scope annotations into classes
 * that match enrichment rules defined in {@code vauban-apt.properties}.
 *
 * <p>For example, given a rule {@code enrich.jakarta.ws.rs.Path=jakarta.enterprise.context.RequestScoped},
 * any class annotated with {@code @Path} that has no CDI scope annotation will receive
 * a synthetic {@code @RequestScoped} annotation in the index. This causes
 * {@code BeanDiscovery} to treat it as a CDI managed bean without any changes
 * to the discovery logic itself.</p>
 */
public final class IndexEnricher {

    private static final Set<DotName> CDI_SCOPE_ANNOTATIONS = Set.of(
            DotName.of("jakarta.enterprise.context.ApplicationScoped"),
            DotName.of("jakarta.enterprise.context.RequestScoped"),
            DotName.of("jakarta.enterprise.context.Dependent"),
            DotName.of("jakarta.enterprise.context.SessionScoped"),
            DotName.of("jakarta.enterprise.context.ConversationScoped"),
            DotName.of("jakarta.inject.Singleton")
    );

    private IndexEnricher() {}

    /**
     * Enriches the index by adding scope annotations to classes that match
     * enrichment rules but have no CDI scope.
     *
     * @param index  the original index
     * @param config the enrichment configuration
     * @return a new index with enriched classes, or the original if no changes were needed
     */
    public static VaubanIndex enrich(VaubanIndex index, EnrichmentConfig config) {
        if (config.rules().isEmpty()) return index;

        var builder = new IndexBuilder();
        boolean modified = false;

        for (var classInfo : index.getKnownClasses()) {
            var enriched = tryEnrich(classInfo, config);
            if (enriched != null) {
                builder.add(enriched);
                modified = true;
            } else {
                builder.add(classInfo);
            }
        }

        return modified ? builder.build() : index;
    }

    private static ClassInfo tryEnrich(ClassInfo classInfo, EnrichmentConfig config) {
        // Skip if already has a CDI scope
        if (hasCdiScope(classInfo)) return null;

        // Check each trigger annotation
        for (var rule : config.rules()) {
            if (classInfo.hasAnnotation(rule.triggerAnnotation())) {
                // Add the scope annotation
                var newAnnotations = new ArrayList<>(classInfo.annotations());
                newAnnotations.add(new AnnotationInfo(rule.targetScope().annotationName(), Map.of()));
                return new ClassInfo(
                        classInfo.name(),
                        classInfo.superName(),
                        classInfo.interfaces(),
                        classInfo.accessFlags(),
                        classInfo.fields(),
                        classInfo.methods(),
                        newAnnotations,
                        classInfo.kind()
                );
            }
        }

        return null;
    }

    private static boolean hasCdiScope(ClassInfo classInfo) {
        for (var annotation : classInfo.annotations()) {
            if (CDI_SCOPE_ANNOTATIONS.contains(annotation.name())) return true;
        }
        return false;
    }
}
