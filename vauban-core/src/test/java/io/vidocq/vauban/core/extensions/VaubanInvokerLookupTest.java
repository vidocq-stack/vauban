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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.InvokerFactory;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invoker that looks its target instance and arguments up: a dependent looked-up instance is destroyed after
 * the call, a normal-scoped one is not; and the validation of argument lookups against the deployed beans.
 */
class VaubanInvokerLookupTest {

    private static final AtomicInteger DESTROYED = new AtomicInteger();

    @Dependent
    public static class Part {
        public String name() {
            return "part";
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }
    }

    @ApplicationScoped
    public static class Assembly {
        public String assemble(Part part) {
            return "assembled " + part.name();
        }
    }

    @Dependent
    public static class Worker {
        public String work(Part part) {
            return "worked with " + part.name();
        }
    }

    public interface Holder<H> {
    }

    @Dependent
    public static class TextHolder implements Holder<String> {
    }

    @Dependent
    public static class IntegerHolder implements Holder<Integer> {
    }

    @Dependent
    public static class IntegerHolderFactory {
        @Produces
        Holder<Integer> integers() {
            return new Holder<>() {
            };
        }
    }

    /** A bean an invoker of {@link LookupBce} targets. */
    public interface LookupTarget {
    }

    @Dependent
    public static class HolderReader implements LookupTarget {
        public String read(Holder<String> holder) {
            return holder.getClass().getSimpleName();
        }

        public String readIntegers(Holder<Integer> holder) {
            return holder.getClass().getSimpleName();
        }

        public String readCharSequences(Holder<? extends CharSequence> holder) {
            return holder.getClass().getSimpleName();
        }
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
    public @interface Fast {
    }

    @Dependent
    @Fast
    public static class FastPart extends Part {
        @Override
        public String name() {
            return "fast part";
        }
    }

    @Dependent
    public static class PartUser implements LookupTarget {
        public String use(@Fast Part part) {
            return part.name();
        }
    }

    /** A target bean with a qualifier, and no {@code @Default}. */
    @Dependent
    @Fast
    public static class FastWorker {
        public String work() {
            return "fast work";
        }
    }

    @Dependent
    public static class PartCounter implements LookupTarget {
        public String name(Instance<Part> parts) {
            return parts.get().name();
        }
    }

    public interface Describer {
        String describe();
    }

    /** Its class is not among its bean types. */
    @Dependent
    @Typed(Describer.class)
    public static class TypedDescriber implements Describer {
        @Override
        public String describe() {
            return "typed";
        }
    }

    @Dependent
    public static class Announcer {
        public boolean announce(Event<String> event, BeanManager beanManager) {
            event.fire("hello");
            return beanManager != null;
        }
    }

    @Dependent
    public static class Listener {
        static final AtomicReference<String> HEARD = new AtomicReference<>();

        void hear(@Observes String message) {
            HEARD.set(message);
        }
    }

    /** No bean defining annotation: only {@link GadgetBce} makes it a bean, a synthetic one. */
    public static class Gadget {
        public String name() {
            return "gadget";
        }
    }

    public static class GadgetCreator implements SyntheticBeanCreator<Gadget> {
        @Override
        public Gadget create(Instance<Object> lookup, Parameters params) {
            return new Gadget();
        }
    }

    public static class GadgetBce implements BuildCompatibleExtension {
        @Synthesis
        public void synthesize(SyntheticComponents components) {
            components.addBean(Gadget.class).type(Gadget.class).scope(Dependent.class).createWith(GadgetCreator.class);
        }
    }

    @Dependent
    public static class GadgetUser implements LookupTarget {
        public String useGadget(Gadget gadget) {
            return gadget.name();
        }
    }

    /** A {@code @Singleton}, created by the first lookup that asks for it, with a {@code @Dependent} it keeps. */
    @Singleton
    public static class PartKeeper {
        @Inject
        Part part;

        public String partName() {
            return "kept " + part.name();
        }
    }

    @Dependent
    public static class KeeperReader {
        public String read(PartKeeper keeper) {
            return keeper.partName();
        }
    }

    /** A qualifier no {@code @Qualifier} declares: {@link RegisteringBce} registers it. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
    public @interface Registered {
        String value() default "";
    }

    /** Registers {@link Registered} as a qualifier whose {@code value} is non-binding. */
    public static class RegisteringBce implements BuildCompatibleExtension {
        @Discovery
        public void discover(MetaAnnotations meta) {
            meta.addQualifier(Registered.class).methods().forEach(member -> member.addAnnotation(Nonbinding.class));
        }
    }

    @Dependent
    @Registered("bean")
    public static class Gear {
        public String name() {
            return "gear";
        }
    }

    @Dependent
    public static class GearUser implements LookupTarget {
        public String useGear(@Registered("lookup") Gear gear) {
            return gear.name();
        }
    }

    public interface Device {
        String name();
    }

    /** The only class bean of {@link Device}: it has a qualifier, and no {@code @Default}. */
    @Dependent
    @Fast
    public static class FastDevice implements Device {
        @Override
        public String name() {
            return "fast device";
        }
    }

    /** No bean defining annotation: only {@link DeviceBce} makes it a bean, a synthetic {@code @Default} one. */
    public static class SyntheticDevice implements Device {
        @Override
        public String name() {
            return "synthetic device";
        }
    }

    public static class DeviceCreator implements SyntheticBeanCreator<SyntheticDevice> {
        @Override
        public SyntheticDevice create(Instance<Object> lookup, Parameters params) {
            return new SyntheticDevice();
        }
    }

    public static class DeviceBce implements BuildCompatibleExtension {
        @Synthesis
        public void synthesize(SyntheticComponents components) {
            components.addBean(SyntheticDevice.class).type(Device.class).scope(Dependent.class)
                    .createWith(DeviceCreator.class);
        }
    }

    @Dependent
    public static class DeviceUser implements LookupTarget {
        public String useDevice(Device device) {
            return device.name();
        }
    }

    @Dependent
    public static class ContainerUser implements LookupTarget {
        public boolean hasContainer(BeanContainer container) {
            return container != null;
        }
    }

    /** Its parameter's declared type is a type variable, {@code T}. */
    @Dependent
    public static class PartTaker<T extends Part> implements LookupTarget {
        public String take(T part) {
            return part.name();
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Tracked {
    }

    /** Records each of its instances that intercepts a call. */
    @Tracked
    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    public static class TrackingInterceptor {
        static final Set<TrackingInterceptor> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());

        @AroundInvoke
        public Object around(InvocationContext invocation) throws Exception {
            SEEN.add(this);
            return invocation.proceed();
        }
    }

    @Dependent
    @Tracked
    public static class Left {
        public String name() {
            return "left";
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }
    }

    @Dependent
    @Tracked
    public static class Right {
        public String name() {
            return "right";
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }
    }

    @Dependent
    public static class Pair {
        public String join(Left left, Right right) {
            return left.name() + "+" + right.name();
        }
    }

    /** Builds an invoker of the method {@link #METHOD} names of each {@link LookupTarget}, every argument looked up. */
    public static class LookupBce implements BuildCompatibleExtension {
        static final AtomicReference<String> METHOD = new AtomicReference<>();

        @Registration(types = LookupTarget.class)
        public void register(BeanInfo bean, InvokerFactory invokers) {
            for (var target : bean.declaringClass().methods()) {
                if (target.name().equals(METHOD.get())) {
                    var builder = invokers.createInvoker(bean, target);
                    for (int i = 0; i < target.parameters().size(); i++) {
                        builder.withArgumentLookup(i);
                    }
                    builder.build();
                }
            }
        }
    }

    @BeforeEach
    void noMethod() {
        LookupBce.METHOD.set(null);
    }

    /**
     * An argument lookup of a {@code Holder<String>} parameter resolves {@code Holder<String>}, which the bean of
     * {@code TextHolder implements Holder<String>} satisfies. The lookup resolves the erased class, {@code Holder}, a
     * raw required type that a parameterized bean type of another argument than {@code Object} does not satisfy (CDI
     * 4.1, 5.2.4; BUG-20261117-27).
     */
    @Test
    @DisplayName("an argument lookup of a parameterized type resolves that type")
    void anArgumentLookupOfAParameterizedTypeResolvesThatType() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(TextHolder.class).addBeanClass(HolderReader.class)
                .build()) {
            var read = new VaubanInvoker(HolderReader.class.getMethod("read", Holder.class), HolderReader.class, true,
                    Set.of(0));

            assertEquals("TextHolder", read.invoke(null, new Object[] {null}));
        }
    }

    /**
     * An argument lookup that no bean satisfies throws the {@code UnsatisfiedResolutionException} of a programmatic
     * lookup when the invoker is called: a bean of {@code Holder<String>} does not satisfy {@code Holder<Integer>}.
     * The deployment validation refuses such an invoker first ({@link #theStartRefusesALookupNoBeanSatisfies}); an
     * invoker built outside an extension meets it here.
     */
    @Test
    @DisplayName("an argument lookup a bean of another parameterization leaves unsatisfied fails the call")
    void anArgumentLookupABeanOfAnotherParameterizationLeavesUnsatisfiedFailsTheCall() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(TextHolder.class).addBeanClass(HolderReader.class)
                .build()) {
            var readIntegers = new VaubanInvoker(HolderReader.class.getMethod("readIntegers", Holder.class),
                    HolderReader.class, true, Set.of(0));
            var arguments = new Object[] {null};

            assertThrows(UnsatisfiedResolutionException.class, () -> readIntegers.invoke(null, arguments));
        }
    }

    /**
     * An argument lookup of {@code Holder<? extends CharSequence>} resolves by the rules of an injection point (CDI
     * 4.1, 5.2.4): the bean of {@code Holder<String>} satisfies it, the bean of {@code Holder<Integer>} does not. It
     * looked the raw {@code Holder} up, which neither satisfies (BUG-20261117-27).
     */
    @Test
    @DisplayName("an argument lookup of a wildcard type resolves by the rules of an injection point")
    void anArgumentLookupOfAWildcardTypeResolvesByTheRulesOfAnInjectionPoint() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(TextHolder.class).addBeanClass(IntegerHolderFactory.class)
                .addBeanClass(HolderReader.class).build()) {
            var readCharSequences = new VaubanInvoker(HolderReader.class.getMethod("readCharSequences", Holder.class),
                    HolderReader.class, true, Set.of(0));

            assertEquals("TextHolder", readCharSequences.invoke(null, new Object[] {null}));
        }
    }

    /**
     * An argument lookup applies the qualifiers of its parameter (CDI 4.1, "Configuring lookups": "the required
     * qualifiers are all qualifiers present on the parameter"): {@code @Fast Part} is the bean of {@code FastPart}.
     * It looked {@code Part} up with {@code @Default}, the bean of {@code Part} (BUG-20261117-27).
     */
    @Test
    @DisplayName("an argument lookup applies the qualifiers of its parameter")
    void anArgumentLookupAppliesTheQualifiersOfItsParameter() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(Part.class).addBeanClass(FastPart.class)
                .addBeanClass(PartUser.class).build()) {
            var use = new VaubanInvoker(PartUser.class.getMethod("use", Part.class), PartUser.class, true, Set.of(0));
            int before = DESTROYED.get();

            assertEquals("fast part", use.invoke(null, new Object[] {null}));
            assertEquals(before + 1, DESTROYED.get(), "the looked-up dependent destroyed after the call");
        }
    }

    /**
     * An instance lookup obtains a contextual reference for the target bean (CDI 4.1, "Configuring lookups"), which
     * may have a qualifier and no {@code @Default}: the bean of {@code @Fast FastWorker}. The lookup requires
     * {@code @Default} (BUG-20261117-38, fixed on the parked branch {@code fix/invoker-instance-lookup}).
     */
    @Test
    @Disabled("BUG-20261117-38")
    @DisplayName("an instance lookup finds a target bean that has a qualifier and no @Default")
    void anInstanceLookupFindsATargetBeanThatHasAQualifier() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(FastWorker.class).build()) {
            var work = new VaubanInvoker(FastWorker.class.getMethod("work"), FastWorker.class, true, Set.of());

            assertEquals("fast work", work.invoke(null, new Object[0]));
        }
    }

    /**
     * An argument lookup of an {@code Instance<Part>} gives the built-in {@code Instance}, as an injection point does,
     * and the {@code @Dependent} instance obtained through it is destroyed once the call is over.
     */
    @Test
    @DisplayName("an argument lookup of an Instance gives the built-in Instance, its dependents destroyed after")
    void anArgumentLookupOfAnInstanceGivesTheBuiltInInstance() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(Part.class).addBeanClass(PartCounter.class).build()) {
            var name = new VaubanInvoker(PartCounter.class.getMethod("name", Instance.class), PartCounter.class, true,
                    Set.of(0));
            int before = DESTROYED.get();

            assertEquals("part", name.invoke(null, new Object[] {null}));
            assertEquals(before + 1, DESTROYED.get(), "the dependent the Instance gave destroyed after the call");
        }
    }

    /**
     * An instance lookup finds the target bean when {@code @Typed} leaves its class out of its bean types. The lookup
     * requires the class as a bean type (BUG-20261117-38).
     */
    @Test
    @Disabled("BUG-20261117-38")
    @DisplayName("an instance lookup finds a target bean whose class @Typed leaves out")
    void anInstanceLookupFindsATargetBeanWhoseClassTypedLeavesOut() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(TypedDescriber.class).build()) {
            var describe = new VaubanInvoker(TypedDescriber.class.getMethod("describe"), TypedDescriber.class, true,
                    Set.of());

            assertEquals("typed", describe.invoke(null, new Object[0]));
        }
    }

    /** Argument lookups of an {@code Event} and of the {@code BeanManager} give the built-in beans. */
    @Test
    @DisplayName("argument lookups of an Event and of the BeanManager give the built-in beans")
    void argumentLookupsOfAnEventAndOfTheBeanManagerGiveTheBuiltInBeans() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(Announcer.class).addBeanClass(Listener.class).build()) {
            var announce = new VaubanInvoker(Announcer.class.getMethod("announce", Event.class, BeanManager.class),
                    Announcer.class, true, Set.of(0, 1));
            Listener.HEARD.set(null);

            assertTrue((boolean) announce.invoke(null, new Object[] {null, null}));
            assertEquals("hello", Listener.HEARD.get(), "the event reached its observer");
        }
    }

    /** The deployment validation leaves a lookup of a built-in {@code Instance} to the container, which has one. */
    @Test
    @DisplayName("the start accepts an argument lookup of an Instance")
    void theStartAcceptsALookupOfAnInstance() {
        LookupBce.METHOD.set("name");
        var builder = VaubanContainer.builder().addBeanClass(Part.class).addBeanClass(PartCounter.class)
                .addBeanClass(LookupBce.class);

        try (var _ = assertDoesNotThrow(builder::build, "the container refused the lookup")) {
            // It started.
        }
    }

    /**
     * The deployment validation identifies the bean of each argument lookup by the rules the call applies (CDI 4.1,
     * "Configuring lookups": an unsatisfied or an unresolvable ambiguous lookup is a deployment problem): a bean of
     * {@code Holder<String>} does not satisfy {@code Holder<Integer>}. It counted the beans whose class is a
     * {@code Holder} (BUG-20261117-27).
     */
    @Test
    @DisplayName("the start refuses an argument lookup no bean satisfies")
    void theStartRefusesALookupNoBeanSatisfies() {
        LookupBce.METHOD.set("readIntegers");
        var builder = VaubanContainer.builder().addBeanClass(TextHolder.class).addBeanClass(HolderReader.class)
                .addBeanClass(LookupBce.class);

        var refused = assertThrows(DeploymentException.class, builder::build, "the container started");
        assertTrue(refused.getMessage().contains("Invoker argument lookup unsatisfied: no bean found for parameter "
                + "type " + Holder.class.getName() + "<java.lang.Integer> at position 0"), refused::getMessage);
    }

    /**
     * The producer of {@code Holder<Integer>} and the bean of {@code IntegerHolder implements Holder<Integer>} make
     * the argument lookup of a {@code Holder<Integer>} ambiguous, as an injection point. It counted the classes that
     * are a {@code Holder}, which left the producer out, and accepted the deployment (BUG-20261117-27).
     */
    @Test
    @DisplayName("the start refuses an ambiguous argument lookup")
    void theStartRefusesAnAmbiguousLookup() {
        LookupBce.METHOD.set("readIntegers");
        var builder = VaubanContainer.builder().addBeanClass(IntegerHolder.class)
                .addBeanClass(IntegerHolderFactory.class).addBeanClass(HolderReader.class)
                .addBeanClass(LookupBce.class);

        var refused = assertThrows(DeploymentException.class, builder::build, "the container started");
        assertTrue(refused.getMessage().contains("Invoker argument lookup ambiguous: 2 beans found for parameter type "
                + Holder.class.getName() + "<java.lang.Integer> at position 0"), refused::getMessage);
    }

    /**
     * The deployment validation applies the qualifiers of the parameter, as the call does: {@code @Fast Part} is the
     * bean of {@code FastPart} alone. It counted the two beans whose class is a {@code Part} (BUG-20261117-27).
     */
    @Test
    @DisplayName("the start accepts an argument lookup of a qualified parameter")
    void theStartAcceptsALookupOfAQualifiedParameter() {
        LookupBce.METHOD.set("use");
        var builder = VaubanContainer.builder().addBeanClass(Part.class).addBeanClass(FastPart.class)
                .addBeanClass(PartUser.class).addBeanClass(LookupBce.class);

        try (var _ = assertDoesNotThrow(builder::build, "the container refused the lookup")) {
            // It started.
        }
    }

    /**
     * The deployment validation finds a producer, as the call does: the producer of {@code Holder<Integer>}
     * satisfies the lookup of a {@code Holder<Integer>}. It compared the class that declares the producer
     * (BUG-20261117-27).
     */
    @Test
    @DisplayName("the start accepts an argument lookup a producer satisfies")
    void theStartAcceptsALookupAProducerSatisfies() {
        LookupBce.METHOD.set("readIntegers");
        var builder = VaubanContainer.builder().addBeanClass(IntegerHolderFactory.class)
                .addBeanClass(HolderReader.class).addBeanClass(LookupBce.class);

        try (var _ = assertDoesNotThrow(builder::build, "the container refused the lookup")) {
            // It started.
        }
    }

    /**
     * The deployment validation identifies the looked-up beans among all the beans of the deployment, the synthetic
     * beans an extension adds in {@code @Synthesis}, after {@code @Registration}, among them (BUG-20261117-39).
     */
    @Test
    @DisplayName("the start accepts an argument lookup a synthetic bean satisfies")
    void theStartAcceptsALookupASyntheticBeanSatisfies() {
        LookupBce.METHOD.set("useGadget");
        var builder = VaubanContainer.builder().addBeanClass(GadgetUser.class).addBeanClass(LookupBce.class)
                .addBeanClass(GadgetBce.class);

        try (var _ = assertDoesNotThrow(builder::build, "the container refused the lookup")) {
            // It started.
        }
    }

    /**
     * A {@code @Singleton} target the instance lookup creates keeps its {@code @Dependent} objects after the call: only
     * the {@code @Dependent} instances created for the lookups are destroyed (CDI 4.1, "Configuring lookups").
     */
    @Test
    @DisplayName("a @Singleton target the lookup creates keeps its @Dependent objects after the call")
    void aSingletonTargetTheLookupCreatesKeepsItsDependents() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(Part.class).addBeanClass(PartKeeper.class).build()) {
            var partName = new VaubanInvoker(PartKeeper.class.getMethod("partName"), PartKeeper.class, true, Set.of());
            int before = DESTROYED.get();

            assertEquals("kept part", partName.invoke(null, new Object[0]));
            assertEquals(before, DESTROYED.get(), "the @Dependent of the @Singleton destroyed by the call");
        }
    }

    /**
     * A {@code @Singleton} argument the lookup creates keeps its {@code @Dependent} objects after the call. The call
     * created it with the creational context it releases once over, which destroyed them (fix round 1 of
     * BUG-20261117-27).
     */
    @Test
    @DisplayName("a @Singleton argument the lookup creates keeps its @Dependent objects after the call")
    void aSingletonArgumentTheLookupCreatesKeepsItsDependents() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(Part.class).addBeanClass(PartKeeper.class)
                .addBeanClass(KeeperReader.class).build()) {
            var read = new VaubanInvoker(KeeperReader.class.getMethod("read", PartKeeper.class), KeeperReader.class,
                    true, Set.of(0));
            int before = DESTROYED.get();

            assertEquals("kept part", read.invoke(null, new Object[] {null}));
            assertEquals("kept part", read.invoke(null, new Object[] {null}));
            assertEquals(before, DESTROYED.get(), "the @Dependent of the @Singleton destroyed by the call");
        }
    }

    /**
     * The deployment validation knows the qualifiers an extension registers, and the members it makes non-binding,
     * as the call does: {@code @Registered("lookup") Gear} is the bean of {@code @Registered("bean") Gear}. It read
     * {@code @Qualifier} alone, so it required {@code @Default}, which that bean has not (fix round 1 of
     * BUG-20261117-27).
     */
    @Test
    @DisplayName("the start and the call accept a lookup of a qualifier an extension registers")
    void theStartAndTheCallAcceptALookupOfAQualifierAnExtensionRegisters() throws Exception {
        LookupBce.METHOD.set("useGear");
        var builder = VaubanContainer.builder().addBeanClass(Gear.class).addBeanClass(GearUser.class)
                .addBeanClass(RegisteringBce.class).addBeanClass(LookupBce.class);

        try (var _ = assertDoesNotThrow(builder::build, "the container refused the lookup")) {
            var useGear = new VaubanInvoker(GearUser.class.getMethod("useGear", Gear.class), GearUser.class, true,
                    Set.of(0));

            assertEquals("gear", useGear.invoke(null, new Object[] {null}));
        }
    }

    /**
     * A synthetic {@code @Default} bean satisfies the lookup of a {@code Device} that the only class bean,
     * {@code @Fast}, does not. Checked in {@code @Registration}, before {@code @Synthesis}, the lookup of a
     * {@code Device} had no bean; the check that counted the classes of {@code Device} found one and accepted it
     * (BUG-20261117-39: why the check waits for {@code @Synthesis}).
     */
    @Test
    @DisplayName("the start accepts a lookup a synthetic bean satisfies beside a qualified class bean")
    void theStartAcceptsALookupASyntheticBeanSatisfiesBesideAQualifiedClassBean() {
        LookupBce.METHOD.set("useDevice");
        var builder = VaubanContainer.builder().addBeanClass(FastDevice.class).addBeanClass(DeviceUser.class)
                .addBeanClass(DeviceBce.class).addBeanClass(LookupBce.class);

        try (var _ = assertDoesNotThrow(builder::build, "the container refused the lookup")) {
            // It started.
        }
    }

    /**
     * The deployment validation refuses the argument lookup of a type variable: the required type is the declared
     * type of the parameter (CDI 4.1, "Configuring lookups"), and a type variable is no legal one (5.2.2). It looked
     * the bounds up, {@code Part}, which a bean satisfied (fix round 1 of BUG-20261117-27).
     */
    @Test
    @DisplayName("the start refuses an argument lookup of a type variable")
    void theStartRefusesALookupOfATypeVariable() {
        LookupBce.METHOD.set("take");
        var builder = VaubanContainer.builder().addBeanClass(Part.class).addBeanClass(PartTaker.class)
                .addBeanClass(LookupBce.class);

        var refused = assertThrows(DeploymentException.class, builder::build, "the container started");
        assertTrue(refused.getMessage().contains("Invoker argument lookup of a type variable: parameter type T at "
                + "position 0 of method " + PartTaker.class.getName() + ".take"), refused::getMessage);
    }

    /**
     * The deployment validation accepts the lookup of a built-in bean the call serves, the {@code BeanContainer}.
     * It leaves {@code Instance}, {@code Event} and {@code BeanManager} alone, and refuses the others
     * (BUG-20261117-44).
     */
    @Test
    @Disabled("BUG-20261117-44")
    @DisplayName("the start accepts an argument lookup of the BeanContainer")
    void theStartAcceptsALookupOfTheBeanContainer() {
        LookupBce.METHOD.set("hasContainer");
        var builder = VaubanContainer.builder().addBeanClass(ContainerUser.class).addBeanClass(LookupBce.class);

        try (var _ = assertDoesNotThrow(builder::build, "the container refused the lookup")) {
            // It started.
        }
    }

    /**
     * Two intercepted {@code @Dependent} arguments have an interceptor instance each, as two injected ones do
     * (Jakarta Interceptors 2.1, 2.2: an interceptor instance per target instance), and both are destroyed once the
     * call is over. One creational context for every lookup of the call cached one interceptor instance for both
     * (fix round 2 of BUG-20261117-27).
     */
    @Test
    @DisplayName("two intercepted @Dependent arguments have an interceptor instance each, destroyed after the call")
    void twoInterceptedDependentArgumentsHaveAnInterceptorInstanceEach() throws Exception {
        try (var _ = VaubanContainer.builder().addBeanClass(TrackingInterceptor.class).addBeanClass(Left.class)
                .addBeanClass(Right.class).addBeanClass(Pair.class).build()) {
            var join = new VaubanInvoker(Pair.class.getMethod("join", Left.class, Right.class), Pair.class, true,
                    Set.of(0, 1));
            TrackingInterceptor.SEEN.clear();
            int before = DESTROYED.get();

            assertEquals("left+right", join.invoke(null, new Object[] {null, null}));
            assertEquals(2, TrackingInterceptor.SEEN.size(), "the interceptor instances of the two arguments");
            assertEquals(before + 2, DESTROYED.get(), "the two arguments destroyed after the call");
        }
    }

    @Test
    void lookedUpDependentsAreDestroyedAfterTheCallAndNormalScopedInstancesKept() throws Exception {
        try (var _ = VaubanContainer.builder()
                .addBeanClass(Part.class)
                .addBeanClass(Assembly.class)
                .addBeanClass(Worker.class)
                .build()) {
            var assemble = new VaubanInvoker(Assembly.class.getMethod("assemble", Part.class), Assembly.class, true,
                    Set.of(0));
            var work = new VaubanInvoker(Worker.class.getMethod("work", Part.class), Worker.class, true, Set.of(0));
            int before = DESTROYED.get();

            assertEquals("assembled part", assemble.invoke(null, new Object[] {null}));
            assertEquals(before + 1, DESTROYED.get());
            assertEquals("worked with part", work.invoke(null, new Object[] {null}));
            assertEquals(before + 2, DESTROYED.get());
        }
    }

    /** Records why {@code createInvoker} refuses the constructor of {@code Worker}. */
    public static class ConstructorInvokerBce implements BuildCompatibleExtension {
        static final AtomicReference<String> REFUSAL = new AtomicReference<>();

        @Registration(types = Worker.class)
        public void register(BeanInfo bean, InvokerFactory invokers) {
            var constructor = bean.declaringClass().constructors().iterator().next();
            try {
                invokers.createInvoker(bean, constructor);
                REFUSAL.set("accepted " + constructor.name());
            } catch (IllegalStateException refused) {
                REFUSAL.set(refused.getMessage());
            }
        }
    }

    /**
     * The invoker factory refuses a constructor, as CDI 4.1 allows no invoker of one. It tells one by
     * {@code isConstructor()}: since its {@code name()} is its class's, no longer {@code <init>} (BUG-20261117-54),
     * the test of the name it made missed the constructor, which then failed later, on the lookup of a method of that
     * name, with another message.
     */
    @Test
    @DisplayName("the invoker factory refuses a constructor")
    void theInvokerFactoryRefusesAConstructor() {
        ConstructorInvokerBce.REFUSAL.set(null);
        try (var _ = VaubanContainer.builder().addBeanClass(Worker.class).addBeanClass(Part.class)
                .addBeanClass(ConstructorInvokerBce.class).build()) {
            assertEquals("Cannot create invoker for constructor of: " + Worker.class.getName(),
                    ConstructorInvokerBce.REFUSAL.get());
        }
    }
}
