package fr.vidocq.vauban.processor;

import fr.vidocq.vauban.processor.apt.ElementScanner;
import fr.vidocq.vauban.processor.codegen.GeneratedClass;
import fr.vidocq.vauban.processor.codegen.factory.BeanFactoryGenerator;
import fr.vidocq.vauban.processor.codegen.proxy.ClientProxyGenerator;
import fr.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.resolution.BeanResolver;
import fr.vidocq.vauban.core.bean.validation.DeploymentValidator;
import fr.vidocq.vauban.core.enrichment.EnrichmentConfig;
import fr.vidocq.vauban.core.enrichment.IndexEnricher;
import fr.vidocq.vauban.core.types.AssignabilityRules;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.model.ClassInfo;
import fr.vidocq.vauban.indexer.model.DotName;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Annotation processor that discovers CDI beans at compile time,
 * generates bean factories, client proxies, and a {@code META-INF/vauban-beans.list}.
 *
 * <p>Supports bean enrichment via {@code vauban-apt.properties}: classes annotated
 * with a trigger annotation (e.g. {@code @Path}) receive a CDI scope automatically,
 * promoting them to managed beans without requiring a Build Compatible Extension.</p>
 */
public class VaubanProcessor extends AbstractProcessor {

    private static final Set<String> CDI_ANNOTATIONS = Set.of(
            "jakarta.enterprise.context.ApplicationScoped",
            "jakarta.enterprise.context.RequestScoped",
            "jakarta.enterprise.context.Dependent",
            "jakarta.inject.Singleton",
            "jakarta.enterprise.inject.Produces"
    );

    private static final String PROPERTIES_FILE = "vauban-apt.properties";
    private static final String BEANS_LIST_PATH = "META-INF/vauban-beans.list";

    private boolean processed = false;
    private EnrichmentConfig enrichmentConfig = EnrichmentConfig.empty();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        loadEnrichmentConfig();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        var types = new LinkedHashSet<>(CDI_ANNOTATIONS);
        types.addAll(enrichmentConfig.triggerAnnotationNames());
        return Set.copyOf(types);
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
                    // Producer methods/fields - add the enclosing class
                    if (!indexBuilder.contains(DotName.of(enclosing.getQualifiedName().toString()))) {
                        indexBuilder.add(scanner.scan(enclosing));
                    }
                }
            }
        }

        var index = indexBuilder.build();
        if (index.size() == 0) return false;

        // Enrich index with synthetic scope annotations from vauban-apt.properties
        index = IndexEnricher.enrich(index, enrichmentConfig);

        // Run bean discovery
        var discovery = new BeanDiscovery(index);
        var beans = discovery.discoverBeans();

        if (beans.isEmpty()) return false;

        // Validate deployment
        var assignability = new AssignabilityRules(index);
        var resolver = new BeanResolver(beans, assignability);
        var validator = new DeploymentValidator(beans, resolver);
        var errors = validator.validate();

        // Report validation errors as compilation errors
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

                // Generate factory
                generateClass(BeanFactoryGenerator.generate(classInfo));

                // Generate proxy for normal-scoped beans
                if (bean.scope().isNormal()) {
                    generateClass(ClientProxyGenerator.generate(classInfo));
                }
            }
        }

        // Write META-INF/vauban-beans.list
        writeBeansList(beans);

        return true;
    }

    private void loadEnrichmentConfig() {
        try {
            var resource = processingEnv.getFiler().getResource(
                    StandardLocation.CLASS_OUTPUT, "", PROPERTIES_FILE);
            try (var is = resource.openInputStream()) {
                enrichmentConfig = EnrichmentConfig.load(is);
                if (!enrichmentConfig.rules().isEmpty()) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                            "[Vauban] Loaded " + enrichmentConfig.rules().size()
                                    + " enrichment rules from " + PROPERTIES_FILE);
                }
            }
        } catch (IOException ignored) {
            // No properties file — enrichment disabled, this is expected
            enrichmentConfig = EnrichmentConfig.empty();
        }
    }

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
