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

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

/** Bytecode fixtures for the weaver tests — synthesized, never loaded. */
final class Fixtures {

    private static final ClassDesc CD_STRING = ClassDesc.of("java.lang.String");
    private static final ClassDesc CD_PROXY_LINK = ClassDesc.of(ProxyLinkWeaver.PROXY_LINK_CLASS);

    private Fixtures() {}

    /** {@code public class <name> { public <name>(String s) { super(); } }} — no no-arg ctor. */
    static byte[] beanWithBusinessCtorOnly(String binaryName) {
        var desc = ClassDesc.of(binaryName);
        return ClassFile.of().build(desc, clb -> clb
                .withSuperclass(ConstantDescs.CD_Object)
                .withMethodBody(ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, CD_STRING),
                        ClassFile.ACC_PUBLIC, cob -> {
                            cob.aload(0);
                            cob.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void));
                            cob.return_();
                        }));
    }

    /** {@code public class <proxy> extends <bean> { public <proxy>() { super((String) null); } }} */
    static byte[] proxyChainingBusinessCtor(String proxyName, String beanName) {
        var proxyDesc = ClassDesc.of(proxyName);
        var beanDesc = ClassDesc.of(beanName);
        return ClassFile.of().build(proxyDesc, clb -> clb
                .withSuperclass(beanDesc)
                .withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, cob -> {
                            cob.aload(0);
                            cob.aconst_null();
                            cob.invokespecial(beanDesc, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_STRING));
                            cob.return_();
                        }));
    }

    /** {@code true} when the (single) constructor chains {@code super((ProxyLink) null)}. */
    static boolean constructorChainsToMarker(byte[] classBytes, String superBinaryName) {
        var superDesc = ClassDesc.of(superBinaryName);
        for (var method : ClassFile.of().parse(classBytes).methods()) {
            if (!method.methodName().equalsString(ConstantDescs.INIT_NAME)) continue;
            for (var element : method.code().orElseThrow()) {
                if (element instanceof InvokeInstruction inv
                        && inv.name().equalsString(ConstantDescs.INIT_NAME)
                        && inv.owner().asSymbol().equals(superDesc)) {
                    return inv.typeSymbol().parameterCount() == 1
                            && inv.typeSymbol().parameterType(0).equals(CD_PROXY_LINK);
                }
            }
        }
        return false;
    }
}
