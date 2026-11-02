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
import io.vidocq.vauban.indexer.model.DotName;

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
            return annotations.stream().anyMatch(a -> a.name().equals(annotation));
        }

        Optional<AnnotationInfo> annotation(DotName annotation) {
            return annotations.stream().filter(a -> a.name().equals(annotation)).findFirst();
        }
    }

    private final VaubanIndex index;
    private final Function<String, Class<?>> loader;
    private final Predicate<DotName> customInterceptorBinding;
    private final Map<DotName, Optional<Meta>> metas = new HashMap<>();

    /**
     * @param loader                   loads an annotation type the index does not hold, or returns {@code null}
     * @param customInterceptorBinding the annotation types an extension registered as interceptor bindings
     */
    AnnotationTypes(VaubanIndex index, Function<String, Class<?>> loader,
            Predicate<DotName> customInterceptorBinding) {
        this.index = index;
        this.loader = loader;
        this.customInterceptorBinding = customInterceptorBinding;
    }

    /** The annotation type {@code name}, or empty when it is neither indexed nor loadable. */
    Optional<Meta> meta(DotName name) {
        return metas.computeIfAbsent(name, this::read);
    }

    boolean isNormalScope(DotName name) {
        return meta(name).map(m -> m.has(NORMAL_SCOPE)).orElse(false);
    }

    /** A normal scope or a pseudo-scope. */
    boolean isScope(DotName name) {
        return meta(name).map(m -> m.has(NORMAL_SCOPE) || m.has(SCOPE)).orElse(false);
    }

    boolean isStereotype(DotName name) {
        return meta(name).map(m -> m.has(STEREOTYPE)).orElse(false);
    }

    boolean isInterceptorBinding(DotName name) {
        return meta(name).map(m -> m.has(INTERCEPTOR_BINDING)).orElse(false) || customInterceptorBinding.test(name);
    }

    /** {@code annotation} with every member its type declares a default for, as a live annotation compares. */
    AnnotationInfo withDefaults(AnnotationInfo annotation) {
        var defaults = meta(annotation.name()).map(Meta::defaults).orElse(Map.of());
        if (defaults.isEmpty()) {
            return annotation;
        }
        var members = new LinkedHashMap<>(defaults);
        members.putAll(annotation.members());
        return new AnnotationInfo(annotation.name(), members);
    }

    private Optional<Meta> read(DotName name) {
        var indexed = index.getClassByName(name).filter(c -> c.isAnnotation());
        if (indexed.isPresent()) {
            var type = indexed.get();
            var defaults = new LinkedHashMap<String, AnnotationValue>();
            type.methods().stream().filter(m -> m.defaultValue() != null)
                    .forEach(m -> defaults.put(m.name(), m.defaultValue()));
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
            var annotations = java.util.Arrays.stream(type.getAnnotations())
                    .map(AnnotationValues::toAnnotationInfo)
                    .toList();
            var defaults = new LinkedHashMap<String, AnnotationValue>();
            for (var member : type.getDeclaredMethods()) {
                var value = member.getDefaultValue();
                if (value != null) {
                    defaults.put(member.getName(), AnnotationValues.of(value));
                }
            }
            return Optional.of(new Meta(type.isAnnotationPresent(java.lang.annotation.Inherited.class), annotations,
                    defaults));
        } catch (RuntimeException | LinkageError _) {
            return Optional.empty();
        }
    }
}
