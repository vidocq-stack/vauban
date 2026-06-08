/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.indexer.codegen;

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Pure-function collector that maps a list of eligible managed classes (supplied by the caller)
 * onto a list of {@link PackageProvider} descriptors, one per package.
 *
 * <p>This class has <strong>no dependency</strong> on {@code javax.lang.model}, Maven, or any APT
 * API — it operates solely on the indexer model ({@code io.vidocq.vauban.indexer.model}).  Both
 * the APT path ({@code VaubanProcessor}) and the Maven-plugin path ({@code VaubanGenerator}) call
 * this class after performing their own eligibility filtering (top-level-type check, class-file
 * existence check, etc.).
 *
 * <p>Because this class cannot log, all diagnostic messages are appended to the caller-supplied
 * {@code warningsOut} list.
 *
 * <h2>Erasure unification (intentional behavior refinement)</h2>
 * The unified {@link #erasure(TypeInfo)} handles {@code TypeVariable} and {@code WildcardType}
 * by resolving to the first bound / upper bound (or {@code "java.lang.Object"} when there is none)
 * instead of returning {@code null}.  This matches the richer behavior that the Maven-plugin path
 * ({@code VaubanGenerator#fieldErasure}) already used, and makes it available to the APT path too:
 * type-variable- or wildcard-typed fields and parameters are now included in the generated provider
 * rather than silently skipped.
 */
public final class ComponentCollector {

    // CDI annotation names used to identify qualifying fields and methods.
    private static final String INJECT        = "jakarta.inject.Inject";
    private static final String PRODUCES      = "jakarta.enterprise.inject.Produces";
    private static final String POST_CONSTRUCT = "jakarta.annotation.PostConstruct";
    private static final String PRE_DESTROY   = "jakarta.annotation.PreDestroy";
    private static final String OBSERVES      = "jakarta.enterprise.event.Observes";
    private static final String OBSERVES_ASYNC = "jakarta.enterprise.event.ObservesAsync";
    private static final String DISPOSES      = "jakarta.enterprise.inject.Disposes";

    private ComponentCollector() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Computes the unified type erasure for use in generated {@code new}, {@code putfield}, and
     * {@code invokevirtual} sites.
     *
     * <ul>
     *   <li>{@link TypeInfo.ClassType} → the class's FQN.</li>
     *   <li>{@link TypeInfo.ParameterizedType} → the raw type's FQN.</li>
     *   <li>{@link TypeInfo.ArrayType} → component erasure + {@code "[]"} per dimension
     *       (or {@code null} if the component erasure is {@code null}).</li>
     *   <li>{@link TypeInfo.TypeVariable} → first bound's erasure, or {@code "java.lang.Object"}
     *       when there are no bounds.</li>
     *   <li>{@link TypeInfo.WildcardType} → upper-bound's erasure, or {@code "java.lang.Object"}
     *       when the upper bound is absent.</li>
     *   <li>{@link TypeInfo.PrimitiveType} → {@code null} (primitives cannot be injected).</li>
     *   <li>{@link TypeInfo.VoidType} → {@code null}.</li>
     * </ul>
     */
    public static String erasure(TypeInfo t) {
        return switch (t) {
            case TypeInfo.ClassType c -> c.name().value();
            case TypeInfo.ParameterizedType p -> p.rawType().value();
            case TypeInfo.ArrayType a -> {
                var comp = erasure(a.componentType());
                yield comp == null ? null : comp + "[]".repeat(a.dimensions());
            }
            case TypeInfo.TypeVariable tv ->
                    tv.bounds().isEmpty() ? "java.lang.Object" : erasure(tv.bounds().getFirst());
            case TypeInfo.WildcardType wt ->
                    wt.upperBound() != null ? erasure(wt.upperBound()) : "java.lang.Object";
            case TypeInfo.PrimitiveType ignored -> null;
            case TypeInfo.VoidType ignored -> null;
        };
    }

    /**
     * Determines whether the class can be instantiated in-module by the generated provider, and
     * if so returns the erased parameter types of the selected constructor.
     *
     * <p>Rules (mirroring CDI bean-discovery constructor selection):
     * <ol>
     *   <li>Abstract classes → {@link Optional#empty()}.</li>
     *   <li>No declared constructors → implicit default constructor → empty list.</li>
     *   <li>A non-private no-arg constructor → empty list (simplest path).</li>
     *   <li>Otherwise the {@code @Inject}-annotated constructor, or the sole declared constructor
     *       when there is exactly one — if non-private and all parameters have a non-null
     *       erasure → the erasure list.  Otherwise {@link Optional#empty()}.</li>
     * </ol>
     *
     * @param ci the class to examine
     * @return the constructor parameter erasures, or empty if the class is not in-module-instantiable
     */
    public static Optional<List<String>> instantiableCtorParams(ClassInfo ci) {
        if (ci.isAbstract()) return Optional.empty();
        var ctors = ci.methods().stream()
                .filter(MethodInfo::isConstructor)
                .toList();
        if (ctors.isEmpty()) return Optional.of(List.of()); // implicit default ctor
        if (ctors.stream().anyMatch(c -> c.parameters().isEmpty() && !c.isPrivate())) {
            return Optional.of(List.of()); // prefer the simplest path
        }
        var injected = ctors.stream()
                .filter(m -> m.annotations().stream()
                        .anyMatch(a -> INJECT.equals(a.name().value())))
                .findFirst();
        if (injected.isEmpty() && ctors.size() == 1) {
            injected = Optional.of(ctors.get(0));
        }
        if (injected.isEmpty() || injected.get().isPrivate()) return Optional.empty();
        var casts = new ArrayList<String>();
        for (var p : injected.get().parameters()) {
            var cast = erasure(p.type());
            if (cast == null) return Optional.empty();
            casts.add(cast);
        }
        return Optional.of(List.copyOf(casts));
    }

    /**
     * Collects non-private, non-static {@code @Inject} instance fields from {@code classInfo}.
     *
     * <p>Private fields are skipped; a warning is appended to {@code warningsOut}:
     * {@code "Field injection for private field <fqn>#<name> still needs opens to
     * io.vidocq.vauban.core"}.  Fields whose erasure is {@code null} are silently skipped.
     *
     * @param classInfo   the class to examine
     * @param warningsOut mutable list to accumulate diagnostic messages (never {@code null})
     * @return the collected field injection descriptors
     */
    public static List<FieldInject> collectFields(ClassInfo classInfo, List<String> warningsOut) {
        var beanFqn = classInfo.name().value();
        var result = new ArrayList<FieldInject>();
        for (FieldInfo field : classInfo.fields()) {
            boolean isInject = field.annotations().stream()
                    .anyMatch(a -> INJECT.equals(a.name().value()));
            if (!isInject) continue;
            if (field.isStatic()) continue;
            if (field.isPrivate()) {
                warningsOut.add("Field injection for private field " + beanFqn + "#" + field.name()
                        + " still needs opens to io.vidocq.vauban.core");
                continue;
            }
            var erasure = erasure(field.type());
            if (erasure == null) continue;
            result.add(new FieldInject(beanFqn, field.name(), erasure));
        }
        return result;
    }

    /**
     * Collects CDI lifecycle / producer / observer methods from {@code classInfo} that the
     * generated provider can invoke in-module without reflection.
     *
     * <p>A method qualifies when it (or one of its parameters) carries one of the following
     * annotations: {@code @Produces}, {@code @PostConstruct}, {@code @PreDestroy},
     * {@code @Inject} (initializer), {@code @Observes}, {@code @ObservesAsync}, {@code @Disposes}.
     *
     * <p>Methods are skipped (with a warning) when:
     * <ul>
     *   <li>they are constructors, static initialisers, private, or abstract;</li>
     *   <li>any parameter erasure is {@code null} or contains {@code '$'} (nested type);</li>
     *   <li>the return type is a primitive (non-void).</li>
     * </ul>
     *
     * @param classInfo   the class to examine
     * @param warningsOut mutable list to accumulate diagnostic messages (never {@code null})
     * @return the collected method invocation descriptors
     */
    public static List<MethodInvoke> collectMethods(ClassInfo classInfo, List<String> warningsOut) {
        var beanFqn = classInfo.name().value();
        var result = new ArrayList<MethodInvoke>();
        for (MethodInfo m : classInfo.methods()) {
            if (m.isConstructor() || m.isStaticInitializer()) continue;
            if (m.isPrivate() || m.isAbstract()) continue;

            boolean qualifies = m.annotations().stream().anyMatch(a -> {
                var n = a.name().value();
                return PRODUCES.equals(n) || POST_CONSTRUCT.equals(n)
                        || PRE_DESTROY.equals(n) || INJECT.equals(n);
            });
            if (!qualifies) {
                qualifies = m.parameters().stream()
                        .flatMap(p -> p.annotations().stream())
                        .anyMatch(a -> {
                            var n = a.name().value();
                            return OBSERVES.equals(n) || OBSERVES_ASYNC.equals(n) || DISPOSES.equals(n);
                        });
            }
            if (!qualifies) continue;

            var paramErasures = new ArrayList<String>();
            boolean skip = false;
            for (var p : m.parameters()) {
                var erasure = erasure(p.type());
                if (erasure == null) {
                    warningsOut.add("Skipping method " + beanFqn + "#" + m.name()
                            + ": parameter type is a non-nameable/primitive — cannot emit invokevirtual");
                    skip = true;
                    break;
                }
                if (erasure.contains("$")) {
                    warningsOut.add("Skipping method " + beanFqn + "#" + m.name()
                            + ": parameter erasure '" + erasure + "' contains '$' (nested type)"
                            + " — cast would not compile");
                    skip = true;
                    break;
                }
                paramErasures.add(erasure);
            }
            if (skip) continue;

            boolean isVoid = m.returnType() instanceof TypeInfo.VoidType;
            if (!isVoid && m.returnType() instanceof TypeInfo.PrimitiveType) {
                warningsOut.add("Skipping method " + beanFqn + "#" + m.name()
                        + ": primitive return type — only void or reference returns are supported");
                continue;
            }
            String returnErasure = isVoid ? null : erasure(m.returnType());

            result.add(new MethodInvoke(beanFqn, m.name(), List.copyOf(paramErasures),
                    m.isStatic(), isVoid, returnErasure));
        }
        return result;
    }

    /**
     * Top-level orchestration: for each supplied {@link ProvidedClass}, extracts component,
     * field-injection, and method-invocation descriptors, then groups them into one
     * {@link PackageProvider} per package.
     *
     * <p>The caller decides which classes are eligible (managed, in-module, top-level) and whether
     * each is instantiable ({@link ProvidedClass#instantiable()}); this method only does the
     * per-class extraction and per-package grouping.
     *
     * <p>Ordering is deterministic:
     * <ul>
     *   <li>packages are sorted lexicographically (via {@link TreeMap});</li>
     *   <li>components within a package are sorted by FQN (the caller supplies a
     *       {@link java.util.TreeMap}-ordered bean list, or any sorted order);</li>
     *   <li>fields and methods are emitted in the order they appear on the class (as scanned).</li>
     * </ul>
     *
     * <p>Empty packages (no component, field, or method) are omitted.
     *
     * @param beans       eligible managed classes to process
     * @param warningsOut mutable list to accumulate diagnostic messages (never {@code null})
     * @return one {@link PackageProvider} per non-empty package, sorted by package name
     */
    public static List<PackageProvider> collect(List<ProvidedClass> beans, List<String> warningsOut) {
        // Per-package accumulators.  TreeMap keeps packages in sorted order.
        record Bundle(
                List<Component> components,
                List<FieldInject>  fields,
                List<MethodInvoke> methods) {}
        var byPackage = new TreeMap<String, Bundle>();

        for (var provided : beans) {
            var fqn = provided.fqn();
            var pkg = packageOf(fqn);
            var bundle = byPackage.computeIfAbsent(pkg,
                    k -> new Bundle(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));

            if (provided.instantiable()) {
                instantiableCtorParams(provided.classInfo())
                        .ifPresent(params -> {
                            bundle.components().add(new Component(fqn, params));
                            // The pre-generated <fqn>$$Intercepted subclass mirrors the bean's
                            // selected constructor (InterceptedEmitter emits super(args…)). Listing
                            // it as a co-located component lets the container instantiate it
                            // in-module — no reflective newInstance. Only the bytecode provider can
                            // reference it (a generated source provider cannot resolve the symbol).
                            if (provided.intercepted()) {
                                bundle.components().add(new Component(fqn + "$$Intercepted", params));
                            }
                        });
            }

            bundle.fields().addAll(collectFields(provided.classInfo(), warningsOut));
            bundle.methods().addAll(collectMethods(provided.classInfo(), warningsOut));
        }

        var result = new ArrayList<PackageProvider>();
        for (var entry : byPackage.entrySet()) {
            var b = entry.getValue();
            if (b.components().isEmpty() && b.fields().isEmpty() && b.methods().isEmpty()) continue;
            result.add(new PackageProvider(entry.getKey(), b.components(), b.fields(), b.methods()));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Returns the package name portion of a fully-qualified class name (empty for default pkg). */
    static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }
}
