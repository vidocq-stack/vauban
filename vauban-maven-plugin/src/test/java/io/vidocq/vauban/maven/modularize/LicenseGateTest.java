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
package io.vidocq.vauban.maven.modularize;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseGateTest {

    @Test
    void permissiveSpellingsAreNormalized() {
        var licenses = Map.of(
                "dev.langchain4j:langchain4j", List.of("The Apache Software License, Version 2.0"),
                "org.slf4j:slf4j-api", List.of("MIT License"),
                "org.eclipse.parsson:parsson", List.of("Eclipse Public License v. 2.0", "GNU General Public License, version 2 with the GNU Classpath Exception"));

        List<String> v = LicenseGate.violations(licenses, Set.of("Apache-2.0", "MIT", "EPL-2.0"));

        assertTrue(v.isEmpty(), v.toString());
    }

    @Test
    void missingOrDisallowedLicenseIsReported() {
        var licenses = Map.of(
                "com.acme:closed", List.<String>of(),
                "com.acme:gpl-only", List.of("GNU General Public License v3.0"));

        List<String> v = LicenseGate.violations(licenses, Set.of("Apache-2.0"));

        assertEquals(2, v.size());
        assertTrue(v.get(0).contains("com.acme:closed") || v.get(1).contains("com.acme:closed"));
        assertTrue(v.get(0).contains("com.acme:gpl-only") || v.get(1).contains("com.acme:gpl-only"));
    }

    @Test
    void bsdBoilerplateTextDoesNotFalselyMatchMit() {
        var licenses = Map.of(
                "com.acme:bsd-boilerplate", List.of(
                        "Redistribution and use in source and binary forms are permitted provided that..."));

        List<String> v = LicenseGate.violations(licenses, Set.of("MIT"));

        assertEquals(1, v.size());
    }
}
