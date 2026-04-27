package io.vidocq.vauban.maven.generate;

import java.util.List;

/**
 * Result of the build-time CDI bean discovery and code generation.
 *
 * @param discoveredBeanClasses fully-qualified names of discovered CDI beans
 * @param generatedProxies      class names of generated client proxies (normal-scoped beans)
 * @param generatedInterceptors class names of generated interceptor subclasses
 * @param warnings              non-fatal issues encountered during generation
 */
public record GenerationResult(
        List<String> discoveredBeanClasses,
        List<String> generatedProxies,
        List<String> generatedInterceptors,
        List<String> warnings
) {
    public GenerationResult {
        discoveredBeanClasses = List.copyOf(discoveredBeanClasses);
        generatedProxies = List.copyOf(generatedProxies);
        generatedInterceptors = List.copyOf(generatedInterceptors);
        warnings = List.copyOf(warnings);
    }
}
