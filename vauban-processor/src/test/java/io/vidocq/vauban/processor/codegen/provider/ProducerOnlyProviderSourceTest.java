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
package io.vidocq.vauban.processor.codegen.provider;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A package holding only a producer proxy still gets the provider method that creates it. */
class ProducerOnlyProviderSourceTest {

    @Test
    void aProducerProxyAloneGetsACreateClientProxyMethod() {
        var source = ComponentProviderGenerator.generateFrom("app", ComponentProviderGenerator.SIMPLE_NAME,
                new ComponentProviderGenerator.Contents(List.of(), List.of(), List.of(), List.of(),
                        List.of(new ComponentProviderGenerator.ProducerProxy("lib.Thing_ClientProxy",
                                "app.Thing$$1f_ClientProxy")),
                        new AnnotationArtefacts.Rendered("", ""), Set.of())).source();

        assertTrue(source.contains("public Object createClientProxy(String proxyClassName,"), source);
        assertTrue(source.contains("case \"lib.Thing_ClientProxy\" -> {"), source);
    }
}
