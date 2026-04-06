package fr.vidocq.vauban.maven.generate;

import java.util.List;

/**
 * Result of the build-time CDI bean discovery and generation.
 *
 * @param discoveredBeanClasses fully-qualified names of discovered CDI beans
 * @param warnings non-fatal issues encountered during generation
 */
public record GenerationResult(
        List<String> discoveredBeanClasses,
        List<String> warnings
) {
    public GenerationResult {
        discoveredBeanClasses = List.copyOf(discoveredBeanClasses);
        warnings = List.copyOf(warnings);
    }
}
