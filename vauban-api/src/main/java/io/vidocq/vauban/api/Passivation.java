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

import jakarta.enterprise.inject.spi.BeanManager;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The container that reads back the session values another container wrote.
 *
 * <p>When Vidocq swaps the application layer in dev mode, the web container writes each value of a live session
 * with Java serialization and reads it back in the new layer (foy#114). A {@code @SessionScoped} instance travels
 * as its bean's passivation id and its state: the read finds the bean by that id in the importing container, the one
 * this class binds. The web container opens the binding around its reads with {@link #importInto}, and the serial
 * forms of Vauban read it with {@link #importingBeanManager}. It serves those reads only: the reads of session values
 * written by a previous container.
 *
 * <p>The binding holds on the reading thread only, for the duration of {@link #importInto}: a read on another
 * thread, or after the call returns, sees no container. A read that finds none fails: it throws
 * {@link java.io.InvalidObjectException}, and the web container drops the session it was reading, rather than bind
 * it to the wrong container.
 */
public final class Passivation {

    private static final ScopedValue<BeanManager> IMPORTING = ScopedValue.newInstance();

    private Passivation() {}

    /**
     * Runs {@code importer} with {@code beanManager} bound as the importing container, and returns what it returns.
     * An inner call binds its own container until it returns; the outer one is bound again after.
     *
     * @param beanManager the container that reads the session values back
     * @param importer the reads, on the current thread
     * @param <T> what the reads return
     * @return the result of {@code importer}
     * @throws NullPointerException if an argument is {@code null}
     */
    public static <T> T importInto(BeanManager beanManager, Supplier<T> importer) {
        Objects.requireNonNull(beanManager, "beanManager");
        Objects.requireNonNull(importer, "importer");
        return ScopedValue.where(IMPORTING, beanManager).call(importer::get);
    }

    /**
     * The container bound by the {@link #importInto} running on this thread.
     *
     * @return the importing container, or empty outside an import
     */
    public static Optional<BeanManager> importingBeanManager() {
        return IMPORTING.isBound() ? Optional.of(IMPORTING.get()) : Optional.empty();
    }
}
