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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every kind of constant-pool entry is read: only class entries and descriptors can name a core type. */
class VaubanModuleReferencesPoolEntryTest {

    private static final ClassDesc CORE_TYPE = ClassDesc.of("io.vidocq.vauban.core.container.VaubanContainer");

    @Test
    void stringsAndMemberReferencesNameNoModule() {
        byte[] calling = ClassFile.of().build(ClassDesc.of("org.dep.Strings"), clb -> clb.withMethodBody("text",
                MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
                    cob.ldc("io.vidocq.vauban.core.container.VaubanContainer");
                    cob.invokevirtual(ConstantDescs.CD_String, "strip", MethodTypeDesc.of(ConstantDescs.CD_String));
                    cob.areturn();
                }));

        assertEquals(Set.of(), VaubanModuleReferences.of(List.of(calling)));
    }

    @Test
    void aClassEntryOfACoreTypeNamesTheCoreModule() {
        byte[] casting = ClassFile.of().build(ClassDesc.of("org.dep.Casting"), clb -> clb.withMethodBody("cast",
                MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
                    cob.aload(0);
                    cob.checkcast(CORE_TYPE);
                    cob.areturn();
                }));

        assertTrue(VaubanModuleReferences.of(List.of(casting)).contains("io.vidocq.vauban.core"));
    }
}
