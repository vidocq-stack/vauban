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

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.util.function.Consumer;

/**
 * Builds every class Vauban generates, at the project's Java release rather than at the release of
 * the JDK that happens to run the build.
 *
 * <p>{@link ClassFile#of()} writes a new class at {@link ClassFile#latestMajorVersion()}, which is
 * the version of the running JDK. That is harmless for a class defined into the same JVM, and wrong
 * for every class written to disk: a brick compiled with {@code --release 25} on a JDK 26 shipped
 * factories at class file 70 next to javac's own 69, and the Java 25 runtime refused them with
 * {@code UnsupportedClassVersionError} — {@code jlink} refused the image too (BUG-20260911-01).
 *
 * <p>Generated classes therefore target {@link #MAJOR_VERSION}. Raise it only together with the
 * project's baseline: a class file may always be older than the runtime that loads it, and older
 * than the class it extends.
 */
public final class GeneratedClassFile {

    /** The class file version of every generated class: Java 25, the project's baseline. */
    public static final int MAJOR_VERSION = ClassFile.JAVA_25_VERSION;

    private GeneratedClassFile() {}

    /**
     * Builds a class at {@link #MAJOR_VERSION}. Same contract as
     * {@link ClassFile#build(ClassDesc, Consumer)}, minus the dependency on the build JDK.
     *
     * @param thisClass the class to generate
     * @param handler   fills in the flags, superclass, fields and methods
     * @return the class file bytes
     */
    public static byte[] build(ClassDesc thisClass, Consumer<? super ClassBuilder> handler) {
        return ClassFile.of().build(thisClass, clb -> {
            clb.withVersion(MAJOR_VERSION, 0);
            handler.accept(clb);
        });
    }
}
