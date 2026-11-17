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

import io.vidocq.vauban.core.proxy.ForwardedMethods;
import io.vidocq.vauban.core.proxy.ForwardedMethods.Placement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The override key of a method seen from the class a walk starts from, the signature as a member of that class
 * (BUG-20261117-49): what the processor's view gives, so the walks need no bridge to tell that {@code echo(T)} of
 * {@code Base<T>} is overridden by {@code echo(String)}, and pass the bridges by.
 */
@DisplayName("ClassTypeView - a method's override key is its signature as a member of the walk's root")
class ClassTypeViewOverrideKeyTest {

    /** The key of {@code Base.echo(T)} seen erased. */
    private static final String ECHO_ERASED = "echo[class java.lang.Object]";

    /** The keys of {@code Base}'s methods seen erased. */
    private static final List<String> ERASED = List.of(ECHO_ERASED, "all[class [Ljava.lang.Object;]");

    public static class Base<T> {
        public String echo(T value) { return "base " + value; }

        public String all(T[] values) { return "base " + values.length; }
    }

    public static class Child extends Base<String> {
    }

    public static class Mid<X> extends Base<X> {
    }

    public static class Leaf extends Mid<Integer> {
    }

    /** Binds {@code Base}'s variable to its own, bounded one. */
    public static class Bounded<N extends Number> extends Base<N> {
    }

    @SuppressWarnings("rawtypes")
    public static class RawChild extends Base {
    }

    @SuppressWarnings("rawtypes")
    public static class AboveRaw extends RawChild {
    }

    public static class Outer<O> {
        public class Inner {
            public String take(O value) { return "took " + value; }
        }
    }

    public static class StringOuter extends Outer<String> {
        public class InnerChild extends Inner {
        }
    }

    public static class Plain {
        public String echo(Object value) { return "plain " + value; }
    }

    public static class PlainChild extends Plain {
    }

    @Test
    @DisplayName("a superclass's type variable takes the argument the root gives it, through every class between")
    void typeVariablesTakeTheRootsArguments() {
        assertEquals(List.of("echo[class java.lang.String]", "all[class [Ljava.lang.String;]"), keysAbove(Child.class));
        assertEquals(List.of("echo[class java.lang.Integer]", "all[class [Ljava.lang.Integer;]"),
                keysAbove(Leaf.class));
        assertEquals(List.of("echo[class java.lang.Number]", "all[class [Ljava.lang.Number;]"),
                keysAbove(Bounded.class), "a variable of the root itself is erased to its bound");
    }

    @Test
    @DisplayName("a class extended raw is seen erased, and so is every class above it")
    void aRawSuperclassIsErased() {
        assertEquals(ERASED, keysAbove(RawChild.class));
        assertEquals(ERASED, keysAbove(AboveRaw.class));
    }

    @Test
    @DisplayName("a generic signature that names a class the loader cannot find gives the erased key")
    void aMissingClassInASignatureGivesTheErasedKey() throws IllegalAccessException {
        // As an optional dependency absent at run time leaves it: the walk goes on, on the erased declarations.
        var lookup = MethodHandles.lookup();
        var missingArgument = lookup.defineClass(subclass("MissingArgumentChild", Base.class,
                "L" + internalName(Base.class) + "<Lio/vidocq/missing/Absent;>;"));
        assertEquals(ERASED, keysAbove(missingArgument), "in the superclass's arguments");

        var brokenBase = lookup.defineClass(brokenMethodBase());
        var brokenChild = lookup.defineClass(subclass("BrokenMethodChild", brokenBase,
                "L" + internalName(brokenBase) + "<Ljava/lang/String;>;"));
        assertEquals(List.of("take[class java.lang.Object, interface java.util.List]"), keysAbove(brokenChild),
                "in a method's parameters: T is not read as String");
    }

    @Test
    @DisplayName("a variable of an enclosing class takes the argument of the enclosing type")
    void anEnclosingClassVariableTakesTheEnclosingTypesArgument() {
        assertEquals(List.of("take[class java.lang.String]"), keysAbove(StringOuter.InnerChild.class));
    }

    @Test
    @DisplayName("a method of a class that binds nothing keeps the key of its declaration")
    void nothingBoundKeepsTheDeclaration() {
        assertEquals(List.of(ECHO_ERASED), keysAbove(PlainChild.class));
        assertEquals(ECHO_ERASED,
                ClassTypeView.member(declared(Base.class, "echo")).overrideKey(), "a member of its declaring class");
    }

    /** Implements {@code Supplier<String>}: javac adds the bridge {@code Object get()}, sorted first. */
    public static class OwnSupplier implements Supplier<String> {
        @Override
        public String get() { return "own"; }
    }

    /** Overrides {@code echo(T)} with {@code echo(String)}: javac adds the bridge {@code echo(Object)}. */
    public static class OverridingChild extends Base<String> {
        @Override
        public String echo(String value) { return "child " + value; }
    }

    @Test
    @DisplayName("the walk forwards the method a bridge stands for, and the method it overrides once, wherever placed")
    void theWalkPassesTheBridgesBy() {
        var overriding = List.of("java.lang.String all(java.lang.Object[])", "java.lang.String echo(java.lang.String)");
        for (var placement : Placement.values()) {
            assertEquals(List.of("java.lang.String get()"), forwarded(OwnSupplier.class, placement), placement.name());
            assertEquals(overriding, forwarded(OverridingChild.class, placement), placement.name());
        }
    }

    /**
     * The override keys, seen from {@code root}, of the methods the first class above it that declares any declares,
     * compiler-generated ones left out (a coverage agent adds one to every class).
     */
    private static List<String> keysAbove(Class<?> root) {
        var keys = new ArrayList<String>();
        for (var above = ClassTypeView.of(root).superclass(); keys.isEmpty(); above = above.superclass()) {
            for (var m : above.methods()) {
                if (!m.isBridge()) {
                    keys.add(m.overrideKey());
                }
            }
        }
        return keys.stream().sorted(Comparator.reverseOrder()).toList();
    }

    /** The class methods a proxy of {@code type}, placed as {@code placement} says, forwards, Object's left out. */
    private static List<String> forwarded(Class<?> type, Placement placement) {
        return ForwardedMethods.select(ClassTypeView.of(type), placement).forwards().stream()
                .filter(f -> !f.declaring().isObject())
                .map(f -> f.method().returnType() + " " + f.method().name() + "("
                        + String.join(",", f.method().parameterTypes()) + ")")
                .sorted()
                .toList();
    }

    /** A public class of this package, {@code simpleName}, that extends {@code superclass} as its signature says. */
    private static byte[] subclass(String simpleName, Class<?> superclass, String superclassSignature) {
        var parent = superclass.describeConstable().orElseThrow();
        return ClassFile.of().build(ClassDesc.of(packageName() + "." + simpleName), cb -> cb
                .withFlags(AccessFlag.PUBLIC, AccessFlag.SUPER)
                .withSuperclass(parent)
                .with(SignatureAttribute.of(ClassSignature.parseFrom(superclassSignature)))
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(parent, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_()));
    }

    /** {@code BrokenMethodBase<T>}, whose {@code take(T, List<Absent>)} names a class no loader finds. */
    private static byte[] brokenMethodBase() {
        return ClassFile.of().build(ClassDesc.of(packageName() + ".BrokenMethodBase"), cb -> cb
                .withFlags(AccessFlag.PUBLIC, AccessFlag.SUPER)
                .withSuperclass(ConstantDescs.CD_Object)
                .with(SignatureAttribute.of(ClassSignature.parseFrom("<T:Ljava/lang/Object;>Ljava/lang/Object;")))
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_())
                .withMethod("take", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object,
                        ConstantDescs.CD_List), ClassFile.ACC_PUBLIC, method -> method
                                .with(SignatureAttribute.of(MethodSignature.parseFrom(
                                        "(TT;Ljava/util/List<Lio/vidocq/missing/Absent;>;)V")))
                                .withCode(CodeBuilder::return_)));
    }

    private static String packageName() {
        return ClassTypeViewOverrideKeyTest.class.getPackageName();
    }

    private static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static java.lang.reflect.Method declared(Class<?> type, String name) {
        for (var m : type.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError(type + " declares no " + name);
    }
}
