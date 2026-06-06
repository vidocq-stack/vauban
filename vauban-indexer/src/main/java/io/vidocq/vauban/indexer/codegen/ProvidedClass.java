package io.vidocq.vauban.indexer.codegen;

import io.vidocq.vauban.indexer.model.ClassInfo;

/**
 * A caller-supplied descriptor for one managed class that may receive an in-module provider entry.
 *
 * <p>The caller (APT processor or Maven-plugin generator) decides which classes are eligible
 * (managed, in-module, top-level) and whether the class is instantiable in-module.  The
 * {@link ComponentCollector} only extracts per-class descriptors and groups them by package.
 *
 * @param fqn           fully-qualified class name (no {@code $} — callers must filter nested types)
 * @param classInfo     the indexer's class descriptor for this class
 * @param instantiable  {@code true} when the collector should attempt to determine constructor
 *                      parameters via {@link ComponentCollector#instantiableCtorParams}; the caller
 *                      sets this based on its own eligibility rules (e.g. top-level type check in
 *                      APT, {@code classFileExists} in the plugin)
 */
public record ProvidedClass(String fqn, ClassInfo classInfo, boolean instantiable) {}
