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
package io.vidocq.vauban.processor;

import io.vidocq.vauban.indexer.codegen.ComponentCollector;
import io.vidocq.vauban.indexer.codegen.FieldInject;
import io.vidocq.vauban.indexer.codegen.PackageProvider;
import io.vidocq.vauban.indexer.codegen.ProvidedClass;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.processor.codegen.interceptor.InterceptedShapeFromElements; import io.vidocq.vauban.processor.codegen.interceptor.InterceptedSourceRenderer;
import io.vidocq.vauban.processor.codegen.provider.AnnotationArtefacts;
import io.vidocq.vauban.processor.codegen.provider.ComponentProviderGenerator;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The {@code <pkg>._VaubanComponents} providers the processor generates, one per package, and the class-path
 * service file listing them.
 */
final class ComponentProviderArtefacts {

    private static final String LOG_PREFIX = "[Vauban] ";

    private final ProcessingEnvironment processingEnv;
    private final GeneratedOutput output;
    private final TypeLookup typeLookup;
    /** Whether a package's provider is a test round's (vauban#101). */
    private final Predicate<String> isTestRound;

    ComponentProviderArtefacts(ProcessingEnvironment processingEnv, GeneratedOutput output, TypeLookup typeLookup,
            Predicate<String> isTestRound) {
        this.processingEnv = processingEnv;
        this.output = output;
        this.typeLookup = typeLookup;
        this.isTestRound = isTestRound;
    }

    /**
     * Emits ONE _VaubanComponents per package that has beans (co-located so each can reach its
     * own package's package-private members with a plain new/putfield/invoke), and lists them all
     * in the class-path service file. A single common-package provider could not reach
     * package-private members in other packages.
     */
    void writeAll(Generation generation, Map<String, List<ClassInfo>> annotationsByPackage) {
        // Delegate extraction + per-package grouping to ComponentCollector.
        // It performs instantiableCtorParams, collectFields, collectMethods and groups by package.
        var collectorWarnings = new ArrayList<String>();
        var packages = ComponentCollector.collect(generation.providedClasses, collectorWarnings);
        for (var w : collectorWarnings) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, LOG_PREFIX + w);
        }

        var providerClassNames = new ArrayList<String>();
        var emittedProviderPackages = new HashSet<String>();
        var producerProxiesByPackage = generation.producerProxiesByPackage;
        for (var pkg : packages) {
            var fields = fieldsWithPublicMutables(pkg, generation.providedClasses);
            // Proxies of this package's beans (their FQN package equals the provider's package).
            // Every input in a stable order: the beans, so their proxies, come in index order, which
            // changes from one JVM run to the next (BUG-20261007-04).
            var pkgProxies = generation.clientProxyFqns.stream()
                    .filter(p -> TypeLookup.packageOfFqn(p).equals(pkg.packageName()))
                    .sorted()
                    .toList();
            var pkgProducerProxies = inStableOrder(
                    producerProxiesByPackage.getOrDefault(pkg.packageName(), List.of()));
            var className = writeComponentProvider(
                    pkg.packageName(), new ComponentProviderGenerator.Contents(pkg.components(), fields,
                            pkg.methods(), pkgProxies, pkgProducerProxies,
                            renderAnnotations(annotationsByPackage.getOrDefault(pkg.packageName(), List.of())),
                            output.bytecodeClasses()));
            if (className != null) providerClassNames.add(className);
            emittedProviderPackages.add(pkg.packageName());
        }
        // A package with no in-module component of its own still needs a provider when it holds a
        // produced-type proxy (issue #42) or declares an annotation type (vauban#70).
        var remainingPackages = new TreeSet<>(producerProxiesByPackage.keySet());
        remainingPackages.addAll(annotationsByPackage.keySet());
        remainingPackages.removeAll(emittedProviderPackages);
        for (var pkg : remainingPackages) {
            var className = writeComponentProvider(pkg, new ComponentProviderGenerator.Contents(
                    List.of(), List.of(), List.of(), List.of(),
                    inStableOrder(producerProxiesByPackage.getOrDefault(pkg, List.of())),
                    renderAnnotations(annotationsByPackage.getOrDefault(pkg, List.of())),
                    output.bytecodeClasses()));
            if (className != null) providerClassNames.add(className);
        }
        if (!providerClassNames.isEmpty()) {
            writeComponentProviderService(providerClassNames);
        }
    }

    /** What {@code types} declare, rendered for a provider, in a stable order. */
    private AnnotationArtefacts.Rendered renderAnnotations(List<ClassInfo> types) {
        return AnnotationArtefacts.render(annotationTypesInStableOrder(types), typeLookup::canonicalName);
    }

    /**
     * The fields the provider of {@code pkg} assigns: those the collector found, plus the public
     * mutable reference fields of the package's provided classes. This is write capability, not
     * injection metadata: a runtime BCE may add {@code @Inject}.
     */
    private List<FieldInject> fieldsWithPublicMutables(PackageProvider pkg,
            List<ProvidedClass> providedClasses) {
        var fields = new ArrayList<>(pkg.fields());
        for (var provided : providedClasses.stream()
                .sorted(Comparator.comparing(ProvidedClass::fqn)).toList()) {
            var owner = TypeLookup.typeElementByBinaryName(processingEnv.getElementUtils(), provided.fqn());
            if (coveredByProvider(owner, provided, pkg)) {
                addPublicMutableFields(owner, provided, fields);
            }
        }
        return fields;
    }

    /** Whether {@code owner}, the type of {@code provided}, belongs to the provider of {@code pkg}. */
    private boolean coveredByProvider(TypeElement owner, ProvidedClass provided,
            PackageProvider pkg) {
        if (owner == null || !processingEnv.getElementUtils().getPackageOf(owner)
                .getQualifiedName().contentEquals(pkg.packageName())) {
            return false;
        }
        // Nested owners need a canonical source name in the provider's component table.
        return provided.fqn().equals(provided.sourceFqn())
                || pkg.components().stream().anyMatch(c -> c.fqn().equals(provided.fqn()));
    }

    /** Adds the public, non-static, non-final reference fields of {@code owner} that {@code fields} lacks. */
    private void addPublicMutableFields(TypeElement owner, ProvidedClass provided, List<FieldInject> fields) {
        for (var field : ElementFilter.fieldsIn(owner.getEnclosedElements())) {
            var modifiers = field.getModifiers();
            if (modifiers.contains(Modifier.PUBLIC) && !modifiers.contains(Modifier.STATIC)
                    && !modifiers.contains(Modifier.FINAL) && !field.asType().getKind().isPrimitive()) {
                var erasure = processingEnv.getTypeUtils().erasure(field.asType());
                var name = field.getSimpleName().toString();
                // The field's own declaration names this type, so javac has already checked that
                // the module reads it. Re-checking readability would complete the module being
                // compiled and resolve its `provides` before this provider exists.
                if (InterceptedShapeFromElements.nameableFrom(erasure,
                        processingEnv.getElementUtils().getPackageOf(owner),
                        processingEnv.getElementUtils(), false)
                        && fields.stream().noneMatch(f -> f.declaringClassFqn().equals(provided.fqn())
                                && f.fieldName().equals(name))) {
                    fields.add(new FieldInject(provided.fqn(), name, erasure.toString()));
                }
            }
        }
    }

    /**
     * Whether the note on a generated provider tells the module to declare it with {@code provides}: unless
     * {@code -Avauban.providesHint=false}, for a runtime that turns {@code META-INF/services} into
     * {@code provides} itself (VAU-APT-004).
     */
    private boolean providesHint() {
        return !"false".equalsIgnoreCase(processingEnv.getOptions().getOrDefault("vauban.providesHint", "true").strip());
    }

    /**
     * Generates the {@code <pkg>._VaubanComponents} provider for one package's beans (in-module
     * instantiation, field injection and method invocation) and returns its fully-qualified name so
     * the caller can list it (with the others) in the single class-path service file. The fields and
     * methods passed in already belong to {@code pkg} (the caller groups by package), so no filtering
     * is needed — a co-located provider can reach its own package's package-private members.
     *
     * <p>Always emitted as readable Java <strong>source</strong>. A package with an intercepted bean
     * has a {@code <bean>$$Intercepted} component; because that subclass is now itself generated as
     * source (by {@link InterceptedSourceRenderer}), the provider source can reference it by name —
     * javac resolves both in a later round. (Previously the provider had to be bytecode for such
     * packages, since a generated source could not see a Filer-emitted {@code $$Intercepted}.)
     */
    private String writeComponentProvider(String pkg, ComponentProviderGenerator.Contents contents) {
        boolean testRound = isTestRound.test(pkg);
        var gen = ComponentProviderGenerator.generateFrom(pkg,
                testRound ? ComponentProviderGenerator.TEST_SIMPLE_NAME : ComponentProviderGenerator.SIMPLE_NAME,
                contents);
        try {
            var file = processingEnv.getFiler().createSourceFile(gen.className());
            output.recordWritten(gen.className());
            try (var w = file.openWriter()) {
                w.write(gen.source());
            }
            String advice;
            if (testRound) {
                advice = " Test compilation: the container finds it through the main sources' "
                        + ComponentProviderGenerator.SIMPLE_NAME + ".";
            } else if (providesHint()) {
                advice = " On the module path, add to module-info: provides "
                        + "io.vidocq.vauban.api.VaubanComponentProvider with " + gen.className() + ";";
            } else {
                advice = "";
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "[Vauban] Generated " + gen.className() + " (" + contents.components().size() + " component(s), "
                            + contents.fieldInjects().size() + " field(s), " + contents.methodInvokes().size()
                            + " method(s))." + advice);
            return gen.className();
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write component provider: " + e.getMessage());
            return null;
        }
    }

    /** {@code proxies} by produced-type key, then proxy name (BUG-20261007-04). */
    private static List<ComponentProviderGenerator.ProducerProxy> inStableOrder(
            List<ComponentProviderGenerator.ProducerProxy> proxies) {
        return proxies.stream()
                .sorted(Comparator.comparing(ComponentProviderGenerator.ProducerProxy::key)
                        .thenComparing(ComponentProviderGenerator.ProducerProxy::proxyFqn))
                .toList();
    }

    /** {@code types} by binary name: they come in index order (BUG-20261007-04). */
    private static List<ClassInfo> annotationTypesInStableOrder(List<ClassInfo> types) {
        return types.stream().sorted(Comparator.comparing(c -> c.name().value())).toList();
    }

    /** Class-path fallback registration (ignored for named modules, which use {@code provides}). */
    private void writeComponentProviderService(List<String> providerClassNames) {
        // The test round's provider is found through the main one (vauban#101): listing it here as well would
        // register it twice on the class path.
        providerClassNames = providerClassNames.stream()
                .filter(name -> !name.endsWith("." + ComponentProviderGenerator.TEST_SIMPLE_NAME)
                        && !name.equals(ComponentProviderGenerator.TEST_SIMPLE_NAME))
                .toList();
        if (providerClassNames.isEmpty()) {
            return;
        }
        try {
            var resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/services/io.vidocq.vauban.api.VaubanComponentProvider");
            try (var w = new PrintWriter(resource.openOutputStream(), false, StandardCharsets.UTF_8)) {
                for (var name : providerClassNames.stream().sorted().toList()) w.println(name);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Vauban] Failed to write component provider service file: " + e.getMessage());
        }
    }
}
