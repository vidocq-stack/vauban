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
 * A bean whose methods are declared out of name order, so that a generator emitting them in
 * reflection order (unspecified, HotSpot's depends on what the JVM loaded before) is caught
 * (BUG-20261007-02).
 */
public class OrderedBean extends OrderedBeanSuper {

    public String soZulu() { return "soZulu"; }

    public String soMike(int n) { return "soMike"; }

    public String soKilo() { return "soKilo"; }

    public String soAlpha() { return "soAlpha"; }

    public String soEcho() { return "soEcho"; }

    public String soMike() { return "soMike"; }

    public String soCharlie(String s) { return "soCharlie"; }

    public String soXray() { return "soXray"; }

    public String soGolf() { return "soGolf"; }

    public String soSierra() { return "soSierra"; }

    public String soIndia() { return "soIndia"; }
}
