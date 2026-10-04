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
package io.vidocq.vauban.processor.codegen.proxy;

import io.vidocq.vauban.core.proxy.ClientProxyShape;
import io.vidocq.vauban.core.proxy.RuntimeClientProxyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-check between the two client-proxy code paths, which must not diverge: the APT/source path
 * ({@link ProducerProxyEligibility}, {@link ClientProxyShapeFromElements}) and the bytecode path
 * ({@link io.vidocq.vauban.core.proxy.ProducerProxyEligibility}, {@link RuntimeClientProxyGenerator}).
 *
 * <p>The bytecode path walks the superclass hierarchy; the source path historically looked only at
 * {@code getEnclosedElements()} — declared methods. That asymmetry silently produced a proxy which
 * neither overrides nor forwards an <em>inherited</em> method: invoking it on the client proxy runs
 * the superclass body against the proxy's own (default-initialised) state instead of the contextual
 * instance. This test pins the two paths together.
 *
 * <p>Deliberate residual divergence: the source path stops at {@code java.lang.Object}, whereas the
 * bytecode path also forwards {@code toString/equals/hashCode}. CDI 4.1 leaves the behaviour of
 * {@code Object} methods (except {@code toString}) undefined, so the comparison below excludes them.
 * And a method whose parameters or return type use a type variable the bean binds is declared, in
 * source, with the signature the bean sees ({@code echo(String)}), in bytecode with the erased
 * descriptor ({@code echo(Object)}): each is the only override its language accepts.
 */
@DisplayName("Client proxy — source and bytecode paths agree on inherited members")
class ClientProxyInheritanceCrossCheckTest {

    // ---- Fixtures: all bases are in this same package, so only visibility varies ----

    /** A clean, fully-public base: its method must be forwarded by the proxy. */
    public static class CleanBase {
        public String inherited() { return "base"; }
    }

    /** Eligible: everything overridable is public, declared or inherited. */
    public static class CleanChild extends CleanBase {
        public String own() { return "own"; }
    }

    /** A base with a public {@code final} method — CDI 4.1 §3.10 makes the subtype unproxyable. */
    public static class FinalBase {
        public final String pinned() { return "pinned"; }
    }

    /** Ineligible through inheritance only: nothing is final here. */
    public static class FinalChild extends FinalBase {
        public String own() { return "own"; }
    }

    /** A base with a package-private overridable method: not overridable across packages. */
    public static class HiddenBase {
        String packagePrivate() { return "hidden"; }
    }

    /** Ineligible through inheritance only. */
    public static class HiddenChild extends HiddenBase {
        public String own() { return "own"; }
    }

    // ---- Eligibility parity ----

    @Test
    @DisplayName("an inherited public final method makes the produced type ineligible")
    void inheritedFinalIsRejected() throws Exception {
        assertEquals(ProducerProxyEligibility.Reason.FINAL_VIRTUALS.name(),
                eligibilityFromElements(FinalChild.class),
                "the source path ignored a final method inherited from " + FinalBase.class.getSimpleName()
                        + " and would have emitted a proxy that silently fails to forward it");
    }

    @Test
    @DisplayName("an inherited package-private method makes the produced type ineligible")
    void inheritedPackagePrivateIsRejected() throws Exception {
        assertEquals(ProducerProxyEligibility.Reason.PACKAGE_PRIVATE_VIRTUALS.name(),
                eligibilityFromElements(HiddenChild.class));
    }

    @Test
    @DisplayName("a fully-public hierarchy stays eligible")
    void cleanHierarchyStaysEligible() throws Exception {
        assertEquals(ProducerProxyEligibility.Reason.ELIGIBLE.name(),
                eligibilityFromElements(CleanChild.class));
    }

    @Test
    @DisplayName("every verdict matches the bytecode path's verdict for the same type")
    void verdictsMatchTheBytecodePath() throws Exception {
        for (var fixture : List.of(CleanChild.class, FinalChild.class, HiddenChild.class)) {
            assertEquals(
                    io.vidocq.vauban.core.proxy.ProducerProxyEligibility.of(fixture).name(),
                    eligibilityFromElements(fixture),
                    "source and bytecode eligibility disagree on " + fixture.getSimpleName());
        }
    }

    // ---- Shape parity ----

    @Test
    @DisplayName("the source shape forwards inherited public methods, like the bytecode shape")
    void shapeIncludesInheritedMethods() throws Exception {
        Set<String> fromElements = shapeFromElements(CleanChild.class);
        assertTrue(fromElements.contains("inherited()"),
                "the proxy would not forward CleanBase.inherited(); it would run against the "
                        + "proxy's empty state. Shape was: " + fromElements);

        ClientProxyShape fromClass = RuntimeClientProxyGenerator.shapeOf(CleanChild.class);
        Set<String> expected = fromClass.methods().stream()
                .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                        .collect(Collectors.joining(",")) + ")")
                .filter(k -> !OBJECT_METHODS.contains(k))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(expected, fromElements, "source and bytecode shapes disagree");
    }

    /** Inherits a protected member from a superclass in another package. */
    public static class ForeignChild extends io.vidocq.vauban.processor.fixture.colocated.ForeignBase {
        public String own() { return "own"; }
    }

    @Test
    @DisplayName("the co-located shape forwards inherited non-public members, like the bytecode shape")
    void colocatedShapeMatchesTheBytecodePath() throws Exception {
        // A placed proxy (#42 Stage 4) lives in the produced type's own package. There an inherited
        // package-private member of a same-package superclass CAN be overridden, and an inherited
        // protected one can be forwarded through a MethodHandle. Leaving either out lets a class of
        // that package call it on the proxy and read the proxy's own empty state.
        Set<String> hidden = colocatedShapeFromElements(HiddenChild.class);
        assertTrue(hidden.contains("packagePrivate()"),
                "HiddenBase.packagePrivate() must be forwarded by a co-located proxy. Shape was: " + hidden);

        for (var fixture : List.of(HiddenChild.class, ForeignChild.class, CleanChild.class)) {
            ClientProxyShape fromClass = RuntimeClientProxyGenerator.shapeOf(fixture);
            Set<String> expected = fromClass.methods().stream()
                    .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                            .collect(Collectors.joining(",")) + ")" + (m.needsMethodHandle() ? "#mh" : ""))
                    .filter(k -> !OBJECT_METHODS.contains(k.replace("#mh", "")))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            assertEquals(expected, colocatedShapeFromElements(fixture),
                    "co-located source and bytecode shapes disagree on " + fixture.getSimpleName()
                            + " (method set or MethodHandle dispatch)");
        }
    }

    /** Default methods: one inherited as is, one overridden by the class, one re-declared lower. */
    public interface Greeting {
        default String greet() { return "hi"; }
        default String rebound() { return "iface"; }
        default String refined() { return "greeting"; }
    }

    /** Overrides {@link Greeting#refined()} with a default method of its own. */
    public interface RefinedGreeting extends Greeting {
        @Override
        default String refined() { return "refined"; }
    }

    public static class DefaultChild implements RefinedGreeting {
        public String own() { return "own"; }

        @Override
        public String rebound() { return "class"; }
    }

    @Test
    @DisplayName("every path forwards an inherited interface default method, once (BUG-20261004-02)")
    void defaultMethodsAreForwarded() throws Exception {
        // Not forwarding greet() lets a call on the proxy run the default body against the proxy
        // itself: the contextual instance and its interceptors are bypassed.
        ClientProxyShape fromClass = RuntimeClientProxyGenerator.shapeOf(DefaultChild.class);
        Set<String> bytecode = fromClass.methods().stream()
                .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                        .collect(Collectors.joining(",")) + ")" + (m.needsMethodHandle() ? "#mh" : ""))
                .filter(k -> !OBJECT_METHODS.contains(k))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> expected = Set.of("own()", "rebound()", "greet()", "refined()");
        assertEquals(expected, bytecode, "bytecode shape");
        assertEquals(fromClass.methods().size() - 3, expected.size(),
                "each signature once (plus the 3 Object methods): " + fromClass.methods());
        assertEquals(expected, shapeFromElements(DefaultChild.class), "source shape");
        assertEquals(expected, colocatedShapeFromElements(DefaultChild.class), "co-located shape");
    }

    /** A generic base and interface whose methods take their type variable. */
    public static class TaggedBase<T> {
        public String echo(T value) { return "echo " + value; }
    }

    public interface Tagged<T> {
        default String tag(T value) { return "tag " + value; }
    }

    public static class GenericChild extends TaggedBase<String> implements Tagged<String> {
    }

    @Test
    @DisplayName("the source shape overrides a generic method as the bean sees it, the bytecode shapes by descriptor")
    void genericMethodsUseTheMemberSignatureInSource() throws Exception {
        // Java source can only override echo(String) in a subclass of TaggedBase<String>: echo(Object)
        // is a name clash (BUG-20261004-06). A class file overrides by descriptor, echo(Object).
        assertEquals(Set.of("echo(java.lang.String)", "tag(java.lang.String)"),
                shapeFromElements(GenericChild.class), "source shape");
        Set<String> bytecode = Set.of("echo(java.lang.Object)", "tag(java.lang.Object)");
        assertEquals(bytecode, colocatedShapeFromElements(GenericChild.class), "co-located (bytecode) shape");
        assertEquals(bytecode, RuntimeClientProxyGenerator.shapeOf(GenericChild.class).methods().stream()
                .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                        .collect(Collectors.joining(",")) + ")")
                .filter(k -> !OBJECT_METHODS.contains(k))
                .collect(Collectors.toSet()), "run-time shape");
    }

    /** Overrides the generic {@link TaggedBase#echo} with the type it binds. */
    public static class OverridingGenericChild extends TaggedBase<String> {
        @Override
        public String echo(String value) { return "child " + value; }
    }

    /** A plain superclass whose method implements {@code Tagged<String>.tag(T)} for its subclass. */
    public static class PlainTagBase {
        public String tag(String value) { return "plain " + value; }
    }

    public static class TaggedBySuperclass extends PlainTagBase implements Tagged<String> {
    }

    @Test
    @DisplayName("one override per member signature, even when two declarations are the same member (BUG-20261004-06)")
    void oneOverridePerMemberSignature() throws Exception {
        // echo(String) overrides TaggedBase.echo(T); PlainTagBase.tag(String) implements Tagged.tag(T).
        // Keyed on the erased declarations, each pair counted twice and the source rendered the same
        // member twice: "method echo(String) is already defined".
        for (var fixture : List.of(OverridingGenericChild.class, TaggedBySuperclass.class)) {
            Set<String> expected = Set.of(fixture == OverridingGenericChild.class
                    ? "echo(java.lang.String)" : "tag(java.lang.String)");
            assertEquals(expected, RuntimeClientProxyGenerator.shapeOf(fixture).methods().stream()
                    .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                            .collect(Collectors.joining(",")) + ")")
                    .filter(k -> !OBJECT_METHODS.contains(k))
                    .collect(Collectors.toSet()), "run-time shape of " + fixture.getSimpleName());
            assertEquals(expected, shapeFromElements(fixture), "source shape of " + fixture.getSimpleName());
            assertEquals(expected, colocatedShapeFromElements(fixture),
                    "co-located shape of " + fixture.getSimpleName());
        }
    }

    private Set<String> colocatedShapeFromElements(Class<?> fixture) throws Exception {
        String joined = capture(fixture, (element, env) -> {
            var shape = ClientProxyShapeFromElements.fromColocated(element, env.elements(), env.types());
            return shape.methods().stream()
                    .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                            .collect(Collectors.joining(",")) + ")" + (m.needsMethodHandle() ? "#mh" : "")
                            + ownerSuffix(m))
                    .filter(k -> !OBJECT_METHODS.contains(k.replace("#mh", "")))
                    .collect(Collectors.joining(";"));
        });
        return distinctParts(joined);
    }

    /**
     * The {@code ;}-separated keys, failing on a signature listed twice — the proxy would declare it
     * twice — whatever the {@code #mh} or {@code @owner} suffix of either entry.
     */
    private static Set<String> distinctParts(String joined) {
        var set = new LinkedHashSet<String>();
        var signatures = new java.util.HashSet<String>();
        for (var part : joined.split(";")) {
            if (!part.isBlank()) {
                var signature = part.replaceAll("[#@].*$", "");
                assertTrue(signatures.add(signature), "the shape lists " + signature + " twice: " + joined);
                set.add(part);
            }
        }
        return set;
    }

    /** Package-private: only a class of this package can name it. */
    interface PackageHidingTag {
        default String hidden() { return "default"; }
    }

    public static class ShadowedPackageTagChild extends PrivateHiddenTag implements PackageHidingTag {
    }

    @Test
    @DisplayName("a proxy rendered in another package forwards a shadowed default only through an interface it can name")
    void shadowedDefaultFromAnotherPackage() throws Exception {
        // Beside the bean, the package-private PackageHidingTag can be named: forwarded through it.
        assertEquals(Set.of("hidden()@" + PackageHidingTag.class.getName()),
                shapeFromElements(ShadowedPackageTagChild.class), "proxy in the bean's package");
        // A producer's proxy rendered in another package cannot name it (nor cast to it): rather than
        // a build break, hidden() is not forwarded there — as when no interface qualifies at all.
        assertEquals(Set.of(), shapeFromElementsIn(ShadowedPackageTagChild.class, "proxycrosscheck"),
                "proxy in another package");
    }

    private Set<String> shapeFromElementsIn(Class<?> fixture, String proxyPackage) throws Exception {
        String joined = capture(fixture, (element, env) -> {
            var shape = ClientProxyShapeFromElements.from(element,
                    env.elements().getPackageElement(proxyPackage), env.elements(), env.types());
            return shape.methods().stream()
                    .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                            .collect(Collectors.joining(",")) + ")" + ownerSuffix(m))
                    .filter(k -> !OBJECT_METHODS.contains(k))
                    .collect(Collectors.joining(";"));
        });
        return distinctParts(joined);
    }

    /** Implements {@link PlainTagged#tag(String)} through the public {@link PlainTagBase#tag(String)}. */
    public interface PlainTagged {
        default String tag(String value) { return "plain-tagged " + value; }
    }

    public static class PlainTaggedChild extends PlainTagBase implements PlainTagged {
    }

    @Test
    @DisplayName("a public superclass method with a default method's descriptor is no shadow: one forward")
    void inheritedClassMethodIsNoShadow() throws Exception {
        // PlainTagBase.tag(String) is the bean's member and implements PlainTagged.tag(String):
        // forwarded once, through the bean class. Taking it for a shadow added a second forward
        // through the interface: "method tag(String) is already defined".
        Set<String> expected = Set.of("tag(java.lang.String)");
        assertEquals(expected, runtimeShape(PlainTaggedChild.class), "run-time shape");
        assertEquals(expected, shapeFromElements(PlainTaggedChild.class), "source shape");
        assertEquals(expected, colocatedShapeFromElements(PlainTaggedChild.class), "co-located shape");
    }

    private static final Set<String> OBJECT_METHODS =
            Set.of("toString()", "hashCode()", "equals(java.lang.Object)");

    // ---- javac harness (mirrors InterceptedShapeFromElementsTest) ----

    private String eligibilityFromElements(Class<?> fixture) throws Exception {
        return capture(fixture, (element, env) ->
                ProducerProxyEligibility.of(element).name());
    }

    private Set<String> shapeFromElements(Class<?> fixture) throws Exception {
        String joined = capture(fixture, (element, env) -> {
            var shape = ClientProxyShapeFromElements.from(element, env.elements(), env.types());
            return shape.methods().stream()
                    .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                            .collect(Collectors.joining(",")) + ")" + ownerSuffix(m))
                    .filter(k -> !OBJECT_METHODS.contains(k))
                    .collect(Collectors.joining(";"));
        });
        return distinctParts(joined);
    }

    /** {@code @<interface>} when the proxy forwards through that interface, else nothing. */
    private static String ownerSuffix(ClientProxyShape.ProxyMethodShape m) {
        return m.interfaceOwner() != null ? "@" + m.interfaceOwner() : "";
    }

    /** A private method with the signature of {@link HidingTag#hidden()}. */
    public static class PrivateHiddenTag {
        @SuppressWarnings("unused")
        private String hidden() { return "private"; }
    }

    public interface HidingTag {
        default String hidden() { return "default"; }
    }

    public static class ShadowedTagChild extends PrivateHiddenTag implements HidingTag {
    }

    /** Inherits a shadowed default whose only interface is package-private in another package. */
    public static class CarriedChild extends io.vidocq.vauban.processor.fixture.colocated.DefaultCarrier {
        public String own() { return "own"; }
    }

    @Test
    @DisplayName("a shadowed default method is forwarded through its interface, or not at all (BUG-20261004-08)")
    void shadowedDefaultMethods() throws Exception {
        // ((ShadowedTagChild) delegate).hidden() would resolve to the private PrivateHiddenTag.hidden():
        // every path forwards through HidingTag instead.
        Set<String> expected = Set.of("hidden()@" + HidingTag.class.getName());
        assertEquals(expected, runtimeShape(ShadowedTagChild.class), "run-time shape");
        assertEquals(expected, shapeFromElements(ShadowedTagChild.class), "source shape");
        assertEquals(expected, colocatedShapeFromElements(ShadowedTagChild.class), "co-located shape");
        // No interface CarriedChild can name carries carried(): no path forwards it.
        Set<String> own = Set.of("own()");
        assertEquals(own, runtimeShape(CarriedChild.class), "run-time shape");
        assertEquals(own, shapeFromElements(CarriedChild.class), "source shape");
        assertEquals(own, colocatedShapeFromElements(CarriedChild.class), "co-located shape");
    }

    /** The run-time shape, through the same duplicate detection as the processor shapes. */
    private static Set<String> runtimeShape(Class<?> fixture) {
        return runtimeShape(RuntimeClientProxyGenerator.shapeOf(fixture));
    }

    /** The run-time shape of a proxy placed in a producer's package: public interfaces only. */
    private static Set<String> runtimeProducerShape(Class<?> fixture) {
        return runtimeShape(RuntimeClientProxyGenerator.shapeOf(fixture, false));
    }

    private static Set<String> runtimeShape(ClientProxyShape shape) {
        return distinctParts(shape.methods().stream()
                .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                        .collect(Collectors.joining(",")) + ")" + (m.needsMethodHandle() ? "#mh" : "")
                        + ownerSuffix(m))
                .filter(k -> !OBJECT_METHODS.contains(k))
                .collect(Collectors.joining(";")));
    }

    // ---- The shapes found by the reviews, each pinned across the three paths ----

    /** Inherits a package-private {@code tag(String)} of another package, under a default of the same descriptor. */
    public static class CrossPackageTagChild
            extends io.vidocq.vauban.processor.fixture.colocated.ForeignPackagePrivateTagBase implements PlainTagged {
    }

    /** The same superclass, no default: nothing to forward, no class of this package can override it. */
    public static class CrossPackageOnlyChild
            extends io.vidocq.vauban.processor.fixture.colocated.ForeignPackagePrivateTagBase {
        public String own() { return "own"; }
    }

    @Test
    @DisplayName("a package-private method of a superclass in another package is no member: the default is forwarded, once")
    void crossPackagePackagePrivateIsNoMember() throws Exception {
        // ForeignPackagePrivateTagBase.tag(String) is not inherited (JLS 8.4.8): a MethodHandle
        // forward of it would target a method the bean does not have, and reach the wrong body. The
        // run-time walk forwarded it so, then forwarded the shadowed default through PlainTagged as
        // well: "Duplicate method name tag" when the proxy was defined.
        Set<String> expected = Set.of("tag(java.lang.String)@" + PlainTagged.class.getName());
        assertEquals(expected, runtimeShape(CrossPackageTagChild.class), "run-time shape");
        assertEquals(expected, shapeFromElements(CrossPackageTagChild.class), "source shape");
        assertEquals(expected, colocatedShapeFromElements(CrossPackageTagChild.class), "co-located shape");
        Set<String> own = Set.of("own()");
        assertEquals(own, runtimeShape(CrossPackageOnlyChild.class), "run-time shape");
        assertEquals(own, shapeFromElements(CrossPackageOnlyChild.class), "source shape");
        assertEquals(own, colocatedShapeFromElements(CrossPackageOnlyChild.class), "co-located shape");
    }

    /** A generic bean: its proxy extends it raw, where every inherited member is erased. */
    public static class GenericNoShadowChild<X> implements Tagged<String> {
    }

    /** A generic bean whose shadowed default comes from a generic interface. */
    public static class GenericShadowedGenericChild<X> extends PrivateObjectTag implements Tagged<String> {
    }

    @Test
    @DisplayName("a generic bean is extended raw: every path overrides the erased member")
    void genericBeanIsExtendedRaw() throws Exception {
        // tag(String) is the member of GenericNoShadowChild<X>, but the proxy extends the raw
        // GenericNoShadowChild, whose member is tag(Object): a tag(String) override "does not
        // override or implement a method from a supertype".
        Set<String> expected = Set.of("tag(java.lang.Object)");
        assertEquals(expected, runtimeShape(GenericNoShadowChild.class), "run-time shape");
        assertEquals(expected, shapeFromElements(GenericNoShadowChild.class), "source shape");
        assertEquals(expected, colocatedShapeFromElements(GenericNoShadowChild.class), "co-located shape");
        Set<String> shadowed = Set.of("tag(java.lang.Object)@" + Tagged.class.getName());
        assertEquals(shadowed, runtimeShape(GenericShadowedGenericChild.class), "run-time shape");
        assertEquals(shadowed, shapeFromElements(GenericShadowedGenericChild.class), "source shape");
        assertEquals(shadowed, colocatedShapeFromElements(GenericShadowedGenericChild.class), "co-located shape");
    }

    /** Inherits a shadowed default whose member signature names a type this package cannot. */
    public static class HiddenArgChild extends io.vidocq.vauban.processor.fixture.colocated.HiddenArgumentBase {
        public String own() { return "own"; }
    }

    @Test
    @DisplayName("a shadowed default whose type argument cannot be named is left out of the source, forwarded in bytecode")
    void shadowedDefaultWithAnInaccessibleTypeArgument() throws Exception {
        // The member is label(HiddenArgument), and HiddenArgument is package-private in another
        // package: Java source can neither declare that override nor cast to
        // HiddenLabeled<HiddenArgument>, so the rendered proxy leaves the method out rather than
        // breaking the build. Bytecode forwards by descriptor, through the raw interface.
        var owner = io.vidocq.vauban.processor.fixture.colocated.HiddenLabeled.class.getName();
        Set<String> bytecode = Set.of("own()", "label(java.lang.Object)@" + owner);
        assertEquals(bytecode, runtimeShape(HiddenArgChild.class), "run-time shape");
        assertEquals(bytecode, colocatedShapeFromElements(HiddenArgChild.class), "co-located shape");
        assertEquals(Set.of("own()"), shapeFromElements(HiddenArgChild.class), "source shape");
    }

    /** Inherits a non-shadowed default whose member signature names a type this package cannot. */
    public static class NonShadowHiddenChild extends io.vidocq.vauban.processor.fixture.colocated.HiddenDefaultBase {
        public String own() { return "own"; }
    }

    @Test
    @DisplayName("a non-shadowed default whose member signature cannot be named is left out of the source, with a warning")
    void nonShadowedDefaultWithAnInaccessibleMemberType() throws Exception {
        // The member is label(HiddenArgument): the rendered override cannot name it, and origin/main
        // forwarded no default at all, so the source proxy leaves it out — and says so. Bytecode
        // forwards by descriptor: the same divergence as for a shadowed one.
        Set<String> bytecode = Set.of("own()", "label(java.lang.Object)");
        assertEquals(bytecode, runtimeShape(NonShadowHiddenChild.class), "run-time shape");
        assertEquals(bytecode, colocatedShapeFromElements(NonShadowHiddenChild.class), "co-located shape");
        var omitted = new java.util.ArrayList<String>();
        assertEquals(Set.of("own()"), shapeFromElementsReporting(NonShadowHiddenChild.class, omitted::add), "source shape");
        assertEquals(1, omitted.size(), "one omitted method reported: " + omitted);
        assertTrue(omitted.getFirst().contains(NonShadowHiddenChild.class.getCanonicalName())
                && omitted.getFirst().contains("label(")
                && omitted.getFirst().contains("HiddenArgument"), omitted.getFirst());
    }

    /** A private nested type of this package, bound by a public nested class (BUG-20261004-09, n11a). */
    public static class PrivateNestedOuter {
        private static class Secret {
        }

        public static class SecretLabeledBase
                implements io.vidocq.vauban.processor.fixture.colocated.HiddenLabeled<Secret> {
        }
    }

    /** Same package as {@link PrivateNestedOuter}: inherits the member {@code label(Secret)}. */
    public static class PrivateNestedChild extends PrivateNestedOuter.SecretLabeledBase {
        public String own() { return "own"; }
    }

    @Test
    @DisplayName("a default whose member signature names a private nested type of the same package is left out of the source")
    void nonShadowedDefaultWithAPrivateNestedMemberType() throws Exception {
        // label(Secret): Secret is private, so only the body of its top-level class can name it
        // (JLS 6.6.1) — not the proxy, a top-level class of the same package. Same outcome as for a
        // package-private type of another package: left out of the source, reported; bytecode
        // forwards by descriptor.
        Set<String> bytecode = Set.of("own()", "label(java.lang.Object)");
        assertEquals(bytecode, runtimeShape(PrivateNestedChild.class), "run-time shape");
        assertEquals(bytecode, colocatedShapeFromElements(PrivateNestedChild.class), "co-located shape");
        assertEquals(Set.of("own()"), shapeFromElements(PrivateNestedChild.class), "source shape");
        var report = onlyReport(PrivateNestedChild.class, false);
        assertTrue(report.contains("label(") && report.contains("Secret")
                && report.contains("runs the default body on the proxy instance"), report);
    }

    @Test
    @DisplayName("the report of a default the source proxy leaves out says what a call on the proxy does")
    void omittedDefaultReportsSayWhatACallDoes() throws Exception {
        // Not shadowed: the default body runs on the proxy instance.
        var nonShadowed = onlyReport(NonShadowHiddenChild.class, false);
        assertTrue(nonShadowed.contains("the client proxy does not forward")
                && nonShadowed.contains("its signature as a member of the bean")
                && nonShadowed.contains("runs the default body on the proxy instance"), nonShadowed);
        // Shadowed — no nameable interface, or an unnameable member signature: the proxy is a
        // subclass of the bean under the shadow, where the JVM does not run the default body
        // (BUG-20261004-08), so the report must not say it does.
        for (var shadowed : List.of(onlyReport(CarriedChild.class, false), onlyReport(HiddenArgChild.class, false))) {
            assertFalse(shadowed.contains("runs the default body"), shadowed);
            assertTrue(shadowed.contains("does not reach the contextual instance")
                    && shadowed.contains("AbstractMethodError"), shadowed);
        }
        // A producer's proxy: the type is the produced type, and the proxy is the producer's.
        var produced = onlyReport(NonShadowHiddenChild.class, true);
        assertTrue(produced.contains("the producer's client proxy does not forward")
                && produced.contains("its signature as a member of the produced type")
                && !produced.contains("member of the bean"), produced);
    }

    /** The one report the source shape of {@code fixture} gives, as a bean's proxy or a producer's. */
    private String onlyReport(Class<?> fixture, boolean produced) throws Exception {
        var omitted = new java.util.ArrayList<String>();
        capture(fixture, (element, env) -> {
            var pkg = env.elements().getPackageOf(element);
            var shape = produced
                    ? ClientProxyShapeFromElements.fromProduced(element, pkg, env.elements(), env.types(), omitted::add)
                    : ClientProxyShapeFromElements.from(element, pkg, env.elements(), env.types(), omitted::add);
            return shape.proxyClassName();
        });
        assertEquals(1, omitted.size(), "one omitted method reported: " + omitted);
        return omitted.getFirst();
    }

    /** The source shape, {@code omitted} receiving what the shape leaves out and the processor reports. */
    private Set<String> shapeFromElementsReporting(Class<?> fixture, java.util.function.Consumer<String> omitted)
            throws Exception {
        String joined = capture(fixture, (element, env) -> {
            var shape = ClientProxyShapeFromElements.from(element, env.elements().getPackageOf(element),
                    env.elements(), env.types(), omitted);
            return shape.methods().stream()
                    .map(m -> m.name() + "(" + m.params().stream().map(Object::toString)
                            .collect(Collectors.joining(",")) + ")" + ownerSuffix(m))
                    .filter(k -> !OBJECT_METHODS.contains(k))
                    .collect(Collectors.joining(";"));
        });
        return distinctParts(joined);
    }

    /** A private {@code tag(Object)}: the erased descriptor of {@link Tagged#tag(Object)}. */
    public static class PrivateObjectTag {
        @SuppressWarnings("unused")
        private String tag(Object value) { return "private " + value; }
    }

    /** A private shadow above a public member: the member wins, and is forwarded through the class. */
    public static class PrivateTopTag {
        @SuppressWarnings("unused")
        private String tag(String value) { return "private " + value; }
    }

    public static class PublicMidTag extends PrivateTopTag {
        public String tag(String value) { return "mid " + value; }
    }

    public static class MemberOverPrivateChild extends PublicMidTag implements PlainTagged {
    }

    /** Two paths to {@code Tagged<String>}. */
    public interface SubTagged extends Tagged<String> {
    }

    public static class TwoPathsChild extends PrivateObjectTag implements SubTagged, Tagged<String> {
    }

    /** The parameterisation comes through a generic superclass. */
    public static class MidTag<T> extends PrivateObjectTag implements Tagged<T> {
    }

    public static class ThroughMidChild extends MidTag<Integer> {
    }

    public static class Holder {
        public static class Item {
        }
    }

    /** A wildcard and a nested type as the type argument. */
    public static class NestedArgChild extends PrivateObjectTag
            implements Tagged<java.util.List<? extends Holder.Item>> {
    }

    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    public @interface NonEmpty {
    }

    /** A type-annotated type argument. */
    public static class AnnotatedArgChild extends PrivateObjectTag implements Tagged<@NonEmpty String> {
    }

    @Test
    @DisplayName("the shadowed-default shapes found by the reviews: every path agrees")
    void reviewedShadowedDefaultShapes() throws Exception {
        Set<String> member = Set.of("tag(java.lang.String)");
        assertEquals(member, runtimeShape(MemberOverPrivateChild.class), "run-time shape");
        assertEquals(member, shapeFromElements(MemberOverPrivateChild.class), "source shape");
        assertEquals(member, colocatedShapeFromElements(MemberOverPrivateChild.class), "co-located shape");
        // The bytecode shapes forward by descriptor; the source shape declares the member signature
        // the bean sees, each through the declaring interface Tagged.
        var owner = "@" + Tagged.class.getName();
        Set<String> throughTagged = Set.of("tag(java.lang.Object)" + owner);
        for (var fixture : Map.of(TwoPathsChild.class, "java.lang.String", ThroughMidChild.class, "java.lang.Integer",
                NestedArgChild.class, "java.util.List", AnnotatedArgChild.class, "java.lang.String").entrySet()) {
            assertEquals(throughTagged, runtimeShape(fixture.getKey()), "run-time shape of " + fixture.getKey().getSimpleName());
            assertEquals(Set.of("tag(" + fixture.getValue() + ")" + owner), shapeFromElements(fixture.getKey()),
                    "source shape of " + fixture.getKey().getSimpleName());
            assertEquals(throughTagged, colocatedShapeFromElements(fixture.getKey()),
                    "co-located shape of " + fixture.getKey().getSimpleName());
        }
    }

    /** A shadowed default of a public top-level interface of an exported package: nameable from any package. */
    public static class PublicShadowedTagChild extends PrivateHiddenTag implements PublicHidingTag {
    }

    @Test
    @DisplayName("a producer's proxy, in another package: through a public interface only, on both paths")
    void producerProxyForwardsThroughPublicInterfacesOnly() throws Exception {
        // PublicHidingTag is public, top-level and exported: forwarded through it from anywhere. (A
        // public interface nested in this package-private test class is not: Java source cannot name
        // it from another package, and this module does not export it to a producer's module.)
        Set<String> through = Set.of("hidden()@" + PublicHidingTag.class.getName());
        assertEquals(through, runtimeProducerShape(PublicShadowedTagChild.class), "run-time producer shape");
        assertEquals(through, shapeFromElementsIn(PublicShadowedTagChild.class, "proxycrosscheck"),
                "source producer shape");
        // ShadowedPackageTagChild's PackageHidingTag is package-private: forwarded from nowhere else.
        assertEquals(Set.of(), runtimeProducerShape(ShadowedPackageTagChild.class), "run-time producer shape");
        assertEquals(Set.of(), shapeFromElementsIn(ShadowedPackageTagChild.class, "proxycrosscheck"),
                "source producer shape");
    }

    /** The Elements/Types pair handed to the callback. */
    record Env(Elements elements, Types types) {}

    /** Runs javac in-process over a trigger source and invokes {@code body} on the fixture's element. */
    private String capture(Class<?> fixture, BiFunction<TypeElement, Env, String> body) throws Exception {
        var captured = new AtomicReference<String>();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();

        var triggerAnnotation = sourceFile("ProxyCrossCheckTrigger.java",
                "package proxycrosscheck; import java.lang.annotation.*;"
                        + " @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)"
                        + " @interface ProxyCrossCheckTrigger {}");
        var trigger = sourceFile("Trigger.java",
                "package proxycrosscheck; @ProxyCrossCheckTrigger class Trigger {}");

        var options = List.of("-proc:only", "--release", "25", "-classpath", classpath());
        try (var fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, fm, diagnostics, options, null,
                    List.of(triggerAnnotation, trigger));
            task.setProcessors(List.of(new CaptureProcessor(fixture, body, captured)));
            task.call();
        }
        assertNotNull(captured.get(), "the processor captured nothing — compilation likely failed: "
                + diagnostics.getDiagnostics().stream()
                        .map(d -> d.getKind() + ": " + d.getMessage(null))
                        .collect(Collectors.joining("; ")));
        return captured.get();
    }

    private static JavaFileObject sourceFile(String name, String content) {
        return new SimpleJavaFileObject(URI.create("file:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    private static final class CaptureProcessor extends AbstractProcessor {
        private final Class<?> fixture;
        private final BiFunction<TypeElement, Env, String> body;
        private final AtomicReference<String> sink;

        CaptureProcessor(Class<?> fixture, BiFunction<TypeElement, Env, String> body,
                AtomicReference<String> sink) {
            this.fixture = fixture;
            this.body = body;
            this.sink = sink;
        }

        @Override public Set<String> getSupportedAnnotationTypes() {
            return Set.of("proxycrosscheck.ProxyCrossCheckTrigger");
        }

        @Override public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
            if (env.processingOver()) return false;
            var elements = processingEnv.getElementUtils();
            var types = processingEnv.getTypeUtils();
            var element = elements.getTypeElement(fixture.getName().replace('$', '.'));
            if (element == null) return false;
            sink.set(body.apply(element, new Env(elements, types)));
            return false;
        }
    }
}
