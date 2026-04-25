package fr.vidocq.vauban.processor;

import fr.vidocq.vauban.processor.apt.ElementScanner;
import fr.vidocq.vauban.processor.codegen.GeneratedClass;
import fr.vidocq.vauban.processor.codegen.factory.BeanFactoryGenerator;
import fr.vidocq.vauban.processor.codegen.proxy.ClientProxyGenerator;
import fr.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.model.ScopeInfo;
import fr.vidocq.vauban.core.bean.resolution.BeanResolver;
import fr.vidocq.vauban.core.bean.validation.DeploymentValidator;
import fr.vidocq.vauban.core.extensions.BceProcessor;
import fr.vidocq.vauban.core.extensions.SyntheticMetadataSerializer;
import fr.vidocq.vauban.core.extensions.VaubanClassConfig;
import fr.vidocq.vauban.core.langmodel.IndexLookup;
import fr.vidocq.vauban.core.types.AssignabilityRules;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.model.DotName;
import fr.vidocq.vauban.indexer.scanner.ClassFileScanner;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Annotation processor that discovers CDI beans at compile time,
 * runs Build Compatible Extensions (BCE), generates bean factories,
 * client proxies, and a {@code META-INF/vauban-beans.list}.
 *
 * <p>Supports bean enrichment via {@code vauban-apt.properties} and
 * BCE execution ({@code @Discovery}, {@code @Enhancement}, {@code @Registration},
 * {@code @Synthesis}, {@code @Validation}) at compile time.</p>
 *
 * <p>When BCEs are processed, a marker file {@code META-INF/vauban-bce-processed}
 * is written so the runtime container skips re-executing them.</p>
 */
public class VaubanProcessor extends AbstractProcessor {

    private static final Set<String> CDI_ANNOTATIONS = Set.of(
            "jakarta.enterprise.context.ApplicationScoped",
            "jakarta.enterprise.context.RequestScoped",
            "jakarta.enterprise.context.Dependent",
            "jakarta.inject.Singleton",
            "jakarta.enterprise.inject.Produces"
    );

    private static final String BEANS_LIST_PATH = "META-INF/vauban-beans.list";
    private static final String BCE_RUNTIME_LIST_PATH = "META-INF/vauban-bce-runtime.list";

    private boolean processed = false;
    private List<Class<?>> discoveredBceClasses;
    private Set<String> bceAnnotationTypes = Set.of();

    /** Visible for testing — allows injecting BCE classes without ServiceLoader. */
    public List<Class<?>> overrideBceClasses;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        // Discover BCEs early so their @Enhancement(withAnnotations=...) annotations
        // are included in getSupportedAnnotationTypes(). Without this, classes annotated
        // with e.g. @Path would never be seen by the APT roundEnv.
        var cl = VaubanProcessor.class.getClassLoader();
        discoveredBceClasses = overrideBceClasses != null ? overrideBceClasses : discoverBceClasses(cl);
        bceAnnotationTypes = extractBceAnnotationTypes(discoveredBceClasses);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        var types = new LinkedHashSet<>(CDI_ANNOTATIONS);
        // Trigger annotations derived from BCE @Enhancement(withAnnotations=...)
        // Replaces vauban-apt.properties — the BCE declares its own triggers
        types.addAll(bceAnnotationTypes);
        return Set.copyOf(types);
    }

    /**
     * Extracts annotation class names from BCE @Enhancement(withAnnotations=...) declarations.
     * These must be in getSupportedAnnotationTypes() so the APT roundEnv includes the annotated classes.
     */
    private static Set<String> extractBceAnnotationTypes(List<Class<?>> bceClasses) {
        var types = new LinkedHashSet<String>();
        for (var bceClass : bceClasses) {
            for (var method : bceClass.getDeclaredMethods()) {
                var enhancement = method.getAnnotation(
                        jakarta.enterprise.inject.build.compatible.spi.Enhancement.class);
                if (enhancement == null) continue;
                for (var ann : enhancement.withAnnotations()) {
                    types.add(ann.getName());
                }
            }
        }
        return types;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (processed || roundEnv.processingOver()) return false;
        processed = true;

        var scanner = new ElementScanner(processingEnv.getElementUtils(), processingEnv.getTypeUtils());
        var indexBuilder = new IndexBuilder();

        // Scan all annotated types
        for (var annotation : annotations) {
            for (var element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element instanceof TypeElement typeElement) {
                    indexBuilder.add(scanner.scan(typeElement));
                } else if (element.getEnclosingElement() instanceof TypeElement enclosing) {
                    if (!indexBuilder.contains(DotName.of(enclosing.getQualifiedName().toString()))) {
                        indexBuilder.add(scanner.scan(enclosing));
                    }
                }
            }
        }

        var index = indexBuilder.build();
        if (index.size() == 0) return false;

        // --- BCE: Use extensions discovered in init() ---
        var aptClassLoader = VaubanProcessor.class.getClassLoader();
        var bceClasses = discoveredBceClasses;
        BceProcessor.DiscoveryResult discoveryResult = null;

        if (!bceClasses.isEmpty()) {
            // --- @Discovery phase ---
            var lookup = new IndexLookup(index);
            discoveryResult = BceProcessor.processDiscovery(bceClasses, lookup);

            // Add scanned classes to the index
            for (var className : discoveryResult.scannedClasses().getAddedClasses()) {
                var bytes = loadClassBytes(className, aptClassLoader);
                if (bytes != null) {
                    try {
                        indexBuilder.add(ClassFileScanner.scan(bytes));
                    } catch (Exception e) {
                        // Class scan failed — skip
                    }
                }
            }
            index = indexBuilder.build();
        }

        // Run bean discovery
        var discovery = new BeanDiscovery(index);

        // Apply @Discovery meta-annotations to BeanDiscovery
        if (discoveryResult != null) {
            applyDiscoveryResult(discovery, discoveryResult);
        }

        var beans = new ArrayList<>(discovery.discoverBeans());

        if (beans.isEmpty() && bceClasses.isEmpty()) return false;

        // --- BCE: remaining phases (@Enhancement → @Validation) ---
        boolean bceProcessed = false;
        if (!bceClasses.isEmpty()) {
            var archiveClasses = loadArchiveClasses(index, aptClassLoader);
            var observers = discovery.discoverObservers();
            var interceptors = discovery.discoverInterceptors();

            var bceResult = BceProcessor.process(bceClasses, beans,
                    observers, interceptors,
                    index, aptClassLoader,
                    discoveryResult.bceInstances(), archiveClasses);

            // Report BCE errors as compilation errors
            boolean hasErrors = false;
            for (var err : bceResult.definitionErrors()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "[Vauban BCE] " + err);
                hasErrors = true;
            }
            for (var err : bceResult.deploymentErrors()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "[Vauban BCE] " + err);
                hasErrors = true;
            }

            if (hasErrors) return true;

            // Apply enhancement modifications
            if (!bceResult.enhancementModifications().isEmpty()) {
                var modified = BceProcessor.applyEnhancements(beans, bceResult.enhancementModifications());
                beans.clear();
                beans.addAll(modified);

                // Promote non-beans that gained a scope via Enhancement
                promoteEnhancedClasses(beans, bceResult.enhancementModifications(), index, discovery);

                // Write the runtime replay list so the runtime container knows
                // which BCEs to re-execute on which classes (bug #7).
                writeBceRuntimeList(bceResult.enhancementModifications());
            }

            // Serialize synthetic beans/observers for runtime
            if (!bceResult.syntheticBeans().isEmpty() || !bceResult.syntheticObservers().isEmpty()) {
                writeSyntheticMetadata(bceResult.syntheticBeans(), bceResult.syntheticObservers());
            }

            bceProcessed = true;
        }

        // Validate deployment
        var assignability = new AssignabilityRules(index);
        var resolver = new BeanResolver(beans, assignability);
        var validator = new DeploymentValidator(beans, resolver);
        var errors = validator.validate();

        for (var error : errors) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "[Vauban] " + error.message());
        }

        if (!errors.isEmpty()) return true;

        // Generate code for each bean
        for (var bean : beans) {
            if (bean.kind() == BeanDescriptor.BeanKind.MANAGED) {
                var classInfo = index.getClassByName(bean.beanClass()).orElse(null);
                if (classInfo == null) continue;

                generateClass(BeanFactoryGenerator.generate(classInfo));

                if (bean.scope().isNormal()) {
                    generateClass(ClientProxyGenerator.generate(classInfo));
                }
            }
        }

        // Write META-INF/vauban-beans.list
        writeBeansList(beans);

        // Write BCE processed marker
        if (bceProcessed) {
            writeBceProcessedMarker();
        }

        return true;
    }

    // --- BCE Discovery ---

    private List<Class<?>> discoverBceClasses(ClassLoader cl) {
        var bceClasses = new ArrayList<Class<?>>();
        try {
            ServiceLoader.load(BuildCompatibleExtension.class, cl)
                    .forEach(ext -> bceClasses.add(ext.getClass()));
        } catch (Exception e) {
            // ServiceLoader failed — no BCEs
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] BCE discovery via ServiceLoader: " + e.getMessage());
        }
        if (!bceClasses.isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Discovered " + bceClasses.size() + " Build Compatible Extension(s)");
        }
        return bceClasses;
    }

    private void applyDiscoveryResult(BeanDiscovery discovery, BceProcessor.DiscoveryResult discoveryResult) {
        var meta = discoveryResult.metaAnnotations();
        discovery.setCustomQualifiers(
                meta.getCustomQualifiers().stream()
                        .map(c -> DotName.of(c.getName()))
                        .collect(Collectors.toSet()));
        discovery.setCustomInterceptorBindings(
                meta.getCustomInterceptorBindings().stream()
                        .map(c -> DotName.of(c.getName()))
                        .collect(Collectors.toSet()));
        discovery.setCustomStereotypes(
                meta.getCustomStereotypes().stream()
                        .map(c -> DotName.of(c.getName()))
                        .collect(Collectors.toSet()));

        var stereotypeAnns = new HashMap<DotName, Set<Class<? extends java.lang.annotation.Annotation>>>();
        for (var entry : meta.getStereotypeAnnotations().entrySet()) {
            stereotypeAnns.put(DotName.of(entry.getKey().getName()), entry.getValue());
        }
        discovery.setCustomStereotypeAnnotations(stereotypeAnns);
        discovery.setCustomNonbindingMembers(meta.getNonbindingMembersPerQualifier());

        if (!discoveryResult.scannedClasses().getAddedClasses().isEmpty()) {
            var scannedDotNames = discoveryResult.scannedClasses().getAddedClasses().stream()
                    .map(DotName::of)
                    .collect(Collectors.toSet());
            discovery.setForcedBeanClasses(scannedDotNames);
        }
    }

    private void promoteEnhancedClasses(List<BeanDescriptor> beans,
                                         Map<DotName, List<VaubanClassConfig>> modifications,
                                         fr.vidocq.vauban.indexer.VaubanIndex index,
                                         BeanDiscovery discovery) {
        var existingBeanClasses = beans.stream()
                .map(BeanDescriptor::beanClass)
                .collect(Collectors.toSet());

        for (var entry : modifications.entrySet()) {
            if (existingBeanClasses.contains(entry.getKey())) continue;
            var enhancedScope = extractEnhancedScope(entry.getValue());
            if (enhancedScope == null) continue;
            var classInfo = index.getClassByName(entry.getKey()).orElse(null);
            if (classInfo == null) continue;

            var newBean = discovery.buildManagedBean(classInfo);
            newBean = new BeanDescriptor(
                    newBean.id(), newBean.beanClass(), newBean.kind(), newBean.types(),
                    newBean.qualifiers(), enhancedScope, newBean.isAlternative(),
                    newBean.priority(), newBean.injectionPoints(), newBean.name(),
                    newBean.interceptorBindings(), newBean.constructorBindings(),
                    newBean.interceptorBindingAnnotations());
            beans.add(newBean);
        }
    }

    private static ScopeInfo extractEnhancedScope(List<VaubanClassConfig> configs) {
        for (var config : configs) {
            for (var ann : config.getAddedAnnotations()) {
                if (ann.isAnnotationPresent(jakarta.enterprise.context.NormalScope.class)) {
                    return new ScopeInfo(DotName.of(ann.getName()), true);
                }
                if (ann.isAnnotationPresent(jakarta.inject.Scope.class)) {
                    return new ScopeInfo(DotName.of(ann.getName()), false);
                }
                String name = ann.getName();
                if (name.equals("jakarta.enterprise.context.RequestScoped")
                        || name.equals("jakarta.enterprise.context.ApplicationScoped")
                        || name.equals("jakarta.enterprise.context.SessionScoped")
                        || name.equals("jakarta.enterprise.context.ConversationScoped")) {
                    return new ScopeInfo(DotName.of(name), true);
                }
                if (name.equals("jakarta.enterprise.context.Dependent")) {
                    return ScopeInfo.DEPENDENT;
                }
                if (name.equals("jakarta.inject.Singleton")) {
                    return ScopeInfo.SINGLETON;
                }
            }
        }
        return null;
    }

    // --- Utility methods ---

    private List<Class<?>> loadArchiveClasses(fr.vidocq.vauban.indexer.VaubanIndex index, ClassLoader cl) {
        var classes = new ArrayList<Class<?>>();
        for (var classInfo : index.getKnownClasses()) {
            try {
                classes.add(Class.forName(classInfo.name().value(), false, cl));
            } catch (ClassNotFoundException ignored) {
                // Class not on processor classpath — skip
            }
        }
        return classes;
    }

    private byte[] loadClassBytes(String className, ClassLoader cl) {
        var resourceName = className.replace('.', '/') + ".class";
        try (var is = cl.getResourceAsStream(resourceName)) {
            return is != null ? is.readAllBytes() : null;
        } catch (IOException e) {
            return null;
        }
    }

    // --- File writing ---

    private void writeBeansList(List<BeanDescriptor> beans) {
        var beanClassNames = beans.stream()
                .map(BeanDescriptor::beanClass)
                .map(DotName::value)
                .distinct()
                .sorted()
                .toList();

        if (beanClassNames.isEmpty()) return;

        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", BEANS_LIST_PATH);
            try (var writer = new PrintWriter(resource.openOutputStream(), false, StandardCharsets.UTF_8)) {
                writer.println("# Vauban discovered beans — generated at compile time by APT");
                for (var className : beanClassNames) {
                    writer.println(className);
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write " + BEANS_LIST_PATH + ": " + e.getMessage());
        }
    }

    private void writeBceRuntimeList(Map<DotName, List<VaubanClassConfig>> modifications) {
        var entries = new TreeSet<String>();
        for (var entry : modifications.entrySet()) {
            var targetFqn = entry.getKey().value();
            for (var config : entry.getValue()) {
                if (!config.isModified()) continue;
                var bce = config.getSourceBce();
                if (bce == null) continue;
                entries.add(bce.getName() + ";" + targetFqn);
            }
        }
        if (entries.isEmpty()) return;

        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", BCE_RUNTIME_LIST_PATH);
            try (var writer = new PrintWriter(resource.openOutputStream(), false, StandardCharsets.UTF_8)) {
                writer.println("# Vauban BCE runtime replay list — generated at compile time by APT");
                for (var line : entries) {
                    writer.println(line);
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write " + BCE_RUNTIME_LIST_PATH + ": " + e.getMessage());
        }
    }

    private void writeSyntheticMetadata(
            List<fr.vidocq.vauban.core.extensions.VaubanSyntheticBeanBuilder<?>> syntheticBeans,
            List<fr.vidocq.vauban.core.extensions.VaubanSyntheticObserverBuilder<?>> syntheticObservers) {
        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", SyntheticMetadataSerializer.METADATA_PATH);
            try (var os = resource.openOutputStream()) {
                SyntheticMetadataSerializer.write(syntheticBeans, syntheticObservers, os);
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Serialized " + syntheticBeans.size() + " synthetic bean(s), "
                            + syntheticObservers.size() + " synthetic observer(s)");
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write synthetic metadata: " + e.getMessage());
        }
    }

    private void writeBceProcessedMarker() {
        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", SyntheticMetadataSerializer.BCE_PROCESSED_MARKER);
            try (var os = resource.openOutputStream()) {
                os.write("# BCE phases executed at compile time by VaubanProcessor\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write BCE marker: " + e.getMessage());
        }
    }

    private void generateClass(GeneratedClass generated) {
        try {
            var filer = processingEnv.getFiler();
            var fileObject = filer.createClassFile(generated.className());
            try (var os = fileObject.openOutputStream()) {
                os.write(generated.bytecode());
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "[Vauban] Failed to write generated class " + generated.className() + ": " + e.getMessage());
        }
    }
}
