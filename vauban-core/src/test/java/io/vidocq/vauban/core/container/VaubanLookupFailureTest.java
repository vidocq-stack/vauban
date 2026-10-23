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

import jakarta.enterprise.inject.CreationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link VaubanLookup} propagates when a reflective call fails: the target's runtime exception or error as it
 * is, a checked exception wrapped in a {@link CreationException}, and a {@link ContainerOperationException} when a
 * field cannot be written.
 */
@DisplayName("VaubanLookup: failures of reflective calls")
class VaubanLookupFailureTest {

    private static final String FAILURE = "target failure";

    public static class Target {

        public void checkedWithRuntimeCause() throws IOException {
            throw new IOException(FAILURE, new IllegalArgumentException("runtime cause"));
        }

        public void checkedWithErrorCause() throws IOException {
            throw new IOException(FAILURE, new AssertionError("error cause"));
        }

        public void checkedWithCheckedCause() throws IOException {
            throw new IOException(FAILURE, new InterruptedException("checked cause"));
        }

        public void checked() throws IOException {
            throw new IOException(FAILURE);
        }

        @SuppressWarnings("java:S112") // a bare Throwable is what this case is about
        public void bareThrowable() throws Throwable {
            throw new Throwable(FAILURE);
        }

        public final String constant = "constant";

        public String echo() {
            return "echo";
        }
    }

    private static final VaubanLookup LOOKUP = new VaubanLookup(MethodHandles.lookup());

    private static Object invoke(String methodName) throws NoSuchMethodException {
        return LOOKUP.invokeMethod(new Target(), Target.class.getMethod(methodName));
    }

    @Test
    @DisplayName("a checked exception caused by a runtime exception propagates the runtime cause")
    void runtimeCause() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> invoke("checkedWithRuntimeCause"));

        assertEquals("runtime cause", thrown.getMessage());
    }

    @Test
    @DisplayName("a checked exception caused by an error throws the error")
    void errorCause() {
        var thrown = assertThrows(AssertionError.class, () -> invoke("checkedWithErrorCause"));

        assertEquals("error cause", thrown.getMessage());
    }

    @Test
    @DisplayName("a checked exception, or its checked cause, is wrapped in a CreationException")
    void checkedExceptions() {
        var direct = assertThrows(CreationException.class, () -> invoke("checked"));
        var caused = assertThrows(CreationException.class, () -> invoke("checkedWithCheckedCause"));

        assertInstanceOf(IOException.class, direct.getCause());
        assertInstanceOf(InterruptedException.class, caused.getCause());
    }

    @Test
    @DisplayName("a bare Throwable is wrapped in a CreationException")
    void bareThrowable() {
        var thrown = assertThrows(CreationException.class, () -> invoke("bareThrowable"));

        assertEquals(FAILURE, thrown.getCause().getMessage());
    }

    @Test
    @DisplayName("with component providers that own nothing, a call with null arguments falls back on reflection")
    void nullArgumentsThroughComponentProviders() throws NoSuchMethodException {
        var lookup = new VaubanLookup(MethodHandles.lookup());
        lookup.setComponentProviders(new ComponentProviders(List.of()));

        var result = lookup.invokeMethod(new Target(), Target.class.getMethod("echo"), (Object[]) null);

        assertEquals("echo", result);
    }

    @Test
    @DisplayName("writing a final field is a ContainerOperationException carrying the access failure")
    void finalFieldWrite() throws NoSuchFieldException {
        var field = Target.class.getField("constant");
        var target = new Target();

        var thrown = assertThrows(ContainerOperationException.class, () -> LOOKUP.setField(target, field, "changed"));

        assertTrue(thrown.getMessage().startsWith("Failed to set field"), thrown.getMessage());
        assertInstanceOf(IllegalAccessException.class, thrown.getCause());
        assertEquals("constant", target.constant);
    }

    @Test
    @DisplayName("a container operation failure carries its message or its cause")
    void containerOperationException() {
        var cause = new IllegalStateException("cause");

        assertEquals("message", new ContainerOperationException("message").getMessage());
        assertSame(cause, new ContainerOperationException(cause).getCause());
        assertNull(new ContainerOperationException("message").getCause());
    }
}
