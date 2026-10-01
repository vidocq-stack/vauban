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
package io.vidocq.vauban.maven.enhance;

import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VaubanModuleReferencesTest {

    @Test
    void findsTheCoreModuleInAClassThatCallsIntoIt() {
        byte[] calling = ClassFile.of().build(ClassDesc.of("org.dep.Calling"), clb -> clb.withMethodBody("call",
                MethodTypeDesc.of(ConstantDescs.CD_void,
                        ClassDesc.of("io.vidocq.vauban.core.interceptor.InterceptorManager")),
                ClassFile.ACC_PUBLIC, cob -> cob.return_()));
        byte[] plain = ClassFile.of().build(ClassDesc.of("org.dep.Plain"), clb -> { });

        assertEquals(Set.of("io.vidocq.vauban.core"), VaubanModuleReferences.of(List.of(plain, calling)));
        assertEquals(Set.of(), VaubanModuleReferences.of(List.of(plain)));
    }
}
