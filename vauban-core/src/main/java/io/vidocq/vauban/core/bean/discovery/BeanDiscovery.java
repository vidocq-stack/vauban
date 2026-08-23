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
    static final String JAVA_LANG_OBJECT = "java.lang.Object";
    private static final String PARAM_PREFIX = "parameter ";
    static final String MEMBER_VALUE = "value";

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
        // "annotated" mode (default): a class carrying a bean-defining annotation is also a
        // discovery candidate. The scanned-classes filter only governs whether *non-annotated*
        // classes (those a BCE forces in via ScannedClasses.add in a non-bean archive) are scanned;
        // it must never hide a legitimately-annotated bean — including its @Produces members
        // (VAU-DISC-001 — Mansart Data relies on this).
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
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(name.value(), false, cl)
                    : Class.forName(name.value());
            for (var ann : clazz.getAnnotations()) {
                var annName = DotName.of(ann.annotationType().getName());
                if (BEAN_DEFINING_ANNOTATIONS.contains(annName)) return true;
                if (isStereotype(annName)) return true;
                if (mapScope(annName) != null) return true;
                if (annName.equals(ALTERNATIVE) && clazz.isAnnotationPresent(jakarta.annotation.Priority.class)) return true;
            }
            for (var ctor : clazz.getDeclaredConstructors()) {
                if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) return true;
            }
            // Check inherited
            var superClass = clazz.getSuperclass();
            while (superClass != null && superClass != Object.class) {
                for (var ann : superClass.getAnnotations()) {
                    if (ann.annotationType().isAnnotationPresent(java.lang.annotation.Inherited.class)) {
                        var annName = DotName.of(ann.annotationType().getName());
                        if (BEAN_DEFINING_ANNOTATIONS.contains(annName)) return true;
                        if (isStereotype(annName)) return true;
                        if (mapScope(annName) != null) return true;
                    }
                }
                superClass = superClass.getSuperclass();
            }
            return false;
        } catch (ClassNotFoundException e) {
            return false;
        }
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
                var cl = Thread.currentThread().getContextClassLoader();
                var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                        : Class.forName(classInfo.name().value());
                if (clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum() || java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                    return false;
                }
            } catch (ClassNotFoundException e) { /* skip */ }
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
                var cl = Thread.currentThread().getContextClassLoader();
                var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                        : Class.forName(classInfo.name().value());
                for (var ctor : clazz.getDeclaredConstructors()) {
                    if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) return true;
                    if (ctor.getParameterCount() == 0) return true;
                }
            } catch (ClassNotFoundException e) { /* skip */ }
            return true; // implicit no-arg
        }

        return classInfo.methods().stream()
                .anyMatch(m -> m.isConstructor() && m.parameters().isEmpty());
    }

    boolean hasBeanDefiningAnnotation(ClassInfo classInfo) {
        for (var annotation : classInfo.annotations()) {
            if (BEAN_DEFINING_ANNOTATIONS.contains(annotation.name())) return true;
            if (isStereotype(annotation.name())) return true;
            // Custom scope annotation (has @NormalScope or @Scope)
            if (mapScope(annotation.name()) != null) return true;
        }
        // CDI 4.0+: @Alternative with @Priority is a bean-defining combination
        if (classInfo.hasAnnotation(ALTERNATIVE) && classInfo.hasAnnotation(PRIORITY)) return true;
        // Also check @Inject on constructor — @Inject constructor makes it a bean
        if (classInfo.methods().stream().anyMatch(m -> m.isConstructor() && hasAnnotation(m.annotations(), INJECT))) {
            return true;
        }

        // Fallback: check reflection for bean-defining annotations (for classes not fully indexed in tests)
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                    : Class.forName(classInfo.name().value());
            for (var ann : clazz.getAnnotations()) {
                var annName = DotName.of(ann.annotationType().getName());
                if (BEAN_DEFINING_ANNOTATIONS.contains(annName)) return true;
                if (isStereotype(annName)) return true;
                if (mapScope(annName) != null) return true;
                if (annName.equals(ALTERNATIVE) && clazz.isAnnotationPresent(jakarta.annotation.Priority.class)) return true;
            }
            for (var ctor : clazz.getDeclaredConstructors()) {
                if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) return true;
            }
        } catch (ClassNotFoundException e) { /* skip */ }

        // Check @Inherited annotations from superclasses via reflection
        for (var ann : getInheritedAnnotations(classInfo)) {
            var annName = DotName.of(ann.annotationType().getName());
            if (BEAN_DEFINING_ANNOTATIONS.contains(annName)) return true;
            if (isStereotype(annName)) return true;
            if (mapScope(annName) != null) return true;
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
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                    : Class.forName(classInfo.name().value());
            
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
        } catch (Exception e) {
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
        // @Inject constructor — the (ProxyLink) client-proxy entry constructor is never an
        // injection candidate (Vidocq/vauban#24)
        var injectConstructor = classInfo.methods().stream()
                .filter(m -> isInjectionCandidateCtor(m) && hasAnnotation(m.annotations(), INJECT))
                .findFirst();

        // CDI 2.0+: if no @Inject constructor, and exactly one constructor, use it
        if (injectConstructor.isEmpty()) {
            var allConstructors = classInfo.methods().stream()
                    .filter(BeanDiscovery::isInjectionCandidateCtor)
                    .toList();
            if (allConstructors.size() == 1) {
                injectConstructor = Optional.of(allConstructors.get(0));
            }
        }

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

    // isInterceptorBinding is defined below (line ~926)

    private BeanDescriptor buildProducerMethodBean(ClassInfo declaringClass, MethodInfo method) {
        var id = BeanId.ofProducerMethod(declaringClass.name(), method.name());
        
        TypeInfo actualReturnType = method.returnType();
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(declaringClass.name().value(), false, cl)
                    : Class.forName(declaringClass.name().value());
            for (var m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(method.name())) {
                    var reflectType = reflectTypeToTypeInfo(m.getGenericReturnType());
                    if (reflectType != null) {
                        actualReturnType = reflectType;
                    }
                    break;
                }
            }
        } catch (Exception e) { /* fallback to indexer return type */ }

        var types = computeProducerTypesWithTyped(actualReturnType, method.annotations());
        var qualifiers = computeQualifiers(method.annotations());
        var scope = computeScopeWithStereotypes(method.annotations());
        var isAlternative = hasAnnotation(method.annotations(), ALTERNATIVE) ||
                method.annotations().stream().anyMatch(a -> isStereotype(a.name()) &&
                        index.getClassByName(a.name()).map(c -> c.hasAnnotation(ALTERNATIVE)).orElse(false))
                || isAlternativeWithStereotypes(declaringClass);
        var priority = extractPriority(method.annotations());
        // Fallback: check @Priority via reflection on the producer method
        if (priority == 0) {
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var clazz = cl != null ? Class.forName(declaringClass.name().value(), false, cl)
                        : Class.forName(declaringClass.name().value());
                for (var m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(method.name())
                            && m.isAnnotationPresent(jakarta.annotation.Priority.class)) {
                        priority = m.getAnnotation(jakarta.annotation.Priority.class).value();
                        break;
                    }
                }
            } catch (ClassNotFoundException e) { /* skip */ }
        }
        if (priority == 0) {
            for (var ann : method.annotations()) {
                if (isStereotype(ann.name())) {
                    var sc = index.getClassByName(ann.name());
                    if (sc.isPresent()) {
                        priority = extractPriority(sc.get().annotations());
                        if (priority > 0) break;
                    }
                }
            }
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
            var paramQualifiers = computeInjectionPointQualifiers(param.annotations());
            injectionPoints.add(new InjectionPointInfo(
                    param.type(), paramQualifiers, InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
                    PARAM_PREFIX + i + " of " + declaringClass.name().simpleName() + "." + method.name() + "()"
            ));
        }

        return new BeanDescriptor(id, declaringClass.name(), BeanDescriptor.BeanKind.PRODUCER_METHOD,
                types, qualifiers, scope, isAlternative, priority, injectionPoints, name);
    }

    private BeanDescriptor buildProducerFieldBean(ClassInfo declaringClass, FieldInfo field) {
        var id = BeanId.ofProducerField(declaringClass.name(), field.name());
        
        TypeInfo actualType = field.type();
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(declaringClass.name().value(), false, cl)
                    : Class.forName(declaringClass.name().value());
            for (var f : clazz.getDeclaredFields()) {
                if (f.getName().equals(field.name())) {
                    var reflectType = reflectTypeToTypeInfo(f.getGenericType());
                    if (reflectType != null) {
                        actualType = reflectType;
                    }
                    break;
                }
            }
        } catch (Exception e) { /* fallback */ }

        var types = computeProducerTypesWithTyped(actualType, field.annotations());
        var qualifiers = computeQualifiers(field.annotations());
        var scope = computeScopeWithStereotypes(field.annotations());
        var isAlternative = hasAnnotation(field.annotations(), ALTERNATIVE) ||
                field.annotations().stream().anyMatch(a -> isStereotype(a.name()) &&
                        index.getClassByName(a.name()).map(c -> c.hasAnnotation(ALTERNATIVE)).orElse(false))
                || isAlternativeWithStereotypes(declaringClass);
        var priority = extractPriority(field.annotations());
        // Fallback: check @Priority via reflection on the producer field
        if (priority == 0) {
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var clazz = cl != null ? Class.forName(declaringClass.name().value(), false, cl)
                        : Class.forName(declaringClass.name().value());
                var f = clazz.getDeclaredField(field.name());
                if (f.isAnnotationPresent(jakarta.annotation.Priority.class)) {
                    priority = f.getAnnotation(jakarta.annotation.Priority.class).value();
                }
            } catch (Exception e) { /* skip */ }
        }
        // Fallback: stereotypes on field
        if (priority == 0) {
            for (var ann : field.annotations()) {
                if (isStereotype(ann.name())) {
                    var sc = index.getClassByName(ann.name());
                    if (sc.isPresent()) {
                        priority = extractPriority(sc.get().annotations());
                        if (priority > 0) break;
                    }
                }
            }
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
     * Bean types = the class itself + all supertypes + all interfaces + Object.
     */
    Set<TypeInfo> computeBeanTypes(ClassInfo classInfo) {
        // CDI spec: @Typed restricts the bean types to the specified types + Object
        var typedAnn = classInfo.annotations().stream()
                .filter(a -> a.name().equals(TYPED))
                .findFirst();
        if (typedAnn.isPresent()) {
            var restrictedRawTypes = new LinkedHashSet<DotName>();
            var value = typedAnn.get().member(MEMBER_VALUE);
            switch (value) {
                case io.vidocq.vauban.indexer.model.AnnotationValue.ArrayVal av -> {
                    for (var v : av.values()) {
                        if (v instanceof io.vidocq.vauban.indexer.model.AnnotationValue.ClassVal cv) {
                            restrictedRawTypes.add(cv.className());
                        }
                    }
                }
                case io.vidocq.vauban.indexer.model.AnnotationValue.ClassVal cv ->
                        restrictedRawTypes.add(cv.className());
                case null, default -> { }
            }
            // Build restricted types — use parameterized versions when available
            var allParamTypes = new LinkedHashSet<TypeInfo>();
            try {
                for (java.lang.reflect.Type t : io.vidocq.vauban.core.types.TypeHierarchyResolver.resolveAllSupertypes(Class.forName(classInfo.name().value()))) {
                    var typeInfo = reflectTypeToTypeInfo(t);
                    if (typeInfo != null) allParamTypes.add(typeInfo);
                }
            } catch (ClassNotFoundException e) { /* skip */ }
            var restrictedTypes = new LinkedHashSet<TypeInfo>();
            for (var rawName : restrictedRawTypes) {
                boolean found = false;
                for (var t : allParamTypes) {
                    if (t instanceof TypeInfo.ParameterizedType pt && pt.rawType().equals(rawName)) {
                        restrictedTypes.add(pt);
                        found = true;
                        break;
                    }
                }
                if (!found) restrictedTypes.add(new TypeInfo.ClassType(rawName));
            }
            restrictedTypes.add(new TypeInfo.ClassType(DotName.of(JAVA_LANG_OBJECT)));
            return restrictedTypes;
        }
        var types = new LinkedHashSet<TypeInfo>();
        collectBeanTypes(classInfo.name(), types);
        // Enrich with parameterized supertypes via reflection
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                    : Class.forName(classInfo.name().value());
            for (java.lang.reflect.Type t : io.vidocq.vauban.core.types.TypeHierarchyResolver.resolveAllSupertypes(clazz)) {
                var typeInfo = reflectTypeToTypeInfo(t);
                if (typeInfo instanceof TypeInfo.ParameterizedType) {
                    types.add(typeInfo);
                }
            }
        } catch (ClassNotFoundException e) {
            // skip
        }
        
        // Remove raw ClassTypes if a ParameterizedType exists for the same class name
        // (CDI spec: parameterized beans only have their parameterized types in the bean types set, not raw types)
        var rawNamesWithParams = types.stream()
                .filter(t -> t instanceof TypeInfo.ParameterizedType)
                .map(t -> ((TypeInfo.ParameterizedType) t).rawType())
                .collect(java.util.stream.Collectors.toSet());
        types.removeIf(t -> t instanceof TypeInfo.ClassType ct && rawNamesWithParams.contains(ct.name()));
        
        types.add(new TypeInfo.ClassType(DotName.of(JAVA_LANG_OBJECT)));
        return types;
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
                yield componentInfo instanceof TypeInfo.ArrayType at
                        ? new TypeInfo.ArrayType(at.componentType(), at.dimensions() + 1)
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
            var cl = Thread.currentThread().getContextClassLoader();
            java.lang.reflect.Type refType = switch (producerType) {
                case TypeInfo.ClassType ct -> cl != null ? Class.forName(ct.name().value(), false, cl)
                        : Class.forName(ct.name().value());
                case TypeInfo.ParameterizedType pt -> {
                    // If it's parameterized, we still need the raw class to extract the tree
                    Class<?> clazz = cl != null ? Class.forName(pt.rawType().value(), false, cl)
                            : Class.forName(pt.rawType().value());

                    // Reconstruct a simple ParameterizedType to pass to TypeHierarchyResolver
                    java.lang.reflect.Type[] args = new java.lang.reflect.Type[pt.typeArguments().size()];
                    for (int i = 0; i < pt.typeArguments().size(); i++) {
                        TypeInfo argInfo = pt.typeArguments().get(i);
                        if (argInfo instanceof TypeInfo.ClassType act) {
                            args[i] = cl != null ? Class.forName(act.name().value(), false, cl)
                                    : Class.forName(act.name().value());
                        } else {
                            // Fallback: Use Object or raw bounds if it's complex wildcard
                            args[i] = Object.class;
                        }
                    }
                    final Class<?> finalClazz = clazz;
                    yield new java.lang.reflect.ParameterizedType() {
                        @Override public java.lang.reflect.Type[] getActualTypeArguments() { return args; }
                        @Override public java.lang.reflect.Type getRawType() { return finalClazz; }
                        @Override public java.lang.reflect.Type getOwnerType() { return null; }
                    };
                }
                default -> null;
            };
            
            if (refType != null) {
                for (java.lang.reflect.Type t : io.vidocq.vauban.core.types.TypeHierarchyResolver.resolveAllSupertypes(refType)) {
                    var typeInfo = reflectTypeToTypeInfo(t);
                    if (typeInfo != null) types.add(typeInfo);
                }
            }
            
        } catch (Exception e) {
            // fallback
            if (producerType instanceof TypeInfo.ClassType ct) {
                collectBeanTypes(ct.name(), types);
            }
        }
        
        types.add(new TypeInfo.ClassType(DotName.of(JAVA_LANG_OBJECT)));
        return types;
    }

    Set<TypeInfo> computeProducerTypesWithTyped(TypeInfo producerType, List<AnnotationInfo> annotations) {
        var typedAnn = annotations.stream()
                .filter(a -> a.name().equals(TYPED))
                .findFirst();
        if (typedAnn.isPresent()) {
            var restrictedTypes = new LinkedHashSet<TypeInfo>();
            var value = typedAnn.get().member(MEMBER_VALUE);
            switch (value) {
                case io.vidocq.vauban.indexer.model.AnnotationValue.ArrayVal av -> {
                    for (var v : av.values()) {
                        if (v instanceof io.vidocq.vauban.indexer.model.AnnotationValue.ClassVal cv) {
                            restrictedTypes.add(new TypeInfo.ClassType(cv.className()));
                        }
                    }
                }
                case io.vidocq.vauban.indexer.model.AnnotationValue.ClassVal cv ->
                        restrictedTypes.add(new TypeInfo.ClassType(cv.className()));
                case null, default -> { }
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
            var cl = Thread.currentThread().getContextClassLoader();
            var cls = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                    : Class.forName(classInfo.name().value());
            var parent = cls.getSuperclass();
            while (parent != null && parent != Object.class) {
                for (var ann : parent.getDeclaredAnnotations()) {
                    if (isScopeAnnotation(ann.annotationType())) {
                        if (ann.annotationType().isAnnotationPresent(java.lang.annotation.Inherited.class)) {
                            var inheritedScope = mapScope(DotName.of(ann.annotationType().getName()));
                            if (inheritedScope != null) return inheritedScope;
                        }
                        // Non-@Inherited scope blocks further inheritance
                        return ScopeInfo.DEPENDENT;
                    }
                }
                parent = parent.getSuperclass();
            }
        } catch (ClassNotFoundException e) {
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
            var annType = Class.forName(annotationName.value());
            if (annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)) {
                return new ScopeInfo(annotationName, true);
            }
            if (annType.isAnnotationPresent(jakarta.inject.Scope.class)) {
                return new ScopeInfo(annotationName, false);
            }
        } catch (ClassNotFoundException e) {
            // skip
        }
        return null;
    }

    private TypeInfo resolveGenericTypeForField(ClassInfo classInfo, FieldInfo field) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                    : Class.forName(classInfo.name().value());
            var reflField = clazz.getDeclaredField(field.name());
            var reflType = reflField.getGenericType();
            var typeInfo = reflectTypeToTypeInfo(reflType);
            if (typeInfo != null) return typeInfo;
        } catch (Exception e) {
            // fallback
        }
        return field.type();
    }

    private TypeInfo resolveGenericTypeForMethodParameter(ClassInfo classInfo, MethodInfo method, int paramIndex) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                    : Class.forName(classInfo.name().value());
            if (method.isConstructor()) {
                for (var ctor : clazz.getDeclaredConstructors()) {
                    // The (ProxyLink) client-proxy entry constructor (manual or woven) has the
                    // same arity as many business constructors — matching it here would swap
                    // the injection-point type for ProxyLink (Vidocq/vauban#24).
                    if (io.vidocq.vauban.core.proxy.ClientProxyShape.isProxyLinkConstructor(ctor)) continue;
                    if (ctor.getParameterCount() == method.parameters().size()) {
                        var reflType = ctor.getGenericParameterTypes()[paramIndex];
                        var typeInfo = reflectTypeToTypeInfo(reflType);
                        if (typeInfo != null) return typeInfo;
                    }
                }
            } else {
                for (var m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(method.name()) && m.getParameterCount() == method.parameters().size()) {
                        var reflType = m.getGenericParameterTypes()[paramIndex];
                        var typeInfo = reflectTypeToTypeInfo(reflType);
                        if (typeInfo != null) return typeInfo;
                    }
                }
            }
        } catch (Exception e) {
            // fallback
        }
        return method.parameters().get(paramIndex).type();
    }

    List<InjectionPointInfo> discoverInjectionPoints(ClassInfo classInfo) {
        var points = new ArrayList<InjectionPointInfo>();

        // @Inject constructor parameters — the (ProxyLink) client-proxy entry constructor
        // is never an injection candidate (Vidocq/vauban#24)
        var injectConstructor = classInfo.methods().stream()
                .filter(m -> isInjectionCandidateCtor(m) && hasAnnotation(m.annotations(), INJECT))
                .findFirst();

        // CDI 2.0+: if no @Inject constructor, and exactly one constructor, use it
        if (injectConstructor.isEmpty()) {
            var allConstructors = classInfo.methods().stream()
                    .filter(BeanDiscovery::isInjectionCandidateCtor)
                    .toList();
            if (allConstructors.size() == 1) {
                injectConstructor = Optional.of(allConstructors.get(0));
            }
        }


        if (injectConstructor.isPresent()) {
            var ctor = injectConstructor.get();
            for (int i = 0; i < ctor.parameters().size(); i++) {
                var param = ctor.parameters().get(i);
                var resolvedType = resolveGenericTypeForMethodParameter(classInfo, ctor, i);
                points.add(new InjectionPointInfo(
                        resolvedType, computeInjectionPointQualifiers(param.annotations()),
                        InjectionPointInfo.InjectionKind.CONSTRUCTOR_PARAMETER,
                        PARAM_PREFIX + i + " of " + classInfo.name().simpleName() + "()"
                ));
            }
        }

        // @Inject fields
        for (var field : classInfo.fields()) {
            if (hasAnnotation(field.annotations(), INJECT)) {
                var qualifiers = computeInjectionPointQualifiers(field.annotations());
                // CDI spec: @Named without value on injection point defaults to the field name
                qualifiers = qualifierResolver.resolveNamedDefault(qualifiers, field.name());
                var resolvedType = resolveGenericTypeForField(classInfo, field);
                points.add(new InjectionPointInfo(
                        resolvedType, qualifiers,
                        InjectionPointInfo.InjectionKind.FIELD,
                        "field " + classInfo.name().simpleName() + "." + field.name()
                ));
            }
        }

        // @Inject initializer methods
        for (var method : classInfo.methods()) {
            if (!method.isConstructor() && !method.isStatic() && hasAnnotation(method.annotations(), INJECT)) {
                // CDI spec: initializer method parameters must not have @Observes, @ObservesAsync, or @Disposes
                for (int i = 0; i < method.parameters().size(); i++) {
                    var param = method.parameters().get(i);
                    for (var ann : param.annotations()) {
                        var annName = ann.name().value();
                        if (annName.equals("jakarta.enterprise.event.Observes")
                                || annName.equals("jakarta.enterprise.event.ObservesAsync")
                                || annName.equals("jakarta.enterprise.inject.Disposes")) {
                            throw new jakarta.enterprise.inject.spi.DefinitionException(
                                    "Initializer method " + classInfo.name().simpleName() + "." + method.name()
                                            + "() parameter " + i + " must not have @" + ann.name().simpleName());
                        }
                    }
                    var resolvedType = resolveGenericTypeForMethodParameter(classInfo, method, i);
                    points.add(new InjectionPointInfo(
                            resolvedType, computeInjectionPointQualifiers(param.annotations()),
                            InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
                            PARAM_PREFIX + i + " of " + classInfo.name().simpleName() + "." + method.name() + "()"
                    ));
                }
            }
        }

        // Observer method non-event parameters are injection points too
        // (CDI 4.1 §10.4.3): they must be visible to deployment validation and
        // to build compatible extensions (BeanInfo.injectionPoints()) — MP
        // Config validates @ConfigProperty observer parameters there and
        // synthesizes the beans that satisfy them. EventMetadata is
        // container-provided, never resolved from beans.
        for (var method : classInfo.methods()) {
            if (method.isConstructor() || method.isStatic()) continue;
            boolean isObserver = method.parameters().stream().anyMatch(
                    p -> hasObserverAnnotation(p.annotations()));
            if (!isObserver) continue;
            for (int i = 0; i < method.parameters().size(); i++) {
                var param = method.parameters().get(i);
                if (hasObserverAnnotation(param.annotations())) continue;
                var resolvedType = resolveGenericTypeForMethodParameter(classInfo, method, i);
                if (resolvedType instanceof TypeInfo.ClassType ct
                        && ct.name().value().equals("jakarta.enterprise.inject.spi.EventMetadata")) {
                    continue;
                }
                points.add(new InjectionPointInfo(
                        resolvedType, computeInjectionPointQualifiers(param.annotations()),
                        InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
                        PARAM_PREFIX + i + " of " + classInfo.name().simpleName() + "." + method.name() + "()"
                ));
            }
        }

        return points;
    }

    private static boolean hasObserverAnnotation(List<AnnotationInfo> annotations) {
        for (var ann : annotations) {
            var name = ann.name().value();
            if (name.equals("jakarta.enterprise.event.Observes")
                    || name.equals("jakarta.enterprise.event.ObservesAsync")) {
                return true;
            }
        }
        return false;
    }

    int extractPriority(List<AnnotationInfo> annotations) {
        for (var ann : annotations) {
            if (ann.name().equals(PRIORITY)) {
                var value = ann.member(MEMBER_VALUE);
                if (value instanceof AnnotationValue.IntVal iv) return iv.value();
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
                        var annType = Class.forName(ann.name().value());
                        if (annType.isAnnotationPresent(jakarta.inject.Named.class)) {
                            return defaultName;
                        }
                    } catch (ClassNotFoundException e) { /* skip */ }
                }
            }
        }
        return null;
    }

    String extractName(List<AnnotationInfo> annotations, String defaultName) {
        for (var ann : annotations) {
            if (ann.name().equals(NAMED)) {
                var value = ann.member(MEMBER_VALUE);
                if (value instanceof AnnotationValue.StringVal sv && !sv.value().isEmpty()) {
                    return sv.value();
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
