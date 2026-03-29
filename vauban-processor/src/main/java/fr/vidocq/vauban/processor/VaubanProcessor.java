package fr.vidocq.vauban.processor;

import fr.vidocq.vauban.processor.apt.ElementScanner;
import fr.vidocq.vauban.processor.codegen.GeneratedClass;
import fr.vidocq.vauban.processor.codegen.factory.BeanFactoryGenerator;
import fr.vidocq.vauban.processor.codegen.proxy.ClientProxyGenerator;
import fr.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import fr.vidocq.vauban.core.bean.model.BeanDescriptor;
import fr.vidocq.vauban.core.bean.resolution.BeanResolver;
import fr.vidocq.vauban.core.bean.validation.DeploymentValidator;
import fr.vidocq.vauban.core.types.AssignabilityRules;
import fr.vidocq.vauban.indexer.IndexBuilder;
import fr.vidocq.vauban.indexer.model.ClassInfo;
import fr.vidocq.vauban.indexer.model.DotName;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import java.io.*;
import java.util.*;

@SupportedAnnotationTypes({
    "jakarta.enterprise.context.ApplicationScoped",
    "jakarta.enterprise.context.RequestScoped",
    "jakarta.enterprise.context.Dependent",
    "jakarta.inject.Singleton",
    "jakarta.enterprise.inject.Produces"
})
public class VaubanProcessor extends AbstractProcessor {

    private boolean processed = false;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
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

        return true;
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
