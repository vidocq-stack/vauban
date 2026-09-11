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
package io.vidocq.vauban.processor.codegen.factory;

import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bean factory is the one class the processor still writes as bytecode rather than source, so it
 * is also the one that silently followed the build JDK: a brick compiled on a newer JDK shipped
 * factories the baseline runtime refused to load (vauban BUG-20260911-01).
 */
@DisplayName("Generated bean factories target the project's Java release, not the build JDK")
class BeanFactoryClassVersionTest {

    /** A bean with nothing but the implicit no-arg constructor. */
    public static class SimpleBean {
    }

    @Test
    @DisplayName("the generated _Factory is a Java 25 class file")
    void factoryTargetsTheProjectRelease() throws IOException {
        var beanClass = ClassFileScanner.scan(bytesOf(SimpleBean.class));

        var generated = BeanFactoryGenerator.generate(beanClass);

        assertEquals(ClassFile.JAVA_25_VERSION,
                ClassFile.of().parse(generated.bytecode()).majorVersion(),
                "javac writes the bean at the project's release; its factory must match, whatever "
                        + "JDK runs the annotation processor");
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (var is = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            return is.readAllBytes();
        }
    }
}
