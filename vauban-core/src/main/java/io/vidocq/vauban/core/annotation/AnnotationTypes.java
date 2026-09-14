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
package io.vidocq.vauban.core.annotation;

import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The annotation type metadata of one container, and the {@link AnnotationKey} of an annotation.
 *
 * <p>A type is looked up once, in this order: the container's index; the type's class file, read
 * through the container's class loaders; its declaration, by reflection — members,
 * {@code getDefaultValue()} and their {@code @Nonbinding} annotation, never an invocation. The loaders
 * matter: the TCK and an application re-layered by Vauban define qualifier types in a loader that
 * vauban-core's own loader cannot see.
 *
 * <p>An instance belongs to one container. Types of the same name can differ between containers, and
 * the members an extension makes non-binding are a container's own decision.
 */
public final class AnnotationTypes {

    private final VaubanIndex index;
    private final List<ClassLoader> loaders;
    private final Map<String, Set<String>> extensionNonbinding;
    private final Map<DotName, Optional<AnnotationTypeInfo>> types = new ConcurrentHashMap<>();

    /**
     * @param index               the container's index, or {@code null} when there is none
     * @param loaders             the class loaders to read class files and declarations through, in
     *                            order; {@code null} entries are skipped
     * @param extensionNonbinding the members extensions made non-binding, by annotation type name, or
     *                            {@code null} when no extension made any
     */
    public AnnotationTypes(VaubanIndex index, List<ClassLoader> loaders, Map<String, Set<String>> extensionNonbinding) {
        this.index = index;
        this.loaders = loaders.stream().filter(Objects::nonNull).distinct().toList();
        this.extensionNonbinding = extensionNonbinding == null ? Map.of() : Map.copyOf(extensionNonbinding);
    }

    /** The metadata of the annotation type {@code name}, or empty when no source can describe it. */
    public Optional<AnnotationTypeInfo> type(DotName name) {
        return types.computeIfAbsent(name, this::load);
    }

    /**
     * The key of an annotation of type {@code type} whose written members are {@code written}.
     *
     * <p>Every member the type declares takes its written value or its default; {@code @Nonbinding}
     * members and those an extension made non-binding are left out; a nested annotation keeps all its
     * members, defaults applied, because it compares like {@link java.lang.annotation.Annotation#equals}.
     * A written member the type does not declare is kept, so that a stale index cannot make two
     * different annotations equal. A type no source describes keeps its written members as they are.
     */
    public AnnotationKey key(DotName type, Map<String, AnnotationValue> written) {
        var ignored = extensionNonbinding.getOrDefault(type.value(), Set.of());
        var members = new HashMap<String, AnnotationValue>();
        var info = type(type);
        if (info.isPresent()) {
            for (var member : info.get().members()) {
                if (member.nonbinding() || ignored.contains(member.name())) {
                    continue;
                }
                var value = written.containsKey(member.name()) ? written.get(member.name()) : member.defaultValue();
                if (value != null) {
                    members.put(member.name(), complete(value));
                }
            }
        }
        written.forEach((name, value) -> {
            if (!ignored.contains(name) && (info.isEmpty() || info.get().member(name).isEmpty())) {
                members.put(name, complete(value));
            }
        });
        return new AnnotationKey(type, members);
    }

    /** Completes every nested annotation of {@code value} with its defaults. */
    private AnnotationValue complete(AnnotationValue value) {
        return switch (value) {
            case AnnotationValue.AnnotationVal nested -> new AnnotationValue.AnnotationVal(
                    new AnnotationInfo(nested.annotation().name(), allMembers(nested.annotation())));
            case AnnotationValue.ArrayVal array -> new AnnotationValue.ArrayVal(array.values().stream()
                    .map(this::complete).toList());
            default -> value;
        };
    }

    private Map<String, AnnotationValue> allMembers(AnnotationInfo nested) {
        var members = new HashMap<String, AnnotationValue>();
        type(nested.name()).ifPresent(info -> {
            for (var member : info.members()) {
                var value = nested.members().containsKey(member.name())
                        ? nested.members().get(member.name()) : member.defaultValue();
                if (value != null) {
                    members.put(member.name(), complete(value));
                }
            }
        });
        nested.members().forEach((name, value) -> members.putIfAbsent(name, complete(value)));
        return members;
    }

    private Optional<AnnotationTypeInfo> load(DotName name) {
        if (index != null) {
            var indexed = index.getClassByName(name).filter(ClassInfo::isAnnotation);
            if (indexed.isPresent()) {
                return indexed.map(AnnotationTypeInfo::of);
            }
        }
        var classFile = name.toInternal() + ".class";
        for (var loader : loaders) {
            try (var in = loader.getResourceAsStream(classFile)) {
                if (in != null) {
                    var scanned = ClassFileScanner.scan(in.readAllBytes());
                    if (scanned.isAnnotation()) {
                        return Optional.of(AnnotationTypeInfo.of(scanned));
                    }
                }
            } catch (IOException | RuntimeException e) {
                // An unreadable class file: the next loader, then reflection, may still describe the type.
            }
        }
        for (var loader : loaders) {
            try {
                var type = Class.forName(name.value(), false, loader);
                if (type.isAnnotation()) {
                    return Optional.of(reflect(type));
                }
            } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
                // Not visible through this loader, or a declaration that cannot be read, such as a
                // default naming a class missing at run time.
            }
        }
        return Optional.empty();
    }

    /** Reads a declaration: member names, their defaults, their annotations. */
    private static AnnotationTypeInfo reflect(Class<?> type) {
        var members = AnnotationValues.members(type).stream()
                .map(method -> new AnnotationTypeInfo.Member(method.getName(),
                        method.getDefaultValue() == null ? null : AnnotationValues.of(method.getDefaultValue()),
                        Arrays.stream(method.getDeclaredAnnotations()).anyMatch(annotation ->
                                annotation.annotationType().getName().equals(AnnotationTypeInfo.NONBINDING.value()))))
                .toList();
        return new AnnotationTypeInfo(DotName.of(type.getName()), members);
    }
}
