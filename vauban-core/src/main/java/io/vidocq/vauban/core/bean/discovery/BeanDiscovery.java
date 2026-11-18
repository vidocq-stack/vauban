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

import io.vidocq.vauban.core.bean.model.*;
import io.vidocq.vauban.core.codegen.CompiledSupertypes;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.loading.DeploymentClasses;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.*;

import java.util.*;

/**
 * Main orchestrator for CDI bean discovery.
 * Scans the index and discovers all beans using "annotated" discovery mode (CDI 4.1 default).
 */
@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public final class BeanDiscovery {

    // Known scope annotations
    private static final Set<DotName> SCOPE_ANNOTATIONS = Set.of(
            DotName.of("jakarta.enterprise.context.ApplicationScoped"),
            DotName.of("jakarta.enterprise.context.RequestScoped"),
            DotName.of("jakarta.enterprise.context.Dependent"),
            DotName.of("jakarta.enterprise.context.SessionScoped"),
            DotName.of("jakarta.inject.Singleton")
    );

    // Known bean-defining annotations (scopes + stereotypes + interceptor/decorator)
    private static final Set<DotName> BEAN_DEFINING_ANNOTATIONS;

    static {
        var annotations = new HashSet<>(SCOPE_ANNOTATIONS);
        annotations.add(DotName.of("jakarta.enterprise.inject.Model"));
        annotations.add(DotName.of("jakarta.interceptor.Interceptor"));
        annotations.add(DotName.of("jakarta.decorator.Decorator"));
        BEAN_DEFINING_ANNOTATIONS = Set.copyOf(annotations);
    }

    private static final DotName VETOED = DotName.of("jakarta.enterprise.inject.Vetoed");
    private static final DotName TYPED = DotName.of("jakarta.enterprise.inject.Typed");
    private static final DotName PRODUCES = DotName.of("jakarta.enterprise.inject.Produces");
    private static final DotName INJECT = DotName.of("jakarta.inject.Inject");
    static final DotName ALTERNATIVE = DotName.of("jakarta.enterprise.inject.Alternative");
    private static final DotName PRIORITY = DotName.of("jakarta.annotation.Priority");
    static final DotName NAMED = DotName.of("jakarta.inject.Named");
    static final DotName OBSERVES = DotName.of("jakarta.enterprise.event.Observes");
    static final DotName OBSERVES_ASYNC = DotName.of("jakarta.enterprise.event.ObservesAsync");
    static final DotName INTERCEPTOR = DotName.of("jakarta.interceptor.Interceptor");
    static final DotName AROUND_INVOKE = DotName.of("jakarta.interceptor.AroundInvoke");
    static final DotName INTERCEPTOR_BINDING = DotName.of("jakarta.interceptor.InterceptorBinding");
    static final DotName AROUND_CONSTRUCT = DotName.of("jakarta.interceptor.AroundConstruct");
    static final DotName DISPOSES = DotName.of("jakarta.enterprise.inject.Disposes");
    static final DotName STEREOTYPE = DotName.of("jakarta.enterprise.inject.Stereotype");

    static final String PREFIX_JAVA_ANNOTATION = "java.lang.annotation.";
    static final String PREFIX_JAKARTA_INTERCEPTOR = "jakarta.interceptor.";
    static final String PREFIX_JAKARTA_INJECT = "jakarta.enterprise.inject.";
    static final String PREFIX_JAKARTA_INJECT_SPEC = "jakarta.inject.";
    static final String JAVA_LANG_OBJECT = "java.lang.Object";
    static final String MEMBER_VALUE = "value";

    /**
     * An annotation of the Java language, Jakarta Interceptors, CDI or Jakarta Inject: never a
     * stereotype nor an interceptor binding of an application bean.
     */
    static boolean isPlatformAnnotation(String annotationName) {
        return annotationName.startsWith(PREFIX_JAVA_ANNOTATION)
                || annotationName.startsWith(PREFIX_JAKARTA_INTERCEPTOR)
                || annotationName.startsWith(PREFIX_JAKARTA_INJECT)
                || annotationName.startsWith(PREFIX_JAKARTA_INJECT_SPEC);
    }

    final VaubanIndex index;
    /** The classes of {@link #index}, and what the annotation processor describes beside them. */
    private final IndexLookup lookup;
    Set<DotName> customQualifiers = Set.of();
    Set<DotName> customInterceptorBindings = Set.of();
    Set<DotName> customStereotypes = Set.of();
    Map<DotName, Set<Class<? extends java.lang.annotation.Annotation>>> customStereotypeAnnotations = Map.of();
    private Map<String, Set<String>> customNonbindingMembers = Map.of();

    // Topic-focused collaborators (extracted from this class — it stays the facade)
    private final ObserverDisposerDiscovery observerDisposers = new ObserverDisposerDiscovery(this);
    private final InterceptorDiscovery interceptorDiscovery = new InterceptorDiscovery(this);
    private final StereotypeResolver stereotypes = new StereotypeResolver(this);
    private final QualifierResolver qualifierResolver = new QualifierResolver(this);

    public BeanDiscovery(VaubanIndex index) {
        this.index = Objects.requireNonNull(index);
        this.lookup = new IndexLookup(index);
    }

    public void setCustomQualifiers(Set<DotName> qualifiers) {
        this.customQualifiers = qualifiers;
    }

    /** The qualifiers extensions registered ({@code MetaAnnotations.addQualifier}). */
    public Set<DotName> getCustomQualifiers() {
        return customQualifiers;
    }

    public void setCustomInterceptorBindings(Set<DotName> bindings) {
        this.customInterceptorBindings = bindings;
    }

    public void setCustomStereotypes(Set<DotName> stereotypes) {
        this.customStereotypes = stereotypes;
    }

    public void setCustomStereotypeAnnotations(Map<DotName, Set<Class<? extends java.lang.annotation.Annotation>>> annotations) {
        this.customStereotypeAnnotations = annotations;
    }

    public void setCustomNonbindingMembers(Map<String, Set<String>> nonbindingMembers) {
        this.customNonbindingMembers = nonbindingMembers;
    }

    public Map<String, Set<String>> getCustomNonbindingMembers() {
        return customNonbindingMembers;
    }

    private Set<DotName> scannedClassesFilter = Set.of();
    private Set<DotName> forcedBeanClasses = Set.of();
    private boolean strictScannedDiscovery = false;

    public void setScannedClassesFilter(Set<DotName> filter) {
        this.scannedClassesFilter = filter;
    }

    /**
     * Selects the bean-discovery mode for a non-bean archive whose discovery is restricted by a
     * scanned-classes filter (a BCE used {@code ScannedClasses.add}).
     *
     * <ul>
     *   <li>{@code false} (default, <em>annotated</em> mode): a bean-defining annotation also makes
     *       a class a discovery candidate — so an annotated bean (e.g. a {@code @Produces} bean)
     *       contributed to the deployment but not in the scanned set is still discovered
     *       (VAU-DISC-001 — Mansart Data relies on this).</li>
     *   <li>{@code true} (<em>none</em> / CDI-Lite synthetic mode): only explicitly-contributed
     *       classes (BCE {@code ScannedClasses} + class-path bean-archive scan, i.e.
     *       {@code forcedBeanClasses}) are beans; a bean-defining annotation alone does NOT make an
     *       otherwise-uncontributed class a bean (CDI TCK {@code CustomStereotypeTest}: a non-scanned
     *       {@code @Dependent} class stays undiscovered). The CDI TCK runner sets this for its
     *       {@code withoutBeansXml()} BCE archives.</li>
     * </ul>
     */
    public void setStrictScannedDiscovery(boolean strict) {
        this.strictScannedDiscovery = strict;
    }

    /** Classes added via ScannedClasses that bypass bean-defining annotation check. */
    public void setForcedBeanClasses(Set<DotName> forced) {
        this.forcedBeanClasses = forced;
    }

    public void addForcedBeanClasses(Set<DotName> classes) {
        if (classes.isEmpty()) return;
        var merged = new HashSet<>(this.forcedBeanClasses);
        merged.addAll(classes);
        this.forcedBeanClasses = Set.copyOf(merged);
    }

    private boolean isAllowedByScannedClassesFilter(ClassInfo classInfo) {
        if (scannedClassesFilter.isEmpty() || scannedClassesFilter.contains(classInfo.name())) {
            return true;
        }
        // "none" / CDI-Lite synthetic mode: only explicitly-contributed classes (forcedBeanClasses)
        // are beans — a bean-defining annotation does NOT admit an otherwise-uncontributed class
        // (CustomStereotypeTest: a non-scanned @Dependent class stays undiscovered).
        if (strictScannedDiscovery) {
            return forcedBeanClasses.contains(classInfo.name());
        }
        // In the default "annotated" mode, a class carrying a bean-defining annotation is also a
        // discovery candidate. The scanned-classes filter only decides whether the classes without
        // such an annotation, the ones a build compatible extension adds to a non-bean archive,
        // are scanned. It never hides an annotated bean, nor the producers it declares
        // (VAU-DISC-001, which Mansart Data relies on).
        return hasBeanDefiningAnnotation(classInfo)
                || hasBeanDefiningAnnotationViaReflection(classInfo.name());
    }

    /**
     * Discovers all beans in the index using "annotated" discovery mode (CDI 4.1 default).
     */
    @SuppressWarnings("java:S135")
    public List<BeanDescriptor> discoverBeans() {
        var beans = new ArrayList<BeanDescriptor>();

        for (var classInfo : index.getKnownClasses()) {
            if (!isAllowedByScannedClassesFilter(classInfo)) continue;
            if (isVetoed(classInfo)) continue;
            if (isDisabledAlternative(classInfo)) continue;
            if (!isBeanCandidate(classInfo)) continue;
            if (!forcedBeanClasses.contains(classInfo.name()) && !hasBeanDefiningAnnotation(classInfo)
                    && !hasBeanDefiningAnnotationViaReflection(classInfo.name())) {
                // Potential bean but no annotation in index, check reflection
                continue;
            }

            // Discover managed bean
            beans.add(buildManagedBean(classInfo));

            // Discover producer methods
            for (var method : classInfo.methods()) {
                if (hasAnnotation(method.annotations(), PRODUCES)) {
                    beans.add(buildProducerMethodBean(classInfo, method));
                }
            }

            // Discover producer fields
            for (var field : classInfo.fields()) {
                if (hasAnnotation(field.annotations(), PRODUCES)) {
                    beans.add(buildProducerFieldBean(classInfo, field));
                }
            }
        }

        return List.copyOf(beans);
    }

    private boolean hasBeanDefiningAnnotationViaReflection(DotName name) {
        try {
            var clazz = DeploymentClasses.load(name.value());
            if (declaresBeanDefiningAnnotation(clazz)) return true;
            // Check inherited
            var superClass = clazz.getSuperclass();
            while (superClass != null && superClass != Object.class) {
                if (inheritsBeanDefiningAnnotation(superClass)) return true;
                superClass = superClass.getSuperclass();
            }
            return false;
        } catch (ClassNotFoundException _) {
            return false;
        }
    }

    /**
     * Whether the annotations {@code clazz} carries, or an {@code @Inject} constructor it declares,
     * make it a bean: the reflective twin of the index checks of {@link #hasBeanDefiningAnnotation}.
     */
    private boolean declaresBeanDefiningAnnotation(Class<?> clazz) {
        for (var ann : clazz.getAnnotations()) {
            var annName = DotName.of(ann.annotationType().getName());
            if (isBeanDefiningAnnotationName(annName)) return true;
            if (annName.equals(ALTERNATIVE) && clazz.isAnnotationPresent(jakarta.annotation.Priority.class)) return true;
        }
        try {
            for (var ctor : clazz.getDeclaredConstructors()) {
                if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) return true;
            }
        } catch (LinkageError _) {
            // A constructor takes a type of an optional dependency that is not there (a library's class that
            // names a provider it never needs): such a class cannot be injected, so it is no bean here.
        }
        return false;
    }

    /** Whether a superclass carries an {@code @Inherited} annotation that makes its subclasses beans. */
    private boolean inheritsBeanDefiningAnnotation(Class<?> superClass) {
        for (var ann : superClass.getAnnotations()) {
            if (ann.annotationType().isAnnotationPresent(java.lang.annotation.Inherited.class)
                    && isBeanDefiningAnnotationName(DotName.of(ann.annotationType().getName()))) {
                return true;
            }
        }
        return false;
    }

    /** A built-in bean-defining annotation, a stereotype, or a scope (custom scopes included). */
    private boolean isBeanDefiningAnnotationName(DotName annName) {
        return BEAN_DEFINING_ANNOTATIONS.contains(annName) || isStereotype(annName) || mapScope(annName) != null;
    }

    boolean isVetoed(ClassInfo classInfo) {
        if (classInfo.hasAnnotation(VETOED)) return true;
        // Check package-level @Vetoed via package-info class
        var packageName = classInfo.name().packageName();
        if (!packageName.isEmpty()) {
            var packageInfo = index.getClassByName(PackageInfos.of(packageName));
            if (packageInfo.isPresent() && packageInfo.get().hasAnnotation(VETOED)) return true;
        }
        return false;
    }

    private boolean isBeanCandidate(ClassInfo classInfo) {
        // Must be a concrete class (not abstract, not interface, not enum, not annotation)
        if (classInfo.isInterface()) return false;
        if (classInfo.isAnnotation()) return false;
        if (classInfo.isEnum()) return false;
        if (classInfo.isAbstract()) return false;

        // Check reflection if index is empty (test environment fallback)
        if (classInfo.methods().isEmpty()) {
            try {
                var clazz = DeploymentClasses.load(classInfo.name().value());
                if (clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum() || java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                    return false;
                }
            } catch (ClassNotFoundException _) { /* skip */ }
        }

        // Must have a suitable constructor
        return hasSuitableConstructor(classInfo);
    }

    private boolean hasSuitableConstructor(ClassInfo classInfo) {
        // Has @Inject constructor
        boolean hasInjectConstructor = classInfo.methods().stream()
                .anyMatch(m -> m.isConstructor() && hasAnnotation(m.annotations(), INJECT));
        if (hasInjectConstructor) return true;

        // Has no-arg constructor or no declared constructors (implicit default)
        boolean hasExplicitConstructor = classInfo.methods().stream().anyMatch(MethodInfo::isConstructor);
        if (!hasExplicitConstructor) {
            // Check reflection for classes not in index
            try {
                var clazz = DeploymentClasses.load(classInfo.name().value());
                for (var ctor : clazz.getDeclaredConstructors()) {
                    if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) return true;
                    if (ctor.getParameterCount() == 0) return true;
                }
            } catch (ClassNotFoundException | LinkageError _) { /* skip: an unlinkable class is no bean */ }
            return true; // implicit no-arg
        }

        return classInfo.methods().stream()
                .anyMatch(m -> m.isConstructor() && m.parameters().isEmpty());
    }

    boolean hasBeanDefiningAnnotation(ClassInfo classInfo) {
        for (var annotation : classInfo.annotations()) {
            // Custom scope annotations (with @NormalScope or @Scope) count as well
            if (isBeanDefiningAnnotationName(annotation.name())) return true;
        }
        // CDI 4.0+: @Alternative with @Priority is a bean-defining combination
        if (classInfo.hasAnnotation(ALTERNATIVE) && classInfo.hasAnnotation(PRIORITY)) return true;
        // Also check @Inject on constructor — @Inject constructor makes it a bean
        if (classInfo.methods().stream().anyMatch(m -> m.isConstructor() && hasAnnotation(m.annotations(), INJECT))) {
            return true;
        }

        // Fallback: check reflection for bean-defining annotations (for classes not fully indexed in tests)
        try {
            if (declaresBeanDefiningAnnotation(DeploymentClasses.load(classInfo.name().value()))) return true;
        } catch (ClassNotFoundException _) { /* skip */ }

        // Check @Inherited annotations from superclasses via reflection
        for (var ann : getInheritedAnnotations(classInfo)) {
            if (isBeanDefiningAnnotationName(DotName.of(ann.annotationType().getName()))) return true;
        }
        return false;
    }

    private boolean isAlternativeWithStereotypes(ClassInfo classInfo) {
        return stereotypes.isAlternativeWithStereotypes(classInfo);
    }

    private java.util.OptionalInt declaredPriorityWithStereotypes(ClassInfo classInfo) {
        return stereotypes.declaredPriorityWithStereotypes(classInfo);
    }

    /** Stereotype detection — delegated to {@link StereotypeResolver}. */
    boolean isStereotype(DotName annotationName) {
        return stereotypes.isStereotype(annotationName);
    }

    /**
     * Build a managed bean descriptor for a given class.
     * Public for use by post-Enhancement bean creation (classes promoted to beans via BCE).
     */
    public BeanDescriptor buildManagedBean(ClassInfo classInfo) {
        var id = BeanId.of(classInfo.name());
        var types = computeBeanTypes(classInfo);
        var qualifiers = qualifierResolver.computeQualifiersWithStereotypes(classInfo);
        var scope = computeScope(classInfo);
        var isAlternative = isAlternativeWithStereotypes(classInfo);
        var priority = declaredPriorityWithStereotypes(classInfo);
        var injectionPoints = discoverInjectionPoints(classInfo);
        var name = extractNameWithStereotypes(classInfo);
        var interceptorBindings = extractInterceptorBindings(classInfo);
        var constructorBindings = extractConstructorBindings(classInfo);
        var interceptorBindingAnnotations = extractInterceptorBindingAnnotations(classInfo);

        // CDI spec: @Named without value defaults to the decapitalized class name
        if (name != null) {
            qualifiers = qualifierResolver.resolveNamedDefault(qualifiers, name);
        }

        return new BeanDescriptor(id, classInfo.name(), BeanDescriptor.BeanKind.MANAGED,
                types, qualifiers, scope, isAlternative, priority.orElse(0), priority.isPresent(), injectionPoints,
                name, interceptorBindings, constructorBindings, interceptorBindingAnnotations, Set.of());
    }

    private List<java.lang.annotation.Annotation> extractInterceptorBindingAnnotations(ClassInfo classInfo) {
        var annotations = new java.util.ArrayList<java.lang.annotation.Annotation>();
        try {
            var clazz = DeploymentClasses.load(classInfo.name().value());

            var collected = new java.util.HashSet<Class<? extends java.lang.annotation.Annotation>>();
            collectBindingAnnotationsRecursively(clazz.getAnnotations(), annotations, collected);

            // Bindings from superclasses (inherited bindings)
            var superClass = clazz.getSuperclass();
            while (superClass != null && superClass != Object.class) {
                var superAnns = new java.util.ArrayList<java.lang.annotation.Annotation>();
                collectBindingAnnotationsRecursively(superClass.getAnnotations(), superAnns, new java.util.HashSet<>());
                for (var ann : superAnns) {
                    var type = ann.annotationType();
                    // Only add if inherited and not already present on subclass (overriding)
                    if (type.isAnnotationPresent(java.lang.annotation.Inherited.class)
                            && !collected.contains(type)) {
                        annotations.add(ann);
                        collected.add(type);
                    }
                }
                superClass = superClass.getSuperclass();
            }
        } catch (Exception _) {
            // Fallback: ignore if class not found
        }
        return annotations;
    }

    private void collectBindingAnnotationsRecursively(java.lang.annotation.Annotation[] annotations, 
            List<java.lang.annotation.Annotation> result, 
            Set<Class<? extends java.lang.annotation.Annotation>> visited) {
        for (var ann : annotations) {
            var type = ann.annotationType();
            if (!visited.add(type)) continue;

            if (type.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                result.add(ann);
                collectBindingAnnotationsRecursively(type.getAnnotations(), result, visited);
            } else if (type.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                collectBindingAnnotationsRecursively(type.getAnnotations(), result, visited);
            }
        }
    }

    /**
     * A constructor the container may select for injection — i.e. anything but the
     * {@code (ProxyLink)} client-proxy entry constructor, which exists solely for the
     * generated proxy to chain to (Vidocq/vauban#24).
     */
    private static boolean isInjectionCandidateCtor(MethodInfo method) {
        return method.isConstructor()
                && !io.vidocq.vauban.core.proxy.ClientProxyShape.isProxyLinkConstructor(method);
    }

    private Set<DotName> extractConstructorBindings(ClassInfo classInfo) {
        var bindings = new java.util.LinkedHashSet<DotName>();
        var injectConstructor = injectionConstructor(classInfo);
        if (injectConstructor.isPresent()) {
            for (var ann : injectConstructor.get().annotations()) {
                if (isInterceptorBinding(ann.name())) {
                    bindings.add(ann.name());
                }
            }
        }
        return bindings;
    }

    /**
     * CDI spec: disabled alternative = @Alternative without @Priority (directly or via stereotype).
     */
    boolean isDisabledAlternative(ClassInfo classInfo) {
        boolean isAlt = isAlternativeWithStereotypes(classInfo);
        if (!isAlt) return false;
        return declaredPriorityWithStereotypes(classInfo).isEmpty();
    }

    /**
     * Extract interceptor bindings from a class and its stereotypes.
     * An interceptor binding is an annotation that is itself annotated with @InterceptorBinding.
     */
    private Set<DotName> extractInterceptorBindings(ClassInfo classInfo) {
        var bindings = new java.util.LinkedHashSet<DotName>();
        collectBindingsRecursively(classInfo.annotations(), bindings, new java.util.HashSet<>());

        // Bindings from superclasses (inherited bindings)
        var superClass = classInfo.superName();
        var objectName = DotName.of(JAVA_LANG_OBJECT);
        while (superClass != null && !superClass.equals(objectName)) {
            var superInfo = index.getClassByName(superClass);
            if (superInfo.isPresent()) {
                var superBindings = new java.util.LinkedHashSet<DotName>();
                collectBindingsRecursively(superInfo.get().annotations(), superBindings, new java.util.HashSet<>());
                for (var binding : superBindings) {
                    // Check if the binding annotation itself is @Inherited
                    var bindingClass = index.getClassByName(binding);
                    if (bindingClass.isPresent() && bindingClass.get().hasAnnotation(DotName.of(java.lang.annotation.Inherited.class.getName()))) {
                        bindings.add(binding);
                    }
                }
                superClass = superInfo.get().superName();
            } else {
                break;
            }
        }
        return bindings;
    }

    private void collectBindingsRecursively(List<io.vidocq.vauban.indexer.model.AnnotationInfo> annotations, Set<DotName> bindings, Set<DotName> visited) {
        for (var ann : annotations) {
            if (!visited.add(ann.name())) continue;

            if (isInterceptorBinding(ann.name())) {
                bindings.add(ann.name());
                // Transitively collect meta-bindings
                var bindingClass = index.getClassByName(ann.name());
                if (bindingClass.isPresent()) {
                    collectBindingsRecursively(bindingClass.get().annotations(), bindings, visited);
                }
            } else if (isStereotype(ann.name())) {
                var stereoClass = index.getClassByName(ann.name());
                if (stereoClass.isPresent()) {
                    collectBindingsRecursively(stereoClass.get().annotations(), bindings, visited);
                }
            }
        }
    }

    // isInterceptorBinding is defined further below

    private BeanDescriptor buildProducerMethodBean(ClassInfo declaringClass, MethodInfo method) {
        var id = isOverloadedProducer(declaringClass, method)
                ? BeanId.ofOverloadedProducerMethod(declaringClass.name(), method.name(),
                        method.parameters().stream().map(parameter -> erasedName(parameter.type())).toList())
                : BeanId.ofProducerMethod(declaringClass.name(), method.name());
        var actualReturnType = reflectedProducerMethodType(declaringClass, method);
        var types = computeProducerTypesWithTyped(actualReturnType, method.annotations());
        var qualifiers = computeQualifiers(method.annotations());
        var scope = computeScopeWithStereotypes(method.annotations());
        var isAlternative = isAlternativeProducer(declaringClass, method.annotations());
        var priority = declaredPriority(method.annotations());
        // Fallback: check @Priority via reflection on the producer method
        if (priority.isEmpty()) {
            priority = reflectedProducerMethodPriority(declaringClass, method);
        }
        if (priority.isEmpty()) {
            priority = producerStereotypePriority(method.annotations());
        }
        // Fallback to declaring class priority
        if (priority.isEmpty()) {
            priority = declaredPriorityWithStereotypes(declaringClass);
        }
        var name = extractNameWithStereotypesFromAnnotations(method.annotations(),
                deriveProducerMethodName(method.name()));

        // Producer method parameters are injection points
        var injectionPoints = new ArrayList<InjectionPointInfo>();
        for (int i = 0; i < method.parameters().size(); i++) {
            var param = method.parameters().get(i);
            injectionPoints.add(parameterInjectionPoint(declaringClass, method, method.name(), i, param.type(),
                    InjectionPointInfo.InjectionKind.METHOD_PARAMETER));
        }

        return new BeanDescriptor(id, declaringClass.name(), BeanDescriptor.BeanKind.PRODUCER_METHOD,
                types, qualifiers, scope, isAlternative, priority.orElse(0), priority.isPresent(), injectionPoints,
                name, Set.of(), Set.of(), List.of(), Set.of());
    }

    /**
     * Whether {@code declaringClass} declares another producer method of {@code method}'s name: overloaded producer
     * methods are told apart by their parameter types, in their ids and in their reflective lookups
     * (BUG-20261116-11).
     */
    private static boolean isOverloadedProducer(ClassInfo declaringClass, MethodInfo method) {
        return declaringClass.methods().stream()
                .filter(candidate -> candidate.name().equals(method.name())
                        && hasAnnotation(candidate.annotations(), PRODUCES))
                .count() > 1;
    }

    /** Whether the reflected {@code m} is the producer {@code method}: its name, and its parameters when overloaded. */
    private static boolean isReflectedProducer(java.lang.reflect.Method m, ClassInfo declaringClass,
            MethodInfo method) {
        return m.getName().equals(method.name())
                && (!isOverloadedProducer(declaringClass, method)
                        || sameErasedParameters(m.getParameterTypes(), method));
    }

    /**
     * The generic return type of the producer method, the reflected method {@link #isReflectedProducer} matches, read
     * from the bean class of the deployment; the index's erased type when the class cannot be read.
     */
    private static TypeInfo reflectedProducerMethodType(ClassInfo declaringClass, MethodInfo method) {
        if (declaringClass.genericMembers()) {
            return asReflected(method.returnType());
        }
        try {
            var clazz = DeploymentClasses.load(declaringClass.name().value());
            for (var m : clazz.getDeclaredMethods()) {
                if (isReflectedProducer(m, declaringClass, method)) {
                    var reflectType = reflectTypeToTypeInfo(m.getGenericReturnType());
                    return reflectType != null ? reflectType : method.returnType();
                }
            }
        } catch (Exception _) { /* fallback to indexer return type */ }
        return method.returnType();
    }

    /** The {@code @Priority} of the first declared method named like the producer, empty when none declares one. */
    private static java.util.OptionalInt reflectedProducerMethodPriority(ClassInfo declaringClass, MethodInfo method) {
        try {
            var clazz = DeploymentClasses.load(declaringClass.name().value());
            for (var m : clazz.getDeclaredMethods()) {
                if (isReflectedProducer(m, declaringClass, method)
                        && m.isAnnotationPresent(jakarta.annotation.Priority.class)) {
                    return java.util.OptionalInt.of(m.getAnnotation(jakarta.annotation.Priority.class).value());
                }
            }
        } catch (ClassNotFoundException _) { /* skip */ }
        return java.util.OptionalInt.empty();
    }

    /**
     * A producer is an alternative when it says so, directly or through an indexed stereotype, or
     * when the bean declaring it is one.
     */
    private boolean isAlternativeProducer(ClassInfo declaringClass, List<AnnotationInfo> annotations) {
        return hasAnnotation(annotations, ALTERNATIVE) ||
                annotations.stream().anyMatch(a -> isStereotype(a.name()) &&
                        index.getClassByName(a.name()).map(c -> c.hasAnnotation(ALTERNATIVE)).orElse(false))
                || isAlternativeWithStereotypes(declaringClass);
    }

    /**
     * The {@code @Priority} a producer takes from its indexed stereotypes: the one the first declares, empty when none
     * does.
     */
    private java.util.OptionalInt producerStereotypePriority(List<AnnotationInfo> annotations) {
        for (var ann : annotations) {
            var stereotype = isStereotype(ann.name())
                    ? index.getClassByName(ann.name()) : Optional.<ClassInfo>empty();
            if (stereotype.isPresent()) {
                var priority = declaredPriority(stereotype.get().annotations());
                if (priority.isPresent()) return priority;
            }
        }
        return java.util.OptionalInt.empty();
    }

    private BeanDescriptor buildProducerFieldBean(ClassInfo declaringClass, FieldInfo field) {
        var id = BeanId.ofProducerField(declaringClass.name(), field.name());
        var actualType = reflectedProducerFieldType(declaringClass, field);
        var types = computeProducerTypesWithTyped(actualType, field.annotations());
        var qualifiers = computeQualifiers(field.annotations());
        var scope = computeScopeWithStereotypes(field.annotations());
        var isAlternative = isAlternativeProducer(declaringClass, field.annotations());
        var priority = declaredPriority(field.annotations());
        // Fallback: check @Priority via reflection on the producer field
        if (priority.isEmpty()) {
            priority = reflectedProducerFieldPriority(declaringClass, field);
        }
        // Fallback: stereotypes on field
        if (priority.isEmpty()) {
            priority = producerStereotypePriority(field.annotations());
        }
        // Fallback to declaring class priority
        if (priority.isEmpty()) {
            priority = declaredPriorityWithStereotypes(declaringClass);
        }
        var name = extractNameWithStereotypesFromAnnotations(field.annotations(), field.name());

        return new BeanDescriptor(id, declaringClass.name(), BeanDescriptor.BeanKind.PRODUCER_FIELD,
                types, qualifiers, scope, isAlternative, priority.orElse(0), priority.isPresent(), List.of(),
                name, Set.of(), Set.of(), List.of(), Set.of());
    }

    /**
     * The generic type of the producer field, read from the bean class of the deployment; the
     * index's erased type when the class cannot be read.
     */
    private static TypeInfo reflectedProducerFieldType(ClassInfo declaringClass, FieldInfo field) {
        if (declaringClass.genericMembers()) {
            return asReflected(field.type());
        }
        try {
            var clazz = DeploymentClasses.load(declaringClass.name().value());
            for (var f : clazz.getDeclaredFields()) {
                if (f.getName().equals(field.name())) {
                    var reflectType = reflectTypeToTypeInfo(f.getGenericType());
                    return reflectType != null ? reflectType : field.type();
                }
            }
        } catch (Exception _) { /* fallback */ }
        return field.type();
    }

    /** The {@code @Priority} the producer field declares, empty when it declares none. */
    private static java.util.OptionalInt reflectedProducerFieldPriority(ClassInfo declaringClass, FieldInfo field) {
        try {
            var f = DeploymentClasses.load(declaringClass.name().value()).getDeclaredField(field.name());
            if (f.isAnnotationPresent(jakarta.annotation.Priority.class)) {
                return java.util.OptionalInt.of(f.getAnnotation(jakarta.annotation.Priority.class).value());
            }
        } catch (Exception _) { /* skip */ }
        return java.util.OptionalInt.empty();
    }

    /**
     * Bean types = the class itself + all supertypes + all interfaces + Object, the legal ones only (CDI 4.1, 2.2.1).
     */
    Set<TypeInfo> computeBeanTypes(ClassInfo classInfo) {
        // CDI spec: @Typed restricts the bean types to the specified types + Object
        var typedAnn = classInfo.annotations().stream()
                .filter(a -> a.name().equals(TYPED))
                .findFirst();
        if (typedAnn.isPresent()) {
            return typedBeanTypes(classInfo, typedClassNames(typedAnn.get()));
        }
        var types = new LinkedHashSet<TypeInfo>();
        types.add(beanClassType(classInfo));
        collectBeanTypes(classInfo.name(), types);
        types.addAll(parameterizedSupertypes(classInfo.name()));
        dropRawFormsOfParameterizedTypes(types);
        types.add(new TypeInfo.ClassType(DotName.of(JAVA_LANG_OBJECT)));
        return legalBeanTypes(types, ownTypeVariables(classInfo));
    }

    /**
     * The bean class as a bean type: with its type parameters, {@code Repo<T>} for {@code class Repo<T>}, when it
     * declares any (CDI 4.1, 3.1.2), as {@code Bean.getTypes()} has it, and raw otherwise. The descriptor named a
     * generic class raw, which satisfied an injection point of {@code Repo<String>} only through a rule CDI does not
     * have (BUG-20261117-16). The type parameters come from the index, the class file's {@code Signature} or the
     * compiler's {@code TypeElement}, with their bounds.
     */
    private static TypeInfo beanClassType(ClassInfo classInfo) {
        var parameters = classInfo.typeParameters();
        return parameters.isEmpty() ? new TypeInfo.ClassType(classInfo.name())
                : new TypeInfo.ParameterizedType(classInfo.name(),
                        parameters.stream().map(BeanDiscovery::asReflected).toList());
    }

    /**
     * The parameterized classes and interfaces above {@code className}, its type variables resolved along the way: while
     * Vauban's annotation processor runs, as it reads them from the compilation ({@link CompiledSupertypes}), and
     * otherwise by reflection, as at start. The index names a class's supertypes raw, and a raw bean type matches every
     * parameterization (BUG-20261117-14). Empty when neither knows the class.
     */
    private static List<TypeInfo> parameterizedSupertypes(DotName className) {
        return CompiledSupertypes.of(new TypeInfo.ClassType(className))
                .orElseGet(() -> reflectedParameterizedSupertypes(className));
    }

    private static List<TypeInfo> reflectedParameterizedSupertypes(DotName className) {
        try {
            var parameterized = new ArrayList<TypeInfo>();
            var beanClass = DeploymentClasses.load(className.value());
            for (java.lang.reflect.Type t : io.vidocq.vauban.core.types.TypeHierarchyResolver.resolveAllSupertypes(
                    beanClass)) {
                if (reflectTypeToTypeInfo(t) instanceof TypeInfo.ParameterizedType type) {
                    parameterized.add(supertypeAsJavaSeesIt(t, type, beanClass));
                }
            }
            return parameterized;
        } catch (ClassNotFoundException _) {
            return List.of();
        }
    }

    /**
     * {@code info}, the type {@code reflected} above {@code root} in the index's terms, or its raw class when it is a
     * parameterized type with a type variable of a class above {@code root} among its type arguments: only a
     * supertype {@code root} names raw leaves one there, and Java erases the types above a raw type (JLS 4.8).
     * {@code Legacy<E> extends ArrayList} is a {@code List}, not a {@code List<E>}, whose {@code E} is
     * {@code ArrayList}'s; the legal bean type rule dropped it, and the raw {@code List} with it (BUG-20261117-14).
     */
    private static TypeInfo supertypeAsJavaSeesIt(java.lang.reflect.Type reflected, TypeInfo info, Class<?> root) {
        return info instanceof TypeInfo.ParameterizedType parameterized && hasForeignTypeVariable(reflected, root)
                ? new TypeInfo.ClassType(parameterized.rawType()) : info;
    }

    /**
     * Whether a type argument of {@code type}, at any depth, a wildcard's bounds included, is a type variable a
     * declaration other than {@code root} declares.
     */
    private static boolean hasForeignTypeVariable(java.lang.reflect.Type type, Class<?> root) {
        return switch (type) {
            case java.lang.reflect.TypeVariable<?> variable -> variable.getGenericDeclaration() != root;
            case java.lang.reflect.ParameterizedType parameterized ->
                    anyHasForeignTypeVariable(parameterized.getActualTypeArguments(), root);
            case java.lang.reflect.WildcardType wildcard -> anyHasForeignTypeVariable(wildcard.getUpperBounds(), root)
                    || anyHasForeignTypeVariable(wildcard.getLowerBounds(), root);
            case java.lang.reflect.GenericArrayType array -> hasForeignTypeVariable(array.getGenericComponentType(),
                    root);
            case null, default -> false;
        };
    }

    private static boolean anyHasForeignTypeVariable(java.lang.reflect.Type[] types, Class<?> root) {
        return Arrays.stream(types).anyMatch(type -> hasForeignTypeVariable(type, root));
    }

    /** The names of the type variables {@code classInfo} declares. */
    private static Set<String> ownTypeVariables(ClassInfo classInfo) {
        var names = new HashSet<String>();
        for (var variable : classInfo.typeParameters()) {
            names.add(variable.name());
        }
        return names;
    }

    /**
     * The legal bean types among {@code types} (CDI 4.1, 2.2.1), as {@code ManagedBean.getTypes()} keeps them. A type
     * variable and a wildcard are none. A parameterized type with a type variable {@code variables} does not hold, at
     * any depth, gives its raw class: only a supertype named raw leaves one there, and Java erases the types above a
     * raw type (JLS 4.8). The variables are the bean class's own, or those of the type a producer produces. Such a
     * type was dropped, and its raw class with it (BUG-20261117-14). A parameterized type with a wildcard among its
     * type arguments is no bean type, nor is its raw class: the CDI TCK's {@code BeanTypesWithIllegalTypeTest} wants
     * none.
     */
    private static Set<TypeInfo> legalBeanTypes(Set<TypeInfo> types, Set<String> variables) {
        var legal = new LinkedHashSet<TypeInfo>();
        for (var type : types) {
            switch (type) {
                case TypeInfo.ParameterizedType parameterized when !variables.containsAll(
                        typeVariableNames(parameterized)) -> legal.add(new TypeInfo.ClassType(parameterized.rawType()));
                case TypeInfo.ParameterizedType parameterized when !hasWildcard(parameterized) -> legal.add(type);
                case TypeInfo.ParameterizedType _, TypeInfo.TypeVariable _, TypeInfo.WildcardType _ -> {
                    // Not a legal bean type, and a type with a wildcard does not give its raw class
                }
                default -> legal.add(type);
            }
        }
        return legal;
    }

    /** Whether a type argument of {@code type} is a wildcard, or a parameterized type that has one, at any depth. */
    private static boolean hasWildcard(TypeInfo.ParameterizedType type) {
        return type.typeArguments().stream().anyMatch(argument -> argument instanceof TypeInfo.WildcardType
                || argument instanceof TypeInfo.ParameterizedType nested && hasWildcard(nested));
    }

    /** The names of the type variables {@code type} has, itself or among its type arguments, at any depth. */
    private static Set<String> typeVariableNames(TypeInfo type) {
        var names = new HashSet<String>();
        addTypeVariableNames(type, names);
        return names;
    }

    private static void addTypeVariableNames(TypeInfo type, Set<String> names) {
        switch (type) {
            case TypeInfo.TypeVariable(var name, _) -> names.add(name);
            case TypeInfo.ParameterizedType(_, var arguments) -> arguments.forEach(
                    argument -> addTypeVariableNames(argument, names));
            case TypeInfo.ArrayType(var component, _) -> addTypeVariableNames(component, names);
            case TypeInfo.WildcardType(var upper, var lower) -> {
                addTypeVariableNames(upper, names);
                addTypeVariableNames(lower, names);
            }
            case null, default -> {
                // A class or a primitive type, or no bound: no type variable
            }
        }
    }

    /**
     * Removes the raw form of each class {@code types} also hold parameterized: a bean of a parameterized type has that
     * type, not its raw class (CDI 4.1, 2.2.1).
     */
    private static void dropRawFormsOfParameterizedTypes(Set<TypeInfo> types) {
        var parameterizedNames = new HashSet<DotName>();
        for (var t : types) {
            if (t instanceof TypeInfo.ParameterizedType(var rawType, _)) {
                parameterizedNames.add(rawType);
            }
        }
        types.removeIf(t -> t instanceof TypeInfo.ClassType(var name) && parameterizedNames.contains(name));
    }

    /**
     * The bean types {@code @Typed} leaves a managed bean: each named type, in its parameterized form
     * when the class is generic or has such a supertype, plus {@code Object}, the legal ones only.
     */
    private static Set<TypeInfo> typedBeanTypes(ClassInfo classInfo, List<DotName> restrictedRawTypes) {
        var allParamTypes = new ArrayList<TypeInfo>();
        allParamTypes.add(beanClassType(classInfo));
        allParamTypes.addAll(parameterizedSupertypes(classInfo.name()));
        return legalBeanTypes(typedForms(allParamTypes, restrictedRawTypes), ownTypeVariables(classInfo));
    }

    /**
     * The types a {@code @Typed} that names {@code restrictedRawTypes} leaves among {@code types}, and {@code Object}:
     * each named class in its parameterized form when {@code types} holds one, raw otherwise. A raw form satisfies
     * no injection point of another parameterization than by {@code Object} (BUG-20261117-16).
     */
    private static Set<TypeInfo> typedForms(Collection<TypeInfo> types, List<DotName> restrictedRawTypes) {
        var restrictedTypes = new LinkedHashSet<TypeInfo>();
        for (var rawName : restrictedRawTypes) {
            restrictedTypes.add(types.stream()
                    .filter(t -> t instanceof TypeInfo.ParameterizedType(var rawType, _) && rawType.equals(rawName))
                    .findFirst()
                    .orElseGet(() -> new TypeInfo.ClassType(rawName)));
        }
        restrictedTypes.add(new TypeInfo.ClassType(DotName.of(JAVA_LANG_OBJECT)));
        return restrictedTypes;
    }

    /** The class names a {@code @Typed} annotation lists, in declaration order. */
    private static List<DotName> typedClassNames(AnnotationInfo typed) {
        return switch (typed.member(MEMBER_VALUE)) {
            case AnnotationValue.ArrayVal(var values) -> {
                var names = new ArrayList<DotName>();
                for (var v : values) {
                    if (v instanceof AnnotationValue.ClassVal(var className)) {
                        names.add(className);
                    }
                }
                yield names;
            }
            case AnnotationValue.ClassVal(var className) -> List.of(className);
            case null, default -> List.of(); // no value: @Typed() leaves only Object
        };
    }

    /**
     * {@code className} and the classes and interfaces above it, read as {@link IndexLookup#describe} gives them: the
     * index, and, while Vauban's annotation processor runs the deployment, its description of a class the index does
     * not hold. A superclass the compilation compiles does not load in the processor, and the bean types stopped there
     * (BUG-20261117-03). A class nothing describes, one of the JDK say, is loaded.
     */
    private void collectBeanTypes(DotName className, Set<TypeInfo> types) {
        types.add(new TypeInfo.ClassType(className));
        var classInfo = lookup.describe(className);
        if (classInfo.isEmpty()) {
            collectUnindexedSupertypes(className, types);
            return;
        }
        var info = classInfo.get();

        if (info.superName() != null && !JAVA_LANG_OBJECT.equals(info.superName().value())) {
            collectBeanTypes(info.superName(), types);
        }
        for (var iface : info.interfaces()) {
            collectBeanTypes(iface, types);
        }
    }

    /**
     * The supertypes of a type nothing describes, such as an interface of a Jakarta API
     * ({@code HttpAuthenticationMechanismHandler} extends {@code HttpAuthenticationMechanism}): read by reflection,
     * since a bean is of every type its supertypes make it, indexed or not. A class that cannot be loaded or
     * linked adds nothing but its own name, as before.
     */
    private void collectUnindexedSupertypes(DotName className, Set<TypeInfo> types) {
        Class<?> clazz;
        try {
            clazz = DeploymentClasses.load(className.value());
        } catch (ClassNotFoundException _) {
            return;
        }
        var superClass = clazz.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            collectBeanTypes(DotName.of(superClass.getName()), types);
        }
        for (var iface : clazz.getInterfaces()) {
            collectBeanTypes(DotName.of(iface.getName()), types);
        }
    }

    /**
     * A member type the index read from its {@code Signature} (VAU-PERF-007), in the form
     * {@link #reflectTypeToTypeInfo} gives the same type: a primitive is the class type of its keyword
     * ({@code int}), as {@code Class.getName()} spells it. Everything else is already alike. The member's
     * class then needs no reflection to type its producers and injection points; a class whose index did not
     * read the signatures ({@link ClassInfo#genericMembers()} false) is read by reflection, as before.
     */
    static TypeInfo asReflected(TypeInfo type) {
        return switch (type) {
            case null -> null;
            case TypeInfo.PrimitiveType(var kind) ->
                    new TypeInfo.ClassType(DotName.of(kind.name().toLowerCase(java.util.Locale.ROOT)));
            case TypeInfo.VoidType _ -> new TypeInfo.ClassType(DotName.of("void"));
            case TypeInfo.ArrayType(var component, var dimensions) ->
                    new TypeInfo.ArrayType(asReflected(component), dimensions);
            case TypeInfo.ParameterizedType(var raw, var arguments) ->
                    new TypeInfo.ParameterizedType(raw, arguments.stream().map(BeanDiscovery::asReflected).toList());
            case TypeInfo.TypeVariable(var name, var bounds) ->
                    new TypeInfo.TypeVariable(name, bounds.stream().map(BeanDiscovery::asReflected).toList());
            case TypeInfo.WildcardType(var upper, var lower) ->
                    new TypeInfo.WildcardType(asReflected(upper), asReflected(lower));
            case TypeInfo.ClassType _ -> type;
        };
    }

    /**
     * Convert a Java reflection ParameterizedType to our TypeInfo model.
     */
    static TypeInfo reflectTypeToTypeInfo(java.lang.reflect.Type type) {
        return reflectTypeToTypeInfo(type, new java.util.HashSet<>());
    }

    private static TypeInfo reflectTypeToTypeInfo(java.lang.reflect.Type type, java.util.Set<java.lang.reflect.TypeVariable<?>> visited) {
        return switch (type) {
            case Class<?> c when c.isArray() -> {
                int dimensions = 0;
                Class<?> comp = c;
                while (comp.isArray()) {
                    dimensions++;
                    comp = comp.getComponentType();
                }
                yield new TypeInfo.ArrayType(reflectTypeToTypeInfo(comp, visited), dimensions);
            }
            case Class<?> c -> new TypeInfo.ClassType(DotName.of(c.getName()));
            case java.lang.reflect.GenericArrayType gat -> {
                TypeInfo componentInfo = reflectTypeToTypeInfo(gat.getGenericComponentType(), visited);
                yield componentInfo instanceof TypeInfo.ArrayType(var componentType, var dimensions)
                        ? new TypeInfo.ArrayType(componentType, dimensions + 1)
                        : new TypeInfo.ArrayType(componentInfo, 1);
            }
            case java.lang.reflect.ParameterizedType pt -> {
                var rawType = DotName.of(((Class<?>) pt.getRawType()).getName());
                var args = new java.util.ArrayList<TypeInfo>();
                for (var arg : pt.getActualTypeArguments()) {
                    var argInfo = reflectTypeToTypeInfo(arg, visited);
                    if (argInfo != null) args.add(argInfo);
                }
                yield args.isEmpty()
                        ? new TypeInfo.ClassType(rawType)
                        : new TypeInfo.ParameterizedType(rawType, args);
            }
            case java.lang.reflect.TypeVariable<?> tv -> {
                if (!visited.add(tv)) {
                    yield new TypeInfo.TypeVariable(tv.getName(), java.util.List.of());
                }
                var bounds = new java.util.ArrayList<TypeInfo>();
                for (var bound : tv.getBounds()) {
                    if (bound != Object.class) {
                        var boundInfo = reflectTypeToTypeInfo(bound, visited);
                        if (boundInfo != null) bounds.add(boundInfo);
                    }
                }
                visited.remove(tv);
                yield new TypeInfo.TypeVariable(tv.getName(), bounds);
            }
            case java.lang.reflect.WildcardType wt -> {
                TypeInfo upper = wt.getUpperBounds().length > 0 && wt.getUpperBounds()[0] != Object.class ? reflectTypeToTypeInfo(wt.getUpperBounds()[0], visited) : null;
                TypeInfo lower = wt.getLowerBounds().length > 0 && wt.getLowerBounds()[0] != Object.class ? reflectTypeToTypeInfo(wt.getLowerBounds()[0], visited) : null;
                yield new TypeInfo.WildcardType(upper, lower);
            }
            case IndexedType(var typeInfo) -> typeInfo;
            case null, default -> null;
        };
    }

    static java.lang.reflect.Type resolveReflectType(java.lang.reflect.Type type, Class<?> concreteClass) {
        // Delegates to ManagedBean — this class used to carry an exact private copy of the
        // substitution machinery (with a hashCode formula that diverged from ManagedBean's)
        return io.vidocq.vauban.core.container.ManagedBean.resolveType(type,
                io.vidocq.vauban.core.container.ManagedBean.buildTypeVariableMapping(concreteClass));
    }

    /**
     * The bean types of a producer of {@code producerType}: the type, the classes and interfaces above it, their type
     * variables resolved, and {@code Object}, the legal ones only. While Vauban's annotation processor runs, the
     * classes and interfaces above the type are those the compilation gives: the type, or a type argument, may be a
     * class it compiles, which does not load there (BUG-20261117-14). Otherwise reflection gives them, as at start, and
     * the index where a class does not load.
     */
    Set<TypeInfo> computeProducerTypes(TypeInfo producerType) {
        var types = new LinkedHashSet<TypeInfo>();
        types.add(producerType);

        var compiled = CompiledSupertypes.of(producerType);
        if (compiled.isPresent() || !addReflectedSupertypes(producerType, types)) {
            var rawName = switch (producerType) {
                case TypeInfo.ClassType(var name) -> name;
                case TypeInfo.ParameterizedType(var rawType, _) -> rawType;
                default -> null;
            };
            if (rawName != null) {
                collectBeanTypes(rawName, types);
            }
            types.addAll(compiled.orElse(List.of()));
            dropRawFormsOfParameterizedTypes(types);
        }

        types.add(new TypeInfo.ClassType(DotName.of(JAVA_LANG_OBJECT)));
        return legalBeanTypes(types, typeVariableNames(producerType));
    }

    /**
     * Adds to {@code types} the classes and interfaces reflection finds above {@code producerType}, through
     * {@code TypeHierarchyResolver}; false when a class it names does not load.
     */
    private static boolean addReflectedSupertypes(TypeInfo producerType, Set<TypeInfo> types) {
        try {
            reflectProducerType(producerType).ifPresent(refType -> {
                var root = refType instanceof java.lang.reflect.ParameterizedType parameterized
                        ? parameterized.getRawType() : refType;
                for (java.lang.reflect.Type t : io.vidocq.vauban.core.types.TypeHierarchyResolver.resolveAllSupertypes(refType)) {
                    var typeInfo = supertypeAsJavaSeesIt(t, reflectTypeToTypeInfo(t), (Class<?>) root);
                    if (typeInfo != null) types.add(typeInfo);
                }
            });
            return true;
        } catch (ClassNotFoundException | RuntimeException _) {
            return false;
        }
    }

    /**
     * The reflective type of a producer's type, for {@code TypeHierarchyResolver}: the class itself,
     * or a parameterized type rebuilt over the raw class; empty for any other kind of type.
     */
    private static Optional<java.lang.reflect.Type> reflectProducerType(TypeInfo producerType)
            throws ClassNotFoundException {
        return switch (producerType) {
            case TypeInfo.ClassType(var name) -> Optional.of(DeploymentClasses.load(name.value()));
            case TypeInfo.ParameterizedType(var rawType, var typeArguments) ->
                    Optional.of(parameterizedReflectType(rawType, typeArguments));
            default -> Optional.empty();
        };
    }

    /**
     * {@code rawType} with {@code typeArguments}, for {@code TypeHierarchyResolver}: a class argument as the class it
     * names, and any other, a parameterized type, an array, a wildcard or a type variable, as the {@link IndexedType}
     * that carries it. The resolver puts an argument where the type variable it stands for appears above the raw
     * type, and no type variable of a class above it can occur in the argument, so {@link #reflectTypeToTypeInfo}
     * gives it back as the index wrote it: as in the produced type itself, and as the processor puts it at build. It
     * was {@code Object}, so a producer of {@code Box<List<String>>} had {@code Repo<Object>} at start where the build
     * gives {@code Repo<List<String>>} (BUG-20261117-17).
     */
    private static java.lang.reflect.ParameterizedType parameterizedReflectType(DotName rawType,
            List<TypeInfo> typeArguments) throws ClassNotFoundException {
        Class<?> clazz = DeploymentClasses.load(rawType.value());
        var args = new java.lang.reflect.Type[typeArguments.size()];
        for (int i = 0; i < typeArguments.size(); i++) {
            args[i] = typeArguments.get(i) instanceof TypeInfo.ClassType(var argName)
                    ? DeploymentClasses.load(argName.value())
                    : new IndexedType(typeArguments.get(i));
        }
        return new java.lang.reflect.ParameterizedType() {
            @Override public java.lang.reflect.Type[] getActualTypeArguments() { return args.clone(); }
            @Override public java.lang.reflect.Type getRawType() { return clazz; }
            @Override public java.lang.reflect.Type getOwnerType() { return null; }
        };
    }

    /** A type argument of a produced type that reflection carries through the classes above it without reading it. */
    private record IndexedType(TypeInfo typeInfo) implements java.lang.reflect.Type {
    }

    Set<TypeInfo> computeProducerTypesWithTyped(TypeInfo producerType, List<AnnotationInfo> annotations) {
        var typedAnn = annotations.stream()
                .filter(a -> a.name().equals(TYPED))
                .findFirst();
        if (typedAnn.isPresent()) {
            // The parameterized forms the produced type gives, as Bean.getTypes() has them: Repo<String> for a
            // @Typed(Repo.class) producer of Box<String>, where Box<T> implements Repo<T>. They were raw, and once
            // a raw type satisfied no other parameterization, nothing satisfied @Inject Repo<String> (BUG-20261117-19)
            return typedForms(computeProducerTypes(producerType), typedClassNames(typedAnn.get()));
        }
        return computeProducerTypes(producerType);
    }

    // Qualifier resolution — delegated to QualifierResolver.

    Set<QualifierInstance> computeQualifiers(List<AnnotationInfo> annotations) {
        return qualifierResolver.computeQualifiers(annotations);
    }

    Set<QualifierInstance> computeInjectionPointQualifiers(List<AnnotationInfo> annotations) {
        return qualifierResolver.computeInjectionPointQualifiers(annotations);
    }

    /** What the point's annotations say, before CDI's completion — see {@code InjectionPointInfo}. */
    Set<QualifierInstance> declaredInjectionPointQualifiers(List<AnnotationInfo> annotations) {
        return qualifierResolver.declaredInjectionPointQualifiers(annotations);
    }

    Set<QualifierInstance> computeObserverQualifiers(List<AnnotationInfo> annotations) {
        return qualifierResolver.computeObserverQualifiers(annotations);
    }

    private String extractNameWithStereotypes(ClassInfo classInfo) {
        return stereotypes.extractNameWithStereotypes(classInfo);
    }

    /** Inherited-annotation lookup — delegated to {@link QualifierResolver}. */
    List<java.lang.annotation.Annotation> getInheritedAnnotations(ClassInfo classInfo) {
        return qualifierResolver.getInheritedAnnotations(classInfo);
    }

    boolean isQualifierAnnotation(DotName name) {
        return qualifierResolver.isQualifierAnnotation(name);
    }

    ScopeInfo computeScope(ClassInfo classInfo) {
        // 1. Explicit scope on the bean itself
        var scope = computeScopeFromAnnotations(classInfo.annotations());
        if (!scope.equals(ScopeInfo.DEPENDENT) || hasScopeAnnotation(classInfo.annotations())) {
            return scope;
        }

        // 2. Check @Inherited scope from superclasses, respecting blocking
        // CDI spec: inherited scope takes precedence over stereotype scope
        // CDI spec: intermediate class with any scope annotation blocks further inheritance
        try {
            var parent = DeploymentClasses.load(classInfo.name().value()).getSuperclass();
            while (parent != null && parent != Object.class) {
                var scopeAnnotation = declaredScopeAnnotation(parent);
                if (scopeAnnotation != null) {
                    return inheritedScope(scopeAnnotation);
                }
                parent = parent.getSuperclass();
            }
        } catch (ClassNotFoundException _) {
            // Fallback to old approach
            for (var ann : getInheritedAnnotations(classInfo)) {
                var annName = DotName.of(ann.annotationType().getName());
                var inheritedScope = mapScope(annName);
                if (inheritedScope != null) return inheritedScope;
            }
        }

        // 3. Check stereotypes for scope (direct + transitive)
        var allAnnotationNames = getAllAnnotationNames(classInfo);
        for (var annName : allAnnotationNames) {
            if (isStereotype(annName)) {
                var stereotypeScope = stereotypes.findScopeInStereotypeRecursive(annName, new java.util.HashSet<>());
                if (stereotypeScope != null) return stereotypeScope;
            }
        }

        return ScopeInfo.DEPENDENT;
    }

    /** The type of the first scope annotation {@code type} declares, or {@code null}. */
    private static Class<? extends java.lang.annotation.Annotation> declaredScopeAnnotation(Class<?> type) {
        for (var ann : type.getDeclaredAnnotations()) {
            if (isScopeAnnotation(ann.annotationType())) {
                return ann.annotationType();
            }
        }
        return null;
    }

    /**
     * The scope a subclass inherits from the first scope annotation met up its hierarchy. A scope
     * that is not {@code @Inherited} blocks further inheritance, and the bean stays
     * {@code @Dependent}.
     */
    private ScopeInfo inheritedScope(Class<? extends java.lang.annotation.Annotation> scopeAnnotation) {
        if (scopeAnnotation.isAnnotationPresent(java.lang.annotation.Inherited.class)) {
            var inheritedScope = mapScope(DotName.of(scopeAnnotation.getName()));
            if (inheritedScope != null) return inheritedScope;
        }
        return ScopeInfo.DEPENDENT;
    }

    private static boolean isScopeAnnotation(Class<? extends java.lang.annotation.Annotation> annType) {
        return annType.isAnnotationPresent(jakarta.inject.Scope.class)
                || annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class);
    }

    /**
     * Returns all annotation DotNames on a class: direct + inherited via @Inherited.
     */
    Set<DotName> getAllAnnotationNames(ClassInfo classInfo) {
        var names = new LinkedHashSet<DotName>();
        for (var ann : classInfo.annotations()) {
            names.add(ann.name());
        }
        for (var ann : getInheritedAnnotations(classInfo)) {
            names.add(DotName.of(ann.annotationType().getName()));
        }
        return names;
    }

    private boolean hasScopeAnnotation(List<AnnotationInfo> annotations) {
        return annotations.stream().anyMatch(a -> mapScope(a.name()) != null);
    }

    ScopeInfo computeScopeFromAnnotations(List<AnnotationInfo> annotations) {
        for (var ann : annotations) {
            var scope = mapScope(ann.name());
            if (scope != null) return scope;
        }
        return ScopeInfo.DEPENDENT; // default
    }

    private ScopeInfo computeScopeWithStereotypes(List<AnnotationInfo> annotations) {
        return stereotypes.computeScopeWithStereotypes(annotations);
    }

    ScopeInfo mapScope(DotName annotationName) {
        if (annotationName.equals(ScopeInfo.APPLICATION.annotationName())) return ScopeInfo.APPLICATION;
        if (annotationName.equals(ScopeInfo.REQUEST.annotationName())) return ScopeInfo.REQUEST;
        if (annotationName.equals(ScopeInfo.DEPENDENT.annotationName())) return ScopeInfo.DEPENDENT;
        if (annotationName.equals(ScopeInfo.SINGLETON.annotationName())) return ScopeInfo.SINGLETON;

        // Check if annotation is annotated with @Scope or @NormalScope in the index
        var annClass = index.getClassByName(annotationName);
        if (annClass.isPresent()) {
            if (annClass.get().hasAnnotation(DotName.of("jakarta.enterprise.context.NormalScope"))) {
                return new ScopeInfo(annotationName, true);
            }
            if (annClass.get().hasAnnotation(DotName.of("jakarta.inject.Scope"))) {
                return new ScopeInfo(annotationName, false);
            }
        }
        // Fallback: check via reflection if annotation is not in the index
        try {
            var annType = DeploymentClasses.load(annotationName.value());
            if (annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)) {
                return new ScopeInfo(annotationName, true);
            }
            if (annType.isAnnotationPresent(jakarta.inject.Scope.class)) {
                return new ScopeInfo(annotationName, false);
            }
        } catch (ClassNotFoundException _) {
            // Not a scope this module can see
        }
        return null;
    }

    private TypeInfo resolveGenericTypeForField(ClassInfo classInfo, FieldInfo field) {
        if (classInfo.genericMembers()) {
            return asReflected(field.type());
        }
        try {
            var reflField = DeploymentClasses.load(classInfo.name().value()).getDeclaredField(field.name());
            var reflType = reflField.getGenericType();
            var typeInfo = reflectTypeToTypeInfo(reflType);
            if (typeInfo != null) return typeInfo;
        } catch (Exception _) {
            // fallback
        }
        return field.type();
    }

    private TypeInfo resolveGenericTypeForMethodParameter(ClassInfo classInfo, MethodInfo method, int paramIndex) {
        if (classInfo.genericMembers()) {
            return asReflected(method.parameters().get(paramIndex).type());
        }
        try {
            var clazz = DeploymentClasses.load(classInfo.name().value());
            if (method.isConstructor()) {
                for (var ctor : clazz.getDeclaredConstructors()) {
                    // The (ProxyLink) client-proxy entry constructor (manual or woven) has the
                    // same arity as many business constructors — matching it here would swap
                    // the injection-point type for ProxyLink (Vidocq/vauban#24).
                    if (io.vidocq.vauban.core.proxy.ClientProxyShape.isProxyLinkConstructor(ctor)) continue;
                    // The erased types, not the arity alone: an overload of the same arity declared first would
                    // type the injection points from the wrong constructor (VAU-DISC-003).
                    if (sameErasedParameters(ctor.getParameterTypes(), method)) {
                        var reflType = ctor.getGenericParameterTypes()[paramIndex];
                        var typeInfo = reflectTypeToTypeInfo(reflType);
                        if (typeInfo != null) return typeInfo;
                    }
                }
            } else {
                for (var m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(method.name()) && sameErasedParameters(m.getParameterTypes(), method)) {
                        var reflType = m.getGenericParameterTypes()[paramIndex];
                        var typeInfo = reflectTypeToTypeInfo(reflType);
                        if (typeInfo != null) return typeInfo;
                    }
                }
            }
        } catch (Exception _) {
            // fallback
        }
        return method.parameters().get(paramIndex).type();
    }

    /** Whether {@code reflected} are the erased parameter types of the indexed {@code method}. */
    static boolean sameErasedParameters(Class<?>[] reflected, MethodInfo method) {
        var parameters = method.parameters();
        if (reflected.length != parameters.size()) {
            return false;
        }
        for (int i = 0; i < reflected.length; i++) {
            var erased = erasedName(parameters.get(i).type());
            if (erased != null && !erased.equals(reflected[i].getTypeName())) {
                return false;
            }
        }
        return true;
    }

    /** The erased type name, as {@link Class#getTypeName()} spells it, or {@code null} when it cannot be told. */
    static String erasedName(TypeInfo type) {
        return switch (type) {
            case TypeInfo.ClassType(var name) -> name.value();
            case TypeInfo.ParameterizedType(var raw, _) -> raw.value();
            case TypeInfo.PrimitiveType(var kind) -> kind.name().toLowerCase(java.util.Locale.ROOT);
            case TypeInfo.ArrayType(var component, var dimensions) -> {
                var element = erasedName(component);
                yield element == null ? null : element + "[]".repeat(dimensions);
            }
            case TypeInfo.TypeVariable(_, var bounds) ->
                    bounds.isEmpty() ? JAVA_LANG_OBJECT : erasedName(bounds.getFirst());
            default -> null;
        };
    }

    /**
     * The injection points of the observers of {@code beanClass} its bean's injection points leave out: the
     * parameters of a static observer it declares, and of an observer it inherits, which the start resolves too
     * (BUG-20261118-11).
     *
     * @param outside describes a superclass the index does not hold, or finds none
     */
    public List<InjectionPointInfo> otherObserverInjectionPoints(ClassInfo beanClass,
            java.util.function.Function<DotName, Optional<ClassInfo>> outside) {
        return new ObserverInjectionPoints(this, outside).of(beanClass);
    }

    List<InjectionPointInfo> discoverInjectionPoints(ClassInfo classInfo) {
        var points = new ArrayList<InjectionPointInfo>();
        addConstructorInjectionPoints(classInfo, points);
        addFieldInjectionPoints(classInfo, points);
        addInitializerInjectionPoints(classInfo, points);
        addObserverInjectionPoints(classInfo, points);
        return points;
    }

    /**
     * The constructor the container injects: the {@code @Inject} one, or else the only constructor
     * (CDI 2.0+). The {@code (ProxyLink)} client-proxy entry constructor is never a candidate
     * (Vidocq/vauban#24).
     */
    private static Optional<MethodInfo> injectionConstructor(ClassInfo classInfo) {
        var injectConstructor = classInfo.methods().stream()
                .filter(m -> isInjectionCandidateCtor(m) && hasAnnotation(m.annotations(), INJECT))
                .findFirst();
        if (injectConstructor.isEmpty()) {
            var allConstructors = classInfo.methods().stream()
                    .filter(BeanDiscovery::isInjectionCandidateCtor)
                    .toList();
            if (allConstructors.size() == 1) {
                injectConstructor = Optional.of(allConstructors.get(0));
            }
        }
        return injectConstructor;
    }

    private void addConstructorInjectionPoints(ClassInfo classInfo, List<InjectionPointInfo> points) {
        var injectConstructor = injectionConstructor(classInfo);
        if (injectConstructor.isEmpty()) {
            return;
        }
        var ctor = injectConstructor.get();
        for (int i = 0; i < ctor.parameters().size(); i++) {
            var resolvedType = resolveGenericTypeForMethodParameter(classInfo, ctor, i);
            points.add(parameterInjectionPoint(classInfo, ctor, null, i, resolvedType,
                    InjectionPointInfo.InjectionKind.CONSTRUCTOR_PARAMETER));
        }
    }

    private void addFieldInjectionPoints(ClassInfo classInfo, List<InjectionPointInfo> points) {
        for (var field : classInfo.fields()) {
            if (hasAnnotation(field.annotations(), INJECT)) {
                var qualifiers = computeInjectionPointQualifiers(field.annotations());
                // CDI spec: @Named without value on injection point defaults to the field name
                qualifiers = qualifierResolver.resolveNamedDefault(qualifiers, field.name());
                var declared = qualifierResolver.resolveNamedDefault(
                        declaredInjectionPointQualifiers(field.annotations()), field.name());
                var resolvedType = resolveGenericTypeForField(classInfo, field);
                points.add(new InjectionPointInfo(
                        resolvedType, qualifiers, declared,
                        InjectionPointInfo.InjectionKind.FIELD,
                        InjectionPointInfo.fieldDescription(classInfo.name().simpleName(), field.name())
                ));
            }
        }
    }

    private void addInitializerInjectionPoints(ClassInfo classInfo, List<InjectionPointInfo> points) {
        for (var method : classInfo.methods()) {
            if (!method.isConstructor() && !method.isStatic() && hasAnnotation(method.annotations(), INJECT)) {
                for (int i = 0; i < method.parameters().size(); i++) {
                    rejectEventOrDisposedParameter(classInfo, method, i);
                    var resolvedType = resolveGenericTypeForMethodParameter(classInfo, method, i);
                    points.add(parameterInjectionPoint(classInfo, method, method.name(), i, resolvedType,
                            InjectionPointInfo.InjectionKind.METHOD_PARAMETER));
                }
            }
        }
    }

    /** CDI spec: initializer method parameters must not have @Observes, @ObservesAsync, or @Disposes. */
    private static void rejectEventOrDisposedParameter(ClassInfo classInfo, MethodInfo method, int index) {
        for (var ann : method.parameters().get(index).annotations()) {
            var annName = ann.name();
            if (annName.equals(OBSERVES) || annName.equals(OBSERVES_ASYNC) || annName.equals(DISPOSES)) {
                throw new jakarta.enterprise.inject.spi.DefinitionException(
                        "Initializer method " + classInfo.name().simpleName() + "." + method.name()
                                + "() parameter " + index + " must not have @" + annName.simpleName());
            }
        }
    }

    /**
     * Observer method non-event parameters are injection points too (CDI 4.1 §10.4.3): they must be
     * visible to deployment validation and to build compatible extensions
     * ({@code BeanInfo.injectionPoints()}). MicroProfile Config validates {@code @ConfigProperty}
     * observer parameters there and synthesizes the beans that satisfy them. {@code EventMetadata} is
     * container-provided, never resolved from beans.
     */
    private void addObserverInjectionPoints(ClassInfo classInfo, List<InjectionPointInfo> points) {
        for (var method : classInfo.methods()) {
            if (isInstanceObserverMethod(method)) {
                for (int i = 0; i < method.parameters().size(); i++) {
                    addObserverParameterInjectionPoint(classInfo, method, i, points);
                }
            }
        }
    }

    private static boolean isInstanceObserverMethod(MethodInfo method) {
        return !method.isConstructor() && !method.isStatic()
                && method.parameters().stream().anyMatch(p -> hasObserverAnnotation(p.annotations()));
    }

    private void addObserverParameterInjectionPoint(ClassInfo classInfo, MethodInfo method, int index,
            List<InjectionPointInfo> points) {
        if (hasObserverAnnotation(method.parameters().get(index).annotations())) {
            return;
        }
        var resolvedType = resolveGenericTypeForMethodParameter(classInfo, method, index);
        boolean eventMetadata = resolvedType instanceof TypeInfo.ClassType(var name)
                && name.value().equals("jakarta.enterprise.inject.spi.EventMetadata");
        if (!eventMetadata) {
            points.add(parameterInjectionPoint(classInfo, method, method.name(), index, resolvedType,
                    InjectionPointInfo.InjectionKind.METHOD_PARAMETER));
        }
    }

    /**
     * The injection point a parameter makes, described the way injection finds it again.
     *
     * @param methodName the method's name in the description, {@code null} for a constructor
     */
    InjectionPointInfo parameterInjectionPoint(ClassInfo classInfo, MethodInfo method,
            String methodName, int index, TypeInfo requiredType, InjectionPointInfo.InjectionKind kind) {
        var annotations = method.parameters().get(index).annotations();
        return new InjectionPointInfo(
                requiredType, computeInjectionPointQualifiers(annotations),
                declaredInjectionPointQualifiers(annotations),
                kind,
                InjectionPointInfo.parameterDescription(classInfo.name().simpleName(), methodName, index,
                        method.parameters().stream().map(ParameterInfo::type).toList()));
    }

    static boolean hasObserverAnnotation(List<AnnotationInfo> annotations) {
        for (var ann : annotations) {
            if (ann.name().equals(OBSERVES) || ann.name().equals(OBSERVES_ASYNC)) {
                return true;
            }
        }
        return false;
    }

    int extractPriority(List<AnnotationInfo> annotations) {
        return declaredPriority(annotations).orElse(0);
    }

    /**
     * The priority {@code annotations} declare, empty when they declare none: 0 is a priority like any other, the
     * earliest a library asks for ({@code Interceptor.Priority.PLATFORM_BEFORE}), not the absence of one.
     */
    java.util.OptionalInt declaredPriority(List<AnnotationInfo> annotations) {
        for (var ann : annotations) {
            if (ann.name().equals(PRIORITY)) {
                var value = ann.member(MEMBER_VALUE);
                if (value instanceof AnnotationValue.IntVal(var priority)) return java.util.OptionalInt.of(priority);
            }
        }
        return java.util.OptionalInt.empty();
    }

    /**
     * Extract name from annotations, checking stereotypes for @Named.
     * Used for producer methods and fields.
     */
    private String extractNameWithStereotypesFromAnnotations(List<AnnotationInfo> annotations, String defaultName) {
        var name = extractName(annotations, defaultName);
        if (name != null) return name;
        // Check stereotypes on the producer for @Named
        for (var ann : annotations) {
            if (isStereotype(ann.name())) {
                var stereotypeClass = index.getClassByName(ann.name());
                if (stereotypeClass.isPresent()) {
                    var stereotypeName = extractName(stereotypeClass.get().annotations(), defaultName);
                    if (stereotypeName != null) return stereotypeName;
                } else {
                    try {
                        var annType = DeploymentClasses.load(ann.name().value());
                        if (annType.isAnnotationPresent(jakarta.inject.Named.class)) {
                            return defaultName;
                        }
                    } catch (ClassNotFoundException _) { /* skip */ }
                }
            }
        }
        return null;
    }

    String extractName(List<AnnotationInfo> annotations, String defaultName) {
        for (var ann : annotations) {
            if (ann.name().equals(NAMED)) {
                var value = ann.member(MEMBER_VALUE);
                if (value instanceof AnnotationValue.StringVal(var name) && !name.isEmpty()) {
                    return name;
                }
                return defaultName; // @Named without value uses default name
            }
        }
        return null;
    }

    /**
     * Discovers all observer methods in the index — delegated to {@link ObserverDisposerDiscovery}.
     */
    public List<ObserverDescriptor> discoverObservers() {
        return observerDisposers.discoverObservers();
    }

    /**
     * Discovers all disposer methods in the index — delegated to {@link ObserverDisposerDiscovery}.
     */
    public List<DisposerDescriptor> discoverDisposerMethods() {
        return observerDisposers.discoverDisposerMethods();
    }

    /**
     * Discovers all interceptors in the index — delegated to {@link InterceptorDiscovery}.
     */
    public List<InterceptorDescriptor> discoverInterceptors() {
        return interceptorDiscovery.discoverInterceptors();
    }

    /**
     * Checks if the given annotation name is an interceptor binding — delegated to
     * {@link InterceptorDiscovery}.
     */
    public boolean isInterceptorBinding(DotName annotationName) {
        return interceptorDiscovery.isInterceptorBinding(annotationName);
    }

    static boolean hasAnnotation(List<AnnotationInfo> annotations, DotName name) {
        return annotations.stream().anyMatch(a -> a.name().equals(name));
    }

    /**
     * Derives the default name for a producer method following CDI rules:
     * "getFoo" -> "foo", "isFoo" -> "foo", "produceFoo" -> "produceFoo"
     */
    static String deriveProducerMethodName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3))) {
            return decapitalize(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2 && Character.isUpperCase(methodName.charAt(2))) {
            return decapitalize(methodName.substring(2));
        }
        return methodName;
    }

    /**
     * Decapitalizes a name following JavaBeans rules:
     * "MyService" -> "myService", "URL" -> "URL" (multiple upper case left as-is).
     */
    static String decapitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        // CDI spec: default bean name is the unqualified class name with first char lowercased
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
