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
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.build.compatible.spi.ScopeInfo;
import jakarta.enterprise.inject.build.compatible.spi.StereotypeInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.inject.Named;
import jakarta.inject.Scope;
import jakarta.interceptor.InterceptorBinding;

import java.util.Collection;
import java.util.Set;

/**
 * A stereotype of a bean, as the language model describes its annotation type: the scope, {@code @Alternative},
 * {@code @Priority}, {@code @Named} and the interceptor bindings it declares (BUG-20261117-13).
 */
final class VaubanBceStereotypeInfo implements StereotypeInfo {

    private static final String PRIORITY = "jakarta.annotation.Priority";

    /** The built-in scopes, which a stereotype from a spec jar may declare though the index does not hold them. */
    private static final Set<String> NORMAL_SCOPES = Set.of("jakarta.enterprise.context.ApplicationScoped",
            "jakarta.enterprise.context.RequestScoped", "jakarta.enterprise.context.SessionScoped",
            "jakarta.enterprise.context.ConversationScoped");
    private static final Set<String> PSEUDO_SCOPES = Set.of("jakarta.enterprise.context.Dependent",
            "jakarta.inject.Singleton");

    private final ClassInfo declaration;
    private final IndexLookup lookup;

    VaubanBceStereotypeInfo(ClassInfo declaration, IndexLookup lookup) {
        this.declaration = declaration;
        this.lookup = lookup;
    }

    @Override
    public ScopeInfo defaultScope() {
        for (var annotation : declaration.annotations()) {
            var name = annotation.name();
            boolean normal = NORMAL_SCOPES.contains(name) || annotation.declaration().hasAnnotation(NormalScope.class);
            if (normal || PSEUDO_SCOPES.contains(name) || annotation.declaration().hasAnnotation(Scope.class)) {
                return new VaubanBceScopeInfo(new io.vidocq.vauban.core.bean.model.ScopeInfo(DotName.of(name), normal),
                        lookup);
            }
        }
        return null;
    }

    @Override
    public Collection<AnnotationInfo> interceptorBindings() {
        return declaration.annotations(annotation -> annotation.declaration().hasAnnotation(InterceptorBinding.class));
    }

    @Override
    public boolean isAlternative() {
        return declaration.hasAnnotation(Alternative.class);
    }

    @Override
    public Integer priority() {
        return declaration.annotations(annotation -> annotation.name().equals(PRIORITY)).stream().findFirst()
                .map(priority -> priority.value().asInt()).orElse(null);
    }

    @Override
    public boolean isNamed() {
        return declaration.hasAnnotation(Named.class);
    }
}
