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
    }

    public void setCustomQualifiers(Set<DotName> qualifiers) {
        this.customQualifiers = qualifiers;
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
        for (var ctor : clazz.getDeclaredConstructors()) {
            if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) return true;
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
            var packageInfoName = DotName.of(packageName + ".package-info");
            var packageInfo = index.getClassByName(packageInfoName);
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
            } catch (ClassNotFoundException _) { /* skip */ }
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

    private int extractPriorityWithStereotypes(ClassInfo classInfo) {
        return stereotypes.extractPriorityWithStereotypes(classInfo);
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
        var priority = extractPriorityWithStereotypes(classInfo);
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
                types, qualifiers, scope, isAlternative, priority, injectionPoints, name,
                interceptorBindings, constructorBindings, interceptorBindingAnnotations);
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
        int priority = extractPriorityWithStereotypes(classInfo);
        return priority <= 0;
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
        var id = BeanId.ofProducerMethod(declaringClass.name(), method.name());
        var actualReturnType = reflectedProducerMethodType(declaringClass, method);
        var types = computeProducerTypesWithTyped(actualReturnType, method.annotations());
        var qualifiers = computeQualifiers(method.annotations());
        var scope = computeScopeWithStereotypes(method.annotations());
        var isAlternative = isAlternativeProducer(declaringClass, method.annotations());
        var priority = extractPriority(method.annotations());
        // Fallback: check @Priority via reflection on the producer method
        if (priority == 0) {
            priority = reflectedProducerMethodPriority(declaringClass, method);
        }
        if (priority == 0) {
            priority = producerStereotypePriority(method.annotations());
        }
        // Fallback to declaring class priority
        if (priority == 0) {
            priority = extractPriorityWithStereotypes(declaringClass);
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
                types, qualifiers, scope, isAlternative, priority, injectionPoints, name);
    }

    /**
     * The generic return type of the first declared method named like the producer, read from the
     * bean class of the deployment; the index's erased type when the class cannot be read.
     */
    private static TypeInfo reflectedProducerMethodType(ClassInfo declaringClass, MethodInfo method) {
        try {
            var clazz = DeploymentClasses.load(declaringClass.name().value());
            for (var m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(method.name())) {
                    var reflectType = reflectTypeToTypeInfo(m.getGenericReturnType());
                    return reflectType != null ? reflectType : method.returnType();
                }
            }
        } catch (Exception _) { /* fallback to indexer return type */ }
        return method.returnType();
    }

    /** The {@code @Priority} of the first declared method named like the producer, or 0. */
    private static int reflectedProducerMethodPriority(ClassInfo declaringClass, MethodInfo method) {
        try {
            var clazz = DeploymentClasses.load(declaringClass.name().value());
            for (var m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(method.name())
                        && m.isAnnotationPresent(jakarta.annotation.Priority.class)) {
                    return m.getAnnotation(jakarta.annotation.Priority.class).value();
                }
            }
        } catch (ClassNotFoundException _) { /* skip */ }
        return 0;
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
     * The {@code @Priority} a producer takes from its indexed stereotypes: the first positive one,
     * otherwise what the last indexed stereotype declares (0 when none does).
     */
    private int producerStereotypePriority(List<AnnotationInfo> annotations) {
        int priority = 0;
        for (var ann : annotations) {
            var stereotype = isStereotype(ann.name())
                    ? index.getClassByName(ann.name()) : Optional.<ClassInfo>empty();
            if (stereotype.isPresent()) {
                priority = extractPriority(stereotype.get().annotations());
                if (priority > 0) return priority;
            }
        }
        return priority;
    }

    private BeanDescriptor buildProducerFieldBean(ClassInfo declaringClass, FieldInfo field) {
        var id = BeanId.ofProducerField(declaringClass.name(), field.name());
        var actualType = reflectedProducerFieldType(declaringClass, field);
        var types = computeProducerTypesWithTyped(actualType, field.annotations());
        var qualifiers = computeQualifiers(field.annotations());
        var scope = computeScopeWithStereotypes(field.annotations());
        var isAlternative = isAlternativeProducer(declaringClass, field.annotations());
        var priority = extractPriority(field.annotations());
        // Fallback: check @Priority via reflection on the producer field
        if (priority == 0) {
            priority = reflectedProducerFieldPriority(declaringClass, field);
        }
        // Fallback: stereotypes on field
        if (priority == 0) {
            priority = producerStereotypePriority(field.annotations());
        }
        // Fallback to declaring class priority
        if (priority == 0) {
            priority = extractPriorityWithStereotypes(declaringClass);
        }
        var name = extractNameWithStereotypesFromAnnotations(field.annotations(), field.name());

        return new BeanDescriptor(id, declaringClass.name(), BeanDescriptor.BeanKind.PRODUCER_FIELD,
                types, qualifiers, scope, isAlternative, priority, List.of(), name);
    }

    /**
     * The generic type of the producer field, read from the bean class of the deployment; the
     * index's erased type when the class cannot be read.
     */
    private static TypeInfo reflectedProducerFieldType(ClassInfo declaringClass, FieldInfo field) {
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

    /** The {@code @Priority} the producer field declares, or 0. */
    private static int reflectedProducerFieldPriority(ClassInfo declaringClass, FieldInfo field) {
        try {
            var f = DeploymentClasses.load(declaringClass.name().value()).getDeclaredField(field.name());
            if (f.isAnnotationPresent(jakarta.annotation.Priority.class)) {
                return f.getAnnotation(jakarta.annotation.Priority.class).value();
            }
        } catch (Exception _) { /* skip */ }
        return 0;
    }

    /**
     * Bean types = the class itself + all supertypes + all interfaces + Object.
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
        collectBeanTypes(classInfo.name(), types);
        // Enrich with parameterized supertypes via reflection
        try {
            var clazz = DeploymentClasses.load(classInfo.name().value());
            for (java.lang.reflect.Type t : io.vidocq.vauban.core.types.TypeHierarchyResolver.resolveAllSupertypes(clazz)) {
                var typeInfo = reflectTypeToTypeInfo(t);
                if (typeInfo instanceof TypeInfo.ParameterizedType) {
                    types.add(typeInfo);
                }
            }
        } catch (ClassNotFoundException _) {
            // The index alone describes the bean types
        }

        // Remove raw ClassTypes if a ParameterizedType exists for the same class name
        // (CDI spec: parameterized beans only have their parameterized types in the bean types set, not raw types)
        var rawNamesWithParams = new java.util.HashSet<DotName>();
        for (var t : types) {
            if (t instanceof TypeInfo.ParameterizedType(var rawType, _)) {
                rawNamesWithParams.add(rawType);
            }
        }
        types.removeIf(t -> t instanceof TypeInfo.ClassType(var name) && rawNamesWithParams.contains(name));

        types.add(new TypeInfo.ClassType(DotName.of(JAVA_LANG_OBJECT)));
        return types;
    }

    /**
     * The bean types {@code @Typed} leaves a managed bean: each named type, in its parameterized form
     * when the class has such a supertype, plus {@code Object}.
     */
    private static Set<TypeInfo> typedBeanTypes(ClassInfo classInfo, List<DotName> restrictedRawTypes) {
        // Build restricted types — use parameterized versions when available
        var allParamTypes = new LinkedHashSet<TypeInfo>();
        try {
            for (java.lang.reflect.Type t : io.vidocq.vauban.core.types.TypeHierarchyResolver.resolveAllSupertypes(DeploymentClasses.load(classInfo.name().value()))) {
                var typeInfo = reflectTypeToTypeInfo(t);
                if (typeInfo != null) allParamTypes.add(typeInfo);
            }
        } catch (ClassNotFoundException _) { /* skip */ }
        var restrictedTypes = new LinkedHashSet<TypeInfo>();
        for (var rawName : restrictedRawTypes) {
            restrictedTypes.add(allParamTypes.stream()
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


    private void collectBeanTypes(DotName className, Set<TypeInfo> types) {
        types.add(new TypeInfo.ClassType(className));
        var classInfo = index.getClassByName(className);
        if (classInfo.isEmpty()) return;
        var info = classInfo.get();

        if (info.superName() != null && !JAVA_LANG_OBJECT.equals(info.superName().value())) {
            collectBeanTypes(info.superName(), types);
        }
        for (var iface : info.interfaces()) {
            collectBeanTypes(iface, types);
        }
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
            case null, default -> null;
        };
    }

    static java.lang.reflect.Type resolveReflectType(java.lang.reflect.Type type, Class<?> concreteClass) {
        // Delegates to ManagedBean — this class used to carry an exact private copy of the
        // substitution machinery (with a hashCode formula that diverged from ManagedBean's)
        return io.vidocq.vauban.core.container.ManagedBean.resolveType(type,
                io.vidocq.vauban.core.container.ManagedBean.buildTypeVariableMapping(concreteClass));
    }

    Set<TypeInfo> computeProducerTypes(TypeInfo producerType) {
        var types = new LinkedHashSet<TypeInfo>();
        types.add(producerType);

        // Use TypeHierarchyResolver to correctly resolve both raw and parameterized supertypes
        try {
            reflectProducerType(producerType).ifPresent(refType -> {
                for (java.lang.reflect.Type t : io.vidocq.vauban.core.types.TypeHierarchyResolver.resolveAllSupertypes(refType)) {
                    var typeInfo = reflectTypeToTypeInfo(t);
                    if (typeInfo != null) types.add(typeInfo);
                }
            });
        } catch (Exception _) {
            // The index alone describes the types of a plain class
            if (producerType instanceof TypeInfo.ClassType(var name)) {
                collectBeanTypes(name, types);
            }
        }

        types.add(new TypeInfo.ClassType(DotName.of(JAVA_LANG_OBJECT)));
        return types;
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

    private static java.lang.reflect.ParameterizedType parameterizedReflectType(DotName rawType,
            List<TypeInfo> typeArguments) throws ClassNotFoundException {
        // If it's parameterized, we still need the raw class to extract the tree
        Class<?> clazz = DeploymentClasses.load(rawType.value());

        // Reconstruct a simple ParameterizedType to pass to TypeHierarchyResolver
        var args = new java.lang.reflect.Type[typeArguments.size()];
        for (int i = 0; i < typeArguments.size(); i++) {
            // Fallback: Object for an argument that is not a plain class, a wildcard for instance
            args[i] = typeArguments.get(i) instanceof TypeInfo.ClassType(var argName)
                    ? DeploymentClasses.load(argName.value())
                    : Object.class;
        }
        return new java.lang.reflect.ParameterizedType() {
            @Override public java.lang.reflect.Type[] getActualTypeArguments() { return args.clone(); }
            @Override public java.lang.reflect.Type getRawType() { return clazz; }
            @Override public java.lang.reflect.Type getOwnerType() { return null; }
        };
    }

    Set<TypeInfo> computeProducerTypesWithTyped(TypeInfo producerType, List<AnnotationInfo> annotations) {
        var typedAnn = annotations.stream()
                .filter(a -> a.name().equals(TYPED))
                .findFirst();
        if (typedAnn.isPresent()) {
            var restrictedTypes = new LinkedHashSet<TypeInfo>();
            for (var className : typedClassNames(typedAnn.get())) {
                restrictedTypes.add(new TypeInfo.ClassType(className));
            }
            restrictedTypes.add(new TypeInfo.ClassType(DotName.of(JAVA_LANG_OBJECT)));
            return restrictedTypes;
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
    private static String erasedName(TypeInfo type) {
        return switch (type) {
            case TypeInfo.ClassType(var name) -> name.value();
            case TypeInfo.ParameterizedType(var raw, var _) -> raw.value();
            case TypeInfo.PrimitiveType(var kind) -> kind.name().toLowerCase(java.util.Locale.ROOT);
            case TypeInfo.ArrayType(var component, var dimensions) -> {
                var element = erasedName(component);
                yield element == null ? null : element + "[]".repeat(dimensions);
            }
            case TypeInfo.TypeVariable(var _, var bounds) ->
                    bounds.isEmpty() ? "java.lang.Object" : erasedName(bounds.getFirst());
            default -> null;
        };
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
    private InjectionPointInfo parameterInjectionPoint(ClassInfo classInfo, MethodInfo method,
            String methodName, int index, TypeInfo requiredType, InjectionPointInfo.InjectionKind kind) {
        var annotations = method.parameters().get(index).annotations();
        return new InjectionPointInfo(
                requiredType, computeInjectionPointQualifiers(annotations),
                declaredInjectionPointQualifiers(annotations),
                kind,
                InjectionPointInfo.parameterDescription(classInfo.name().simpleName(), methodName, index,
                        method.parameters().stream().map(ParameterInfo::type).toList()));
    }

    private static boolean hasObserverAnnotation(List<AnnotationInfo> annotations) {
        for (var ann : annotations) {
            if (ann.name().equals(OBSERVES) || ann.name().equals(OBSERVES_ASYNC)) {
                return true;
            }
        }
        return false;
    }

    int extractPriority(List<AnnotationInfo> annotations) {
        for (var ann : annotations) {
            if (ann.name().equals(PRIORITY)) {
                var value = ann.member(MEMBER_VALUE);
                if (value instanceof AnnotationValue.IntVal(var priority)) return priority;
            }
        }
        return 0;
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
