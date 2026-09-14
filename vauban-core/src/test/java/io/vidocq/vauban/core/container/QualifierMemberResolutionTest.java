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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.AnnotationBuilder;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Safety net for vauban#70, written before the qualifier engines are reworked: each member kind a
 * qualifier can carry, resolved through each path the container takes today.
 *
 * <ul>
 *   <li><b>field injection</b>: validated at boot by {@code QualifierMatcher}, then resolved at
 *       creation through {@code QualifierHelper} and {@code VaubanBeanManager#getBeans};</li>
 *   <li><b>constructor injection</b>: validated at boot, then resolved from
 *       {@code Parameter#getAnnotations()};</li>
 *   <li><b>programmatic lookup</b>: {@code CDI.current().select(type, qualifiers)}, with no boot
 *       validation.</li>
 * </ul>
 *
 * <p>The qualifier instances handed to programmatic lookups are read from {@link Qualifiers}, so
 * each one is built by the JDK, the reference implementation of {@link Annotation#equals}. The field
 * consumer always targets the second bean of a pair and the constructor consumer the first, so a
 * resolution that falls back on another bean cannot pass. A test disabled with a BUG id reproduces a
 * defect logged in {@code BUG.md}.
 */
@DisplayName("vauban#70 safety net: qualifier members in field, constructor and programmatic resolution")
class QualifierMemberResolutionTest {

    public interface Service {
        String id();
    }

    /** Exposes what a consumer received, so every scenario reads it the same way. */
    public interface Consumer {
        Service service();
    }

    public enum Hue { RED, BLUE }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    public @interface Inner {
        String value();
    }

    // ---- one qualifier per member kind ----

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Label {
        String value();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Sized {
        int level();

        long budget();

        boolean fast();

        float ratio();

        double weight();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Coded {
        char grade();

        byte tier();

        short rank();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Colored {
        Hue value();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface OfKind {
        Class<?> value();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Tagged {
        String[] value();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Numbered {
        int[] value();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Wrapped {
        Inner value();
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Graded {
        String value() default "standard";
    }

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Noted {
        String value();

        @Nonbinding String note() default "";
    }

    @Qualifier
    @Repeatable(Roles.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Role {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Roles {
        Role[] value();
    }

    @Inherited
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Branded {
        String value();
    }

    @Inherited
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Leveled {
        long value();
    }

    /** A qualifier only through {@link ExtLabelBce}, which also makes {@code value} non-binding. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface ExtLabel {
        String value();

        String group();
    }

    /** Carries the annotation instances handed to programmatic lookups. Never a bean. */
    static final class Qualifiers {
        @Label("two") void label() {}

        @Sized(level = 2, budget = 20L, fast = false, ratio = 2.5f, weight = 3.5) void sized() {}

        @Coded(grade = 'b', tier = 2, rank = 20) void coded() {}

        @Colored(Hue.BLUE) void colored() {}

        @OfKind(Integer.class) void ofKind() {}

        @Tagged({"c"}) void tagged() {}

        @Numbered({3}) void numbered() {}

        @Wrapped(@Inner("y")) void wrapped() {}

        @Graded("standard") void graded() {}

        @Noted(value = "two", note = "lookup") void noted() {}

        @Named("reportService") void named() {}

        @Role("admin") @Role("audit") void roles() {}

        @Branded("acme") void branded() {}

        @Leveled(1L) void leveled() {}

        @ExtLabel(value = "lookup", group = "two") void extLabel() {}
    }

    // ---- String ----

    @Label("one") @Dependent
    public static class LabelOne implements Service {
        @Override public String id() { return "label-one"; }
    }

    @Label("two") @Dependent
    public static class LabelTwo implements Service {
        @Override public String id() { return "label-two"; }
    }

    @Dependent
    public static class LabelField implements Consumer {
        @Inject @Label("two") public Service service;

        @Override public Service service() { return service; }
    }

    @Dependent
    public static class LabelConstructor implements Consumer {
        private final Service service;

        @Inject public LabelConstructor(@Label("one") Service service) { this.service = service; }

        @Override public Service service() { return service; }
    }

    // ---- int, long, boolean, float, double ----

    @Sized(level = 1, budget = 10L, fast = true, ratio = 0.5f, weight = 1.5) @Dependent
    public static class SizedOne implements Service {
        @Override public String id() { return "sized-one"; }
    }

    @Sized(level = 2, budget = 20L, fast = false, ratio = 2.5f, weight = 3.5) @Dependent
    public static class SizedTwo implements Service {
        @Override public String id() { return "sized-two"; }
    }

    @Dependent
    public static class SizedField implements Consumer {
        @Inject @Sized(level = 2, budget = 20L, fast = false, ratio = 2.5f, weight = 3.5) public Service service;

        @Override public Service service() { return service; }
    }

    @Dependent
    public static class SizedConstructor implements Consumer {
        private final Service service;

        @Inject public SizedConstructor(
                @Sized(level = 1, budget = 10L, fast = true, ratio = 0.5f, weight = 1.5) Service service) {
            this.service = service;
        }

        @Override public Service service() { return service; }
    }

    // ---- char, byte, short ----

    @Coded(grade = 'a', tier = 1, rank = 10) @Dependent
    public static class CodedOne implements Service {
        @Override public String id() { return "coded-one"; }
    }

    @Coded(grade = 'b', tier = 2, rank = 20) @Dependent
    public static class CodedTwo implements Service {
        @Override public String id() { return "coded-two"; }
    }

    @Dependent
    public static class CodedField implements Consumer {
        @Inject @Coded(grade = 'b', tier = 2, rank = 20) public Service service;

        @Override public Service service() { return service; }
    }

    @Dependent
    public static class CodedConstructor implements Consumer {
        private final Service service;

        @Inject public CodedConstructor(@Coded(grade = 'a', tier = 1, rank = 10) Service service) {
            this.service = service;
        }

        @Override public Service service() { return service; }
    }

    // ---- enum ----

    @Colored(Hue.RED) @Dependent
    public static class ColoredOne implements Service {
        @Override public String id() { return "colored-one"; }
    }

    @Colored(Hue.BLUE) @Dependent
    public static class ColoredTwo implements Service {
        @Override public String id() { return "colored-two"; }
    }

    @Dependent
    public static class ColoredField implements Consumer {
        @Inject @Colored(Hue.BLUE) public Service service;

        @Override public Service service() { return service; }
    }

    @Dependent
    public static class ColoredConstructor implements Consumer {
        private final Service service;

        @Inject public ColoredConstructor(@Colored(Hue.RED) Service service) { this.service = service; }

        @Override public Service service() { return service; }
    }

    // ---- Class ----

    @OfKind(String.class) @Dependent
    public static class OfKindOne implements Service {
        @Override public String id() { return "of-kind-one"; }
    }

    @OfKind(Integer.class) @Dependent
    public static class OfKindTwo implements Service {
        @Override public String id() { return "of-kind-two"; }
    }

    @Dependent
    public static class OfKindField implements Consumer {
        @Inject @OfKind(Integer.class) public Service service;

        @Override public Service service() { return service; }
    }

    @Dependent
    public static class OfKindConstructor implements Consumer {
        private final Service service;

        @Inject public OfKindConstructor(@OfKind(String.class) Service service) { this.service = service; }

        @Override public Service service() { return service; }
    }

    // ---- String[] ----

    @Tagged({"a", "b"}) @Dependent
    public static class TaggedOne implements Service {
        @Override public String id() { return "tagged-one"; }
    }

    @Tagged({"c"}) @Dependent
    public static class TaggedTwo implements Service {
        @Override public String id() { return "tagged-two"; }
    }

    @Dependent
    public static class TaggedField implements Consumer {
        @Inject @Tagged({"c"}) public Service service;

        @Override public Service service() { return service; }
    }

    @Dependent
    public static class TaggedConstructor implements Consumer {
        private final Service service;

        @Inject public TaggedConstructor(@Tagged({"a", "b"}) Service service) { this.service = service; }

        @Override public Service service() { return service; }
    }

    // ---- int[] ----

    @Numbered({1, 2}) @Dependent
    public static class NumberedOne implements Service {
        @Override public String id() { return "numbered-one"; }
    }

    @Numbered({3}) @Dependent
    public static class NumberedTwo implements Service {
        @Override public String id() { return "numbered-two"; }
    }

    @Dependent
    public static class NumberedField implements Consumer {
        @Inject @Numbered({3}) public Service service;

        @Override public Service service() { return service; }
    }

    @Dependent
    public static class NumberedConstructor implements Consumer {
        private final Service service;

        @Inject public NumberedConstructor(@Numbered({1, 2}) Service service) { this.service = service; }

        @Override public Service service() { return service; }
    }

    // ---- nested annotation ----

    @Wrapped(@Inner("x")) @Dependent
    public static class WrappedOne implements Service {
        @Override public String id() { return "wrapped-one"; }
    }

    @Wrapped(@Inner("y")) @Dependent
    public static class WrappedTwo implements Service {
        @Override public String id() { return "wrapped-two"; }
    }

    @Dependent
    public static class WrappedField implements Consumer {
        @Inject @Wrapped(@Inner("y")) public Service service;

        @Override public Service service() { return service; }
    }

    @Dependent
    public static class WrappedConstructor implements Consumer {
        private final Service service;

        @Inject public WrappedConstructor(@Wrapped(@Inner("x")) Service service) { this.service = service; }

        @Override public Service service() { return service; }
    }

    // ---- defaulted member: @Graded and @Graded("standard") are the same annotation ----

    @Graded @Dependent
    public static class GradedImplicit implements Service {
        @Override public String id() { return "graded-implicit"; }
    }

    @Graded("standard") @Dependent
    public static class GradedExplicit implements Service {
        @Override public String id() { return "graded-explicit"; }
    }

    @Graded("premium") @Dependent
    public static class GradedPremium implements Service {
        @Override public String id() { return "graded-premium"; }
    }

    @Dependent
    public static class GradedField implements Consumer {
        @Inject @Graded("standard") public Service service;

        @Override public Service service() { return service; }
    }

    @Dependent
    public static class GradedConstructor implements Consumer {
        private final Service service;

        @Inject public GradedConstructor(@Graded Service service) { this.service = service; }

        @Override public Service service() { return service; }
    }

    // ---- @Nonbinding member ----

    @Noted(value = "one", note = "bean") @Dependent
    public static class NotedOne implements Service {
        @Override public String id() { return "noted-one"; }
    }

    @Noted(value = "two", note = "bean") @Dependent
    public static class NotedTwo implements Service {
        @Override public String id() { return "noted-two"; }
    }

    @Dependent
    public static class NotedField implements Consumer {
        @Inject @Noted(value = "two", note = "field") public Service service;

        @Override public Service service() { return service; }
    }

    @Dependent
    public static class NotedConstructor implements Consumer {
        private final Service service;

        @Inject public NotedConstructor(@Noted(value = "one", note = "constructor") Service service) {
            this.service = service;
        }

        @Override public Service service() { return service; }
    }

    // ---- @Named, with the default name ----

    @Named @Dependent
    public static class ReportService implements Service {
        @Override public String id() { return "report"; }
    }

    @Named("audit") @Dependent
    public static class AuditService implements Service {
        @Override public String id() { return "audit"; }
    }

    @Dependent
    public static class NamedField implements Consumer {
        @Inject @Named("reportService") public Service service;

        @Override public Service service() { return service; }
    }

    @Dependent
    public static class NamedConstructor implements Consumer {
        private final Service service;

        @Inject public NamedConstructor(@Named("audit") Service service) { this.service = service; }

        @Override public Service service() { return service; }
    }

    // ---- repeatable qualifier ----

    @Role("admin") @Role("audit") @Dependent
    public static class RoleAdmin implements Service {
        @Override public String id() { return "role-admin"; }
    }

    @Role("user") @Dependent
    public static class RoleUser implements Service {
        @Override public String id() { return "role-user"; }
    }

    @Dependent
    public static class RoleField implements Consumer {
        @Inject @Role("user") public Service service;

        @Override public Service service() { return service; }
    }

    @Dependent
    public static class RoleConstructor implements Consumer {
        private final Service service;

        @Inject public RoleConstructor(@Role("audit") Service service) { this.service = service; }

        @Override public Service service() { return service; }
    }

    // ---- @Inherited qualifiers, declared on an abstract superclass ----

    /** Declares no bean type: {@code Service} comes from the child, so only the qualifier is inherited. */
    @Branded("acme")
    public abstract static class BrandedBase {
    }

    @Dependent
    public static class BrandedChild extends BrandedBase implements Service {
        @Override public String id() { return "branded-child"; }
    }

    @Branded("other") @Dependent
    public static class BrandedOther implements Service {
        @Override public String id() { return "branded-other"; }
    }

    @Dependent
    public static class BrandedField implements Consumer {
        @Inject @Branded("acme") public Service service;

        @Override public Service service() { return service; }
    }

    /** Declares no bean type: {@code Service} comes from the child, so only the qualifier is inherited. */
    @Leveled(1L)
    public abstract static class LeveledBase {
    }

    @Dependent
    public static class LeveledChild extends LeveledBase implements Service {
        @Override public String id() { return "leveled-child"; }
    }

    @Leveled(2L) @Dependent
    public static class LeveledOther implements Service {
        @Override public String id() { return "leveled-other"; }
    }

    @Dependent
    public static class LeveledField implements Consumer {
        @Inject @Leveled(1L) public Service service;

        @Override public Service service() { return service; }
    }

    // ---- member made non-binding by an extension ----

    public static class ExtLabelBce implements BuildCompatibleExtension {
        @Discovery
        public void register(MetaAnnotations meta) {
            meta.addQualifier(ExtLabel.class).methods().stream()
                    .filter(method -> method.info().name().equals("value"))
                    .forEach(method -> method.addAnnotation(Nonbinding.class));
        }
    }

    @ExtLabel(value = "bean", group = "one") @Dependent
    public static class ExtOne implements Service {
        @Override public String id() { return "ext-one"; }
    }

    @ExtLabel(value = "bean", group = "two") @Dependent
    public static class ExtTwo implements Service {
        @Override public String id() { return "ext-two"; }
    }

    @Dependent
    public static class ExtLabelField implements Consumer {
        @Inject @ExtLabel(value = "field", group = "two") public Service service;

        @Override public Service service() { return service; }
    }

    // ---- qualifier added by an @Enhancement ----

    public static class PaintLabelBce implements BuildCompatibleExtension {
        @Enhancement(types = PaintedLabel.class)
        public void paint(ClassConfig config) {
            config.addAnnotation(AnnotationBuilder.of(Label.class).member("value", "two").build());
        }
    }

    @Dependent
    public static class PaintedLabel implements Service {
        @Override public String id() { return "painted-label"; }
    }

    public static class PaintBlueBce implements BuildCompatibleExtension {
        @Enhancement(types = PaintedBlue.class)
        public void paint(ClassConfig config) {
            config.addAnnotation(AnnotationBuilder.of(Colored.class).member("value", Hue.BLUE).build());
        }
    }

    @Dependent
    public static class PaintedBlue implements Service {
        @Override public String id() { return "painted-blue"; }
    }

    public static class PaintKindBce implements BuildCompatibleExtension {
        @Enhancement(types = PaintedKind.class)
        public void paint(ClassConfig config) {
            config.addAnnotation(AnnotationBuilder.of(OfKind.class).member("value", Integer.class).build());
        }
    }

    @Dependent
    public static class PaintedKind implements Service {
        @Override public String id() { return "painted-kind"; }
    }

    // ---- a @Default bean next to qualified ones ----

    @Dependent
    public static class PlainService implements Service {
        @Override public String id() { return "plain"; }
    }

    // ---- helpers ----

    private static String injected(Class<? extends Consumer> consumer, Class<?>... beans) {
        try (var container = build(consumer, beans)) {
            return container.select(consumer).service().id();
        }
    }

    private static String selected(Annotation[] qualifiers, Class<?>... beans) {
        try (var container = build(null, beans)) {
            return CDI.current().select(Service.class, qualifiers).get().id();
        }
    }

    private static VaubanContainer build(Class<?> consumer, Class<?>... beans) {
        var builder = VaubanContainer.builder();
        if (consumer != null) {
            builder.addBeanClass(consumer);
        }
        for (var bean : beans) {
            builder.addBeanClass(bean);
        }
        return builder.build();
    }

    private static Annotation[] qualifiers(String carrierMethod) {
        return carrier(carrierMethod).getDeclaredAnnotations();
    }

    private static Method carrier(String name) {
        try {
            return Qualifiers.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("no carrier method " + name, e);
        }
    }

    // ---- scenarios ----

    @Nested
    @DisplayName("String member")
    class StringMember {
        @Test @DisplayName("field injection")
        void field() {
            assertEquals("label-two", injected(LabelField.class, LabelOne.class, LabelTwo.class));
        }

        @Test @DisplayName("constructor injection")
        void constructor() {
            assertEquals("label-one", injected(LabelConstructor.class, LabelOne.class, LabelTwo.class));
        }

        @Test @DisplayName("programmatic lookup")
        void programmatic() {
            assertEquals("label-two", selected(qualifiers("label"), LabelOne.class, LabelTwo.class));
        }
    }

    @Nested
    @DisplayName("int, long, boolean, float and double members")
    class PrimitiveMembers {
        @Test @DisplayName("field injection")
        void field() {
            assertEquals("sized-two", injected(SizedField.class, SizedOne.class, SizedTwo.class));
        }

        @Test @DisplayName("constructor injection")
        void constructor() {
            assertEquals("sized-one", injected(SizedConstructor.class, SizedOne.class, SizedTwo.class));
        }

        @Test @DisplayName("programmatic lookup")
        void programmatic() {
            assertEquals("sized-two", selected(qualifiers("sized"), SizedOne.class, SizedTwo.class));
        }
    }

    @Nested
    @DisplayName("char, byte and short members")
    class NarrowPrimitiveMembers {
        @Test @DisplayName("field injection")
        void field() {
            assertEquals("coded-two", injected(CodedField.class, CodedOne.class, CodedTwo.class));
        }

        @Test @DisplayName("constructor injection")
        void constructor() {
            assertEquals("coded-one", injected(CodedConstructor.class, CodedOne.class, CodedTwo.class));
        }

        @Test @DisplayName("programmatic lookup")
        void programmatic() {
            assertEquals("coded-two", selected(qualifiers("coded"), CodedOne.class, CodedTwo.class));
        }
    }

    @Nested
    @DisplayName("enum member")
    class EnumMember {
        @Test @DisplayName("field injection")
        void field() {
            assertEquals("colored-two", injected(ColoredField.class, ColoredOne.class, ColoredTwo.class));
        }

        @Test @DisplayName("constructor injection")
        void constructor() {
            assertEquals("colored-one", injected(ColoredConstructor.class, ColoredOne.class, ColoredTwo.class));
        }

        @Test @DisplayName("programmatic lookup")
        void programmatic() {
            assertEquals("colored-two", selected(qualifiers("colored"), ColoredOne.class, ColoredTwo.class));
        }
    }

    @Nested
    @DisplayName("Class member")
    class ClassMember {
        @Test @DisplayName("field injection")
        void field() {
            assertEquals("of-kind-two", injected(OfKindField.class, OfKindOne.class, OfKindTwo.class));
        }

        @Test @DisplayName("constructor injection")
        void constructor() {
            assertEquals("of-kind-one", injected(OfKindConstructor.class, OfKindOne.class, OfKindTwo.class));
        }

        @Test @DisplayName("programmatic lookup")
        void programmatic() {
            assertEquals("of-kind-two", selected(qualifiers("ofKind"), OfKindOne.class, OfKindTwo.class));
        }
    }

    @Nested
    @DisplayName("String[] member")
    class ObjectArrayMember {
        @Test @DisplayName("field injection")
        void field() {
            assertEquals("tagged-two", injected(TaggedField.class, TaggedOne.class, TaggedTwo.class));
        }

        @Test @DisplayName("constructor injection")
        void constructor() {
            assertEquals("tagged-one", injected(TaggedConstructor.class, TaggedOne.class, TaggedTwo.class));
        }

        @Test @DisplayName("programmatic lookup")
        void programmatic() {
            assertEquals("tagged-two", selected(qualifiers("tagged"), TaggedOne.class, TaggedTwo.class));
        }
    }

    @Nested
    @DisplayName("int[] member")
    class PrimitiveArrayMember {
        @Test @DisplayName("field injection")
        void field() {
            assertEquals("numbered-two", injected(NumberedField.class, NumberedOne.class, NumberedTwo.class));
        }

        @Test @DisplayName("constructor injection")
        void constructor() {
            assertEquals("numbered-one",
                    injected(NumberedConstructor.class, NumberedOne.class, NumberedTwo.class));
        }

        @Test @DisplayName("programmatic lookup")
        void programmatic() {
            assertEquals("numbered-two", selected(qualifiers("numbered"), NumberedOne.class, NumberedTwo.class));
        }
    }

    @Nested
    @DisplayName("nested annotation member")
    class NestedAnnotationMember {
        @Test @DisplayName("field injection")
        void field() {
            assertEquals("wrapped-two", injected(WrappedField.class, WrappedOne.class, WrappedTwo.class));
        }

        @Test @DisplayName("constructor injection")
        void constructor() {
            assertEquals("wrapped-one", injected(WrappedConstructor.class, WrappedOne.class, WrappedTwo.class));
        }

        @Test @DisplayName("programmatic lookup")
        void programmatic() {
            assertEquals("wrapped-two", selected(qualifiers("wrapped"), WrappedOne.class, WrappedTwo.class));
        }
    }

    @Nested
    @DisplayName("defaulted member")
    class DefaultedMember {
        @Test @DisplayName("field injection: @Graded(\"standard\") resolves the bean declared @Graded")
        void explicitPointImplicitBean() {
            assertEquals("graded-implicit",
                    injected(GradedField.class, GradedImplicit.class, GradedPremium.class));
        }

        @Test @DisplayName("constructor injection: @Graded resolves the bean declared @Graded(\"standard\")")
        void implicitPointExplicitBean() {
            assertEquals("graded-explicit",
                    injected(GradedConstructor.class, GradedExplicit.class, GradedPremium.class));
        }

        @Test @DisplayName("programmatic lookup: @Graded(\"standard\") resolves the bean declared @Graded")
        void programmatic() {
            assertEquals("graded-implicit",
                    selected(qualifiers("graded"), GradedImplicit.class, GradedPremium.class));
        }
    }

    @Nested
    @DisplayName("@Nonbinding member")
    class NonbindingMember {
        @Test @DisplayName("field injection")
        void field() {
            assertEquals("noted-two", injected(NotedField.class, NotedOne.class, NotedTwo.class));
        }

        @Test @DisplayName("constructor injection")
        void constructor() {
            assertEquals("noted-one", injected(NotedConstructor.class, NotedOne.class, NotedTwo.class));
        }

        @Test @DisplayName("programmatic lookup")
        void programmatic() {
            assertEquals("noted-two", selected(qualifiers("noted"), NotedOne.class, NotedTwo.class));
        }
    }

    @Nested
    @DisplayName("@Named with the default bean name")
    class NamedQualifier {
        @Test @DisplayName("field injection")
        @Disabled("BUG-20260914-11: a nested bean class's default name keeps its enclosing class")
        void field() {
            assertEquals("report", injected(NamedField.class, ReportService.class, AuditService.class));
        }

        @Test @DisplayName("constructor injection")
        void constructor() {
            assertEquals("audit", injected(NamedConstructor.class, ReportService.class, AuditService.class));
        }

        @Test @DisplayName("programmatic lookup")
        @Disabled("BUG-20260914-11: a nested bean class's default name keeps its enclosing class")
        void programmatic() {
            assertEquals("report", selected(qualifiers("named"), ReportService.class, AuditService.class));
        }
    }

    @Nested
    @DisplayName("repeatable qualifier")
    class RepeatableQualifier {
        @Test @DisplayName("field injection")
        void field() {
            assertEquals("role-user", injected(RoleField.class, RoleAdmin.class, RoleUser.class));
        }

        @Test @DisplayName("constructor injection matches one of the repeated values")
        void constructor() {
            assertEquals("role-admin", injected(RoleConstructor.class, RoleAdmin.class, RoleUser.class));
        }

        @Test @DisplayName("programmatic lookup with both repeated values")
        void programmatic() {
            Annotation[] both = carrier("roles").getDeclaredAnnotationsByType(Role.class);
            assertEquals("role-admin", selected(both, RoleAdmin.class, RoleUser.class));
        }
    }

    @Nested
    @DisplayName("@Inherited qualifier")
    class InheritedQualifier {
        @Test @DisplayName("String member: field injection")
        void stringField() {
            assertEquals("branded-child", injected(BrandedField.class, BrandedChild.class, BrandedOther.class));
        }

        @Test @DisplayName("String member: programmatic lookup")
        void stringProgrammatic() {
            assertEquals("branded-child", selected(qualifiers("branded"), BrandedChild.class, BrandedOther.class));
        }

        @Test @DisplayName("long member: field injection")
        void longField() {
            assertEquals("leveled-child", injected(LeveledField.class, LeveledChild.class, LeveledOther.class));
        }

        @Test @DisplayName("long member: programmatic lookup")
        void longProgrammatic() {
            assertEquals("leveled-child", selected(qualifiers("leveled"), LeveledChild.class, LeveledOther.class));
        }
    }

    @Nested
    @DisplayName("member made non-binding by an extension")
    class ExtensionNonbindingMember {
        @Test @DisplayName("field injection")
        void field() {
            assertEquals("ext-two",
                    injected(ExtLabelField.class, ExtLabelBce.class, ExtOne.class, ExtTwo.class));
        }

        @Test @DisplayName("programmatic lookup")
        void programmatic() {
            assertEquals("ext-two",
                    selected(qualifiers("extLabel"), ExtLabelBce.class, ExtOne.class, ExtTwo.class));
        }
    }

    @Nested
    @DisplayName("qualifier added by an @Enhancement")
    class EnhancementAddedQualifier {
        @Test @DisplayName("String member: field injection")
        void stringMember() {
            assertEquals("painted-label",
                    injected(LabelField.class, PaintLabelBce.class, PaintedLabel.class, LabelOne.class));
        }

        @Test @DisplayName("enum member: field injection")
        void enumMember() {
            assertEquals("painted-blue",
                    injected(ColoredField.class, PaintBlueBce.class, PaintedBlue.class, ColoredOne.class));
        }

        @Test @DisplayName("Class member: field injection")
        void classMember() {
            assertEquals("painted-kind",
                    injected(OfKindField.class, PaintKindBce.class, PaintedKind.class, OfKindOne.class));
        }
    }

    @Nested
    @DisplayName("an unmatched qualifier never falls back on the @Default bean")
    class NoDefaultFallback {
        @Test @DisplayName("programmatic lookup")
        void programmatic() {
            try (var container = build(null, LabelOne.class, PlainService.class)) {
                assertThrows(UnsatisfiedResolutionException.class,
                        () -> CDI.current().select(Service.class, qualifiers("label")).get());
            }
        }

        @Test @DisplayName("field injection with an enum member")
        void enumField() {
            assertEquals("colored-two",
                    injected(ColoredField.class, ColoredOne.class, ColoredTwo.class, PlainService.class));
        }
    }

    /**
     * The TCK deploys each archive through its own class loader, and so does an application
     * re-layered by Vauban. The fixtures are generated with the Class-File API so that no class of the
     * test's own loader can see them. The first two tests set the thread context class loader to the
     * application loader, as the TCK runner and the Vauban layer do; the third leaves it alone.
     */
    @Nested
    @DisplayName("qualifier type visible only to the application class loader")
    class IsolatedClassLoader {
        @TempDir
        Path classes;

        @Test @DisplayName("control: equal member values resolve")
        void equalMembers() throws Exception {
            assertEquals("iso-one", isolatedLookup("iso.MarkedExact", true));
        }

        @Test @DisplayName("a @Nonbinding member whose values differ is ignored")
        void nonbindingMember() throws Exception {
            assertEquals("iso-one", isolatedLookup("iso.MarkedLoose", true));
        }

        @Test @DisplayName("field injection does not depend on the thread context class loader")
        @Disabled("BUG-20260914-05: field injection loads qualifier types through the thread context class loader")
        void withoutContextClassLoader() throws Exception {
            assertEquals("iso-one", isolatedLookup("iso.MarkedExact", false));
        }

        private String isolatedLookup(String consumerName, boolean contextLoader) throws Exception {
            IsolatedFixtures.write(classes);
            var thread = Thread.currentThread();
            var previous = thread.getContextClassLoader();
            try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                    QualifierMemberResolutionTest.class.getClassLoader())) {
                if (contextLoader) {
                    thread.setContextClassLoader(loader);
                }
                var consumerType = loader.loadClass(consumerName);
                var builder = VaubanContainer.builder();
                builder.classLoader(loader);
                builder.addBeanClass(loader.loadClass("iso.MarkedOne"));
                builder.addBeanClass(loader.loadClass("iso.MarkedTwo"));
                builder.addBeanClass(consumerType);
                try (var container = builder.build()) {
                    Object consumer = container.select(consumerType);
                    var supplier = (Supplier<?>) consumerType.getField("supplier").get(consumer);
                    return String.valueOf(supplier.get());
                }
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    /**
     * {@code @Qualifier @interface Marked { String value(); @Nonbinding String note(); }}, two
     * {@code @Dependent} suppliers qualified {@code @Marked("a", note "one")} and
     * {@code @Marked("b", note "one")}, and two consumers of {@code @Marked("a", …) Supplier}: one with
     * the same note, one with another.
     */
    private static final class IsolatedFixtures {
        private static final ClassDesc MARKED = ClassDesc.of("iso.Marked");
        private static final ClassDesc SUPPLIER = ClassDesc.of("java.util.function.Supplier");
        private static final ClassDesc DEPENDENT = ClassDesc.of("jakarta.enterprise.context.Dependent");

        static void write(Path root) throws IOException {
            put(root, MARKED, qualifierType());
            put(root, ClassDesc.of("iso.MarkedOne"), bean("iso.MarkedOne", "a", "iso-one"));
            put(root, ClassDesc.of("iso.MarkedTwo"), bean("iso.MarkedTwo", "b", "iso-two"));
            put(root, ClassDesc.of("iso.MarkedExact"), consumer("iso.MarkedExact", "one"));
            put(root, ClassDesc.of("iso.MarkedLoose"), consumer("iso.MarkedLoose", "two"));
        }

        private static byte[] qualifierType() {
            return ClassFile.of().build(MARKED, clb -> clb
                    .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
                            | ClassFile.ACC_ANNOTATION)
                    .withInterfaceSymbols(ClassDesc.of("java.lang.annotation.Annotation"))
                    .with(RuntimeVisibleAnnotationsAttribute.of(
                            java.lang.classfile.Annotation.of(ClassDesc.of("jakarta.inject.Qualifier")),
                            java.lang.classfile.Annotation.of(ClassDesc.of("java.lang.annotation.Retention"),
                                    AnnotationElement.of("value", java.lang.classfile.AnnotationValue.ofEnum(
                                            ClassDesc.of("java.lang.annotation.RetentionPolicy"), "RUNTIME")))))
                    .withMethod("value", MethodTypeDesc.of(ConstantDescs.CD_String),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, mb -> { })
                    .withMethod("note", MethodTypeDesc.of(ConstantDescs.CD_String),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
                            mb -> mb.with(RuntimeVisibleAnnotationsAttribute.of(java.lang.classfile.Annotation.of(
                                    ClassDesc.of("jakarta.enterprise.util.Nonbinding"))))));
        }

        private static byte[] bean(String name, String value, String id) {
            return ClassFile.of().build(ClassDesc.of(name), clb -> clb
                    .withFlags(ClassFile.ACC_PUBLIC)
                    .withInterfaceSymbols(SUPPLIER)
                    .with(RuntimeVisibleAnnotationsAttribute.of(
                            java.lang.classfile.Annotation.of(DEPENDENT), marked(value, "one")))
                    .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                            IsolatedFixtures::superConstructor)
                    .withMethodBody("get", MethodTypeDesc.of(ConstantDescs.CD_Object), ClassFile.ACC_PUBLIC,
                            cob -> cob.ldc(id).areturn()));
        }

        private static byte[] consumer(String name, String note) {
            return ClassFile.of().build(ClassDesc.of(name), clb -> clb
                    .withFlags(ClassFile.ACC_PUBLIC)
                    .with(RuntimeVisibleAnnotationsAttribute.of(java.lang.classfile.Annotation.of(DEPENDENT)))
                    .withField("supplier", SUPPLIER, fb -> fb
                            .withFlags(ClassFile.ACC_PUBLIC)
                            .with(RuntimeVisibleAnnotationsAttribute.of(
                                    java.lang.classfile.Annotation.of(ClassDesc.of("jakarta.inject.Inject")),
                                    marked("a", note))))
                    .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                            IsolatedFixtures::superConstructor));
        }

        private static java.lang.classfile.Annotation marked(String value, String note) {
            return java.lang.classfile.Annotation.of(MARKED,
                    AnnotationElement.ofString("value", value),
                    AnnotationElement.ofString("note", note));
        }

        private static void superConstructor(CodeBuilder cob) {
            cob.aload(0)
                    .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                    .return_();
        }

        private static void put(Path root, ClassDesc type, byte[] bytes) throws IOException {
            var file = root.resolve(type.packageName().replace('.', '/')).resolve(type.displayName() + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        }
    }
}
