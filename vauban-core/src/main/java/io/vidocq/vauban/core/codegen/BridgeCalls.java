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

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * What a compiler bridge calls, read from the class bytes of the class that declares it. Reflection tells a bridge
 * apart ({@link Method#isBridge()}) but not what it calls, and a run-time generator must know: a <em>visibility</em>
 * bridge copies a public method of a non-public superclass into a public class, with the method's own descriptor,
 * and stands for that method (BUG-20261118-16); a bridge that implements an interface method with a superclass
 * method of another erasure calls that method by {@code invokespecial}, past any override a subclass declares
 * (BUG-20261118-17); any other bridge calls a method of its own class virtually.
 *
 * <p>The class bytes come from the class's own loader, as for any class it defines from a resource. A class without
 * bytes there, one defined in memory, is judged by the names its classes declare instead ({@link #byNames}), which
 * an overload of the bridge's name and arity can mislead.
 */
public final class BridgeCalls {

    private BridgeCalls() {}

    /** What a bridge does. */
    public enum Kind {
        /** Copies a public method of a non-public superclass, same descriptor, which it calls by invokespecial. */
        VISIBILITY,
        /** Calls a superclass method of another descriptor by invokespecial. */
        SUPER_CALL,
        /** Calls a method of its own class: an override of that method catches it. */
        OWN
    }

    /**
     * What {@code bridge} calls.
     *
     * @param kind   its kind
     * @param target the superclass method it calls by {@code invokespecial}; {@code null} for {@link Kind#OWN}, or
     *               when the names alone judged a {@link Kind#SUPER_CALL}
     */
    public record Call(Kind kind, Method target) {}

    /** What {@code bridge}, a method for which {@link Method#isBridge()} holds, calls. */
    public static Call of(Method bridge) {
        return fromBytes(bridge).orElseGet(() -> byNames(bridge));
    }

    /** The JVM descriptor of {@code method}. */
    public static String descriptorOf(Method method) {
        return MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString();
    }

    private static Optional<Call> fromBytes(Method bridge) {
        var declaring = bridge.getDeclaringClass();
        var bytes = classBytes(declaring);
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        ClassModel model;
        try {
            model = ClassFile.of().parse(bytes.get());
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        var descriptor = descriptorOf(bridge);
        for (MethodModel method : model.methods()) {
            if (method.methodName().equalsString(bridge.getName()) && method.methodType().equalsString(descriptor)) {
                return method.code().map(code -> callIn(code, model, bridge, descriptor));
            }
        }
        return Optional.empty();
    }

    /** The call the code of {@code bridge} makes: an invokespecial on another class, else one of its own class. */
    private static Call callIn(CodeModel code, ClassModel model, Method bridge, String descriptor) {
        var self = model.thisClass().asInternalName();
        for (var element : code) {
            if (element instanceof InvokeInstruction invoke && invoke.opcode() == Opcode.INVOKESPECIAL
                    && !invoke.owner().asInternalName().equals(self)
                    && invoke.name().equalsString(bridge.getName())) {
                var called = invoke.type().stringValue();
                var target = superclassMethod(bridge.getDeclaringClass(), bridge.getName(), called);
                return new Call(called.equals(descriptor) ? Kind.VISIBILITY : Kind.SUPER_CALL, target);
            }
        }
        return new Call(Kind.OWN, null);
    }

    /** The method an invokespecial of {@code name} and {@code descriptor} from {@code type} resolves to. */
    private static Method superclassMethod(Class<?> type, String name, String descriptor) {
        for (Class<?> c = type.getSuperclass(); c != null; c = c.getSuperclass()) {
            for (var method : c.getDeclaredMethods()) {
                if (method.getName().equals(name) && descriptorOf(method).equals(descriptor)) {
                    return method;
                }
            }
        }
        return null;
    }

    /**
     * The judgement without class bytes: a bridge whose class declares another method of its name and arity calls
     * that one; one whose descriptor a superclass declares is a visibility bridge; any other calls a superclass
     * method of another descriptor, which the names do not tell.
     */
    static Call byNames(Method bridge) {
        var declaring = bridge.getDeclaringClass();
        for (var declared : declaring.getDeclaredMethods()) {
            if (!declared.isBridge() && declared.getName().equals(bridge.getName())
                    && declared.getParameterCount() == bridge.getParameterCount()) {
                return new Call(Kind.OWN, null);
            }
        }
        var copied = superclassMethod(declaring, bridge.getName(), descriptorOf(bridge));
        return copied != null ? new Call(Kind.VISIBILITY, copied) : new Call(Kind.SUPER_CALL, null);
    }

    /** The bytes of {@code type}'s class file, through its own class or module; empty when there are none. */
    private static Optional<byte[]> classBytes(Class<?> type) {
        var resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException | SecurityException _) {
            return Optional.empty();
        }
    }
}
