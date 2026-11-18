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
package io.vidocq.vauban.core.langmodel.declarations;

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.RepeatableAnnotations;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import io.vidocq.vauban.core.langmodel.types.TypeParameterScope;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeAnnotationInfo.Target;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.lang.annotation.Annotation;
import java.lang.classfile.ClassFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.IntStream;

public final class VaubanClassInfo implements jakarta.enterprise.lang.model.declarations.ClassInfo {

    private static final DotName OBJECT = DotName.of("java.lang.Object");
    private static final DotName INHERITED = DotName.of("java.lang.annotation.Inherited");

    private final DotName name;
    private final IndexLookup lookup;
    /**
     * The class as the index, or {@link IndexLookup#declaration}, describes it; for a declaration reached by name, read
     * the first time more than the name is asked (BUG-20261117-31). Written once with an immutable record: a reader
     * that races sees {@code null} and describes the class again, the lookup giving the same description.
     */
    private io.vidocq.vauban.indexer.model.ClassInfo indexClass;

    public VaubanClassInfo(io.vidocq.vauban.indexer.model.ClassInfo indexClass, IndexLookup lookup) {
        this.name = indexClass.name();
        this.indexClass = indexClass;
        this.lookup = lookup;
    }

    private VaubanClassInfo(DotName name, IndexLookup lookup) {
        this.name = name;
        this.lookup = lookup;
    }

    /**
     * The declaration of the class {@code name}, which the index may not hold: a class of a library or of the JDK, or
     * one the deployment did not add, is described as {@link IndexLookup#declaration} gives it, the first time the
     * language model needs more than its name; with no {@code lookup}, it is a stub (BUG-20261117-31).
     */
    public static VaubanClassInfo of(DotName name, IndexLookup lookup) {
        return new VaubanClassInfo(name, lookup);
    }

    private io.vidocq.vauban.indexer.model.ClassInfo indexClass() {
        var described = indexClass;
        if (described == null) {
            described = lookup == null ? IndexLookup.stub(name) : lookup.declaration(name);
            indexClass = described;
        }
        return described;
    }

    @Override
    public String name() {
        return name.value();
    }

    @Override
    public String simpleName() {
        var full = name.simpleName();
        int dollarIdx = full.lastIndexOf('$');
        return dollarIdx >= 0 ? full.substring(dollarIdx + 1) : full;
    }

    @Override
    public jakarta.enterprise.lang.model.declarations.PackageInfo packageInfo() {
        String pkg = name.packageName();
        return pkg.isEmpty() ? null : new VaubanPackageInfo(pkg, lookup);
    }

    /**
     * The type parameters the class declares, read from the index. The index leaves out the implicit
     * {@code java.lang.Object} bound; the lang model gives every type parameter at least one bound, so a type
     * parameter that declares none has {@code java.lang.Object} as its single bound.
     */
    @Override
    public List<TypeVariable> typeParameters() {
        return typeParameterScope().typeParameters(lookup);
    }

    /** The type parameters the class declares, as the index records them. */
    List<io.vidocq.vauban.indexer.model.TypeInfo.TypeVariable> indexTypeParameters() {
        return indexClass().typeParameters();
    }

    /**
     * The type parameters of the class, with the annotations on the types the class declares (BUG-20261117-60): the
     * scope its members' types are read in, within that of the class enclosing an inner member class, whose type
     * variables it sees (BUG-20261118-01).
     */
    public TypeParameterScope typeParameterScope() {
        var described = indexClass();
        return TypeParameterScope.of(described.typeParameters(), described.typeAnnotations(),
                enclosingClass().map(VaubanClassInfo::typeParameterScope).orElse(null));
    }

    /** The class that encloses this inner member class, as the index records it and the language model finds it. */
    Optional<VaubanClassInfo> enclosingClass() {
        var enclosing = indexClass().enclosingClass();
        return enclosing == null || lookup == null ? Optional.empty()
                : lookup.find(enclosing).map(found -> new VaubanClassInfo(found, lookup));
    }

    /**
     * The superclass as the class declares it, with its type arguments: {@code Base<List<String>>}, a class only when
     * the class names it raw (BUG-20261117-22). {@code null} for {@code java.lang.Object}, wherever its description
     * comes from, and for an interface, as the language model asks, though the class file of an interface names
     * {@code java.lang.Object} as its superclass (BUG-20261117-32).
     */
    @Override
    public Type superClass() {
        var described = indexClass();
        var superclass = described.genericSuperclass();
        return superclass == null || hasNoSuperclass(described) ? null
                : TypeMapper.map(superclass, lookup, typeParameterScope().use(Target.SUPERCLASS, 0));
    }

    /**
     * The declaration of the superclass, described as the index would describe it when the index does not hold it:
     * {@code java.lang.Object} for a class that names no other superclass (BUG-20261117-31). {@code null} when nothing
     * describes it, as it was for any superclass outside the index: a consumer keeps its "I cannot see this class"
     * path. {@code null} for {@code java.lang.Object} and for an interface, as {@link #superClass()}.
     */
    @Override
    public jakarta.enterprise.lang.model.declarations.ClassInfo superClassDeclaration() {
        var described = indexClass();
        return described.superName() == null || hasNoSuperclass(described) ? null : found(described.superName());
    }

    private boolean hasNoSuperclass(io.vidocq.vauban.indexer.model.ClassInfo described) {
        return described.isInterface() || OBJECT.equals(name);
    }

    /** The declaration of {@code type} as {@link IndexLookup#find} describes it; {@code null} when nothing does. */
    private VaubanClassInfo found(DotName type) {
        return lookup == null ? null
                : lookup.find(type).map(described -> new VaubanClassInfo(described, lookup)).orElse(null);
    }

    /**
     * The interfaces as the class declares them, in declaration order, with their type arguments:
     * {@code Crud<Note, Long>}, a class only for an interface the class names raw (BUG-20261117-22).
     */
    @Override
    public List<Type> superInterfaces() {
        var interfaces = indexClass().genericInterfaces();
        var scope = typeParameterScope();
        return IntStream.range(0, interfaces.size())
                .mapToObj(i -> TypeMapper.map(interfaces.get(i), lookup, scope.use(Target.INTERFACE, i)))
                .toList();
    }

    /**
     * The declarations of the interfaces, in declaration order, as {@link #superInterfaces()} names them: one the
     * index does not hold is described as the index would describe it (BUG-20261117-31). One nothing describes is
     * left out, as any interface outside the index was.
     */
    @Override
    public List<jakarta.enterprise.lang.model.declarations.ClassInfo> superInterfacesDeclarations() {
        return indexClass().interfaces().stream()
                .<jakarta.enterprise.lang.model.declarations.ClassInfo>map(this::found)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public boolean isPlainClass() {
        return indexClass().kind() == io.vidocq.vauban.indexer.model.ClassInfo.ClassKind.CLASS;
    }

    @Override
    public boolean isInterface() {
        return indexClass().kind() == io.vidocq.vauban.indexer.model.ClassInfo.ClassKind.INTERFACE;
    }

    @Override
    public boolean isEnum() {
        return indexClass().isEnum();
    }

    @Override
    public boolean isAnnotation() {
        return indexClass().isAnnotation();
    }

    @Override
    public boolean isRecord() {
        return indexClass().isRecord();
    }

    @Override
    public boolean isAbstract() {
        return indexClass().isAbstract();
    }

    @Override
    public boolean isFinal() {
        return indexClass().isFinal();
    }

    @Override
    public int modifiers() {
        return indexClass().accessFlags();
    }

    /**
     * Whether the language model shows a member whose class file flags are {@code accessFlags}: not one the compiler
     * emits that the source declares neither explicitly nor implicitly (JLS 13.1), a bridge method, the body of a
     * lambda, an enum's {@code $values()}, an outer instance's {@code this$0}. The compiler's model has none of them,
     * and neither has the processor (BUG-20261117-43). The index keeps them, for the generators that read it.
     */
    private static boolean shown(int accessFlags) {
        return (accessFlags & (ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) == 0;
    }

    private static boolean isShownMethod(io.vidocq.vauban.indexer.model.MethodInfo method) {
        return !method.isConstructor() && !method.isStaticInitializer() && shown(method.accessFlags());
    }

    @Override
    public Collection<jakarta.enterprise.lang.model.declarations.MethodInfo> constructors() {
        return indexClass().methods().stream()
                .filter(m -> m.isConstructor() && shown(m.accessFlags()))
                .map(m -> (jakarta.enterprise.lang.model.declarations.MethodInfo) new VaubanMethodInfo(m, this, lookup))
                .toList();
    }

    /**
     * The methods of this class and of all its supertypes, as the language model defines them: those it declares, those
     * of its superclasses up to and excluding {@code java.lang.Object}, and those of every superinterface, direct or
     * not, each interface once; an interface has no superclass. Methods of the same signature all show, each with the
     * class that declares it. A supertype nothing describes is left out, with what is above it (BUG-20261117-42).
     */
    @Override
    public Collection<jakarta.enterprise.lang.model.declarations.MethodInfo> methods() {
        var result = new ArrayList<jakarta.enterprise.lang.model.declarations.MethodInfo>(declaredMethods());
        supertypes().forEach(type -> result.addAll(type.declaredMethods()));
        return result;
    }

    /** The fields of this class and of all its supertypes, as {@link #methods()} reads them (BUG-20261117-42). */
    @Override
    public Collection<jakarta.enterprise.lang.model.declarations.FieldInfo> fields() {
        var result = new ArrayList<jakarta.enterprise.lang.model.declarations.FieldInfo>(declaredFields());
        supertypes().forEach(type -> result.addAll(type.declaredFields()));
        return result;
    }

    /** The methods this class declares, its constructors and its static initializer aside. */
    private List<jakarta.enterprise.lang.model.declarations.MethodInfo> declaredMethods() {
        return indexClass().methods().stream().filter(VaubanClassInfo::isShownMethod)
                .map(m -> (jakarta.enterprise.lang.model.declarations.MethodInfo) new VaubanMethodInfo(m, this, lookup))
                .toList();
    }

    /** The fields this class declares. */
    private List<jakarta.enterprise.lang.model.declarations.FieldInfo> declaredFields() {
        return indexClass().fields().stream().filter(f -> shown(f.accessFlags()))
                .map(f -> (jakarta.enterprise.lang.model.declarations.FieldInfo) new VaubanFieldInfo(f, this, lookup))
                .toList();
    }

    /**
     * The methods an {@code @Enhancement} extension configures or visits for this class: those it declares, and those
     * of its direct superinterfaces the index holds, as before the language model showed the inherited methods. A
     * {@code MethodConfig} changes the class it configures; a method a superclass declares belongs to that class.
     */
    public List<jakarta.enterprise.lang.model.declarations.MethodInfo> configurableMethods() {
        var result = new ArrayList<>(declaredMethods());
        for (var interfaceName : indexClass().interfaces()) {
            lookup.getClass(interfaceName).ifPresent(indexed -> result.addAll(new VaubanClassInfo(indexed, lookup)
                    .declaredMethods()));
        }
        return result;
    }

    /** The fields an {@code @Enhancement} extension configures or visits for this class: those it declares. */
    public List<jakarta.enterprise.lang.model.declarations.FieldInfo> configurableFields() {
        return declaredFields();
    }

    /**
     * The supertypes whose members this class has: its superclasses up to and excluding {@code java.lang.Object}, then
     * every interface above it or them, in the order met, each once.
     */
    private List<VaubanClassInfo> supertypes() {
        var classes = new ArrayList<VaubanClassInfo>();
        var seen = new HashSet<DotName>();
        seen.add(name);
        var superclass = (VaubanClassInfo) superClassDeclaration();
        while (superclass != null && !OBJECT.equals(superclass.name) && seen.add(superclass.name)) {
            classes.add(superclass);
            superclass = (VaubanClassInfo) superclass.superClassDeclaration();
        }
        var interfaces = new ArrayList<VaubanClassInfo>();
        var pending = new ArrayDeque<VaubanClassInfo>();
        pending.add(this);
        pending.addAll(classes);
        while (!pending.isEmpty()) {
            for (var found : pending.poll().superInterfacesDeclarations()) {
                var type = (VaubanClassInfo) found;
                if (seen.add(type.name)) {
                    interfaces.add(type);
                    pending.add(type);
                }
            }
        }
        classes.addAll(interfaces);
        return classes;
    }

    @Override
    public Collection<jakarta.enterprise.lang.model.declarations.RecordComponentInfo> recordComponents() {
        return List.of();
    }

    // -- AnnotationTarget --

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return present(DotName.of(annotationType.getName())).isPresent();
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().anyMatch(predicate);
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        return present(DotName.of(annotationType.getName())).orElse(null);
    }

    /**
     * The annotations of {@code annotationType} this class declares, directly or in their container; when it declares
     * none, those its superclass has, the type being {@code @Inherited}, as
     * {@code Class.getAnnotationsByType} reads them (BUG-20261117-42).
     */
    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        var declared = RepeatableAnnotations.of(declaredAnnotations(), annotationType);
        var superclass = annotatedSuperclass();
        return declared.isEmpty() && superclass != null && isInherited(DotName.of(annotationType.getName()))
                ? superclass.repeatableAnnotation(annotationType) : declared;
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().filter(predicate).toList();
    }

    /**
     * The annotations present on this class: those it declares, and the {@code @Inherited} ones its superclass has
     * whose type it does not declare, as {@code Class.getAnnotations} reads them; an interface inherits none
     * (BUG-20261117-42).
     */
    @Override
    public Collection<AnnotationInfo> annotations() {
        var result = new ArrayList<>(declaredAnnotations());
        var superclass = annotatedSuperclass();
        if (superclass != null) {
            var declared = new HashSet<DotName>();
            result.forEach(annotation -> declared.add(typeOf(annotation)));
            superclass.annotations().stream()
                    .filter(annotation -> !declared.contains(typeOf(annotation)) && isInherited(typeOf(annotation)))
                    .forEach(result::add);
        }
        return result;
    }

    private List<AnnotationInfo> declaredAnnotations() {
        return indexClass().annotations().stream()
                .map(a -> (AnnotationInfo) new VaubanAnnotationInfo(a, lookup))
                .toList();
    }

    /** The binary name of the type of {@code annotation}, one this class built. */
    private static DotName typeOf(AnnotationInfo annotation) {
        return ((VaubanAnnotationInfo) annotation).indexAnnotation().name();
    }

    /** The annotation of {@code type} present on this class, declared or inherited. */
    private Optional<AnnotationInfo> present(DotName type) {
        var declared = indexClass().annotation(type).map(a -> (AnnotationInfo) new VaubanAnnotationInfo(a, lookup));
        if (declared.isPresent() || !isInherited(type)) {
            return declared;
        }
        var superclass = annotatedSuperclass();
        return superclass == null ? Optional.empty() : superclass.present(type);
    }

    /** The superclass whose {@code @Inherited} annotations this class has: none for an interface. */
    private VaubanClassInfo annotatedSuperclass() {
        return isInterface() ? null : (VaubanClassInfo) superClassDeclaration();
    }

    /** Whether the annotation type {@code type} is {@code @Inherited}; {@code false} when nothing describes it. */
    private boolean isInherited(DotName type) {
        return lookup != null && lookup.find(type).map(described -> described.hasAnnotation(INHERITED)).orElse(false);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VaubanClassInfo other && name().equals(other.name());
    }

    @Override
    public int hashCode() {
        return name().hashCode();
    }

    @Override
    public String toString() {
        return "class " + name();
    }
}
