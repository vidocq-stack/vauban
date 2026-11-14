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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The interfaces whose client proxies the Vauban annotation processor generates at build time, for the synthetic
 * beans of a normal scope that an extension registers with one of these interfaces as its proxied type.
 *
 * <p>The processor generates the client proxy of a normal-scoped bean it sees: a bean class, or a producer. A
 * synthetic bean has no class it sees, so the container would build the proxy of such a bean of an interface type at
 * run time, with {@code java.lang.reflect.Proxy}: every call then goes through reflection, and the boot warns of
 * the fallback. Listing the interface here makes the processor generate a class {@code implements} it, in the package
 * of the annotated class, which the module's {@code _VaubanComponents} provider creates:
 *
 * {@snippet lang = java:
 * @ClientProxies({EntityManager.class, EntityManagerFactory.class})
 * public class PersistenceExtension implements BuildCompatibleExtension {
 *     // registers @TransactionScoped EntityManager and @ApplicationScoped EntityManagerFactory synthetic beans
 * }
 * }
 *
 * <p>Each listed type must be a public interface. A proxy forwards every method, abstract or {@code default}, to the
 * contextual instance. Any class of the module may carry the annotation; the module must be compiled with
 * {@code vauban-processor} and declare its provider ({@code provides io.vidocq.vauban.api.VaubanComponentProvider
 * with <package>._VaubanComponents}), as for its beans.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ClientProxies {

    /** The public interfaces to generate a client proxy for. */
    Class<?>[] value();
}
