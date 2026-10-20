/*
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 * Copyright (c) 2026 Yann Blazart and the Vidocq contributors
 */
module experiment.external.beans {
    requires jakarta.cdi;
    requires jakarta.inject;
    requires microprofile.fault.tolerance.api;

    exports experiment.external.beans;
    opens experiment.external.beans to jakarta.cdi;
}
