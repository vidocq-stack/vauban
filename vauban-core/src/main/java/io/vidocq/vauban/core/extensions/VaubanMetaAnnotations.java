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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.langmodel.IndexLookup;
import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;

import java.lang.annotation.Annotation;
import java.util.*;

public final class VaubanMetaAnnotations implements MetaAnnotations {

    private final IndexLookup lookup;
    private final Map<Class<? extends Annotation>, VaubanClassConfig> customQualifiers = new LinkedHashMap<>();
    private final Map<Class<? extends Annotation>, VaubanClassConfig> customInterceptorBindings = new LinkedHashMap<>();
    private final Map<Class<? extends Annotation>, VaubanClassConfig> customStereotypes = new LinkedHashMap<>();
    private final List<ContextRegistration> customContexts = new ArrayList<>();

    public record ContextRegistration(
            Class<? extends Annotation> scopeAnnotation,
            boolean isNormal,
            Class<? extends AlterableContext> contextClass
    ) {}

    public VaubanMetaAnnotations(IndexLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public ClassConfig addQualifier(Class<? extends Annotation> annotationType) {
        var config = new VaubanClassConfig(annotationType, lookup);
        customQualifiers.put(annotationType, config);
        return config;
    }

    @Override
    public ClassConfig addInterceptorBinding(Class<? extends Annotation> annotationType) {
        var config = new VaubanClassConfig(annotationType, lookup);
        customInterceptorBindings.put(annotationType, config);
        return config;
    }

    @Override
    public ClassConfig addStereotype(Class<? extends Annotation> annotationType) {
        var config = new VaubanClassConfig(annotationType, lookup);
        customStereotypes.put(annotationType, config);
        return config;
    }

    @Override
    public void addContext(Class<? extends Annotation> scopeAnnotation,
                           Class<? extends AlterableContext> contextClass) {
        customContexts.add(new ContextRegistration(scopeAnnotation, true, contextClass));
    }

    @Override
    public void addContext(Class<? extends Annotation> scopeAnnotation, boolean isNormal,
                           Class<? extends AlterableContext> contextClass) {
        customContexts.add(new ContextRegistration(scopeAnnotation, isNormal, contextClass));
    }

    public Set<Class<? extends Annotation>> getCustomQualifiers() {
        return Set.copyOf(customQualifiers.keySet());
    }

    public Set<Class<? extends Annotation>> getCustomInterceptorBindings() {
        return Set.copyOf(customInterceptorBindings.keySet());
    }

    public Set<Class<? extends Annotation>> getCustomStereotypes() {
        return Set.copyOf(customStereotypes.keySet());
    }

    public List<ContextRegistration> getCustomContexts() {
        return List.copyOf(customContexts);
    }

    public VaubanClassConfig getQualifierConfig(Class<? extends Annotation> annotationType) {
        return customQualifiers.get(annotationType);
    }

    public VaubanClassConfig getStereotypeConfig(Class<? extends Annotation> annotationType) {
        return customStereotypes.get(annotationType);
    }

    /**
     * The members an extension made {@code @Nonbinding}, by annotation type name — for the
     * qualifiers it declared <em>and</em> for the interceptor bindings. Both take part in matching,
     * and both are compared as normalized keys; leaving the bindings out meant a member an extension
     * had made non-binding still bound its interceptor (BUG-20260914-08).
     */
    public Map<String, Set<String>> getNonbindingMembers() {
        var result = new HashMap<String, Set<String>>();
        collectNonbinding(customQualifiers, result);
        collectNonbinding(customInterceptorBindings, result);
        return result;
    }

    private static void collectNonbinding(Map<Class<? extends Annotation>, VaubanClassConfig> configs,
            Map<String, Set<String>> into) {
        for (var entry : configs.entrySet()) {
            var nonbinding = entry.getValue().getNonbindingMembers();
            if (!nonbinding.isEmpty()) {
                into.put(entry.getKey().getName(), nonbinding);
            }
        }
    }

    public Map<Class<? extends Annotation>, Set<Class<? extends Annotation>>> getStereotypeAnnotations() {
        var result = new HashMap<Class<? extends Annotation>, Set<Class<? extends Annotation>>>();
        for (var entry : customStereotypes.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getAddedAnnotations());
        }
        return result;
    }
}
