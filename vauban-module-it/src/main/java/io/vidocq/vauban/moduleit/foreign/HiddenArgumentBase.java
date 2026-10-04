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
package io.vidocq.vauban.moduleit.foreign;

import io.vidocq.vauban.moduleit.Labeled;
import io.vidocq.vauban.moduleit.PrivateObjectLabelBase;

/**
 * Binds {@link Labeled} to the package-private {@link HiddenArgument}, under the private
 * {@code label(Object)} of {@link PrivateObjectLabelBase} that shadows the default: a bean in
 * another package inherits {@code label(HiddenArgument)} as its member, yet its rendered subclass
 * can name neither that parameter type nor {@code Labeled<HiddenArgument>} (BUG-20261004-08).
 */
public class HiddenArgumentBase extends PrivateObjectLabelBase implements Labeled<HiddenArgument> {

    /** {@code bean.label(…)} as this package writes it, with an argument only it can make. */
    public static String callLabel(HiddenArgumentBase bean) {
        return bean.label(new HiddenArgument());
    }
}
