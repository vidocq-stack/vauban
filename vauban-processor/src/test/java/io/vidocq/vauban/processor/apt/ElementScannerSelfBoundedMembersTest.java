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
package io.vidocq.vauban.processor.apt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261117-36: the processor reads the type of a field, a parameter or a return as the bytecode scan does: a type
 * variable met again while its own bounds are read ({@code T} in {@code <T extends Comparable<T>>}) has no bound, so
 * the model stays finite. The processor's scan read the bounds of such a variable again and again, until the
 * compilation failed with a {@code StackOverflowError}.
 */
@DisplayName("BUG-20261117-36: a member typed by a type variable bounded by itself compiles")
class ElementScannerSelfBoundedMembersTest {

    @TempDir
    Path workDir;

    @Test
    @DisplayName("a bean with a field, a parameter and a return of a type variable bounded by itself")
    void aBeanWithASelfBoundedMemberCompiles() throws IOException {
        var result = ProcessorCompiler.compile(workDir, Map.of("app/Box.java", """
                package app;
                @jakarta.enterprise.context.Dependent
                public class Box<T extends Comparable<T>> {
                    private T value;
                    public T get() {
                        return value;
                    }
                    public void set(T value) {
                        this.value = value;
                    }
                }
                """));

        assertTrue(result.success(), () -> "the compilation must succeed: " + result.messages());
    }
}
