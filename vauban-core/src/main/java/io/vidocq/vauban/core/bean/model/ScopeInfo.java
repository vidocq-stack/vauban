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
package io.vidocq.vauban.core.bean.model;

import io.vidocq.vauban.indexer.model.DotName;

/**
 * Describes a CDI scope.
 * Normal scopes require a proxy (ApplicationScoped, RequestScoped, etc.).
 * Pseudo-scopes don't (Dependent, Singleton).
 */
public record ScopeInfo(DotName annotationName, boolean isNormal) {

    public static final ScopeInfo DEPENDENT = new ScopeInfo(
            DotName.of("jakarta.enterprise.context.Dependent"), false);

    public static final ScopeInfo APPLICATION = new ScopeInfo(
            DotName.of("jakarta.enterprise.context.ApplicationScoped"), true);

    public static final ScopeInfo REQUEST = new ScopeInfo(
            DotName.of("jakarta.enterprise.context.RequestScoped"), true);

    public static final ScopeInfo SINGLETON = new ScopeInfo(
            DotName.of("jakarta.inject.Singleton"), false);

    public static final ScopeInfo SESSION = new ScopeInfo(
            DotName.of("jakarta.enterprise.context.SessionScoped"), true);

    public static final ScopeInfo CONVERSATION = new ScopeInfo(
            DotName.of("jakarta.enterprise.context.ConversationScoped"), true);
}
