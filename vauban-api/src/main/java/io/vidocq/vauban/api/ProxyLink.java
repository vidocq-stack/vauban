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
package io.vidocq.vauban.api;

/**
 * Marker parameter type for a bean's <em>client-proxy entry constructor</em>.
 *
 * <p>A normal-scoped bean class whose only constructors take parameters — the single
 * {@code @Inject} constructor style — is <em>unproxyable</em> per CDI 4.1 ("Unproxyable
 * bean types"): the generated {@code <Bean>_ClientProxy} subclass has no side-effect-free
 * super constructor to chain to, and the JVM does not allow skipping the super constructor
 * altogether. Vauban reports such beans as a deployment problem.
 *
 * <p>Declaring one additional constructor taking a {@code ProxyLink} lifts that
 * restriction (a Vauban extension, comparable to Weld's relaxed construction):
 *
 * {@snippet lang = java:
 * @ApplicationScoped
 * public class Oidc {
 *     private final String issuer;
 *
 *     @Inject
 *     public Oidc(Config config) {
 *         this.issuer = config.issuer();
 *     }
 *
 *     protected Oidc(ProxyLink link) {   // client-proxy entry point — keep the body empty
 *         this.issuer = null;            // javac requires blank finals to be assigned
 *     }
 * }
 * }
 *
 * <p>Every client-proxy generator then chains to that constructor instead of calling
 * another constructor with default arguments, so creating the proxy runs no bean logic.
 * The constraints on the marker constructor:
 *
 * <ul>
 *   <li>its body must stay empty apart from assigning blank {@code final} fields to their
 *       defaults — the instance it initializes is the proxy shell, never used as state;</li>
 *   <li>the parameter is always {@code null}: {@code ProxyLink} cannot be instantiated;</li>
 *   <li>any non-private visibility works ({@code protected} recommended);</li>
 *   <li>field initializers still run in it — keep those side-effect free.</li>
 * </ul>
 *
 * <p>The container never considers this constructor for constructor injection or for
 * instantiating the contextual instance.
 */
public final class ProxyLink {

    /** Binary name of this class, shared by the proxy generators and validators. */
    public static final String CLASS_NAME = "io.vidocq.vauban.api.ProxyLink";

    private ProxyLink() {
        // Never instantiated — the generated proxies pass null.
    }
}
