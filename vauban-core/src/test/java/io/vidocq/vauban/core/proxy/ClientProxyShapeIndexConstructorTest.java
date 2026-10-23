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
package io.vidocq.vauban.core.proxy;

import io.vidocq.vauban.api.ProxyLink;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The index-model check for the {@code (ProxyLink)} entry constructor, over each constructor of a scanned class. */
class ClientProxyShapeIndexConstructorTest {

    @SuppressWarnings("unused")
    public static class Constructors {
        public Constructors(ProxyLink link) {
        }

        public Constructors(String name) {
        }

        public Constructors(int size) {
        }

        public Constructors() {
        }
    }

    private static String signature(MethodInfo constructor) {
        return constructor.parameters().stream().map(p -> p.type().toString()).collect(Collectors.joining(","));
    }

    @Test
    void onlyTheSingleProxyLinkParameterConstructorIsTheEntryConstructor() throws IOException {
        byte[] bytes;
        try (var in = Constructors.class.getResourceAsStream("/" + Constructors.class.getName().replace('.', '/') + ".class")) {
            bytes = in.readAllBytes();
        }
        var verdicts = ClassFileScanner.scan(bytes).methods().stream()
                .filter(MethodInfo::isConstructor)
                .collect(Collectors.toMap(c -> c.parameters().size() + ":" + signature(c),
                        ClientProxyShape::isProxyLinkConstructor));

        assertEquals(4, verdicts.size());
        assertEquals(Map.of(true, 1L, false, 3L),
                verdicts.values().stream().collect(Collectors.groupingBy(v -> v, Collectors.counting())));
    }
}
