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

import io.vidocq.vauban.core.interceptor.TypeRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.reflect.AccessFlag;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client proxy emitted as bytecode follows the contract of the source one (vauban#227): {@code final}, so no
 * class can extend it, and {@code @Vetoed}, so a container that scans the archive never takes it for a bean.
 */
@DisplayName("ClientProxyEmitter — final and @Vetoed, like the source proxy")
class ClientProxyEmitterContractTest {

    @Test
    @DisplayName("the emitted proxy class is final and annotated @Vetoed")
    void finalAndVetoed() {
        var shape = new ClientProxyShape("com.example.MyService", List.of(), List.of(
                new ClientProxyShape.ProxyMethodShape("run", TypeRef.ofVoid(), List.of(), List.of(), false)));

        var model = ClassFile.of().parse(ClientProxyEmitter.emit(shape));

        assertTrue(model.flags().has(AccessFlag.FINAL), "final");
        var annotations = model.findAttribute(Attributes.runtimeVisibleAnnotations()).orElseThrow().annotations();
        assertTrue(annotations.stream().anyMatch(a -> a.className().stringValue()
                .equals("Ljakarta/enterprise/inject/Vetoed;")), String.valueOf(annotations));
    }
}
