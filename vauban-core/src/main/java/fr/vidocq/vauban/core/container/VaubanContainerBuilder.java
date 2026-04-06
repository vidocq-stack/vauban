package fr.vidocq.vauban.core.container;

import fr.vidocq.vauban.core.BeanFactory;
import fr.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.BeanId;
import fr.vidocq.vauban.core.bean.model.DisposerDescriptor;
import fr.vidocq.vauban.core.bean.model.ObserverDescriptor;
import fr.vidocq.vauban.core.bean.model.QualifierInstance;
import fr.vidocq.vauban.core.bean.resolution.BeanResolver;
import fr.vidocq.vauban.core.types.AssignabilityRules;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.context.spi.CreationalContext;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VaubanContainerBuilder {


    private final List<Class<?>> beanClasses = new ArrayList<>();
    private final Map<DotName, BeanFactory<?>> factories = new LinkedHashMap<>();
    private java.util.function.BiFunction<String, byte[], Class<?>> classDefiner;
    private ClassLoader classLoader;
    private java.lang.invoke.MethodHandles.Lookup lookup;
    private boolean isBeanArchive = true;
    private VaubanLookup builderLookup;

    private VaubanLookup getBuilderLookup() {
        if (builderLookup == null) {
            builderLookup = new VaubanLookup(lookup != null ? lookup : java.lang.invoke.MethodHandles.lookup());
        }
        return builderLookup;
    }

    public VaubanContainerBuilder beanArchive(boolean isBeanArchive) {
        this.isBeanArchive = isBeanArchive;
        return this;
    }

    /**
     * Add a bean class. The container will scan it and create a default factory.
     */
    public VaubanContainerBuilder addBeanClass(Class<?> beanClass) {
        if (!beanClasses.contains(beanClass)) {
            beanClasses.add(beanClass);
        }
        return this;
    }

    /**
     * Register a custom factory for a bean class.
     */
    /**
     * Set a custom class definer for dynamically generated classes (e.g., interceptor subclasses).
     * The function takes (className, bytecode) and returns the defined Class.
     */
    public VaubanContainerBuilder classDefiner(java.util.function.BiFunction<String, byte[], Class<?>> definer) {
        this.classDefiner = definer;
        return this;
    }

    public VaubanContainerBuilder classLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
        return this;
    }

    /**
     * Provide a {@link java.lang.invoke.MethodHandles.Lookup} from the user's module.
     * This enables JPMS-compliant access to private bean members without
     * {@code setAccessible(true)}. The lookup should be obtained via
     * {@code MethodHandles.lookup()} in the user's code.
     *
     * <p>If not provided, {@link #scanLocal()} will auto-capture one from the caller.
     * For classpath mode (unnamed modules), a default lookup is used.
     */
    public VaubanContainerBuilder lookup(java.lang.invoke.MethodHandles.Lookup lookup) {
        this.lookup = lookup;
        return this;
    }

    public <T> VaubanContainerBuilder addFactory(Class<T> beanClass, BeanFactory<T> factory) {
        factories.put(DotName.of(beanClass.getName()), factory);
        return this;
    }

    /**
     * Scan the caller's package (and sub-packages) for CDI bean classes and add them.
     * Uses stack walking to determine the calling class's package.
     * <p>Example:
     * <pre>{@code
     * // In com.example.MyApp — scans com.example.**
     * VaubanContainer.builder().scanLocal().build();
     * }</pre>
     */
    public VaubanContainerBuilder scanLocal() {
        var callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(c -> c != VaubanContainer.class && c != VaubanContainerBuilder.class)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Cannot determine caller package")));
        // Auto-capture a Lookup from the caller's module if not already set
        if (this.lookup == null) {
            try {
                this.lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                        callerClass, java.lang.invoke.MethodHandles.lookup());
            } catch (IllegalAccessException e) {
                // Fallback: use default lookup (unnamed modules / classpath)
                this.lookup = java.lang.invoke.MethodHandles.lookup();
            }
        }
        return scanPackage(callerClass.getPackageName());
    }

    /**
     * Scan all classes in the given package (and sub-packages) for CDI bean annotations
     * and add them as bean classes.
     *
     * @param packageName the root package to scan (e.g. "com.example")
     */
    public VaubanContainerBuilder scanPackage(String packageName) {
        var cl = this.classLoader != null ? this.classLoader
                : Thread.currentThread().getContextClassLoader();
        var packagePath = packageName.replace('.', '/');
        try {
            var resources = cl.getResources(packagePath);
            while (resources.hasMoreElements()) {
                var url = resources.nextElement();
                if ("file".equals(url.getProtocol())) {
                    scanDirectory(java.nio.file.Path.of(url.toURI()), packageName, cl);
                } else if ("jar".equals(url.getProtocol())) {
                    scanJarEntries(url, packagePath, cl);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to scan package: " + packageName, e);
        }
        return this;
    }

    private void scanDirectory(java.nio.file.Path dir, String packageName, ClassLoader cl) throws Exception {
        if (!java.nio.file.Files.isDirectory(dir)) return;
        try (var stream = java.nio.file.Files.walk(dir)) {
            stream.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        var relative = dir.relativize(p).toString();
                        var className = packageName + "." + relative
                                .replace(java.io.File.separatorChar, '.')
                                .replace(".class", "");
                        tryAddBeanClass(className, cl);
                    });
        }
    }

    private void scanJarEntries(java.net.URL jarUrl, String packagePath, ClassLoader cl) throws Exception {
        var connection = (java.net.JarURLConnection) jarUrl.openConnection();
        try (var jarFile = connection.getJarFile()) {
            jarFile.entries().asIterator().forEachRemaining(entry -> {
                var name = entry.getName();
                if (name.startsWith(packagePath) && name.endsWith(".class")) {
                    var className = name.replace('/', '.').replace(".class", "");
                    tryAddBeanClass(className, cl);
                }
            });
        }
    }

    private void tryAddBeanClass(String className, ClassLoader cl) {
        try {
            var clazz = Class.forName(className, false, cl);
            if (clazz.isAnnotation() || clazz.isInterface() || clazz.isSynthetic()) return;
            addBeanClass(clazz);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip unloadable classes
        }
    }

    /**
     * Scan the classpath for {@code META-INF/vauban-beans.list} files and add
     * all listed bean classes. These files are generated at build time by the
     * {@code vauban-maven-plugin:generate} goal.
     */
    public VaubanContainerBuilder scanClasspath() {
        var cl = this.classLoader != null ? this.classLoader
                : Thread.currentThread().getContextClassLoader();
        try {
            var urls = cl.getResources("META-INF/vauban-beans.list");
            while (urls.hasMoreElements()) {
                var url = urls.nextElement();
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    reader.lines()
                            .map(String::strip)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .forEach(className -> {
                                try {
                                    addBeanClass(Class.forName(className, false, cl));
                                } catch (ClassNotFoundException e) {
                                    // Bean class not on classpath — skip silently
                                }
                            });
                }
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to scan classpath for vauban-beans.list", e);
        }
        return this;
    }

    public VaubanContainer build() {
        var indexBuilder = new IndexBuilder();

        for (var clazz : beanClasses) {
            try {
                String resource = clazz.getName().replace('.', '/') + ".class";
                try (var is = clazz.getClassLoader().getResourceAsStream(resource)) {
                    if (is != null) {
                        indexBuilder.add(fr.vidocq.vauban.indexer.scanner.ClassFileScanner.scan(is.readAllBytes()));
                    } else {
                        // Fallback for classes not in resources (e.g. dynamic or some test classes)
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan class: " + clazz.getName(), e);
            }

            // Auto-register factory — will be replaced with constructor-aware
            // version after discovery if @Inject constructor is found
            if (!factories.containsKey(DotName.of(clazz.getName()))) {
                var beanClass2 = clazz;
                var lkp = getBuilderLookup();
                factories.put(DotName.of(clazz.getName()), () -> {
                    try {
                        return lkp.newInstance(beanClass2);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new jakarta.enterprise.inject.CreationException(e);
                    }
                });
            }
        }

        // Set TCCL to the bean class's ClassLoader so BeanDiscovery can
        // resolve inherited annotations via reflection on TCK archive classes
        var previousCl = Thread.currentThread().getContextClassLoader();
        ClassLoader discoveryClassLoader = !beanClasses.isEmpty()
                ? beanClasses.getFirst().getClassLoader()
                : Thread.currentThread().getContextClassLoader();
        if (!beanClasses.isEmpty()) {
            Thread.currentThread().setContextClassLoader(discoveryClassLoader);
        }

        // --- @Discovery phase (BCE) — runs BEFORE bean discovery ---
        var bceClasses = beanClasses.stream()
                .filter(c -> isBuildCompatibleExtension(c))
                .toList();

        fr.vidocq.vauban.core.extensions.BceProcessor.DiscoveryResult discoveryResult = null;
        if (!bceClasses.isEmpty()) {
            var tempIndex = indexBuilder.build();
            var tempLookup = new fr.vidocq.vauban.core.langmodel.IndexLookup(tempIndex);
            discoveryResult = fr.vidocq.vauban.core.extensions.BceProcessor.processDiscovery(bceClasses, tempLookup);

            // Add scanned classes to the index
            for (var className : discoveryResult.scannedClasses().getAddedClasses()) {
                try {
                    var cls = Class.forName(className, false, discoveryClassLoader);
                    String resource = className.replace('.', '/') + ".class";
                    try (var is = discoveryClassLoader.getResourceAsStream(resource)) {
                        if (is != null) {
                            indexBuilder.add(fr.vidocq.vauban.indexer.scanner.ClassFileScanner.scan(is.readAllBytes()));
                        }
                    }
                    if (!factories.containsKey(DotName.of(className))) {
                        var lkp = getBuilderLookup();
                        factories.put(DotName.of(className), () -> {
                            try {
                                return lkp.newInstance(cls);
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new jakarta.enterprise.inject.CreationException(e);
                            }
                        });
                    }
                } catch (Exception e) {
                    // Class not found — skip
                }
            }
        }

        var index = indexBuilder.build();

        // Validate class-level CDI rules (before bean discovery)
        try {
            var classErrors = fr.vidocq.vauban.core.bean.validation.ClassValidator.validate(index);
            if (!classErrors.isEmpty()) {
                var msg = new StringBuilder("CDI definition validation failed:\n");
                for (var error : classErrors) {
                    msg.append("  - ").append(error).append("\n");
                }
                throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
            }

            // Reflection-based validation (for generic signatures not in bytecode index)
            // Exclude BCE classes from validation (they are not beans)
            var nonBceClasses = beanClasses.stream()
                    .filter(c -> !isBuildCompatibleExtension(c))
                    .toList();
            var reflectionErrors = validateWithReflection(nonBceClasses);
            if (!reflectionErrors.isEmpty()) {
                var msg = new StringBuilder("CDI definition validation failed:\n");
                for (var error : reflectionErrors) {
                    msg.append("  - ").append(error).append("\n");
                }
                throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
            }

            var discovery = new BeanDiscovery(index);

            // Apply @Discovery results to BeanDiscovery
            if (discoveryResult != null) {
                var meta = discoveryResult.metaAnnotations();
                discovery.setCustomQualifiers(
                        meta.getCustomQualifiers().stream()
                                .map(c -> DotName.of(c.getName()))
                                .collect(java.util.stream.Collectors.toSet()));
                discovery.setCustomInterceptorBindings(
                        meta.getCustomInterceptorBindings().stream()
                                .map(c -> DotName.of(c.getName()))
                                .collect(java.util.stream.Collectors.toSet()));
                discovery.setCustomStereotypes(
                        meta.getCustomStereotypes().stream()
                                .map(c -> DotName.of(c.getName()))
                                .collect(java.util.stream.Collectors.toSet()));
                var stereotypeAnns = new java.util.HashMap<DotName, java.util.Set<Class<? extends java.lang.annotation.Annotation>>>();
                for (var entry : meta.getStereotypeAnnotations().entrySet()) {
                    stereotypeAnns.put(DotName.of(entry.getKey().getName()), entry.getValue());
                }
                discovery.setCustomStereotypeAnnotations(stereotypeAnns);
                discovery.setCustomNonbindingMembers(meta.getNonbindingMembersPerQualifier());
                fr.vidocq.vauban.core.bean.resolution.QualifierMatcher.setCustomNonbindingMembers(
                        meta.getNonbindingMembersPerQualifier());
                VaubanBeanManager.setCustomQualifierTypes(meta.getCustomQualifiers());
                VaubanBeanManager.setCustomInterceptorBindingTypes(meta.getCustomInterceptorBindings());
                VaubanBeanManager.setCustomStereotypeTypes(meta.getCustomStereotypes());

                // Classes added via ScannedClasses bypass annotation check
                if (!discoveryResult.scannedClasses().getAddedClasses().isEmpty()) {
                    var scannedDotNames = discoveryResult.scannedClasses().getAddedClasses().stream()
                            .map(DotName::of)
                            .collect(java.util.stream.Collectors.toSet());
                    discovery.setForcedBeanClasses(scannedDotNames);
                    // When not a bean archive, also restrict discovery to scanned classes only
                    if (!isBeanArchive) {
                        discovery.setScannedClassesFilter(scannedDotNames);
                    }
                }
            }
            var descriptors = new ArrayList<>(discovery.discoverBeans());
            var observers = new ArrayList<>(discovery.discoverObservers());
            var interceptors = discovery.discoverInterceptors();
            var disposers = discovery.discoverDisposerMethods();
            var syntheticDisposers = new LinkedHashMap<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>>();

            // --- Build Compatible Extensions (BCE) — remaining phases ---
            if (!bceClasses.isEmpty()) {
                var bceResult = fr.vidocq.vauban.core.extensions.BceProcessor.process(
                        bceClasses, descriptors, observers, interceptors, index,
                        beanClasses.isEmpty() ? Thread.currentThread().getContextClassLoader()
                                : beanClasses.getFirst().getClassLoader(),
                        discoveryResult != null ? discoveryResult.bceInstances() : null,
                        nonBceClasses);

                // BCE definition errors → DefinitionException
                if (!bceResult.definitionErrors().isEmpty()) {
                    var msg = new StringBuilder("CDI definition validation failed:\n");
                    for (var error : bceResult.definitionErrors()) {
                        msg.append("  - ").append(error).append("\n");
                    }
                    throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
                }

                // BCE deployment errors → DeploymentException
                if (!bceResult.deploymentErrors().isEmpty()) {
                    var msg = new StringBuilder("CDI deployment validation failed:\n");
                    for (var error : bceResult.deploymentErrors()) {
                        msg.append("  - ").append(error).append("\n");
                    }
                    throw new jakarta.enterprise.inject.spi.DeploymentException(msg.toString());
                }

                // Register synthetic beans
                for (var synBean : bceResult.syntheticBeans()) {
                    registerSyntheticBean(synBean, descriptors, factories, syntheticDisposers);
                }

                // Register synthetic observers
                for (var synObs : bceResult.syntheticObservers()) {
                    observers.add(buildSyntheticObserver(synObs));
                }

                // Apply enhancement modifications to bean descriptors
                if (!bceResult.enhancementModifications().isEmpty()) {
                    var modified = fr.vidocq.vauban.core.extensions.BceProcessor.applyEnhancements(
                            descriptors, bceResult.enhancementModifications());
                    descriptors.clear();
                    descriptors.addAll(modified);

                    // Apply enhancement modifications to interceptor descriptors (e.g. @Priority)
                    interceptors = new ArrayList<>(fr.vidocq.vauban.core.extensions.BceProcessor.applyInterceptorEnhancements(
                            interceptors, bceResult.enhancementModifications()));

                    // Apply enhancement modifications to observer descriptors (parameter qualifier changes)
                    var modifiedObservers = fr.vidocq.vauban.core.extensions.BceProcessor.applyObserverEnhancements(
                            observers, bceResult.enhancementModifications());
                    observers.clear();
                    observers.addAll(modifiedObservers);
                }
            }

            // Validate observer/disposer method parameters (CDI spec)
            VaubanContainer.validateObserverParameters(observers, descriptors, index);
            VaubanContainer.validateDisposerParameters(disposers, descriptors, index);
            // Validate disposer method definitions (CDI 4.1 Section 3.5)
            validateDisposerDefinitions(disposers, descriptors);


            // Validate deployment — throw if there are errors
            var assignability = new AssignabilityRules(index);
            var tempResolver = new BeanResolver(descriptors, interceptors, assignability);
            var validator = new fr.vidocq.vauban.core.bean.validation.DeploymentValidator(
                    descriptors, tempResolver);
            var errors = validator.validate();
            if (!errors.isEmpty()) {
                var definitionErrors = errors.stream()
                        .filter(e -> e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.DEFINITION_ERROR)
                        .toList();

                if (!definitionErrors.isEmpty()) {
                    var msg = new StringBuilder("CDI definition validation failed:\n");
                    for (var error : definitionErrors) {
                        msg.append("  - ").append(error.message()).append("\n");
                    }
                    throw new jakarta.enterprise.inject.spi.DefinitionException(msg.toString());
                }

                // Ambiguous or unsatisfied dependencies are DeploymentExceptions in CDI
                var deploymentErrors = errors.stream()
                        .filter(e -> e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.DEPLOYMENT_ERROR
                                || e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.UNSATISFIED_DEPENDENCY
                                || e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.AMBIGUOUS_DEPENDENCY
                                || e.kind() == fr.vidocq.vauban.core.bean.validation.DeploymentValidator.ValidationError.Kind.CIRCULAR_DEPENDENCY)
                        .toList();

                if (!deploymentErrors.isEmpty()) {
                    var msg = new StringBuilder("CDI deployment validation failed:\n");
                    for (var error : deploymentErrors) {
                        msg.append("  - ").append(error.message()).append("\n");
                    }
                    throw new jakarta.enterprise.inject.spi.DeploymentException(msg.toString());
                }

                // Default fallback
                var msg = new StringBuilder("CDI validation failed:\n");
                for (var error : errors) {
                    msg.append("  - ").append(error.message()).append("\n");
                }
                throw new jakarta.enterprise.inject.spi.DeploymentException(msg.toString());
            }

            var beanClassLoader = this.classLoader != null 
                    ? this.classLoader
                    : (beanClasses.isEmpty()
                    ? Thread.currentThread().getContextClassLoader()
                    : beanClasses.getFirst().getClassLoader());
            var vaubanLookup = getBuilderLookup();
            var container = new VaubanContainer(index, descriptors, observers, interceptors, disposers, factories, syntheticDisposers, beanClassLoader, classDefiner, vaubanLookup);

            // Register custom contexts from Build Compatible Extensions
            if (discoveryResult != null) {
                for (var reg : discoveryResult.metaAnnotations().getCustomContexts()) {
                    try {
                        var ctx = (jakarta.enterprise.context.spi.Context) vaubanLookup.newInstance(reg.contextClass());
                        container.contexts.computeIfAbsent(reg.scopeAnnotation(), k -> new java.util.ArrayList<>()).add(ctx);
                    } catch (Exception e) {
                        // Skip context if instantiation fails
                    }
                }
            }

            return container;
        } finally {
            Thread.currentThread().setContextClassLoader(previousCl);
        }
    }

    /**
     * Validates disposer method definitions (CDI 4.1 Section 3.5).
     * - Each disposer must match at least one producer bean in the same declaring class
     * - Multiple disposers for the same producer in the same class are DefinitionException
     */
    private static void validateDisposerDefinitions(
            List<DisposerDescriptor> disposers,
            List<BeanDescriptor> descriptors) {
        var producerBeans = descriptors.stream()
                .filter(d -> d.kind() == BeanDescriptor.BeanKind.PRODUCER_METHOD
                        || d.kind() == BeanDescriptor.BeanKind.PRODUCER_FIELD)
                .toList();

        // CDI spec: Each disposer must have a matching producer in the same bean class
        for (var disposer : disposers) {
            boolean found = false;
            for (var producer : producerBeans) {
                // Disposer must be in the same declaring class as the producer
                if (!producer.beanClass().equals(disposer.declaringClass())
                        && !producerDeclaredIn(producer, disposer.declaringClass())) {
                    continue;
                }
                if (disposerMatchesProducerType(disposer, producer)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                // Only throw if the disposer is in a bean class (not a non-bean utility class)
                boolean isInBeanClass = descriptors.stream()
                        .anyMatch(d -> d.beanClass().equals(disposer.declaringClass())
                                && d.kind() == BeanDescriptor.BeanKind.MANAGED);
                if (isInBeanClass) {
                    throw new jakarta.enterprise.inject.spi.DefinitionException(
                            "Disposer method " + disposer.declaringClass().value() + "." + disposer.methodName()
                                    + "(): no matching producer found for disposed type " + disposer.disposedType());
                }
            }
        }

    }

    private static boolean producerDeclaredIn(BeanDescriptor producer, DotName declaringClass) {
        // Check if the producer's ID references this declaring class
        return producer.id().value().startsWith(declaringClass.value());
    }

    private static boolean disposerMatchesProducerType(DisposerDescriptor disposer, BeanDescriptor producer) {
        for (var producerType : producer.types()) {
            if (producerType instanceof TypeInfo.ClassType ct
                    && disposer.disposedType() instanceof TypeInfo.ClassType dt
                    && ct.name().equals(dt.name())) {
                return true;
            }
            if (producerType.equals(disposer.disposedType())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ObserverDescriptor buildSyntheticObserver(
            fr.vidocq.vauban.core.extensions.VaubanSyntheticObserverBuilder<?> synObs) {
        var eventReflectType = synObs.getEventType();
        TypeInfo eventTypeInfo;
        if (eventReflectType instanceof Class<?> cls) {
            eventTypeInfo = new TypeInfo.ClassType(DotName.of(cls.getName()));
        } else if (eventReflectType instanceof java.lang.reflect.ParameterizedType pt
                && pt.getRawType() instanceof Class<?> rawCls) {
            var typeArgs = new java.util.ArrayList<TypeInfo>();
            for (var arg : pt.getActualTypeArguments()) {
                if (arg instanceof Class<?> argCls) {
                    typeArgs.add(new TypeInfo.ClassType(DotName.of(argCls.getName())));
                } else {
                    typeArgs.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
                }
            }
            eventTypeInfo = new TypeInfo.ParameterizedType(DotName.of(rawCls.getName()), typeArgs);
        } else {
            eventTypeInfo = new TypeInfo.ClassType(DotName.of("java.lang.Object"));
        }

        var qualifiers = new java.util.ArrayList<QualifierInstance>();
        for (var q : synObs.getQualifiers()) {
            var qName = DotName.of(q.annotationType().getName());
            qualifiers.add(new QualifierInstance(qName, java.util.Map.of()));
        }

        var observerClass = synObs.getObserverClass();
        var params = synObs.getParams();

        java.util.function.BiConsumer<Object, java.lang.annotation.Annotation[]> invoker = (event, eventQualifiers) -> {
            try {
                var observer = (jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver) observerClass.getDeclaredConstructor().newInstance();
                var vaubanParams = new fr.vidocq.vauban.core.extensions.VaubanParameters(params);
                var metadata = new jakarta.enterprise.inject.spi.EventMetadata() {
                    @Override public java.util.Set<java.lang.annotation.Annotation> getQualifiers() {
                        var qs = new java.util.LinkedHashSet<java.lang.annotation.Annotation>();
                        if (eventQualifiers != null) {
                            for (var q : eventQualifiers) qs.add(q);
                        }
                        qs.add(jakarta.enterprise.inject.Any.Literal.INSTANCE);
                        return java.util.Set.copyOf(qs);
                    }
                    @Override public jakarta.enterprise.inject.spi.InjectionPoint getInjectionPoint() { return null; }
                    @Override public java.lang.reflect.Type getType() { return event.getClass(); }
                };
                var eventContext = new jakarta.enterprise.inject.spi.EventContext() {
                    @Override public Object getEvent() { return event; }
                    @Override public jakarta.enterprise.inject.spi.EventMetadata getMetadata() { return metadata; }
                };
                observer.observe(eventContext, vaubanParams);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new jakarta.enterprise.event.ObserverException("Synthetic observer failed", e);
            }
        };

        return new ObserverDescriptor(
                DotName.of(observerClass.getName()),
                "observe",
                eventTypeInfo,
                qualifiers,
                synObs.isAsync(),
                synObs.getPriority(),
                "ALWAYS",
                "IN_PROGRESS",
                invoker
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerSyntheticBean(
            fr.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder<?> synBean,
            List<BeanDescriptor> descriptors,
            Map<DotName, BeanFactory<?>> factories,
            Map<DotName, java.util.function.BiConsumer<Object, CreationalContext<?>>> syntheticDisposers) {
        var beanClass = synBean.getBeanClass();
        var beanName = DotName.of(beanClass.getName());

        // Build bean types from the builder's types
        var beanTypes = new java.util.LinkedHashSet<TypeInfo>();
        for (var type : synBean.getTypes()) {
            if (type instanceof Class<?> cls) {
                beanTypes.add(new TypeInfo.ClassType(DotName.of(cls.getName())));
            }
        }
        if (beanTypes.isEmpty()) {
            beanTypes.add(new TypeInfo.ClassType(beanName));
            beanTypes.add(new TypeInfo.ClassType(DotName.of("java.lang.Object")));
        }

        // Determine scope
        var scope = fr.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT;
        if (synBean.getScopeAnnotation() != null) {
            var scopeAnn = synBean.getScopeAnnotation();
            if (scopeAnn == jakarta.enterprise.context.ApplicationScoped.class) {
                scope = fr.vidocq.vauban.core.bean.model.ScopeInfo.APPLICATION;
            } else if (scopeAnn == jakarta.enterprise.context.RequestScoped.class) {
                scope = fr.vidocq.vauban.core.bean.model.ScopeInfo.REQUEST;
            } else if (scopeAnn == jakarta.inject.Singleton.class) {
                scope = fr.vidocq.vauban.core.bean.model.ScopeInfo.SINGLETON;
            }
        }

        // Build qualifiers from builder's qualifier set
        var qualifiers = new java.util.LinkedHashSet<QualifierInstance>();
        boolean hasExplicitQualifier = false;
        for (var q : synBean.getQualifiers()) {
            var qName = DotName.of(q.annotationType().getName());
            if (!qName.equals(QualifierInstance.DEFAULT_NAME) && !qName.equals(QualifierInstance.ANY_NAME)) {
                hasExplicitQualifier = true;
            }
            qualifiers.add(new QualifierInstance(qName, java.util.Map.of()));
        }
        if (!hasExplicitQualifier) {
            qualifiers.add(QualifierInstance.DEFAULT);
        }
        qualifiers.add(QualifierInstance.ANY);

        // Use unique key to avoid collisions when multiple synthetic beans share the same type
        var syntheticKey = DotName.of(beanName.value() + "#synthetic#" + descriptors.size());
        var descriptor = new BeanDescriptor(
                new BeanId(syntheticKey.value()),
                beanName,
                BeanDescriptor.BeanKind.SYNTHETIC,
                beanTypes,
                qualifiers,
                scope,
                synBean.isAlternative(),
                synBean.getPriority(),
                List.of(),
                synBean.getName()
        );
        descriptors.add(descriptor);

        // Create factory using SyntheticBeanCreator
        var creatorClass = synBean.getCreatorClass();
        var creatorParams = synBean.getParams();
        var isDependent = scope.equals(fr.vidocq.vauban.core.bean.model.ScopeInfo.DEPENDENT);
        factories.put(syntheticKey, new BeanFactory<Object>() {
            @Override
            public Object create() { return create((CreationalContext<Object>) null); }
            @Override
            public Object create(CreationalContext<Object> ctx) {
                try {
                    @SuppressWarnings("unchecked")
                    var creator = (jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator<Object>)
                            creatorClass.getDeclaredConstructor().newInstance();
                    var vaubanParams = new fr.vidocq.vauban.core.extensions.VaubanParameters(creatorParams);
                    var container = VaubanContainer.current();
                    var previousIp = VaubanContainer.getCurrentInjectionPoint();
                    if (previousIp == null && isDependent) {
                        VaubanContainer.setInjectionPoint(VaubanInjectionPoint.EMPTY);
                    }
                    try {
                        var parentCtx = ctx instanceof fr.vidocq.vauban.core.context.CreationalContextImpl<?> cci ? cci : null;
                        var lookup = new InstanceImpl<>(container, Object.class, new Annotation[0], null, parentCtx);
                        return creator.create(lookup, vaubanParams);
                    } finally {
                        if (previousIp == null && isDependent) {
                            VaubanContainer.setInjectionPoint(previousIp);
                        }
                    }
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new jakarta.enterprise.inject.CreationException(e);
                }
            }
        });

        // Register synthetic disposer if present
        var disposerClass = synBean.getDisposerClass();
        if (disposerClass != null) {
            syntheticDisposers.put(syntheticKey, (instance, ctx) -> {
                try {
                    @SuppressWarnings("unchecked")
                    var disposer = (jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer<Object>)
                            disposerClass.getDeclaredConstructor().newInstance();
                    var vaubanParams = new fr.vidocq.vauban.core.extensions.VaubanParameters(creatorParams);
                    var container = VaubanContainer.current();
                    var lookup = new InstanceImpl<>(container, Object.class, new Annotation[]{jakarta.enterprise.inject.Default.Literal.INSTANCE}, null, (fr.vidocq.vauban.core.context.CreationalContextImpl<?>) ctx);
                    disposer.dispose(instance, lookup, vaubanParams);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    // CDI spec: exceptions in disposer methods are suppressed
                }
            });
        }
    }

    /**
     * Validates CDI rules that require generic type information (only available via reflection).
     * Detects raw Event/Instance injection, producer type variables, generic beans, etc.
     */
    private static List<String> validateWithReflection(List<Class<?>> beanClasses) {
        var errors = new ArrayList<String>();
        for (var clazz : beanClasses) {
            // Skip interfaces, annotations, enums
            if (clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum()) continue;

            // CDI spec: stereotype with @Named must have empty value
            for (var ann : clazz.getAnnotations()) {
                if (ann.annotationType().isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                    var named = ann.annotationType().getAnnotation(jakarta.inject.Named.class);
                    if (named != null && !named.value().isEmpty()) {
                        errors.add("Stereotype " + ann.annotationType().getName()
                                + " has @Named with non-empty value '" + named.value() + "'");
                    }
                }
            }

            // CDI spec: a managed bean with type parameters and a non-@Dependent scope
            // is a DefinitionException (CDI 4.1 Section 3.1)
            if (hasBeanDefiningAnnotation(clazz) && clazz.getTypeParameters().length > 0) {
                boolean isNonDependent = false;
                for (var ann : clazz.getAnnotations()) {
                    var annType = ann.annotationType();
                    if (annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                            || annType == jakarta.inject.Singleton.class) {
                        isNonDependent = true;
                        break;
                    }
                }
                if (isNonDependent) {
                    errors.add("Managed bean " + clazz.getName()
                            + " has type parameters and is not @Dependent");
                }
            }

            // CDI spec: @Named without value on non-field injection points
            if (hasBeanDefiningAnnotation(clazz)) {
                for (var method : clazz.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                        for (var param : method.getParameters()) {
                            var named = param.getAnnotation(jakarta.inject.Named.class);
                            if (named != null && named.value().isEmpty()) {
                                errors.add("@Named without value on initializer parameter: "
                                        + clazz.getName() + "." + method.getName());
                            }
                        }
                    }
                }
                for (var ctor : clazz.getDeclaredConstructors()) {
                    if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) {
                        for (var param : ctor.getParameters()) {
                            var named = param.getAnnotation(jakarta.inject.Named.class);
                            if (named != null && named.value().isEmpty()) {
                                errors.add("@Named without value on constructor parameter: "
                                        + clazz.getName());
                            }
                        }
                    }
                }
            }

            // CDI spec: @Named without value on producer/observer/disposer method parameters
            for (var method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                    for (var param : method.getParameters()) {
                        var named = param.getAnnotation(jakarta.inject.Named.class);
                        if (named != null && named.value().isEmpty()) {
                            errors.add("@Named without value on producer method parameter: "
                                    + clazz.getName() + "." + method.getName());
                        }
                    }
                }
                // Observer/disposer method non-event/non-disposes parameters
                boolean hasObservesOrDisposes = false;
                for (var param : method.getParameters()) {
                    if (param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                            || param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)
                            || param.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) {
                        hasObservesOrDisposes = true;
                        break;
                    }
                }
                if (hasObservesOrDisposes) {
                    for (var param : method.getParameters()) {
                        if (param.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                || param.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)
                                || param.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) continue;
                        var named = param.getAnnotation(jakarta.inject.Named.class);
                        if (named != null && named.value().isEmpty()) {
                            errors.add("@Named without value on observer/disposer method parameter: "
                                    + clazz.getName() + "." + method.getName());
                        }
                    }
                }
            }

            // CDI spec: normal-scoped beans must not have non-static public fields
            // This is a DefinitionException (not DeploymentException)
            if (hasBeanDefiningAnnotation(clazz)) {
                boolean isNormalScoped = false;
                for (var ann : clazz.getAnnotations()) {
                    if (ann.annotationType().isAnnotationPresent(
                            jakarta.enterprise.context.NormalScope.class)) {
                        isNormalScoped = true;
                        break;
                    }
                }
                if (isNormalScoped) {
                    for (var field : clazz.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isPublic(field.getModifiers())
                                && !java.lang.reflect.Modifier.isStatic(field.getModifiers())
                                && !field.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                            errors.add("Normal-scoped bean " + clazz.getName()
                                    + " has non-static public field '" + field.getName() + "'");
                            break;
                        }
                    }
                }
            }

            // CDI spec: @Typed values must be legal bean types (supertypes of the bean class)
            if (clazz.isAnnotationPresent(jakarta.enterprise.inject.Typed.class)) {
                var typed = clazz.getAnnotation(jakarta.enterprise.inject.Typed.class);
                for (var t : typed.value()) {
                    if (!t.isAssignableFrom(clazz)) {
                        errors.add("@Typed value " + t.getName() + " on " + clazz.getName()
                                + " is not a legal bean type (not a supertype)");
                    }
                }
            }

            // CDI spec: @Typed on producer methods/fields — values must be legal
            for (var method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)
                        && method.isAnnotationPresent(jakarta.enterprise.inject.Typed.class)) {
                    var typed = method.getAnnotation(jakarta.enterprise.inject.Typed.class);
                    var returnType = method.getReturnType();
                    for (var t : typed.value()) {
                        if (!t.isAssignableFrom(returnType) && t != Object.class) {
                            errors.add("@Typed value " + t.getName() + " on producer method "
                                    + clazz.getName() + "." + method.getName()
                                    + " is not a legal bean type");
                        }
                    }
                }
            }
            for (var field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)
                        && field.isAnnotationPresent(jakarta.enterprise.inject.Typed.class)) {
                    var typed = field.getAnnotation(jakarta.enterprise.inject.Typed.class);
                    var fieldType = field.getType();
                    for (var t : typed.value()) {
                        if (!t.isAssignableFrom(fieldType) && t != Object.class) {
                            errors.add("@Typed value " + t.getName() + " on producer field "
                                    + clazz.getName() + "." + field.getName()
                                    + " is not a legal bean type");
                        }
                    }
                }
            }

            // Generic managed bean — deferred

            // Check @Inject fields for raw Event/Instance
            for (var field : clazz.getDeclaredFields()) {
                if (!field.isAnnotationPresent(jakarta.inject.Inject.class)) continue;
                validateNoRawParameterized(field.getGenericType(), field.getType(),
                        clazz.getName() + "." + field.getName(), errors);
            }

            // Check methods
            for (var method : clazz.getDeclaredMethods()) {
                // Producer method return type validation
                if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                    validateProducerReturnType(method.getGenericReturnType(),
                            clazz.getName() + "." + method.getName(), errors);
                    // Multiple scope annotations on producer method
                    validateNoMultipleScopes(method.getAnnotations(),
                            "Producer method " + clazz.getName() + "." + method.getName(), errors);
                }

                // CDI spec: producer with TypeVariable return type must be @Dependent
                if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                    var genRetType = method.getGenericReturnType();
                    if (containsTypeVariable(genRetType)) {
                        boolean isDependent = true;
                        for (var mAnn : method.getAnnotations()) {
                            if (mAnn.annotationType().isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                                    || (mAnn.annotationType().isAnnotationPresent(jakarta.inject.Scope.class)
                                        && mAnn.annotationType() != jakarta.enterprise.context.Dependent.class)) {
                                isDependent = false;
                                break;
                            }
                        }
                        if (!isDependent) {
                            errors.add("Producer method " + clazz.getName() + "." + method.getName()
                                    + " has TypeVariable return type and non-@Dependent scope");
                        }
                    }
                }

                // CDI spec: producer with wildcard type parameter is not a legal bean type
                if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                    var genRetType = method.getGenericReturnType();
                    if (containsWildcard(genRetType)) {
                        errors.add("Producer method " + clazz.getName() + "." + method.getName()
                                + " has wildcard type parameter in return type");
                    }
                }

                // Generic initializer method
                if (method.isAnnotationPresent(jakarta.inject.Inject.class)
                        && method.getTypeParameters().length > 0
                        && !method.getName().equals("<init>")) {
                    errors.add("Initializer method " + clazz.getName() + "." + method.getName()
                            + " cannot declare type parameters");
                }

                // @Inject method parameters: raw Event/Instance
                if (method.isAnnotationPresent(jakarta.inject.Inject.class)) {
                    var paramTypes = method.getGenericParameterTypes();
                    var rawTypes = method.getParameterTypes();
                    for (int i = 0; i < paramTypes.length; i++) {
                        validateNoRawParameterized(paramTypes[i], rawTypes[i],
                                clazz.getName() + "." + method.getName() + " param " + i, errors);
                    }
                }

                // Observer method injection parameters: raw Event/Instance
                var params = method.getParameters();
                boolean hasObserves = false;
                for (var p : params) {
                    if (p.isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                            || p.isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                        hasObserves = true;
                        break;
                    }
                }
                if (hasObserves) {
                    var paramTypes = method.getGenericParameterTypes();
                    var rawTypes = method.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (!params[i].isAnnotationPresent(jakarta.enterprise.event.Observes.class)
                                && !params[i].isAnnotationPresent(jakarta.enterprise.event.ObservesAsync.class)) {
                            validateNoRawParameterized(paramTypes[i], rawTypes[i],
                                    clazz.getName() + "." + method.getName() + " observer param", errors);
                        }
                    }
                }

                // Disposer method injection parameters: raw Event/Instance
                boolean hasDisposes = false;
                for (var p : params) {
                    if (p.isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) {
                        hasDisposes = true;
                        break;
                    }
                }
                if (hasDisposes) {
                    var paramTypes = method.getGenericParameterTypes();
                    var rawTypes = method.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (!params[i].isAnnotationPresent(jakarta.enterprise.inject.Disposes.class)) {
                            validateNoRawParameterized(paramTypes[i], rawTypes[i],
                                    clazz.getName() + "." + method.getName() + " disposer param", errors);
                        }
                        // CDI spec: disposer methods must not have InjectionPoint parameter
                        if (rawTypes[i] == jakarta.enterprise.inject.spi.InjectionPoint.class) {
                            errors.add("Disposer method " + clazz.getName() + "." + method.getName()
                                    + " must not have InjectionPoint parameter");
                        }
                    }
                }

                // Producer method parameters: raw Event/Instance
                if (method.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                    var paramTypes = method.getGenericParameterTypes();
                    var rawTypes = method.getParameterTypes();
                    for (int i = 0; i < paramTypes.length; i++) {
                        validateNoRawParameterized(paramTypes[i], rawTypes[i],
                                clazz.getName() + "." + method.getName() + " producer param", errors);
                    }
                }
            }

            // Check producer fields
            for (var field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.enterprise.inject.Produces.class)) {
                    validateProducerReturnType(field.getGenericType(),
                            clazz.getName() + "." + field.getName(), errors);
                    // Multiple scope annotations on producer field
                    validateNoMultipleScopes(field.getAnnotations(),
                            "Producer field " + clazz.getName() + "." + field.getName(), errors);
                    // CDI spec: producer field with TypeVariable type must be @Dependent
                    if (containsTypeVariable(field.getGenericType())) {
                        boolean isDependent = true;
                        for (var fAnn : field.getAnnotations()) {
                            if (fAnn.annotationType().isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                                    || (fAnn.annotationType().isAnnotationPresent(jakarta.inject.Scope.class)
                                        && fAnn.annotationType() != jakarta.enterprise.context.Dependent.class)) {
                                isDependent = false;
                                break;
                            }
                        }
                        if (!isDependent) {
                            errors.add("Producer field " + clazz.getName() + "." + field.getName()
                                    + " has TypeVariable type and non-@Dependent scope");
                        }
                    }
                }
            }

            // Check constructors for raw Event/Instance
            for (var ctor : clazz.getDeclaredConstructors()) {
                if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) {
                    var paramTypes = ctor.getGenericParameterTypes();
                    var rawTypes = ctor.getParameterTypes();
                    for (int i = 0; i < paramTypes.length; i++) {
                        validateNoRawParameterized(paramTypes[i], rawTypes[i],
                                clazz.getName() + " constructor param " + i, errors);
                    }
                }
            }
            // CDI spec: stereotypes must not declare conflicting priorities (direct or transitive)
            if (hasBeanDefiningAnnotation(clazz)) {
                var priorities = new java.util.LinkedHashSet<Integer>();
                collectStereotypePriorities(clazz, priorities, new java.util.HashSet<>());
                if (priorities.size() > 1) {
                    errors.add("Bean " + clazz.getName()
                            + " has conflicting stereotype priorities: " + priorities);
                }
            }
            // CDI spec: if stereotypes declare conflicting scopes and bean has no explicit scope,
            // it's a DefinitionException
            if (hasBeanDefiningAnnotation(clazz) && !hasExplicitScope(clazz)) {
                var scopes = new java.util.LinkedHashSet<Class<?>>();
                collectStereotypeScopes(clazz, scopes, new java.util.HashSet<>());
                if (scopes.size() > 1) {
                    errors.add("Bean " + clazz.getName()
                            + " has conflicting stereotype scopes: " + scopes);
                }
            }
            // CDI spec: stereotypes must not declare same interceptor binding with different values
            // CDI spec: conflicting interceptor binding values (from stereotypes or transitive bindings)
            if (hasBeanDefiningAnnotation(clazz)) {
                var bindingsByType = new java.util.HashMap<Class<?>, java.lang.annotation.Annotation>();
                collectTransitiveInterceptorBindings(clazz.getAnnotations(), bindingsByType, errors, clazz.getName(), new java.util.HashSet<>());
            }
        }
        return errors;
    }

    private static void collectTransitiveInterceptorBindings(
            java.lang.annotation.Annotation[] annotations,
            java.util.Map<Class<?>, java.lang.annotation.Annotation> bindingsByType,
            List<String> errors, String beanName, Set<Class<?>> visited) {
        for (var ann : annotations) {
            var annType = ann.annotationType();
            // Check both stereotypes and interceptor bindings for transitive bindings
            if (annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)
                    || annType.isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                if (!visited.add(annType)) continue;
                for (var metaAnn : annType.getAnnotations()) {
                    if (metaAnn.annotationType().isAnnotationPresent(jakarta.interceptor.InterceptorBinding.class)) {
                        var prev = bindingsByType.put(metaAnn.annotationType(), metaAnn);
                        if (prev != null && !prev.equals(metaAnn)) {
                            errors.add("Bean " + beanName
                                    + " has conflicting interceptor binding values for "
                                    + metaAnn.annotationType().getSimpleName());
                        }
                    }
                }
                // Recurse into meta-annotations
                collectTransitiveInterceptorBindings(annType.getAnnotations(), bindingsByType, errors, beanName, visited);
            }
        }
    }

    private static void collectStereotypePriorities(Class<?> clazz,
            Set<Integer> priorities, Set<Class<?>> visited) {
        for (var ann : clazz.getAnnotations()) {
            var annType = ann.annotationType();
            if (annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                if (!visited.add(annType)) continue;
                // Check if stereotype has @Priority
                var priority = annType.getAnnotation(jakarta.annotation.Priority.class);
                if (priority != null) {
                    priorities.add(priority.value());
                }
                // Check transitive stereotypes
                collectStereotypePriorities(annType, priorities, visited);
            }
        }
    }

    private static boolean hasExplicitScope(Class<?> clazz) {
        for (var ann : clazz.getDeclaredAnnotations()) {
            var annType = ann.annotationType();
            if (annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                    || annType.isAnnotationPresent(jakarta.inject.Scope.class)) {
                return true;
            }
        }
        return false;
    }

    private static void collectStereotypeScopes(Class<?> clazz,
            Set<Class<?>> scopes, Set<Class<?>> visited) {
        for (var ann : clazz.getAnnotations()) {
            var annType = ann.annotationType();
            if (annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) {
                if (!visited.add(annType)) continue;
                // Check if stereotype has a scope
                for (var metaAnn : annType.getAnnotations()) {
                    if (metaAnn.annotationType().isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                            || metaAnn.annotationType().isAnnotationPresent(jakarta.inject.Scope.class)) {
                        scopes.add(metaAnn.annotationType());
                    }
                }
                // Check transitive stereotypes
                collectStereotypeScopes(annType, scopes, visited);
            }
        }
    }

    private static void validateNoRawParameterized(java.lang.reflect.Type genericType,
            Class<?> rawType, String location, List<String> errors) {
        if (rawType == jakarta.enterprise.event.Event.class
                && !(genericType instanceof java.lang.reflect.ParameterizedType)) {
            errors.add("Raw Event type injected at " + location + " — must be parameterized");
        }
        if (rawType == jakarta.enterprise.inject.Instance.class
                && !(genericType instanceof java.lang.reflect.ParameterizedType)) {
            errors.add("Raw Instance type injected at " + location + " — must be parameterized");
        }
    }

    private static void validateProducerReturnType(java.lang.reflect.Type type, String location,
            List<String> errors) {
        // CDI spec: producer return type cannot be a naked type variable or wildcard
        if (type instanceof java.lang.reflect.TypeVariable<?>) {
            errors.add("Producer " + location + " has type variable return type");
        }
        if (type instanceof java.lang.reflect.WildcardType) {
            errors.add("Producer " + location + " has wildcard return type");
        }
        // CDI spec: parameterized type with wildcard type arguments
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            for (var arg : pt.getActualTypeArguments()) {
                if (arg instanceof java.lang.reflect.WildcardType) {
                    errors.add("Producer " + location + " has parameterized type with wildcard argument");
                    break;
                }
            }
        }
        if (type instanceof java.lang.reflect.GenericArrayType gat) {
            var componentType = gat.getGenericComponentType();
            if (componentType instanceof java.lang.reflect.TypeVariable<?>) {
                errors.add("Producer " + location + " has array type with type variable component");
            }
            if (componentType instanceof java.lang.reflect.WildcardType) {
                errors.add("Producer " + location + " has array type with wildcard component");
            }
            // Array of parameterized type with wildcards
            if (componentType instanceof java.lang.reflect.ParameterizedType cpt) {
                for (var arg : cpt.getActualTypeArguments()) {
                    if (arg instanceof java.lang.reflect.WildcardType) {
                        errors.add("Producer " + location + " has array of parameterized type with wildcard");
                        break;
                    }
                }
            }
        }
    }

    private static void validateNoMultipleScopes(java.lang.annotation.Annotation[] annotations,
            String location, List<String> errors) {
        int scopeCount = 0;
        for (var ann : annotations) {
            var annType = ann.annotationType();
            if (annType.isAnnotationPresent(jakarta.inject.Scope.class)
                    || annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)) {
                scopeCount++;
            }
        }
        if (scopeCount > 1) {
            errors.add(location + " has multiple scope annotations");
        }
    }

    private static boolean containsWildcard(java.lang.reflect.Type type) {
        if (type instanceof java.lang.reflect.WildcardType) return true;
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            for (var arg : pt.getActualTypeArguments()) {
                if (containsWildcard(arg)) return true;
            }
        }
        if (type instanceof java.lang.reflect.GenericArrayType gat) {
            return containsWildcard(gat.getGenericComponentType());
        }
        return false;
    }

    private static boolean containsTypeVariable(java.lang.reflect.Type type) {
        if (type instanceof java.lang.reflect.TypeVariable<?>) return true;
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            for (var arg : pt.getActualTypeArguments()) {
                if (containsTypeVariable(arg)) return true;
            }
        }
        if (type instanceof java.lang.reflect.GenericArrayType gat) {
            return containsTypeVariable(gat.getGenericComponentType());
        }
        if (type instanceof java.lang.reflect.WildcardType wt) {
            for (var bound : wt.getUpperBounds()) {
                if (containsTypeVariable(bound)) return true;
            }
            for (var bound : wt.getLowerBounds()) {
                if (containsTypeVariable(bound)) return true;
            }
        }
        return false;
    }

    /**
     * Check if a class implements BuildCompatibleExtension, using interface name
     * comparison to avoid ClassLoader issues.
     */
    static boolean isBuildCompatibleExtension(Class<?> clazz) {
    return implementsInterface(clazz, "jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension");
    }

    static boolean implementsInterface(Class<?> clazz, String interfaceName) {
    if (clazz == null || clazz == Object.class) return false;
    for (var iface : clazz.getInterfaces()) {
        if (iface.getName().equals(interfaceName)) return true;
        if (implementsInterface(iface, interfaceName)) return true;
    }
    return implementsInterface(clazz.getSuperclass(), interfaceName);
    }

    private static boolean hasBeanDefiningAnnotation(Class<?> clazz) {
        for (var ann : clazz.getAnnotations()) {
            var annType = ann.annotationType();
            if (annType == jakarta.enterprise.context.ApplicationScoped.class
                    || annType == jakarta.enterprise.context.RequestScoped.class
                    || annType == jakarta.enterprise.context.Dependent.class
                    || annType == jakarta.inject.Singleton.class) return true;
            if (annType.isAnnotationPresent(jakarta.inject.Scope.class)
                    || annType.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)
                    || annType.isAnnotationPresent(jakarta.enterprise.inject.Stereotype.class)) return true;
        }
        // @Inject constructor
        for (var ctor : clazz.getDeclaredConstructors()) {
            if (ctor.isAnnotationPresent(jakarta.inject.Inject.class)) return true;
        }
        return false;
    }
}
