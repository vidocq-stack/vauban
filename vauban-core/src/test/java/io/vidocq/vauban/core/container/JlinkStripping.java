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
package io.vidocq.vauban.core.container;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.SourceDebugExtensionAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;

/**
 * What {@code jlink --strip-debug} does to a class file, as the JDK's {@code StripJavaDebugAttributesPlugin} does it:
 * the source file, the line numbers, the local variables and the parameter names go, the code is written again.
 */
final class JlinkStripping {

    private JlinkStripping() {
    }

    static byte[] strip(byte[] classFile) {
        var stripping = ClassFile.of(ClassFile.DebugElementsOption.DROP_DEBUG,
                ClassFile.LineNumbersOption.DROP_LINE_NUMBERS);
        return stripping.transformClass(stripping.parse(classFile),
                ClassTransform.dropping(element -> element instanceof SourceFileAttribute
                                || element instanceof SourceDebugExtensionAttribute)
                        .andThen(ClassTransform.transformingMethods(
                                MethodTransform.dropping(MethodParametersAttribute.class::isInstance)
                                        .andThen(MethodTransform.transformingCode(CodeTransform.ACCEPT_ALL)))));
    }
}
