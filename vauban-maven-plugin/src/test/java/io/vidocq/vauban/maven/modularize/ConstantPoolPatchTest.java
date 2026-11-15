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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("modularize: a method reference names another class, in the constant pool only")
class ConstantPoolPatchTest {

    private static final ClassDesc LOADER = ClassDesc.of("java.util.ServiceLoader");

    /** A class with a long and a double constant before the method reference: each takes two entries. */
    private static byte[] caller() {
        return ClassFile.of().build(ClassDesc.of("p.Caller"), clb -> {
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withMethodBody("call", MethodTypeDesc.of(LOADER, ConstantDescs.CD_Class), ClassFile.ACC_STATIC, cob -> {
                cob.ldc(42L).pop2().ldc(4.2d).pop2();
                cob.aload(0);
                cob.invokestatic(LOADER, "load", MethodTypeDesc.of(LOADER, ConstantDescs.CD_Class));
                cob.areturn();
            });
        });
    }

    private static int methodRef(byte[] bytes) {
        for (var entry : ClassFile.of().parse(bytes).constantPool()) {
            if (entry instanceof MethodRefEntry ref && ref.name().equalsString("load")) {
                return ref.index();
            }
        }
        throw new AssertionError("no method reference");
    }

    @Test
    @DisplayName("the reference names the new class, past a long and a double")
    void theReferenceNamesTheNewClass() {
        byte[] bytes = caller();

        byte[] patched = ConstantPoolPatch.redirectOwner(bytes, Set.of(methodRef(bytes)), "p/Bridge");

        var model = ClassFile.of().parse(patched);
        var ref = (MethodRefEntry) model.constantPool().entryByIndex(methodRef(patched));
        assertEquals("p/Bridge", ref.owner().asInternalName());
        assertEquals("p/Caller", model.thisClass().asInternalName());
    }

    @Test
    @DisplayName("an index that is not a method reference is refused")
    void anotherConstantIsRefused() {
        byte[] bytes = caller();
        int utf8 = 0;
        for (var entry : ClassFile.of().parse(bytes).constantPool()) {
            if (entry instanceof Utf8Entry) {
                utf8 = entry.index();
                break;
            }
        }
        Set<Integer> indexes = Set.of(utf8);

        assertThrows(IllegalArgumentException.class, () -> ConstantPoolPatch.redirectOwner(bytes, indexes, "p/Bridge"));
    }
}
