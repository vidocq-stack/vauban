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
package io.vidocq.vauban.core.interceptor.fixtures;

/**
 * Declares the method names of {@link OrderedBean} and {@link OrderedBeanSuper} in reverse name
 * order. Loaded first, it makes HotSpot create their name symbols in that order, which is the order
 * {@code getDeclaredMethods} then lists the bean's methods in: a load history in which reflection
 * order and name order differ (BUG-20261007-02).
 */
public abstract class OrderedBeanPrimer {
    public abstract void soZulu();
    public abstract void soYankee();
    public abstract void soXray();
    public abstract void soSierra();
    public abstract void soPapa();
    public abstract void soMike();
    public abstract void soKilo();
    public abstract void soIndia();
    public abstract void soHotel();
    public abstract void soGolf();
    public abstract void soEcho();
    public abstract void soDelta();
    public abstract void soCharlie();
    public abstract void soBravo();
    public abstract void soAlpha();
}
