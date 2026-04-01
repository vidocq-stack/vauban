package fr.vidocq.vauban.core.bean.discovery;

import fr.vidocq.vauban.core.bean.model.*;
import fr.vidocq.vauban.indexer.VaubanIndex;
import fr.vidocq.vauban.indexer.model.*;

import java.util.*;
import java.util.Comparator;

/**
 * Main orchestrator for CDI bean discovery.
 * Scans the index and discovers all beans using "annotated" discovery mode (CDI 4.1 default).
 */
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
    private static final DotName ALTERNATIVE = DotName.of("jakarta.enterprise.inject.Alternative");
    private static final DotName PRIORITY = DotName.of("jakarta.annotation.Priority");
    private static final DotName NAMED = DotName.of("jakarta.inject.Named");
    private static final DotName OBSERVES = DotName.of("jakarta.enterprise.event.Observes");
    private static final DotName OBSERVES_ASYNC = DotName.of("jakarta.enterprise.event.ObservesAsync");
    private static final DotName INTERCEPTOR = DotName.of("jakarta.interceptor.Interceptor");
    private static final DotName AROUND_INVOKE = DotName.of("jakarta.interceptor.AroundInvoke");
    private static final DotName INTERCEPTOR_BINDING = DotName.of("jakarta.interceptor.InterceptorBinding");
    private static final DotName DISPOSES = DotName.of("jakarta.enterprise.inject.Disposes");
    private static final DotName STEREOTYPE = DotName.of("jakarta.enterprise.inject.Stereotype");

    private final VaubanIndex index;

    public BeanDiscovery(VaubanIndex index) {
        this.index = Objects.requireNonNull(index);
    }

    /**
     * Discovers all beans in the index using "annotated" discovery mode (CDI 4.1 default).
     */
    public List<BeanDescriptor> discoverBeans() {
        var beans = new ArrayList<BeanDescriptor>();

        for (var classInfo : index.getKnownClasses()) {
            if (isVetoed(classInfo)) continue;
            if (!isBeanCandidate(classInfo)) continue;
            if (!hasBeanDefiningAnnotation(classInfo)) continue;

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

    private boolean isVetoed(ClassInfo classInfo) {
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
        if (!hasExplicitConstructor) return true; // implicit no-arg

        return classInfo.methods().stream()
                .anyMatch(m -> m.isConstructor() && m.parameters().isEmpty());
    }

    private boolean hasBeanDefiningAnnotation(ClassInfo classInfo) {
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
        if (classInfo.hasAnnotation(ALTERNATIVE)) return true;
        // Check inherited @Alternative
        for (var ann : getInheritedAnnotations(classInfo)) {
            if (DotName.of(ann.annotationType().getName()).equals(ALTERNATIVE)) return true;
        }
        // Check stereotypes for @Alternative (direct + transitive)
        var allAnnotationNames = getAllAnnotationNames(classInfo);
        for (var annName : allAnnotationNames) {
            if (isStereotype(annName) && isAlternativeStereotype(annName, new java.util.HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    private boolean isAlternativeStereotype(DotName stereotypeName, Set<DotName> visited) {
        if (!visited.add(stereotypeName)) return false;
        var stereotypeClass = index.getClassByName(stereotypeName);
        if (stereotypeClass.isPresent()) {
            if (stereotypeClass.get().hasAnnotation(ALTERNATIVE)) return true;
            // Check transitive stereotypes
            for (var ann : stereotypeClass.get().annotations()) {
                if (isStereotype(ann.name()) && isAlternativeStereotype(ann.name(), visited)) {
                    return true;
                }
            }
        } else {
            // Reflection fallback
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var annType = cl != null ? Class.forName(stereotypeName.value(), false, cl)
                        : Class.forName(stereotypeName.value());
                if (annType.isAnnotationPresent(jakarta.enterprise.inject.Alternative.class)) return true;
                for (var metaAnn : annType.getAnnotations()) {
                    if (metaAnn.annotationType().isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                        if (isAlternativeStereotype(DotName.of(metaAnn.annotationType().getName()), visited)) {
                            return true;
                        }
                    }
                }
            } catch (ClassNotFoundException e) { /* skip */ }
        }
        return false;
    }

    private int extractPriorityWithStereotypes(ClassInfo classInfo) {
        int priority = extractPriority(classInfo.annotations());
        if (priority > 0) return priority;
        var allAnnotationNames = getAllAnnotationNames(classInfo);
        for (var annName : allAnnotationNames) {
            if (isStereotype(annName)) {
                var stereotypeClass = index.getClassByName(annName);
                if (stereotypeClass.isPresent()) {
                    int stereotypePriority = extractPriority(stereotypeClass.get().annotations());
                    if (stereotypePriority > 0) return stereotypePriority;
                }
            }
        }
        return 0;
    }

    private boolean isStereotype(DotName annotationName) {
        var annClass = index.getClassByName(annotationName);
        if (annClass.isPresent()) {
            return annClass.get().hasAnnotation(STEREOTYPE);
        }
        // Fallback: check via reflection
        try {
            var annType = Class.forName(annotationName.value());
            return annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private BeanDescriptor buildManagedBean(ClassInfo classInfo) {
        var id = BeanId.of(classInfo.name());
        var types = computeBeanTypes(classInfo);
        var qualifiers = computeQualifiersWithStereotypes(classInfo);
        var scope = computeScope(classInfo);
        var isAlternative = isAlternativeWithStereotypes(classInfo);
        var priority = extractPriorityWithStereotypes(classInfo);
        var injectionPoints = discoverInjectionPoints(classInfo);
        var name = extractNameWithStereotypes(classInfo);
        var interceptorBindings = extractInterceptorBindings(classInfo);

        return new BeanDescriptor(id, classInfo.name(), BeanDescriptor.BeanKind.MANAGED,
                types, qualifiers, scope, isAlternative, priority, injectionPoints, name,
                interceptorBindings);
    }

    /**
     * CDI spec: disabled alternative = @Alternative without @Priority (directly or via stereotype).
     */
    private boolean isDisabledAlternative(ClassInfo classInfo) {
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
        // Direct bindings on the class
        for (var ann : classInfo.annotations()) {
            if (isInterceptorBinding(ann.name())) {
                bindings.add(ann.name());
            }
            // Bindings from stereotypes
            if (isStereotype(ann.name())) {
                var stereo = index.getClassByName(ann.name());
                if (stereo.isPresent()) {
                    for (var sa : stereo.get().annotations()) {
                        if (isInterceptorBinding(sa.name())) {
                            bindings.add(sa.name());
                        }
                    }
                }
            }
        }
        // Fallback: check via reflection for bindings not in the index
        try {
            var clazz = Class.forName(classInfo.name().value());
            for (var ann : clazz.getAnnotations()) {
                var annName = DotName.of(ann.annotationType().getName());
                if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                    bindings.add(annName);
                }
            }
        } catch (ClassNotFoundException e) {
            // skip
        }
        return bindings;
    }

    // isInterceptorBinding is defined below (line ~926)

    private BeanDescriptor buildProducerMethodBean(ClassInfo declaringClass, MethodInfo method) {
        var id = BeanId.ofProducerMethod(declaringClass.name(), method.name());
        var types = computeProducerTypesWithTyped(method.returnType(), method.annotations());
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
            var paramQualifiers = computeQualifiers(param.annotations());
            injectionPoints.add(new InjectionPointInfo(
                    param.type(), paramQualifiers, InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
                    "parameter " + i + " of " + declaringClass.name().simpleName() + "." + method.name() + "()"
            ));
        }

        return new BeanDescriptor(id, declaringClass.name(), BeanDescriptor.BeanKind.PRODUCER_METHOD,
                types, qualifiers, scope, isAlternative, priority, injectionPoints, name);
    }

    private BeanDescriptor buildProducerFieldBean(ClassInfo declaringClass, FieldInfo field) {
        var id = BeanId.ofProducerField(declaringClass.name(), field.name());
        var types = computeProducerTypesWithTyped(field.type(), field.annotations());
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
            var value = typedAnn.get().member("value");
            if (value instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.ArrayVal av) {
                for (var v : av.values()) {
                    if (v instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.ClassVal cv) {
                        restrictedRawTypes.add(cv.className());
                    }
                }
            } else if (value instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.ClassVal cv) {
                restrictedRawTypes.add(cv.className());
            }
            // Build restricted types — use parameterized versions when available
            var allParamTypes = new LinkedHashSet<TypeInfo>();
            try {
                collectParameterizedSupertypes(Class.forName(classInfo.name().value()), allParamTypes);
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
            restrictedTypes.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
            return restrictedTypes;
        }
        var types = new LinkedHashSet<TypeInfo>();
        collectBeanTypes(classInfo.name(), types);
        // Enrich with parameterized supertypes via reflection
        try {
            var clazz = Class.forName(classInfo.name().value());
            collectParameterizedSupertypes(clazz, types);
        } catch (ClassNotFoundException e) {
            // skip
        }
        types.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
        return types;
    }

    private void collectBeanTypes(DotName className, Set<TypeInfo> types) {
        types.add(new TypeInfo.ClassType(className));
        var classInfo = index.getClassByName(className);
        if (classInfo.isEmpty()) return;
        var info = classInfo.get();

        if (info.superName() != null && !"java.lang.Object".equals(info.superName().value())) {
            collectBeanTypes(info.superName(), types);
        }
        for (var iface : info.interfaces()) {
            collectBeanTypes(iface, types);
        }
    }

    /**
     * Add parameterized supertypes to the bean types set via reflection.
     * E.g., for IntegerStringDao extends Dao&lt;Integer, String&gt;,
     * adds ParameterizedType("Dao", [ClassType("Integer"), ClassType("String")]).
     */
    private static void collectParameterizedSupertypes(Class<?> clazz, Set<TypeInfo> types) {
        if (clazz == null || clazz == Object.class) return;
        // Check generic superclass
        var genericSuper = clazz.getGenericSuperclass();
        if (genericSuper instanceof java.lang.reflect.ParameterizedType pt) {
            var typeInfo = reflectTypeToTypeInfo(pt);
            if (typeInfo != null) types.add(typeInfo);
            collectParameterizedSupertypes((Class<?>) pt.getRawType(), types);
        } else if (genericSuper instanceof Class<?> c) {
            collectParameterizedSupertypes(c, types);
        }
        // Check generic interfaces
        for (var gi : clazz.getGenericInterfaces()) {
            if (gi instanceof java.lang.reflect.ParameterizedType pt) {
                var typeInfo = reflectTypeToTypeInfo(pt);
                if (typeInfo != null) types.add(typeInfo);
                collectParameterizedSupertypes((Class<?>) pt.getRawType(), types);
            } else if (gi instanceof Class<?> c) {
                collectParameterizedSupertypes(c, types);
            }
        }
    }

    /**
     * Convert a Java reflection ParameterizedType to our TypeInfo model.
     */
    static TypeInfo reflectTypeToTypeInfo(java.lang.reflect.Type type) {
        if (type instanceof Class<?> c) {
            return new TypeInfo.ClassType(DotName.of(c.getName()));
        }
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            var rawType = DotName.of(((Class<?>) pt.getRawType()).getName());
            var args = new java.util.ArrayList<TypeInfo>();
            for (var arg : pt.getActualTypeArguments()) {
                var argInfo = reflectTypeToTypeInfo(arg);
                if (argInfo != null) args.add(argInfo);
            }
            if (args.isEmpty()) return new TypeInfo.ClassType(rawType);
            return new TypeInfo.ParameterizedType(rawType, args);
        }
        if (type instanceof java.lang.reflect.TypeVariable<?> tv) {
            return new TypeInfo.TypeVariable(tv.getName(), List.of());
        }
        if (type instanceof java.lang.reflect.WildcardType wt) {
            TypeInfo upper = wt.getUpperBounds().length > 0 ? reflectTypeToTypeInfo(wt.getUpperBounds()[0]) : null;
            TypeInfo lower = wt.getLowerBounds().length > 0 ? reflectTypeToTypeInfo(wt.getLowerBounds()[0]) : null;
            return new TypeInfo.WildcardType(upper, lower);
        }
        return null;
    }

    Set<TypeInfo> computeProducerTypes(TypeInfo producerType) {
        var types = new LinkedHashSet<TypeInfo>();
        types.add(producerType);
        if (producerType instanceof TypeInfo.ClassType ct) {
            collectBeanTypes(ct.name(), types);
        }
        types.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
        return types;
    }

    Set<TypeInfo> computeProducerTypesWithTyped(TypeInfo producerType, List<AnnotationInfo> annotations) {
        var typedAnn = annotations.stream()
                .filter(a -> a.name().equals(TYPED))
                .findFirst();
        if (typedAnn.isPresent()) {
            var restrictedTypes = new LinkedHashSet<TypeInfo>();
            var value = typedAnn.get().member("value");
            if (value instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.ArrayVal av) {
                for (var v : av.values()) {
                    if (v instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.ClassVal cv) {
                        restrictedTypes.add(new TypeInfo.ClassType(cv.className()));
                    }
                }
            } else if (value instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.ClassVal cv) {
                restrictedTypes.add(new TypeInfo.ClassType(cv.className()));
            }
            restrictedTypes.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
            return restrictedTypes;
        }
        return computeProducerTypes(producerType);
    }

    Set<QualifierInstance> computeQualifiers(List<AnnotationInfo> annotations) {
        var qualifiers = new LinkedHashSet<QualifierInstance>();
        boolean hasExplicitQualifier = false;

        for (var ann : annotations) {
            if (isQualifierAnnotation(ann.name())) {
                qualifiers.add(QualifierInstance.from(ann));
                // @Named and @Any don't count as "explicit qualifiers" for @Default rule
                if (!ann.name().equals(QualifierInstance.NAMED_NAME)
                        && !ann.name().equals(QualifierInstance.ANY_NAME)) {
                    hasExplicitQualifier = true;
                }
            }
        }

        // CDI: if no qualifier other than @Named/@Any, add @Default
        if (!hasExplicitQualifier) {
            qualifiers.add(QualifierInstance.DEFAULT);
        }
        // @Any is always present
        qualifiers.add(QualifierInstance.ANY);

        return qualifiers;
    }

    /**
     * Computes qualifiers for observer methods — only explicit qualifiers,
     * no automatic @Default/@Any (CDI spec: an observer with no qualifiers
     * observes all events of that type regardless of qualifiers).
     */
    Set<QualifierInstance> computeObserverQualifiers(List<AnnotationInfo> annotations) {
        var qualifiers = new LinkedHashSet<QualifierInstance>();
        for (var ann : annotations) {
            if (isQualifierAnnotation(ann.name())) {
                qualifiers.add(QualifierInstance.from(ann));
            }
        }
        return qualifiers;
    }

    Set<QualifierInstance> computeQualifiersWithStereotypes(ClassInfo classInfo) {
        var allAnnotations = new ArrayList<>(classInfo.annotations());

        // Add inherited annotations from superclasses
        for (var ann : getInheritedAnnotations(classInfo)) {
            allAnnotations.add(toAnnotationInfo(ann));
        }

        // Add annotations from stereotypes (direct + inherited)
        // CDI spec: @Named from stereotype gives name but is NOT added as qualifier
        var allAnnotationNames = getAllAnnotationNames(classInfo);
        for (var annName : allAnnotationNames) {
            if (isStereotype(annName)) {
                var stereotypeClass = index.getClassByName(annName);
                if (stereotypeClass.isPresent()) {
                    for (var sa : stereotypeClass.get().annotations()) {
                        if (!sa.name().equals(NAMED)) {
                            allAnnotations.add(sa);
                        }
                    }
                }
            }
        }

        return computeQualifiers(allAnnotations);
    }

    private String extractNameWithStereotypes(ClassInfo classInfo) {
        // Check bean itself first (direct + inherited annotations)
        var name = extractName(classInfo.annotations(), decapitalize(classInfo.name().simpleName()));
        if (name != null) return name;
        // Check inherited @Named
        for (var ann : getInheritedAnnotations(classInfo)) {
            if (ann.annotationType() == jakarta.inject.Named.class) {
                var named = (jakarta.inject.Named) ann;
                return named.value().isEmpty() ? decapitalize(classInfo.name().simpleName()) : named.value();
            }
        }

        // Check stereotypes (direct + inherited)
        var allAnnotationNames = getAllAnnotationNames(classInfo);
        for (var annName : allAnnotationNames) {
            if (isStereotype(annName)) {
                var stereotypeClass = index.getClassByName(annName);
                if (stereotypeClass.isPresent()) {
                    var stereotypeName = extractName(stereotypeClass.get().annotations(),
                            decapitalize(classInfo.name().simpleName()));
                    if (stereotypeName != null) return stereotypeName;
                } else {
                    // Fallback: check via reflection if stereotype has @Named
                    try {
                        var annType = Class.forName(annName.value());
                        if (annType.isAnnotationPresent(jakarta.inject.Named.class)) {
                            return decapitalize(classInfo.name().simpleName());
                        }
                    } catch (ClassNotFoundException e) {
                        // skip
                    }
                }
            }
        }

        return null;
    }

    /**
     * Returns annotations inherited from superclasses (those NOT declared directly on classInfo).
     * Uses Java reflection — Class.getAnnotations() handles @Inherited automatically per JLS.
     */
    private List<java.lang.annotation.Annotation> getInheritedAnnotations(ClassInfo classInfo) {
        try {
            // Use TCCL first (TCK sets this to its custom ClassLoader), fallback to system
            var cl = Thread.currentThread().getContextClassLoader();
            Class<?> cls;
            try {
                cls = Class.forName(classInfo.name().value(), false, cl);
            } catch (ClassNotFoundException e1) {
                cls = Class.forName(classInfo.name().value());
            }
            var declared = cls.getDeclaredAnnotations();
            var all = cls.getAnnotations();
            var declaredNames = new HashSet<Class<?>>();
            for (var d : declared) {
                declaredNames.add(d.annotationType());
            }
            var inherited = new ArrayList<java.lang.annotation.Annotation>();
            for (var a : all) {
                if (!declaredNames.contains(a.annotationType())) {
                    inherited.add(a);
                }
            }
            return inherited;
        } catch (ClassNotFoundException e) {
            return List.of();
        }
    }

    /**
     * Converts a java.lang.annotation.Annotation to an AnnotationInfo for indexer compatibility.
     */
    private AnnotationInfo toAnnotationInfo(java.lang.annotation.Annotation ann) {
        var members = new java.util.LinkedHashMap<String, fr.vidocq.vauban.indexer.model.AnnotationValue>();
        for (var method : ann.annotationType().getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && method.getDeclaringClass() == ann.annotationType()) {
                try {
                    var value = method.invoke(ann);
                    if (value instanceof String s) {
                        members.put(method.getName(), new fr.vidocq.vauban.indexer.model.AnnotationValue.StringVal(s));
                    } else if (value instanceof Boolean b) {
                        members.put(method.getName(), new fr.vidocq.vauban.indexer.model.AnnotationValue.BooleanVal(b));
                    } else if (value instanceof Integer i) {
                        members.put(method.getName(), new fr.vidocq.vauban.indexer.model.AnnotationValue.IntVal(i));
                    } else if (value instanceof Class<?> c) {
                        members.put(method.getName(), new fr.vidocq.vauban.indexer.model.AnnotationValue.ClassVal(DotName.of(c.getName())));
                    } else if (value instanceof Enum<?> e) {
                        members.put(method.getName(), new fr.vidocq.vauban.indexer.model.AnnotationValue.EnumVal(
                                DotName.of(e.getClass().getName()), e.name()));
                    }
                } catch (Exception e) { /* skip */ }
            }
        }
        return new AnnotationInfo(DotName.of(ann.annotationType().getName()), members);
    }

    private boolean isQualifierAnnotation(DotName name) {
        // Built-in qualifiers
        if (name.equals(QualifierInstance.DEFAULT_NAME)
                || name.equals(QualifierInstance.ANY_NAME)
                || name.equals(QualifierInstance.NAMED_NAME)) {
            return true;
        }

        // Check the index for the annotation class having @Qualifier
        var annClass = index.getClassByName(name);
        if (annClass.isPresent()) {
            return annClass.get().hasAnnotation(DotName.of("jakarta.inject.Qualifier"));
        }
        // Fallback: check via reflection with TCCL
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var annType = cl != null ? Class.forName(name.value(), false, cl) : Class.forName(name.value());
            return annType.isAnnotationPresent(jakarta.inject.Qualifier.class);
        } catch (ClassNotFoundException e) {
            return false;
        }
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

        // 3. Check stereotypes for scope (direct + inherited)
        var allAnnotationNames = getAllAnnotationNames(classInfo);
        for (var annName : allAnnotationNames) {
            if (isStereotype(annName)) {
                var stereotypeClass = index.getClassByName(annName);
                if (stereotypeClass.isPresent()) {
                    var stereotypeScope = computeScopeFromAnnotations(stereotypeClass.get().annotations());
                    if (!stereotypeScope.equals(ScopeInfo.DEPENDENT)
                            || hasScopeAnnotation(stereotypeClass.get().annotations())) {
                        return stereotypeScope;
                    }
                } else {
                    // Fallback: check stereotype scope via reflection
                    try {
                        var annType = Class.forName(annName.value());
                        for (var metaAnn : annType.getAnnotations()) {
                            var reflScope = mapScope(DotName.of(metaAnn.annotationType().getName()));
                            if (reflScope != null) return reflScope;
                        }
                    } catch (ClassNotFoundException e) {
                        // skip
                    }
                }
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
    private Set<DotName> getAllAnnotationNames(ClassInfo classInfo) {
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
        // 1. Explicit scope
        for (var ann : annotations) {
            var scope = mapScope(ann.name());
            if (scope != null) return scope;
        }
        // 2. Scope from stereotype
        for (var ann : annotations) {
            if (isStereotype(ann.name())) {
                var stereotypeClass = index.getClassByName(ann.name());
                if (stereotypeClass.isPresent()) {
                    var scope = computeScopeFromAnnotations(stereotypeClass.get().annotations());
                    if (!scope.equals(ScopeInfo.DEPENDENT) ||
                            stereotypeClass.get().annotations().stream().anyMatch(a -> mapScope(a.name()) != null)) {
                        return scope;
                    }
                } else {
                    // Fallback: check stereotype scope via reflection
                    try {
                        var annType = Class.forName(ann.name().value());
                        for (var metaAnn : annType.getAnnotations()) {
                            var scope = mapScope(DotName.of(metaAnn.annotationType().getName()));
                            if (scope != null) return scope;
                        }
                    } catch (ClassNotFoundException e) {
                        // skip
                    }
                }
            }
        }
        return ScopeInfo.DEPENDENT;
    }

    private ScopeInfo mapScope(DotName annotationName) {
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

    List<InjectionPointInfo> discoverInjectionPoints(ClassInfo classInfo) {
        var points = new ArrayList<InjectionPointInfo>();

        // @Inject constructor parameters
        var injectConstructor = classInfo.methods().stream()
                .filter(m -> m.isConstructor() && hasAnnotation(m.annotations(), INJECT))
                .findFirst();

        if (injectConstructor.isPresent()) {
            var ctor = injectConstructor.get();
            for (int i = 0; i < ctor.parameters().size(); i++) {
                var param = ctor.parameters().get(i);
                points.add(new InjectionPointInfo(
                        param.type(), computeQualifiers(param.annotations()),
                        InjectionPointInfo.InjectionKind.CONSTRUCTOR_PARAMETER,
                        "parameter " + i + " of " + classInfo.name().simpleName() + "()"
                ));
            }
        }

        // @Inject fields
        for (var field : classInfo.fields()) {
            if (hasAnnotation(field.annotations(), INJECT)) {
                points.add(new InjectionPointInfo(
                        field.type(), computeQualifiers(field.annotations()),
                        InjectionPointInfo.InjectionKind.FIELD,
                        "field " + classInfo.name().simpleName() + "." + field.name()
                ));
            }
        }

        // @Inject initializer methods
        for (var method : classInfo.methods()) {
            if (!method.isConstructor() && !method.isStatic() && hasAnnotation(method.annotations(), INJECT)) {
                for (int i = 0; i < method.parameters().size(); i++) {
                    var param = method.parameters().get(i);
                    points.add(new InjectionPointInfo(
                            param.type(), computeQualifiers(param.annotations()),
                            InjectionPointInfo.InjectionKind.METHOD_PARAMETER,
                            "parameter " + i + " of " + classInfo.name().simpleName() + "." + method.name() + "()"
                    ));
                }
            }
        }

        return points;
    }

    private int extractPriority(List<AnnotationInfo> annotations) {
        for (var ann : annotations) {
            if (ann.name().equals(PRIORITY)) {
                var value = ann.member("value");
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

    private String extractName(List<AnnotationInfo> annotations, String defaultName) {
        for (var ann : annotations) {
            if (ann.name().equals(NAMED)) {
                var value = ann.member("value");
                if (value instanceof AnnotationValue.StringVal sv && !sv.value().isEmpty()) {
                    return sv.value();
                }
                return defaultName; // @Named without value uses default name
            }
        }
        return null;
    }

    /**
     * Discovers all observer methods in the index.
     * An observer method has a parameter annotated with {@code @Observes} or {@code @ObservesAsync}.
     */
    public List<ObserverDescriptor> discoverObservers() {
        var result = new ArrayList<ObserverDescriptor>();

        for (var classInfo : index.getKnownClasses()) {
            if (isVetoed(classInfo)) continue;
            if (!hasBeanDefiningAnnotation(classInfo)) continue;
            // CDI spec: observer methods of disabled beans are NOT registered
            if (isDisabledAlternative(classInfo)) continue;

            // Check declared methods in the index
            discoverObserversFromMethods(classInfo, classInfo.methods(), result);

            // Check inherited methods via reflection (not in bytecode index)
            try {
                var clazz = Class.forName(classInfo.name().value());
                for (var method : clazz.getMethods()) {
                    if (method.getDeclaringClass() == clazz) continue; // already handled above
                    for (var param : method.getParameters()) {
                        if (param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                || param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                            boolean async = param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class);

                            var reception = "ALWAYS";
                            var transactionPhase = "IN_PROGRESS";
                            var observesAnn = param.getAnnotation(jakarta.enterprise.event.Observes.class);
                            if (observesAnn != null) {
                                reception = observesAnn.notifyObserver().name();
                                transactionPhase = observesAnn.during().name();
                            }

                            var priority = jakarta.enterprise.inject.spi.ObserverMethod.DEFAULT_PRIORITY;
                            if (method.isAnnotationPresent(jakarta.annotation.Priority.class)) {
                                priority = method.getAnnotation(jakarta.annotation.Priority.class).value();
                            }

                            var eventType = new TypeInfo.ClassType(DotName.of(param.getType().getName()));
                            // Collect qualifier annotations from the parameter
                            var qualifiers = new ArrayList<QualifierInstance>();
                            for (var ann : param.getAnnotations()) {
                                if (ann.annotationType() == jakarta.enterprise.event.Observes.class
                                        || ann.annotationType() == jakarta.enterprise.event.ObservesAsync.class)
                                    continue;
                                if (isQualifierAnnotation(DotName.of(ann.annotationType().getName()))) {
                                    qualifiers.add(QualifierInstance.from(
                                            new AnnotationInfo(DotName.of(ann.annotationType().getName()), Map.of())));
                                }
                            }
                            result.add(new ObserverDescriptor(
                                    classInfo.name(), method.getName(), eventType,
                                    qualifiers, async, priority, reception, transactionPhase));
                            break;
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                // skip
            }
        }

        return List.copyOf(result);
    }

    private void discoverObserversFromMethods(ClassInfo classInfo, List<MethodInfo> methods,
            List<ObserverDescriptor> result) {
        for (var method : methods) {
            if (method.isConstructor()) continue;
            // CDI 4.1: static observer methods are supported

            for (var param : method.parameters()) {
                boolean isObserves = hasAnnotation(param.annotations(), OBSERVES);
                boolean isObservesAsync = hasAnnotation(param.annotations(), OBSERVES_ASYNC);

                if (isObserves || isObservesAsync) {
                        // Qualifiers on the observed parameter (excluding @Observes/@ObservesAsync)
                        var qualifiers = computeObserverQualifiers(param.annotations().stream()
                                .filter(a -> !a.name().equals(OBSERVES) && !a.name().equals(OBSERVES_ASYNC))
                                .toList());
                        // CDI spec: @Priority on observer is on the @Observes parameter, not the method
                        var priority = extractPriority(param.annotations());
                        if (priority == 0) priority = extractPriority(method.annotations());
                        if (priority == 0) priority = jakarta.enterprise.inject.spi.ObserverMethod.DEFAULT_PRIORITY;

                        // Extract reception and transactionPhase from @Observes annotation
                        var reception = "ALWAYS";
                        var transactionPhase = "IN_PROGRESS";
                        var observesAnn = param.annotations().stream()
                                .filter(a -> a.name().equals(OBSERVES) || a.name().equals(OBSERVES_ASYNC))
                                .findFirst();
                        if (observesAnn.isPresent()) {
                            var recVal = observesAnn.get().member("notifyObserver");
                            if (recVal instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.EnumVal ev) {
                                reception = ev.constantName();
                            }
                            var txVal = observesAnn.get().member("during");
                            if (txVal instanceof fr.vidocq.vauban.indexer.model.AnnotationValue.EnumVal ev) {
                                transactionPhase = ev.constantName();
                            }
                        }

                        // Try to get the parameterized type via reflection
                        var eventType = resolveObserverParamType(
                                classInfo.name().value(), method.name(),
                                method.parameters().indexOf(param), param.type());

                        result.add(new ObserverDescriptor(
                                classInfo.name(),
                                method.name(),
                                eventType,
                                List.copyOf(qualifiers),
                                isObservesAsync,
                                priority,
                                reception,
                                transactionPhase
                        ));
                        break; // only one observed parameter per method
                }
            }
        }
    }

    /**
     * Resolve the observer parameter type to a ParameterizedType via reflection if possible.
     */
    private TypeInfo resolveObserverParamType(String className, String methodName, int paramIndex, TypeInfo fallback) {
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var clazz = cl != null ? Class.forName(className, false, cl)
                    : Class.forName(className);
            for (var m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    var genericParamTypes = m.getGenericParameterTypes();
                    if (paramIndex < genericParamTypes.length) {
                        var genericType = genericParamTypes[paramIndex];
                        if (genericType instanceof java.lang.reflect.ParameterizedType pt) {
                            var rawTypeName = ((Class<?>) pt.getRawType()).getName();
                            var typeArgs = new ArrayList<TypeInfo>();
                            for (var arg : pt.getActualTypeArguments()) {
                                if (arg instanceof Class<?> c) {
                                    typeArgs.add(new TypeInfo.ClassType(DotName.of(c.getName())));
                                } else if (arg instanceof java.lang.reflect.WildcardType wt) {
                                    var upper = wt.getUpperBounds().length > 0 && wt.getUpperBounds()[0] != Object.class
                                            ? typeInfoFromReflect(wt.getUpperBounds()[0]) : null;
                                    var lower = wt.getLowerBounds().length > 0
                                            ? typeInfoFromReflect(wt.getLowerBounds()[0]) : null;
                                    typeArgs.add(new TypeInfo.WildcardType(upper, lower));
                                } else if (arg instanceof java.lang.reflect.TypeVariable<?> tv) {
                                    typeArgs.add(new TypeInfo.TypeVariable(tv.getName(), List.of()));
                                } else {
                                    typeArgs.add(new TypeInfo.ClassType(DotName.of(arg.getTypeName())));
                                }
                            }
                            return new TypeInfo.ParameterizedType(DotName.of(rawTypeName), typeArgs);
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) { /* fallback */ }
        return fallback;
    }

    private TypeInfo typeInfoFromReflect(java.lang.reflect.Type type) {
        if (type instanceof Class<?> c) return new TypeInfo.ClassType(DotName.of(c.getName()));
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            var rawTypeName = ((Class<?>) pt.getRawType()).getName();
            var args = new ArrayList<TypeInfo>();
            for (var arg : pt.getActualTypeArguments()) {
                args.add(typeInfoFromReflect(arg));
            }
            return new TypeInfo.ParameterizedType(DotName.of(rawTypeName), args);
        }
        return new TypeInfo.ClassType(DotName.of(type.getTypeName()));
    }

    /**
     * Discovers all disposer methods in the index.
     * A disposer method has exactly one parameter annotated with {@code @Disposes}.

     */
    public List<DisposerDescriptor> discoverDisposerMethods() {
        var disposers = new ArrayList<DisposerDescriptor>();

        for (var classInfo : index.getKnownClasses()) {
            if (isVetoed(classInfo)) continue;
            if (!hasBeanDefiningAnnotation(classInfo)) continue;

            for (var method : classInfo.methods()) {
                if (method.isConstructor() || method.isStatic()) continue;

                for (int i = 0; i < method.parameters().size(); i++) {
                    var param = method.parameters().get(i);
                    if (hasAnnotation(param.annotations(), DISPOSES)) {
                        // Qualifiers on the disposed parameter (excluding @Disposes)
                        var qualifiers = computeQualifiers(param.annotations().stream()
                                .filter(a -> !a.name().equals(DISPOSES))
                                .toList());
                        disposers.add(new DisposerDescriptor(
                                classInfo.name(), method.name(), param.type(),
                                qualifiers, i));
                        break; // only one @Disposes per method
                    }
                }
            }
        }

        return List.copyOf(disposers);
    }

    /**
     * Discovers all interceptors in the index.
     * An interceptor is a class annotated with {@code @jakarta.interceptor.Interceptor}.
     */
    public List<InterceptorDescriptor> discoverInterceptors() {
        var interceptors = new ArrayList<InterceptorDescriptor>();

        for (var classInfo : index.getKnownClasses()) {
            if (!classInfo.hasAnnotation(INTERCEPTOR)) continue;

            // Find bindings: annotations on the class whose annotation type is @InterceptorBinding
            var bindings = new LinkedHashSet<DotName>();
            for (var ann : classInfo.annotations()) {
                if (isInterceptorBinding(ann.name())) {
                    bindings.add(ann.name());
                }
            }

            // Fallback: also find bindings via reflection (for annotations not in index)
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                        : Class.forName(classInfo.name().value());
                for (var ann : clazz.getAnnotations()) {
                    if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                        bindings.add(DotName.of(ann.annotationType().getName()));
                    }
                }
            } catch (ClassNotFoundException e) { /* skip */ }

            // Find @AroundInvoke method (from index + reflection fallback)
            String aroundInvoke = null;
            for (var method : classInfo.methods()) {
                if (hasAnnotation(method.annotations(), AROUND_INVOKE)) {
                    aroundInvoke = method.name();
                    break;
                }
            }
            // Reflection fallback for @AroundInvoke
            if (aroundInvoke == null) {
                try {
                    var cl = Thread.currentThread().getContextClassLoader();
                    var clazz = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                            : Class.forName(classInfo.name().value());
                    for (var m : clazz.getDeclaredMethods()) {
                        if (m.isAnnotationPresent(jakarta.interceptor.AroundInvoke.class)) {
                            aroundInvoke = m.getName();
                            break;
                        }
                    }
                    // Also check superclass
                    if (aroundInvoke == null) {
                        var superClass = clazz.getSuperclass();
                        while (superClass != null && superClass != Object.class) {
                            for (var m : superClass.getDeclaredMethods()) {
                                if (m.isAnnotationPresent(jakarta.interceptor.AroundInvoke.class)) {
                                    aroundInvoke = m.getName();
                                    break;
                                }
                            }
                            if (aroundInvoke != null) break;
                            superClass = superClass.getSuperclass();
                        }
                    }
                } catch (ClassNotFoundException e) { /* skip */ }
            }

            // Collect actual binding annotations for member comparison
            var bindingAnnotations = new ArrayList<java.lang.annotation.Annotation>();
            try {
                var cl = Thread.currentThread().getContextClassLoader();
                var clazz2 = cl != null ? Class.forName(classInfo.name().value(), false, cl)
                        : Class.forName(classInfo.name().value());
                for (var ann : clazz2.getAnnotations()) {
                    if (ann.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                        bindingAnnotations.add(ann);
                    }
                }
            } catch (ClassNotFoundException e) { /* skip */ }

            var priority = extractPriority(classInfo.annotations());
            interceptors.add(new InterceptorDescriptor(classInfo.name(), bindings, aroundInvoke, priority, bindingAnnotations));
        }

        // Sort by priority
        interceptors.sort(Comparator.comparingInt(InterceptorDescriptor::priority));
        return interceptors;
    }

    /**
     * Checks if the given annotation name is an interceptor binding
     * (i.e., it is itself annotated with {@code @InterceptorBinding} in the index).
     */
    public boolean isInterceptorBinding(DotName annotationName) {
        var annClass = index.getClassByName(annotationName);
        if (annClass.isPresent()) {
            // Check direct @InterceptorBinding
            if (annClass.get().hasAnnotation(INTERCEPTOR_BINDING)) return true;
            // CDI spec: transitive interceptor bindings — check meta-annotations
            for (var metaAnn : annClass.get().annotations()) {
                if (metaAnn.name().equals(INTERCEPTOR_BINDING)) return true;
                // Check if a meta-annotation is itself an interceptor binding (transitive)
                var metaClass = index.getClassByName(metaAnn.name());
                if (metaClass.isPresent() && metaClass.get().hasAnnotation(INTERCEPTOR_BINDING)) {
                    return true;
                }
            }
        }
        // Fallback: check via reflection with TCCL
        try {
            var cl = Thread.currentThread().getContextClassLoader();
            var annType = cl != null ? Class.forName(annotationName.value(), false, cl)
                    : Class.forName(annotationName.value());
            if (annType.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) return true;
            // Check transitive bindings via reflection
            for (var metaAnn : annType.getAnnotations()) {
                if (metaAnn.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                    return true;
                }
            }
            return false;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean hasAnnotation(List<AnnotationInfo> annotations, DotName name) {
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
