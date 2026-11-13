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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.extensions.BceProcessor;
import io.vidocq.vauban.core.extensions.FrozenContexts;
import io.vidocq.vauban.core.extensions.FrozenExtensions;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the {@code @Discovery} phase of the build compatible extensions registered, whichever way the container
 * learned it: the extensions that just ran, the record of a deployment whose extensions the build froze
 * (VAU-PERF-005), or both when some extensions run at start beside the record.
 *
 * @param qualifiers            the annotations made qualifiers
 * @param interceptorBindings   the annotations made interceptor bindings
 * @param stereotypes           the annotations made stereotypes
 * @param stereotypeAnnotations the annotations each of those stereotypes carries
 * @param nonbindingMembers     the members made non-binding, by annotation type name
 * @param scannedClasses        the classes added through {@code ScannedClasses}, by name
 */
record ExtensionDiscovery(Set<Class<? extends Annotation>> qualifiers,
                          Set<Class<? extends Annotation>> interceptorBindings,
                          Set<Class<? extends Annotation>> stereotypes,
                          Map<Class<? extends Annotation>, Set<Class<? extends Annotation>>> stereotypeAnnotations,
                          Map<String, Set<String>> nonbindingMembers,
                          List<String> scannedClasses) {

    /** What the extensions that ran registered. */
    static ExtensionDiscovery of(BceProcessor.DiscoveryResult live) {
        var meta = live.metaAnnotations();
        return new ExtensionDiscovery(meta.getCustomQualifiers(), meta.getCustomInterceptorBindings(),
                meta.getCustomStereotypes(), meta.getStereotypeAnnotations(), meta.getNonbindingMembers(),
                List.copyOf(live.scannedClasses().getAddedClasses()));
    }

    /**
     * What the record says the extensions registered at build, its annotation types loaded by {@code loader}. One
     * that cannot be loaded fails the deployment: the record is this deployment's, so its annotations are there.
     */
    static ExtensionDiscovery of(FrozenExtensions.Discovery frozen, ClassLoader loader) {
        var stereotypeAnnotations = new LinkedHashMap<Class<? extends Annotation>, Set<Class<? extends Annotation>>>();
        frozen.stereotypes().forEach((stereotype, carried) ->
                stereotypeAnnotations.put(annotationType(stereotype, loader), annotationTypes(carried, loader)));
        var nonbinding = new LinkedHashMap<String, Set<String>>();
        frozen.nonbindingMembers().forEach((annotation, members) ->
                nonbinding.put(annotation, new LinkedHashSet<>(members)));
        return new ExtensionDiscovery(annotationTypes(frozen.qualifiers(), loader),
                annotationTypes(frozen.interceptorBindings(), loader),
                new LinkedHashSet<>(stereotypeAnnotations.keySet()), stereotypeAnnotations, nonbinding,
                frozen.scannedClasses());
    }

    /** This and {@code other} together. */
    ExtensionDiscovery plus(ExtensionDiscovery other) {
        var stereotypeAnnotations = new LinkedHashMap<>(this.stereotypeAnnotations);
        stereotypeAnnotations.putAll(other.stereotypeAnnotations);
        var nonbinding = new LinkedHashMap<>(nonbindingMembers);
        nonbinding.putAll(other.nonbindingMembers);
        var scanned = new LinkedHashSet<>(scannedClasses);
        scanned.addAll(other.scannedClasses);
        return new ExtensionDiscovery(union(qualifiers, other.qualifiers),
                union(interceptorBindings, other.interceptorBindings), union(stereotypes, other.stereotypes),
                stereotypeAnnotations, nonbinding, List.copyOf(scanned));
    }

    /** By name, sorted, with {@code contexts}: what a build writes in its record. */
    FrozenExtensions.Discovery named(List<FrozenContexts.Entry> contexts) {
        var stereotypeNames = new TreeMap<String, List<String>>();
        stereotypes.forEach(stereotype -> stereotypeNames.put(stereotype.getName(),
                names(stereotypeAnnotations.getOrDefault(stereotype, Set.of()))));
        var nonbinding = new TreeMap<String, List<String>>();
        nonbindingMembers.forEach((annotation, members) ->
                nonbinding.put(annotation, List.copyOf(new TreeSet<>(members))));
        return new FrozenExtensions.Discovery(List.copyOf(scannedClasses), names(qualifiers),
                names(interceptorBindings), stereotypeNames, nonbinding, contexts);
    }

    private static List<String> names(Set<Class<? extends Annotation>> types) {
        var names = new TreeSet<String>();
        types.forEach(type -> names.add(type.getName()));
        return List.copyOf(names);
    }

    private static Set<Class<? extends Annotation>> union(Set<Class<? extends Annotation>> one,
            Set<Class<? extends Annotation>> other) {
        var all = new LinkedHashSet<>(one);
        all.addAll(other);
        return all;
    }

    private static Set<Class<? extends Annotation>> annotationTypes(List<String> names, ClassLoader loader) {
        var types = new LinkedHashSet<Class<? extends Annotation>>();
        for (String name : names) {
            types.add(annotationType(name, loader));
        }
        return types;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> annotationType(String name, ClassLoader loader) {
        try {
            // A name of the deployment itself, as the record of its own build wrote it.
            Class<?> type = io.vidocq.vauban.core.loading.DeploymentClasses.load(name, loader);
            if (!type.isAnnotation()) {
                throw new jakarta.enterprise.inject.spi.DeploymentException(name + ", which the extensions"
                        + " registered at build (" + FrozenExtensions.PATH + "), is not an annotation type");
            }
            return (Class<? extends Annotation>) type;
        } catch (ClassNotFoundException e) {
            throw new jakarta.enterprise.inject.spi.DeploymentException("Cannot load the annotation type " + name
                    + " the extensions registered at build (" + FrozenExtensions.PATH + ")", e);
        }
    }
}
