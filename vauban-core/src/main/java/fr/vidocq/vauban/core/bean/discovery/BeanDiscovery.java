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
        return classInfo.hasAnnotation(VETOED);
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
        }
        // CDI 4.0+: @Alternative with @Priority is a bean-defining combination
        if (classInfo.hasAnnotation(ALTERNATIVE) && classInfo.hasAnnotation(PRIORITY)) return true;
        // Also check @Inject on constructor — @Inject constructor makes it a bean
        if (classInfo.methods().stream().anyMatch(m -> m.isConstructor() && hasAnnotation(m.annotations(), INJECT))) {
            return true;
        }
        return false;
    }

    private boolean isAlternativeWithStereotypes(ClassInfo classInfo) {
        if (classInfo.hasAnnotation(ALTERNATIVE)) return true;
        for (var ann : classInfo.annotations()) {
            if (isStereotype(ann.name())) {
                var stereotypeClass = index.getClassByName(ann.name());
                if (stereotypeClass.isPresent() && stereotypeClass.get().hasAnnotation(ALTERNATIVE)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int extractPriorityWithStereotypes(ClassInfo classInfo) {
        int priority = extractPriority(classInfo.annotations());
        if (priority > 0) return priority;
        for (var ann : classInfo.annotations()) {
            if (isStereotype(ann.name())) {
                var stereotypeClass = index.getClassByName(ann.name());
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
        return annClass.isPresent() && annClass.get().hasAnnotation(STEREOTYPE);
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

        return new BeanDescriptor(id, classInfo.name(), BeanDescriptor.BeanKind.MANAGED,
                types, qualifiers, scope, isAlternative, priority, injectionPoints, name);
    }

    private BeanDescriptor buildProducerMethodBean(ClassInfo declaringClass, MethodInfo method) {
        var id = BeanId.ofProducerMethod(declaringClass.name(), method.name());
        var types = computeProducerTypes(method.returnType());
        var qualifiers = computeQualifiers(method.annotations());
        var scope = computeScopeWithStereotypes(method.annotations());
        var isAlternative = hasAnnotation(method.annotations(), ALTERNATIVE) ||
                method.annotations().stream().anyMatch(a -> isStereotype(a.name()) &&
                        index.getClassByName(a.name()).map(c -> c.hasAnnotation(ALTERNATIVE)).orElse(false));
        var priority = extractPriority(method.annotations());
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
        var name = extractName(method.annotations(), deriveProducerMethodName(method.name()));

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
        var types = computeProducerTypes(field.type());
        var qualifiers = computeQualifiers(field.annotations());
        var scope = computeScopeWithStereotypes(field.annotations());
        var isAlternative = hasAnnotation(field.annotations(), ALTERNATIVE);
        var priority = extractPriority(field.annotations());
        var name = extractName(field.annotations(), null);

        return new BeanDescriptor(id, declaringClass.name(), BeanDescriptor.BeanKind.PRODUCER_FIELD,
                types, qualifiers, scope, isAlternative, priority, List.of(), name);
    }

    /**
     * Bean types = the class itself + all supertypes + all interfaces + Object.
     */
    Set<TypeInfo> computeBeanTypes(ClassInfo classInfo) {
        var types = new LinkedHashSet<TypeInfo>();
        collectBeanTypes(classInfo.name(), types);
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

    Set<TypeInfo> computeProducerTypes(TypeInfo producerType) {
        var types = new LinkedHashSet<TypeInfo>();
        types.add(producerType);
        if (producerType instanceof TypeInfo.ClassType ct) {
            collectBeanTypes(ct.name(), types);
        }
        types.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
        return types;
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

        // Add annotations from stereotypes
        for (var ann : classInfo.annotations()) {
            if (isStereotype(ann.name())) {
                var stereotypeClass = index.getClassByName(ann.name());
                if (stereotypeClass.isPresent()) {
                    allAnnotations.addAll(stereotypeClass.get().annotations());
                }
            }
        }

        return computeQualifiers(allAnnotations);
    }

    private String extractNameWithStereotypes(ClassInfo classInfo) {
        // Check bean itself first
        var name = extractName(classInfo.annotations(), decapitalize(classInfo.name().simpleName()));
        if (name != null) return name;

        // Check stereotypes
        for (var ann : classInfo.annotations()) {
            if (isStereotype(ann.name())) {
                var stereotypeClass = index.getClassByName(ann.name());
                if (stereotypeClass.isPresent()) {
                    var stereotypeName = extractName(stereotypeClass.get().annotations(),
                            decapitalize(classInfo.name().simpleName()));
                    if (stereotypeName != null) return stereotypeName;
                }
            }
        }

        return null;
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
        return annClass.isPresent() && annClass.get().hasAnnotation(DotName.of("jakarta.inject.Qualifier"));
    }

    ScopeInfo computeScope(ClassInfo classInfo) {
        // 1. Explicit scope on the bean itself
        var scope = computeScopeFromAnnotations(classInfo.annotations());
        if (!scope.equals(ScopeInfo.DEPENDENT) || hasScopeAnnotation(classInfo.annotations())) {
            return scope;
        }

        // 2. Check stereotypes for scope
        for (var ann : classInfo.annotations()) {
            if (isStereotype(ann.name())) {
                var stereotypeClass = index.getClassByName(ann.name());
                if (stereotypeClass.isPresent()) {
                    var stereotypeScope = computeScopeFromAnnotations(stereotypeClass.get().annotations());
                    if (!stereotypeScope.equals(ScopeInfo.DEPENDENT)
                            || hasScopeAnnotation(stereotypeClass.get().annotations())) {
                        return stereotypeScope;
                    }
                }
            }
        }

        return ScopeInfo.DEPENDENT;
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

            for (var method : classInfo.methods()) {
                if (method.isConstructor() || method.isStatic()) continue;

                for (var param : method.parameters()) {
                    boolean isObserves = hasAnnotation(param.annotations(), OBSERVES);
                    boolean isObservesAsync = hasAnnotation(param.annotations(), OBSERVES_ASYNC);

                    if (isObserves || isObservesAsync) {
                        // Qualifiers on the observed parameter (excluding @Observes/@ObservesAsync)
                        var qualifiers = computeObserverQualifiers(param.annotations().stream()
                                .filter(a -> !a.name().equals(OBSERVES) && !a.name().equals(OBSERVES_ASYNC))
                                .toList());
                        var priority = extractPriority(method.annotations());

                        result.add(new ObserverDescriptor(
                                classInfo.name(),
                                method.name(),
                                param.type(),
                                List.copyOf(qualifiers),
                                isObservesAsync,
                                priority
                        ));
                        break; // only one observed parameter per method
                    }
                }
            }
        }

        return List.copyOf(result);
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

            // Find @AroundInvoke method
            String aroundInvoke = null;
            for (var method : classInfo.methods()) {
                if (hasAnnotation(method.annotations(), AROUND_INVOKE)) {
                    aroundInvoke = method.name();
                    break;
                }
            }

            var priority = extractPriority(classInfo.annotations());
            interceptors.add(new InterceptorDescriptor(classInfo.name(), bindings, aroundInvoke, priority));
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
        return annClass.isPresent() && annClass.get().hasAnnotation(INTERCEPTOR_BINDING);
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
        // If first two chars are uppercase, don't decapitalize (e.g. "URL" stays "URL")
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
