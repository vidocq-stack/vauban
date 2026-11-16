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
package io.vidocq.vauban.core.proxy;

import io.vidocq.vauban.core.codegen.ClassTypeView;
import io.vidocq.vauban.core.interceptor.TypeRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client proxy emitted as bytecode follows the contract of the source one (vauban#227): {@code final}, so no
 * class can extend it, and {@code @Vetoed}, so a container that scans the archive never takes it for a bean. It is
 * {@code Serializable} and writes its delegate in its place, so a session value that holds it can move to another
 * container (foy#114).
 */
@DisplayName("ClientProxyEmitter — final and @Vetoed, like the source proxy")
class ClientProxyEmitterContractTest {

    private static final ClientProxyShape SHAPE = new ClientProxyShape("com.example.MyService", List.of(), List.of(
            new ClientProxyShape.ProxyMethodShape("run", TypeRef.ofVoid(), List.of(), List.of(), false)));

    @Test
    @DisplayName("the emitted proxy class is final and annotated @Vetoed")
    void finalAndVetoed() {
        var model = ClassFile.of().parse(ClientProxyEmitter.emit(SHAPE));

        assertTrue(model.flags().has(AccessFlag.FINAL), "final");
        var annotations = model.findAttribute(Attributes.runtimeVisibleAnnotations()).orElseThrow().annotations();
        assertTrue(annotations.stream().anyMatch(a -> a.className().stringValue()
                .equals("Ljakarta/enterprise/inject/Vetoed;")), String.valueOf(annotations));
    }

    @Test
    @DisplayName("the emitted proxy is Serializable, with a serialVersionUID and a transient delegate")
    void serializable() {
        var model = ClassFile.of().parse(ClientProxyEmitter.emit(SHAPE));

        assertTrue(model.interfaces().stream().anyMatch(i -> i.asInternalName().equals("java/io/Serializable")),
                String.valueOf(model.interfaces()));
        var uid = field(model, "serialVersionUID");
        assertEquals("J", uid.fieldType().stringValue());
        assertTrue(uid.flags().has(AccessFlag.PRIVATE) && uid.flags().has(AccessFlag.STATIC)
                && uid.flags().has(AccessFlag.FINAL), String.valueOf(uid.flags()));
        assertTrue(uid.findAttribute(Attributes.constantValue()).isPresent(), "a constant serialVersionUID");
        var delegate = field(model, ClientProxyShape.FIELD_DELEGATE);
        assertTrue(delegate.flags().has(AccessFlag.TRANSIENT), String.valueOf(delegate.flags()));
    }

    @Test
    @DisplayName("the emitted proxy declares private Object writeReplace() throws ObjectStreamException, returning its delegate")
    void writeReplaceReturnsTheDelegate() {
        var model = ClassFile.of().parse(ClientProxyEmitter.emit(SHAPE));

        var writeReplace = onlyMethod(model, "writeReplace");
        assertEquals("()Ljava/lang/Object;", writeReplace.methodType().stringValue());
        assertTrue(writeReplace.flags().has(AccessFlag.PRIVATE), String.valueOf(writeReplace.flags()));
        var thrown = writeReplace.findAttribute(Attributes.exceptions()).orElseThrow().exceptions();
        assertEquals(List.of("java/io/ObjectStreamException"),
                thrown.stream().map(ClassEntry::asInternalName).toList());
        var code = writeReplace.code().orElseThrow();
        var reads = code.elementStream()
                .filter(e -> e instanceof FieldInstruction f && f.opcode() == Opcode.GETFIELD
                        && f.name().equalsString(ClientProxyShape.FIELD_DELEGATE))
                .count();
        assertEquals(1, reads, "the body returns this.$$delegate");
    }

    @Test
    @DisplayName("an overriding writeReplace is public and declares no exception, as the source one")
    void anOverridingWriteReplaceDeclaresNoException() {
        var overriding = new ClientProxyShape(SHAPE.beanBinaryName(), List.of(), SHAPE.methods(), List.of(), true);
        var model = ClassFile.of().parse(ClientProxyEmitter.emit(overriding));

        var writeReplace = onlyMethod(model, "writeReplace");
        assertTrue(writeReplace.flags().has(AccessFlag.PUBLIC), String.valueOf(writeReplace.flags()));
        assertTrue(writeReplace.findAttribute(Attributes.exceptions()).isEmpty(),
                "no Exceptions attribute: the source override has no throws clause");
    }

    @Test
    @DisplayName("a protected writeReplace is exposed to a proxy in another package too, which inherits it")
    void aProtectedWriteReplaceIsExposedInAnotherPackage() {
        var type = ClassTypeView.of(OwnHooks.class);

        assertTrue(ForwardedMethods.exposesWriteReplace(type, ForwardedMethods.Placement.TYPE_PACKAGE));
        assertTrue(ForwardedMethods.exposesWriteReplace(type, ForwardedMethods.Placement.OTHER_PACKAGE));
    }

    @Test
    @DisplayName("a writeReplace returning another type than Object is no hook to override: the proxy's own is private")
    void aWriteReplaceOfAnotherTypeIsNotOverridden() throws Exception {
        var shape = RuntimeClientProxyGenerator.shapeOf(TypedHook.class);

        assertFalse(shape.overridesWriteReplace(), "String writeReplace() is not overridden by Object writeReplace()");
        assertFalse(RuntimeClientProxyGenerator.shapeOf(LabelledBean.class).overridesWriteReplace(),
                "nor is an interface's String writeReplace()");
        var generated = RuntimeClientProxyGenerator.generate(TypedHook.class);
        var model = ClassFile.of().parse(generated.bytecode());
        var writeReplace = onlyMethod(model, "writeReplace");
        assertEquals("()Ljava/lang/Object;", writeReplace.methodType().stringValue(),
                "a forwarded String writeReplace() would hide the hook from serialization");
        assertTrue(writeReplace.flags().has(AccessFlag.PRIVATE), String.valueOf(writeReplace.flags()));

        var proxyClass = MethodHandles.privateLookupIn(TypedHook.class, MethodHandles.lookup())
                .defineClass(generated.bytecode());
        var proxy = proxyClass.getDeclaredConstructor(Supplier.class).newInstance(new Delegate("typed"));
        assertEquals(new Delegate("typed"), roundTrip(proxy), "the proxy writes its delegate");
    }

    @Test
    @DisplayName("a bean with a static writeReplace gets a proxy that loads and writes its delegate")
    void aStaticWriteReplaceOfTheBeanIsLeftAlone() throws Exception {
        var generated = RuntimeClientProxyGenerator.generate(StaticHook.class);
        var model = ClassFile.of().parse(generated.bytecode());
        assertTrue(onlyMethod(model, "writeReplace").flags().has(AccessFlag.PRIVATE));

        var proxyClass = MethodHandles.privateLookupIn(StaticHook.class, MethodHandles.lookup())
                .defineClass(generated.bytecode());
        var proxy = proxyClass.getDeclaredConstructor(Supplier.class).newInstance(new Delegate("static"));
        assertEquals(new Delegate("static"), roundTrip(proxy), "the proxy wrote its delegate");
    }

    @Test
    @DisplayName("a bean declaring its own writeReplace and readResolve gets a proxy with neither forwarded")
    void aBeanWithItsOwnSerialHooksGetsNoDuplicate() throws Exception {
        var shape = RuntimeClientProxyGenerator.shapeOf(OwnHooks.class);
        var forwarded = shape.methods().stream().map(ClientProxyShape.ProxyMethodShape::name).toList();
        assertFalse(forwarded.contains("writeReplace"), String.valueOf(forwarded));
        assertFalse(forwarded.contains("readResolve"), String.valueOf(forwarded));
        assertTrue(forwarded.contains("name"), String.valueOf(forwarded));

        var generated = RuntimeClientProxyGenerator.generate(OwnHooks.class);
        var model = ClassFile.of().parse(generated.bytecode());
        var writeReplace = onlyMethod(model, "writeReplace");
        // The bean's protected writeReplace is overridden, so the proxy's own is public.
        assertTrue(writeReplace.flags().has(AccessFlag.PUBLIC), String.valueOf(writeReplace.flags()));
        assertTrue(model.methods().stream().noneMatch(m -> m.methodName().equalsString("readResolve")),
                "readResolve is not forwarded");

        // The class loads, and writes its delegate rather than the bean's own replacement.
        var proxyClass = MethodHandles.privateLookupIn(OwnHooks.class, MethodHandles.lookup())
                .defineClass(generated.bytecode());
        var proxy = proxyClass.getDeclaredConstructor(Supplier.class).newInstance(new Delegate("hooks"));
        assertEquals("own", ((OwnHooks) proxy).name(), "the proxy forwards to its delegate");
        assertEquals(new Delegate("hooks"), roundTrip(proxy), "the proxy wrote its delegate");
    }

    @Test
    @DisplayName("a bean whose own writeReplace is private gets the private hook")
    void aPrivateWriteReplaceOfTheBeanIsNotOverridden() {
        var shape = RuntimeClientProxyGenerator.shapeOf(PrivateHook.class);

        assertFalse(shape.overridesWriteReplace(), "a private method is not inherited");
        var model = ClassFile.of().parse(RuntimeClientProxyGenerator.generate(PrivateHook.class).bytecode());
        assertTrue(onlyMethod(model, "writeReplace").flags().has(AccessFlag.PRIVATE));
    }

    @Test
    @DisplayName("a writeReplace the bean inherits from an interface is overridden by the proxy's own, public")
    void anInterfaceWriteReplaceIsOverridden() throws Exception {
        var shape = RuntimeClientProxyGenerator.shapeOf(DefaultHooked.class);

        assertTrue(shape.overridesWriteReplace(), "a default method of an interface is public");
        assertFalse(shape.methods().stream().anyMatch(m -> m.name().equals("writeReplace")),
                String.valueOf(shape.methods()));
        var generated = RuntimeClientProxyGenerator.generate(DefaultHooked.class);
        var model = ClassFile.of().parse(generated.bytecode());
        assertTrue(onlyMethod(model, "writeReplace").flags().has(AccessFlag.PUBLIC));
        var proxyClass = MethodHandles.privateLookupIn(DefaultHooked.class, MethodHandles.lookup())
                .defineClass(generated.bytecode());
        var proxy = proxyClass.getDeclaredConstructor(Supplier.class).newInstance(new Delegate("default"));
        assertEquals(new Delegate("default"), ((Hooked) proxy).writeReplace(), "the proxy's own, not the default");
    }

    // --- fixtures ---

    /** An interface whose default writeReplace a class inherits. */
    public interface Hooked {
        default Object writeReplace() {
            return "default replacement";
        }
    }

    /** A bean that inherits its writeReplace from an interface. */
    public static class DefaultHooked implements Hooked {
        public String name() {
            return "hooked";
        }
    }

    /** A bean that declares its own serial hooks, visible to a subclass. */
    public static class OwnHooks implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        public String name() {
            return "own";
        }

        @Serial
        protected Object writeReplace() throws ObjectStreamException {
            return "own replacement";
        }

        @Serial
        protected Object readResolve() throws ObjectStreamException {
            return this;
        }
    }

    /** A bean whose writeReplace a subclass does not see. */
    public static class PrivateHook implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        public String name() {
            return "private";
        }

        @Serial
        private Object writeReplace() throws ObjectStreamException {
            return "private replacement";
        }
    }

    /** A bean whose writeReplace returns a String: a business method, which serialization does not call. */
    public static class TypedHook {

        public String writeReplace() {
            return "typed";
        }
    }

    /** An interface whose default writeReplace returns a String. */
    public interface Labelled {
        default String writeReplace() {
            return "a label";
        }
    }

    /** A bean that inherits a String writeReplace from an interface. */
    public static class LabelledBean implements Labelled {
        public String name() {
            return "labelled";
        }
    }

    /** A bean with a static writeReplace, which a subclass's own instance method cannot override in source. */
    public static class StaticHook {

        public String name() {
            return "static";
        }

        public static Object writeReplace() {
            return "static replacement";
        }
    }

    /** A delegate that writes itself as it is. */
    record Delegate(String name) implements Supplier<Object>, Serializable {
        @Override
        public Object get() {
            return new OwnHooks();
        }
    }

    private static Object roundTrip(Object value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return in.readObject();
        }
    }

    private static FieldModel field(ClassModel model, String name) {
        return model.fields().stream().filter(f -> f.fieldName().equalsString(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no field " + name));
    }

    private static MethodModel onlyMethod(ClassModel model, String name) {
        var methods = model.methods().stream().filter(m -> m.methodName().equalsString(name)).toList();
        assertEquals(1, methods.size(), "one method " + name + ": " + methods);
        return methods.getFirst();
    }
}
