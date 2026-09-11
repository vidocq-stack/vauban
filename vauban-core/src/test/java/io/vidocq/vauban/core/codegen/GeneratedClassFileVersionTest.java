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
package io.vidocq.vauban.core.codegen;

import io.vidocq.vauban.core.interceptor.InterceptedEmitter;
import io.vidocq.vauban.core.interceptor.InterceptedShape;
import io.vidocq.vauban.core.provider.ComponentProviderClassGenerator;
import io.vidocq.vauban.core.proxy.ClientProxyEmitter;
import io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator;
import io.vidocq.vauban.indexer.codegen.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every class Vauban generates must be written at the project's Java release, not at the release of
 * the JDK that happens to run the build. A build on a newer JDK would otherwise ship class files the
 * baseline runtime cannot load: {@code UnsupportedClassVersionError} at deployment, and {@code jlink}
 * refusing the image (vauban BUG-20260911-01).
 */
@DisplayName("Generated class files target the project's Java release, not the build JDK")
class GeneratedClassFileVersionTest {

    /** A plain proxyable bean: public, non-final, with an implicit no-arg constructor. */
    public static class Bean {
        public String hello() {
            return "hi";
        }
    }

    @Test
    @DisplayName("client proxy, component provider and interceptor subclass are all Java 25 class files")
    void everyEmitterTargetsTheProjectRelease() {
        assertEquals(ClassFile.JAVA_25_VERSION,
                majorOf(ClientProxyEmitter.emit(RuntimeClientProxyGenerator.shapeOf(Bean.class))),
                "client proxy");
        assertEquals(ClassFile.JAVA_25_VERSION,
                majorOf(ComponentProviderClassGenerator.generate(
                        "io.vidocq.vauban.core.codegen._VersionTestComponents",
                        List.of(new Component(Bean.class.getName(), List.of()))).bytecode()),
                "component provider");
        assertEquals(ClassFile.JAVA_25_VERSION,
                majorOf(InterceptedEmitter.emit(
                        new InterceptedShape(Bean.class.getName(), List.of(), List.of()))),
                "interceptor subclass");
    }

    private static int majorOf(byte[] bytecode) {
        return ClassFile.of().parse(bytecode).majorVersion();
    }
}
