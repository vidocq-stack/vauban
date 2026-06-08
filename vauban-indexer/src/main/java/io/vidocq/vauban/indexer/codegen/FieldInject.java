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

/**
 * Describes an {@code @Inject} instance field (non-private, non-static) that the generated
 * {@code _VaubanComponents} provider can assign in-module without reflection.
 *
 * <p>Only fields in the same package as the generated provider are eligible: a {@code putfield}
 * to a package-private field only compiles (or executes without {@code opens}) from the same
 * package.
 *
 * @param declaringClassFqn fully-qualified name of the bean class declaring the field
 * @param fieldName         simple name of the field
 * @param fieldTypeErasure  erased, nameable type of the field (e.g. {@code "app.Repo"},
 *                          {@code "java.lang.String[]"})
 */
public record FieldInject(String declaringClassFqn, String fieldName, String fieldTypeErasure) {}
