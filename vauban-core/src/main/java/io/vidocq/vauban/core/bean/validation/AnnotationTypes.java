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
package io.vidocq.vauban.core.bean.validation;

import io.vidocq.vauban.core.annotation.AnnotationValues;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What the validation needs to know about an annotation type: its own annotations ({@code @NormalScope},
 * {@code @Stereotype}, {@code @Named}, {@code @Priority}, interceptor bindings…), whether it is {@code @Inherited},
 * and the defaults of its members (VAU-PERF-008).
 *
 * <p>The index describes it when it holds the annotation type. Otherwise — {@code jakarta.*} annotations, or a type
 * of an archive that was not scanned — the annotation type itself is read by reflection, once: a handful of types,
 * where the reflective validation read every member of every bean class. A type that cannot be loaded is absent,
 * as reflection leaves an annotation whose type it cannot load out of {@code getAnnotations()}.
 */
final class AnnotationTypes {

    static final DotName NORMAL_SCOPE = DotName.of("jakarta.enterprise.context.NormalScope");
    static final DotName SCOPE = DotName.of("jakarta.inject.Scope");
    static final DotName STEREOTYPE = DotName.of("jakarta.enterprise.inject.Stereotype");
    static final DotName INTERCEPTOR_BINDING = DotName.of("jakarta.interceptor.InterceptorBinding");
    private static final DotName INHERITED = DotName.of("java.lang.annotation.Inherited");

    /** An annotation type: whether it is {@code @Inherited}, its annotations and its members' defaults. */
    record Meta(boolean inherited, List<AnnotationInfo> annotations, Map<String, AnnotationValue> defaults) {

        boolean has(DotName annotation) {
            return annotation(annotation).isPresent();
        }

        Optional<AnnotationInfo> annotation(DotName annotation) {
            for (var a : annotations) {
                if (a.name().equals(annotation)) {
                    return Optional.of(a);
                }
            }
            return Optional.empty();
        }
    }

    private final VaubanIndex index;
    private final Function<DotName, Optional<ClassInfo>> describer;
    private final Function<String, Class<?>> loader;
    private final Predicate<DotName> customInterceptorBinding;
    private final Map<DotName, Optional<Meta>> metas = new HashMap<>();

    /**
     * @param loader                   loads an annotation type the index does not hold, or returns {@code null}
     * @param customInterceptorBinding the annotation types an extension registered as interceptor bindings
     */
    AnnotationTypes(VaubanIndex index, Function<String, Class<?>> loader,
            Predicate<DotName> customInterceptorBinding) {
        this(index, null, loader, customInterceptorBinding);
    }

    /**
     * @param describer describes an annotation type the index does not hold, without loading it — the processor
     *                  reads the compiler's elements — or returns empty; {@code loader} is asked next;
     *                  {@code null} when nothing but the index describes a type
     */
    AnnotationTypes(VaubanIndex index, Function<DotName, Optional<ClassInfo>> describer,
            Function<String, Class<?>> loader, Predicate<DotName> customInterceptorBinding) {
        this.index = index;
        this.describer = describer;
        this.loader = loader;
        this.customInterceptorBinding = customInterceptorBinding;
    }

    /** The annotation type {@code name}, or empty when it is neither indexed nor loadable. */
    Optional<Meta> meta(DotName name) {
        var meta = metas.get(name);
        if (meta == null) {
            meta = read(name);
            metas.put(name, meta);
        }
        return meta;
    }

    boolean isNormalScope(DotName name) {
        return has(name, NORMAL_SCOPE);
    }

    /** A normal scope or a pseudo-scope. */
    boolean isScope(DotName name) {
        return has(name, NORMAL_SCOPE) || has(name, SCOPE);
    }

    boolean isStereotype(DotName name) {
        return has(name, STEREOTYPE);
    }

    boolean isInterceptorBinding(DotName name) {
        return has(name, INTERCEPTOR_BINDING) || customInterceptorBinding.test(name);
    }

    /** Whether the annotation type {@code name} is known and carries {@code annotation}. */
    private boolean has(DotName name, DotName annotation) {
        var meta = meta(name);
        return meta.isPresent() && meta.get().has(annotation);
    }

    /** {@code annotation} with every member its type declares a default for, as a live annotation compares. */
    AnnotationInfo withDefaults(AnnotationInfo annotation) {
        var meta = meta(annotation.name());
        if (meta.isEmpty() || meta.get().defaults().isEmpty()) {
            return annotation;
        }
        var members = new LinkedHashMap<>(meta.get().defaults());
        members.putAll(annotation.members());
        return new AnnotationInfo(annotation.name(), members);
    }

    private Optional<Meta> read(DotName name) {
        var indexed = index.getClassByName(name);
        if (indexed.isEmpty() && describer != null) {
            indexed = describer.apply(name);
        }
        if (indexed.isPresent() && indexed.get().isAnnotation()) {
            var type = indexed.get();
            var defaults = new LinkedHashMap<String, AnnotationValue>();
            for (var m : type.methods()) {
                if (m.defaultValue() != null) {
                    defaults.put(m.name(), m.defaultValue());
                }
            }
            return Optional.of(new Meta(type.hasAnnotation(INHERITED), type.annotations(), defaults));
        }
        return reflected(name);
    }

    private Optional<Meta> reflected(DotName name) {
        try {
            var type = loader.apply(name.value());
            if (type == null || !type.isAnnotation()) {
                return Optional.empty();
            }
            var annotations = new ArrayList<AnnotationInfo>();
            for (var annotation : type.getAnnotations()) {
                annotations.add(AnnotationValues.toAnnotationInfo(annotation));
            }
            var defaults = new LinkedHashMap<String, AnnotationValue>();
            for (var member : type.getDeclaredMethods()) {
                var value = member.getDefaultValue();
                if (value != null) {
                    defaults.put(member.getName(), AnnotationValues.of(value));
                }
            }
            return Optional.of(new Meta(type.isAnnotationPresent(java.lang.annotation.Inherited.class),
                    List.copyOf(annotations), defaults));
        } catch (RuntimeException | LinkageError _) {
            return Optional.empty();
        }
    }
}
