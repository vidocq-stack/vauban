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
/**
 * A third-party, CDI-agnostic Java module (the "library A" of jakartaee/cdi#1015). It exports its
 * types so an application can use them, but declares <strong>no {@code opens}</strong> and has no
 * dependency on any CDI implementation. Producing its types as normal-scoped CDI beans is exactly
 * the case the CDI expert group discussed: a runtime proxy would have to land in this module's
 * package, which requires {@code --add-opens}. Vauban proxies them at build time instead.
 */
module io.vidocq.vauban.example.cdi1015.lib {
    exports io.vidocq.vauban.example.cdi1015.lib;
}
