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
package io.vidocq.vauban.weaver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Weaving patches a class someone else compiled, so it must hand back the same class file version it
 * was given. Rebuilding it at the running JDK's version would make a woven library unloadable on the
 * runtime it was compiled for — the disk-side twin of vauban BUG-20260911-01.
 */
@DisplayName("Weaving preserves the class file version of the class it patches")
class WovenClassVersionTest {

    @Test
    @DisplayName("a Java 17 bean keeps its version once the marker constructor is woven in")
    void markerConstructorKeepsTheInputVersion() {
        var legacy = ClassFile.of().build(ClassDesc.of("acme.Legacy"), clb -> {
            clb.withVersion(ClassFile.JAVA_17_VERSION, 0);
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC, cob -> {
                        cob.aload(0);
                        cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.return_();
                    });
        });

        var woven = ProxyLinkWeaver.addMarkerConstructor(legacy, ProxyLinkWeaver.SuperChain.NO_ARG);

        assertNotNull(woven, "the class carries no marker constructor yet, so weaving must patch it");
        assertEquals(ClassFile.JAVA_17_VERSION, ClassFile.of().parse(woven).majorVersion(),
                "the woven class must stay loadable everywhere the original was");
    }
}
