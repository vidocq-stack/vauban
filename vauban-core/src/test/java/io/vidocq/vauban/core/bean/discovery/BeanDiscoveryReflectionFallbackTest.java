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
package io.vidocq.vauban.core.bean.discovery;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.core.codegen.CompiledSupertypes;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.loading.IsolatedLoader;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.AnnotationValue;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.DefinitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bean discovery when the index does not tell the whole story: a class the index describes without methods or
 * annotations, a member the index lists but the class does not declare, a class that cannot be loaded at all. The
 * discovery then reads the class by reflection, or falls back on what the index says.
 */
@DisplayName("BeanDiscovery: reflection fallbacks and index-only descriptions")
class BeanDiscoveryReflectionFallbackTest {

    private static final String MISSING_CLASS = "io.vidocq.vauban.core.bean.discovery.NoSuchBean";
    private static final String PRODUCES = "jakarta.enterprise.inject.Produces";
    private static final String INJECT = "jakarta.inject.Inject";
    private static final String DEPENDENT = "jakarta.enterprise.context.Dependent";
    private static final String OBJECT = "java.lang.Object";
    private static final String VALUE = "value";
    private static final String SERIALIZABLE = "java.io.Serializable";
    /** A class the compilation compiles: the index does not hold it, and no class loader defines it. */
    private static final String COMPILED_SUPERCLASS = "io.vidocq.vauban.core.bean.discovery.NoSuchWrapping";
    /** A generic interface the compilation compiles: the index does not hold it, and no class loader defines it. */
    private static final String COMPILED_REPO = "io.vidocq.vauban.core.bean.discovery.NoSuchRepo";
    /** {@code Repo<String>}, as Vauban's annotation processor reads the interface above {@link #MISSING_CLASS}. */
    private static final TypeInfo REPO_OF_STRING = new TypeInfo.ParameterizedType(DotName.of(COMPILED_REPO),
            List.of(new TypeInfo.ClassType(DotName.of("java.lang.String"))));

    /** Inherited, but not bean defining. */
    @Inherited
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Tag {
    }

    @Tag
    @ApplicationScoped
    public static class ScopedBase {
    }

    /** No annotation of its own: a bean through the scope it inherits. */
    public static class InheritingBean extends ScopedBase {
    }

    @Tag
    public static class TaggedBase {
    }

    public static class TaggedMiddle extends TaggedBase {
    }

    /** Inherits only a non-bean-defining annotation: not a bean. */
    public static class TaggedLeaf extends TaggedMiddle {
    }

    public abstract static class AbstractBean {
    }

    public static class ProducerHost {

        @Produces
        @Priority(7)
        public String rank() {
            return "rank";
        }

        @Produces
        @Priority(9)
        public Integer level = 9;

        @Produces
        public Long plain = 1L;

        @SuppressWarnings("unchecked")
        public List<String>[][] grid = new List[0][0];
    }

    public static class TypedHost extends ArrayList<String> {
    }

    @jakarta.enterprise.inject.Stereotype
    @jakarta.enterprise.inject.Alternative
    @Priority(3)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RankedAlternative {
    }

    @jakarta.enterprise.inject.Stereotype
    @Retention(RetentionPolicy.RUNTIME)
    public @interface PlainStereotype {
    }

    @jakarta.enterprise.inject.Stereotype
    @Retention(RetentionPolicy.RUNTIME)
    public @interface UnindexedStereotype {
    }

    @jakarta.enterprise.inject.Stereotype
    @jakarta.inject.Named
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NamingStereotype {
    }

    @jakarta.enterprise.context.Dependent
    public static class StereotypedProducers {
        @Produces
        @RankedAlternative
        public String ranked() {
            return "ranked";
        }

        @Produces
        @PlainStereotype
        @UnindexedStereotype
        public Integer plain() {
            return 1;
        }

        @Produces
        @NamingStereotype
        public Long named() {
            return 2L;
        }
    }

    private static ClassInfo scan(Class<?> type) {
        var resource = type.getName().replace('.', '/') + ".class";
        try (var in = type.getClassLoader().getResourceAsStream(resource)) {
            return ClassFileScanner.scan(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static AnnotationInfo ann(String name) {
        return new AnnotationInfo(DotName.of(name), Map.of());
    }

    private static AnnotationInfo ann(String name, AnnotationValue value) {
        return new AnnotationInfo(DotName.of(name), Map.of(VALUE, value));
    }

    private static TypeInfo cls(String name) {
        return new TypeInfo.ClassType(DotName.of(name));
    }

    private static MethodInfo constructor() {
        return new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of());
    }

    private static MethodInfo producerMethod(String name) {
        return new MethodInfo(name, cls("java.lang.String"), List.of(), List.of(), 0x0001, List.of(ann(PRODUCES)));
    }

    private static FieldInfo producerField(String name, String type) {
        return new FieldInfo(name, cls(type), 0x0001, List.of(ann(PRODUCES)));
    }

    private static ClassInfo classInfo(String name, List<FieldInfo> fields, List<MethodInfo> methods,
                                       List<AnnotationInfo> annotations) {
        return new ClassInfo(DotName.of(name), DotName.of(OBJECT), List.of(), 0x0001, fields, methods, annotations,
                ClassInfo.ClassKind.CLASS);
    }

    private static BeanDiscovery discovery(ClassInfo... classes) {
        var builder = new IndexBuilder();
        for (var info : classes) {
            builder.add(info);
        }
        return new BeanDiscovery(builder.build());
    }

    private static List<String> beanClassNames(List<BeanDescriptor> beans) {
        return beans.stream().map(b -> b.beanClass().value()).toList();
    }

    @Test
    @DisplayName("a class inherits a bean-defining scope from its superclass, but not from a mere @Inherited tag")
    void inheritedBeanDefiningAnnotation() {
        var beans = discovery(scan(InheritingBean.class), scan(TaggedLeaf.class)).discoverBeans();

        var names = beanClassNames(beans);
        assertTrue(names.contains(InheritingBean.class.getName()), names::toString);
        assertFalse(names.contains(TaggedLeaf.class.getName()), names::toString);
    }

    @Test
    @DisplayName("outside the scanned classes, only a class that is a bean by its annotations is admitted")
    void scannedClassesFilterReadsReflection() {
        var discovery = discovery(scan(TaggedLeaf.class), scan(InheritingBean.class));
        discovery.setScannedClassesFilter(java.util.Set.of(DotName.of(MISSING_CLASS)));

        var names = beanClassNames(discovery.discoverBeans());

        assertEquals(List.of(InheritingBean.class.getName()), names);
    }

    @Test
    @DisplayName("an index entry without methods is checked by reflection; an abstract or unloadable class is no bean")
    void indexEntriesWithoutMethods() {
        var dependent = List.of(ann(DEPENDENT));
        var abstractBean = classInfo(AbstractBean.class.getName(), List.of(), List.of(), dependent);
        var missing = classInfo(MISSING_CLASS, List.of(), List.of(), dependent);

        var names = beanClassNames(discovery(abstractBean, missing).discoverBeans());

        assertEquals(List.of(MISSING_CLASS), names,
                "the abstract class is rejected by reflection; the unloadable one keeps its implicit constructor");
    }

    /**
     * A class whose constructor takes a type no loader defines, as the classes of a library with an optional
     * dependency do: reading its constructors by reflection throws {@link NoClassDefFoundError}.
     */
    private static Class<?> classWithAnUnlinkableConstructor(String name) throws ClassNotFoundException {
        var self = java.lang.constant.ClassDesc.of(name);
        var absent = java.lang.constant.ClassDesc.of("io.vidocq.vauban.core.bean.discovery.absent.Absent");
        byte[] bytes = java.lang.classfile.ClassFile.of().build(self, builder -> builder
                .withFlags(java.lang.classfile.ClassFile.ACC_PUBLIC)
                .withMethodBody("<init>",
                        java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void, absent),
                        java.lang.classfile.ClassFile.ACC_PUBLIC,
                        code -> code.aload(0)
                                .invokespecial(java.lang.constant.ConstantDescs.CD_Object, "<init>",
                                        java.lang.constant.ConstantDescs.MTD_void)
                                .return_()));
        var loader = new ClassLoader(BeanDiscoveryReflectionFallbackTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String className) throws ClassNotFoundException {
                if (!className.equals(name)) throw new ClassNotFoundException(className);
                return defineClass(className, bytes, 0, bytes.length);
            }
        };
        return loader.loadClass(name);
    }

    /** An interface of a library, which the deployment's index does not describe, and the one it extends. */
    public interface LibraryBase {
    }

    public interface LibrarySub extends LibraryBase {
    }

    public static class LibraryBean implements LibrarySub {
    }

    @Test
    @DisplayName("the bean types follow the supertypes the index does not describe, by reflection")
    void beanTypesFollowUnindexedSupertypes() {
        var bean = scan(LibraryBean.class);
        var discovery = discovery(bean);

        var types = discovery.computeBeanTypes(bean);

        assertTrue(types.contains(cls(LibrarySub.class.getName())), types::toString);
        assertTrue(types.contains(cls(LibraryBase.class.getName())),
                "a bean is also of the types the interface it implements extends: " + types);
    }

    /**
     * How Vauban's annotation processor describes {@link #COMPILED_SUPERCLASS}, a class its compilation compiles: an
     * abstract class that implements {@code Serializable}. It describes no other class here.
     */
    private static Optional<ClassInfo> describedByTheCompilation(DotName name) {
        return name.value().equals(COMPILED_SUPERCLASS)
                ? Optional.of(new ClassInfo(name, DotName.of(OBJECT), List.of(DotName.of(SERIALIZABLE)), 0x0401,
                        List.of(), List.of(), List.of(), ClassInfo.ClassKind.CLASS))
                : Optional.empty();
    }

    /**
     * The processor's class loader does not load a class the compilation compiles, and the index holds no class without
     * an annotation: the bean types stopped there, where the container, which loads it at start, reads on
     * (BUG-20261117-03). The walk reads such a class as the processor describes it; unbound, as at start, it loads it.
     */
    @Test
    @DisplayName("the bean types follow a supertype nothing loads as the processor describes it")
    void beanTypesFollowTheSupertypesTheProcessorDescribes() {
        var bean = new ClassInfo(DotName.of(MISSING_CLASS), DotName.of(COMPILED_SUPERCLASS), List.of(), 0x0001,
                List.of(), List.of(constructor()), List.of(ann(DEPENDENT)), ClassInfo.ClassKind.CLASS);
        var discovery = discovery(bean);

        var unbound = discovery.computeBeanTypes(bean);
        var described = IndexLookup.whereUnindexedDescribedBy(BeanDiscoveryReflectionFallbackTest::describedByTheCompilation,
                () -> discovery.computeBeanTypes(bean));

        assertEquals(Set.of(cls(MISSING_CLASS), cls(COMPILED_SUPERCLASS), cls(OBJECT)), unbound,
                "a supertype that neither loads nor is described ends the walk");
        assertEquals(Set.of(cls(MISSING_CLASS), cls(COMPILED_SUPERCLASS), cls(SERIALIZABLE), cls(OBJECT)), described);
    }

    @Test
    @DisplayName("the types of a producer of a class nothing loads follow its supertypes as the processor describes them")
    void producerTypesFollowTheSupertypesTheProcessorDescribes() {
        var discovery = discovery();

        var described = IndexLookup.whereUnindexedDescribedBy(BeanDiscoveryReflectionFallbackTest::describedByTheCompilation,
                () -> discovery.computeProducerTypes(cls(COMPILED_SUPERCLASS)));

        assertEquals(Set.of(cls(COMPILED_SUPERCLASS), cls(SERIALIZABLE), cls(OBJECT)), described);
    }

    private static Optional<List<TypeInfo>> readByTheCompilation(TypeInfo type) {
        return type.equals(cls(MISSING_CLASS)) ? Optional.of(List.of(REPO_OF_STRING)) : Optional.empty();
    }

    /** The processor's reading of {@link LibraryBean}, a class that loads: a {@code NoSuchRepo<String>}. */
    private static Optional<List<TypeInfo>> readOfALoadedClass(TypeInfo type) {
        return type.equals(cls(LibraryBean.class.getName())) ? Optional.of(List.of(REPO_OF_STRING)) : Optional.empty();
    }

    /** A bean of {@link #MISSING_CLASS}, a class nothing loads, that implements {@link #COMPILED_REPO}. */
    private static ClassInfo repositoryBean(List<AnnotationInfo> annotations) {
        return new ClassInfo(DotName.of(MISSING_CLASS), DotName.of(OBJECT), List.of(DotName.of(COMPILED_REPO)), 0x0001,
                List.of(), List.of(constructor()), annotations, ClassInfo.ClassKind.CLASS);
    }

    /**
     * The index names the interfaces of a class raw, and a raw bean type matches every parameterization of it: two
     * repositories of the same generic interface were ambiguous at build, where the container, which reads the type
     * arguments by reflection at start, chose one (BUG-20261117-14).
     */
    @Test
    @DisplayName("the bean types of a class nothing loads carry the type arguments the processor reads")
    void beanTypesCarryTheTypeArgumentsTheProcessorReads() {
        var bean = repositoryBean(List.of(ann(DEPENDENT)));
        var discovery = discovery(bean);

        var unbound = discovery.computeBeanTypes(bean);
        var read = CompiledSupertypes.where(BeanDiscoveryReflectionFallbackTest::readByTheCompilation,
                () -> discovery.computeBeanTypes(bean));

        assertEquals(Set.of(cls(MISSING_CLASS), cls(COMPILED_REPO), cls(OBJECT)), unbound,
                "unbound, as at start, nothing reads the type arguments of a class that does not load");
        assertEquals(Set.of(cls(MISSING_CLASS), REPO_OF_STRING, cls(OBJECT)), read);
    }

    /**
     * While the processor runs, its reading of the compilation comes before reflection: in a build that runs javac in
     * process, {@code vidocq:generate} say, the class loader of the processor can hold another copy of a class the
     * compilation compiles, and reflection read that copy.
     */
    @Test
    @DisplayName("while the processor runs, its reading of the compilation comes before a class that loads")
    void theCompilationsReadingComesBeforeAClassThatLoads() {
        var bean = scan(LibraryBean.class);
        var discovery = discovery(bean);

        var beanTypes = CompiledSupertypes.where(BeanDiscoveryReflectionFallbackTest::readOfALoadedClass,
                () -> discovery.computeBeanTypes(bean));
        var producerTypes = CompiledSupertypes.where(BeanDiscoveryReflectionFallbackTest::readOfALoadedClass,
                () -> discovery.computeProducerTypes(cls(LibraryBean.class.getName())));

        assertTrue(beanTypes.contains(REPO_OF_STRING), beanTypes::toString);
        assertTrue(producerTypes.contains(REPO_OF_STRING), producerTypes::toString);
    }

    @Test
    @DisplayName("@Typed keeps the parameterized type the processor reads of a class nothing loads")
    void typedKeepsTheTypeArgumentsTheProcessorReads() {
        var bean = repositoryBean(List.of(ann(DEPENDENT),
                ann("jakarta.enterprise.inject.Typed", new AnnotationValue.ClassVal(DotName.of(COMPILED_REPO)))));
        var discovery = discovery(bean);

        var read = CompiledSupertypes.where(BeanDiscoveryReflectionFallbackTest::readByTheCompilation,
                () -> discovery.computeBeanTypes(bean));

        assertEquals(Set.of(REPO_OF_STRING, cls(OBJECT)), read);
    }

    @Test
    @DisplayName("the types of a producer of a class nothing loads carry the type arguments the processor reads")
    void producerTypesCarryTheTypeArgumentsTheProcessorReads() {
        var discovery = discovery();

        var read = CompiledSupertypes.where(BeanDiscoveryReflectionFallbackTest::readByTheCompilation,
                () -> discovery.computeProducerTypes(cls(MISSING_CLASS)));

        assertEquals(Set.of(cls(MISSING_CLASS), REPO_OF_STRING, cls(OBJECT)), read);
    }

    @Test
    @DisplayName("a class whose constructor cannot be linked is no bean, and discovery goes on")
    void aClassWithAnUnlinkableConstructorIsSkipped() throws Exception {
        var name = "io.vidocq.vauban.core.bean.discovery.OptionalDependencyUser";
        var unlinkable = classWithAnUnlinkableConstructor(name);
        var previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(unlinkable.getClassLoader());
        try {
            var names = beanClassNames(discovery(classInfo(name, List.of(), List.of(), List.of()),
                    scan(InheritingBean.class)).discoverBeans());

            assertEquals(List.of(InheritingBean.class.getName()), names);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("a producer's @Priority missing from the index is read on the method or field by reflection")
    void producerPriorityByReflection() {
        var host = classInfo(ProducerHost.class.getName(),
                List.of(producerField("level", "java.lang.Integer"), producerField("plain", "java.lang.Long"),
                        producerField("ghostField", "java.lang.Short")),
                List.of(constructor(), producerMethod("rank"), producerMethod("ghost")),
                List.of(ann(DEPENDENT)));

        var beans = discovery(host).discoverBeans();

        assertEquals(7, priorityOf(beans, "rank"));
        assertEquals(9, priorityOf(beans, "level"));
        assertEquals(0, priorityOf(beans, "plain"));
        assertEquals(0, priorityOf(beans, "ghost"));
        assertEquals(0, priorityOf(beans, "ghostField"));
    }

    private static int priorityOf(List<BeanDescriptor> beans, String member) {
        return beans.stream()
                .filter(b -> b.id().value().endsWith("#" + member) || b.id().value().endsWith("." + member))
                .findFirst().orElseThrow().priority();
    }

    @Test
    @DisplayName("the producers of a class that cannot be loaded keep the index's types")
    void producersOfAnUnloadableClass() {
        var host = classInfo(MISSING_CLASS, List.of(producerField("value", "java.lang.Short")),
                List.of(constructor(), producerMethod("make")), List.of(ann(DEPENDENT)));

        var beans = discovery(host).discoverBeans();

        assertEquals(3, beans.size(), beans::toString);
        assertTrue(beans.stream().filter(b -> b.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD)
                .allMatch(b -> b.types().contains(cls("java.lang.Short"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jakarta.enterprise.event.ObservesAsync", "jakarta.enterprise.inject.Disposes"})
    @DisplayName("an initializer method parameter must not observe an event nor be disposed")
    void initializerParameterRestrictions(String annotation) {
        var parameter = new ParameterInfo("event", cls("java.lang.String"), List.of(ann(annotation)));
        var initializer = new MethodInfo("init", new TypeInfo.VoidType(), List.of(parameter), List.of(), 0x0001,
                List.of(ann(INJECT)));
        var bean = classInfo(MISSING_CLASS, List.of(), List.of(constructor(), initializer), List.of(ann(DEPENDENT)));
        var discovery = discovery(bean);

        var thrown = assertThrows(DefinitionException.class, () -> discovery.buildManagedBean(bean));

        assertTrue(thrown.getMessage().contains("must not have @"), thrown.getMessage());
    }

    @Test
    @DisplayName("@Typed with a single class value, with a non-class element, or naming an unloadable bean")
    void typedVariants() {
        var single = classInfo(TypedHost.class.getName(), List.of(), List.of(constructor()),
                List.of(ann("jakarta.enterprise.inject.Typed", new AnnotationValue.ClassVal(DotName.of("java.util.List")))));
        var mixed = classInfo(TypedHost.class.getName(), List.of(), List.of(constructor()),
                List.of(ann("jakarta.enterprise.inject.Typed", new AnnotationValue.ArrayVal(List.of(
                        new AnnotationValue.StringVal("not a class"),
                        new AnnotationValue.ClassVal(DotName.of("java.util.Collection")))))));
        var unloadable = classInfo(MISSING_CLASS, List.of(), List.of(constructor()),
                List.of(ann("jakarta.enterprise.inject.Typed", new AnnotationValue.ClassVal(DotName.of("java.util.List")))));
        var discovery = discovery();

        var singleTypes = discovery.computeBeanTypes(single);
        var mixedTypes = discovery.computeBeanTypes(mixed);
        var unloadableTypes = discovery.computeBeanTypes(unloadable);

        assertTrue(singleTypes.contains(new TypeInfo.ParameterizedType(DotName.of("java.util.List"),
                List.of(cls("java.lang.String")))), singleTypes::toString);
        assertEquals(2, mixedTypes.size(), mixedTypes::toString);
        assertEquals(java.util.Set.of(cls("java.util.List"), cls(OBJECT)), unloadableTypes);
    }

    @Test
    @DisplayName("the producer types of an unloadable class are what the index describes")
    void producerTypesOfUnloadableClasses() {
        var discovery = discovery();

        var plain = discovery.computeProducerTypes(cls(MISSING_CLASS));
        var parameterized = discovery.computeProducerTypes(
                new TypeInfo.ParameterizedType(DotName.of(MISSING_CLASS), List.of(cls("java.lang.String"))));

        assertEquals(java.util.Set.of(cls(MISSING_CLASS), cls(OBJECT)), plain);
        assertEquals(2, parameterized.size(), parameterized::toString);
    }

    @Test
    @DisplayName("a two-dimensional generic array type becomes one array type of two dimensions")
    void genericArrayOfArrays() throws NoSuchFieldException {
        var type = BeanDiscovery.reflectTypeToTypeInfo(ProducerHost.class.getField("grid").getGenericType());

        var array = (TypeInfo.ArrayType) type;
        assertEquals(2, array.dimensions());
        assertEquals(new TypeInfo.ParameterizedType(DotName.of("java.util.List"), List.of(cls("java.lang.String"))),
                array.componentType());
    }

    @Test
    @DisplayName("@Priority without an int value is no priority; @Named with an empty or non-string value takes the default")
    void malformedPriorityAndName() {
        var discovery = discovery();

        assertEquals(0, discovery.extractPriority(List.of(ann("jakarta.annotation.Priority",
                new AnnotationValue.StringVal("high")))));
        assertEquals(5, discovery.extractPriority(List.of(ann(DEPENDENT), ann("jakarta.annotation.Priority",
                new AnnotationValue.IntVal(5)))));
        assertEquals("fallback", discovery.extractName(List.of(ann("jakarta.inject.Named",
                new AnnotationValue.StringVal(""))), "fallback"));
        assertEquals("fallback", discovery.extractName(List.of(ann("jakarta.inject.Named",
                new AnnotationValue.IntVal(3))), "fallback"));
        assertNull(discovery.extractName(List.of(ann(DEPENDENT)), "fallback"));
    }

    @Test
    @DisplayName("a producer's custom stereotype that cannot be loaded gives it no name")
    void unloadableCustomStereotype() {
        var stereotyped = new MethodInfo("make", cls("java.lang.String"), List.of(), List.of(), 0x0001,
                List.of(ann(PRODUCES), ann(MISSING_CLASS)));
        var host = classInfo(MISSING_CLASS + "Host", List.of(), List.of(constructor(), stereotyped),
                List.of(ann(DEPENDENT)));
        var discovery = discovery(host);
        discovery.setCustomStereotypes(java.util.Set.of(DotName.of(MISSING_CLASS)));

        var producer = discovery.discoverBeans().stream()
                .filter(b -> b.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD).findFirst().orElseThrow();

        assertNull(producer.name());
    }

    @Test
    @DisplayName("a scope the index does not describe resolves through the deployment's loader, not vauban-core's")
    void scopeOnlyTheDeploymentLoaderSees() {
        var loader = IsolatedLoader.ofAnnotation("io.vidocq.vauban.core.bean.discovery.isolated.LayerScope",
                NormalScope.class);
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            var scope = discovery().mapScope(DotName.of(loader.name()));

            assertEquals(new ScopeInfo(DotName.of(loader.name()), true), scope);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("a producer's stereotypes make it an alternative, give it a priority, or name it")
    void producerStereotypes() {
        var beans = discovery(scan(StereotypedProducers.class), scan(RankedAlternative.class),
                scan(PlainStereotype.class)).discoverBeans();

        var ranked = producerNamed(beans, "ranked");
        var plain = producerNamed(beans, "plain");
        var named = producerNamed(beans, "named");
        assertTrue(ranked.isAlternative());
        assertEquals(3, ranked.priority());
        assertFalse(plain.isAlternative());
        assertEquals(0, plain.priority());
        assertNull(plain.name());
        assertEquals("named", named.name());
    }

    private static BeanDescriptor producerNamed(List<BeanDescriptor> beans, String method) {
        return beans.stream().filter(b -> b.id().value().endsWith("#" + method)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("an index entry without methods for a concrete class keeps its implicit constructor")
    void concreteEntryWithoutMethods() {
        var concrete = classInfo(TypedHost.class.getName(), List.of(), List.of(), List.of(ann(DEPENDENT)));

        var names = beanClassNames(discovery(concrete).discoverBeans());

        assertEquals(List.of(TypedHost.class.getName()), names);
    }

    @Test
    @DisplayName("a container annotation on a producer contributes the qualifiers it repeats, and only those")
    void repeatedQualifiers() {
        var container = ann("com.example.Repeated", new AnnotationValue.ArrayVal(List.of(
                new AnnotationValue.AnnotationVal(ann("jakarta.inject.Named", new AnnotationValue.StringVal("first"))),
                new AnnotationValue.AnnotationVal(ann("com.example.NotAQualifier")),
                new AnnotationValue.StringVal("not an annotation"))));
        var producer = new MethodInfo("make", cls("java.lang.String"), List.of(), List.of(), 0x0001,
                List.of(ann(PRODUCES), container));
        var host = classInfo(MISSING_CLASS, List.of(), List.of(constructor(), producer), List.of(ann(DEPENDENT)));

        var made = discovery(host).discoverBeans().stream()
                .filter(b -> b.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD).findFirst().orElseThrow();

        var names = made.qualifiers().stream().map(q -> q.annotationName().value()).toList();
        assertTrue(names.contains("jakarta.inject.Named"), names::toString);
        assertFalse(names.contains("com.example.NotAQualifier"), names::toString);
    }

    @Test
    @DisplayName("a class the thread context class loader cannot see is read through the discovery's own loader")
    void contextLoaderBlind() {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        var discovery = discovery(scan(InheritingBean.class));
        thread.setContextClassLoader(new ClassLoader(null) { });
        try {
            var bean = discovery.buildManagedBean(scan(InheritingBean.class));

            assertEquals(InheritingBean.class.getName(), bean.beanClass().value());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
