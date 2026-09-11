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
package it.beanb;

/**
 * The application entry point the launcher re-enters from inside the Vauban layer. It hands the
 * probe's report back through a system property — the one channel that crosses the layer
 * boundary without sharing a class.
 */
public final class LaunchedMain {

    public static final String RESULT_PROPERTY = "it.beanb.launched.result";

    private LaunchedMain() {}

    public static void main(String[] args) {
        System.setProperty(RESULT_PROPERTY, LayerProbe.probe());
    }
}
