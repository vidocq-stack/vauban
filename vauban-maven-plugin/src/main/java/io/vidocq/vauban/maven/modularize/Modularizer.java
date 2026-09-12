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
package io.vidocq.vauban.maven.modularize;

import io.vidocq.vauban.maven.module.ModuleAnalysisResult;
import io.vidocq.vauban.maven.module.ModuleAnalyzer;
import io.vidocq.vauban.maven.module.ModuleDescriptorSynthesizer;
import io.vidocq.vauban.maven.module.SplitPackage;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Patches non-modular dependency jars with a synthesized {@code module-info}: {@code requires}
 * derived from the types each jar uses, every package exported, {@code META-INF/services} promoted
 * to {@code provides}, and the {@code uses} this build scanned for. The descriptor itself is built
 * by {@link ModuleDescriptorSynthesizer}, with the JDK alone. Output goes to
 * {@code target/vauban-modularized/} under the ORIGINAL file name so
 * {@link ModularizedJars#resolve} substitutes it.
 */
public final class Modularizer {

    /** Which automatic jars are patched. */
    public enum Mode {
        /** Only jars whose automatic name is derived from the file name (default). */
        DERIVED,
        /** Every automatic jar, including those with an {@code Automatic-Module-Name} (jlink needs this). */
        ALL_AUTOMATIC
    }

    /**
     * Tuning knobs, all mirrored 1:1 by the mojo parameters.
     *
     * @param forceExplicit patch a jar even when its own {@code ServiceLoader} lookups cannot be
     *                      declared legally (the illegal directives are dropped either way). Off by
     *                      default: such a jar has no working explicit form — see {@link UsesLegality}.
     */
    public record Options(Mode mode, Set<String> includeArtifactIds, Set<String> excludeArtifactIds,
                          Map<String, String> moduleNames, boolean openModules,
                          boolean forceExplicit) {}

    /** What was patched, what was left alone, and a human-readable report. */
    public record Result(List<Path> patched, List<Path> skipped, String report) {}

    /** {@code <artifactId>-<version>.jar} → {@code <version>}. */
    private static final Pattern VERSION_IN_FILE_NAME = Pattern.compile("-(\\d[^/]*?)\\.jar$");

    /** Name of the human-readable summary dropped next to the patched jars. */
    public static final String REPORT_FILE_NAME = "report.txt";

    /**
     * Prefix of the report line naming a jar this goal deliberately left automatic. Public because
     * {@code vidocq:jlink} reads those lines back to explain its own failure with the reason
     * recorded here instead of advising a re-run that cannot help.
     */
    public static final String KEPT_AUTOMATIC_PREFIX = "kept automatic: ";

    private Modularizer() {}

    /**
     * Patches the automatic jars of {@code closure} selected by {@code options}.
     *
     * <p>The output directory is emptied first, so a jar patched by an earlier run with
     * different includes/excludes can never linger and be silently substituted.
     *
     * @param closure         every jar of the dependency closure, patched or not: jdeps needs
     *                        the whole set to resolve {@code requires}
     * @param artifactIdByJar artifact id per jar, used by the include/exclude filters and the
     *                        {@code moduleNames} overrides; the file name is used as a fallback
     * @param buildDir        the project {@code target/} directory
     * @param log             receives one line per patched jar, plus what the synthesis reports
     * @throws IllegalStateException if two automatic jars of the closure share a package
     * @throws IOException           if a jar cannot be read, or holds no class in any package
     */
    public static Result run(List<Path> closure, Map<Path, String> artifactIdByJar, Path buildDir,
                             Options options, Consumer<String> log) throws IOException {
        // 1. start from a clean output directory: a jar left over from a previous run with
        //    other includes/excludes would still be substituted by ModularizedJars.resolve.
        Path outDir = ModularizedJars.root(buildDir);
        Files.createDirectories(outDir);
        deleteFilesIn(outDir);

        // 2. classify
        List<JarModuleInfo> infos = new ArrayList<>();
        for (Path jar : closure) {
            infos.add(JarModuleClassifier.classify(jar));
        }

        // 3. select
        Selection selection = select(infos, artifactIdByJar, options);

        // 4. split-package guard over the automatic part of the closure
        assertNoSplitPackage(infos);

        // 5. generate every candidate descriptor, before writing any of them
        Generated generated = generateAll(closure, selection.selected(), artifactIdByJar, options, log);

        // 6. keep only the `uses` the module system will accept, demote what has none it may declare
        Legality legality = resolveLegality(closure, generated, options);

        // 7. write the survivors
        StringBuilder report = new StringBuilder("# vauban:modularize report\n");
        List<Path> patched = write(legality, generated, closure, options, outDir, report, log);

        // 8. account for everything that was left alone, with the reason when there is one
        List<Path> allSkipped = report(selection, legality, generated, report, log);

        Files.writeString(outDir.resolve(REPORT_FILE_NAME), report.toString());
        return new Result(patched, allSkipped, report.toString());
    }

    /** Phase 3: the jars {@code options} wants patched, and everything it leaves alone. */
    private record Selection(List<JarModuleInfo> selected, List<Path> skipped) {}

    /** Phase 5: one generated descriptor, module name and service scan per selected jar. */
    private record Generated(Map<Path, JarModuleInfo> infoByJar, Map<Path, String> moduleNames,
                             Map<Path, ModuleDescriptor> descriptors,
                             Map<Path, Set<String>> scannedServices) {}

    /** Phase 6: what may still be patched, the surviving {@code uses}, and what was demoted. */
    private record Legality(Set<Path> candidates, UsesLegality.Verdict verdict,
                            UsesLegality.Verdict firstPass, List<Path> demoted) {}

    /** Phase 3: applies the mode and the include/exclude filters to the classified closure. */
    private static Selection select(List<JarModuleInfo> infos, Map<Path, String> artifactIdByJar,
                                    Options options) {
        List<JarModuleInfo> selected = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();
        for (JarModuleInfo info : infos) {
            String artifactId = artifactIdOf(artifactIdByJar, info.jar());
            boolean wanted = switch (info.kind()) {
                case EXPLICIT -> false;
                case AUTOMATIC_DERIVED -> true;
                case AUTOMATIC_NAMED -> options.mode() == Mode.ALL_AUTOMATIC;
            };
            // Both filters only ever restrict: an explicit module named in <includes> stays
            // untouched, patching it would overwrite a descriptor its author wrote by hand.
            if (!options.includeArtifactIds().isEmpty()) {
                wanted &= options.includeArtifactIds().contains(artifactId);
            }
            if (options.excludeArtifactIds().contains(artifactId)) {
                wanted = false;
            }
            if (wanted) {
                selected.add(info);
            } else {
                skipped.add(info.jar());
            }
        }
        return new Selection(selected, skipped);
    }

    /**
     * Phase 5: synthesizes a descriptor for every selected jar and scans the closure for {@code uses}.
     * Nothing is written to the output directory yet — which jars end up explicit decides what the
     * others may legally declare, so the whole set has to exist on paper before any of it is real.
     */
    private static Generated generateAll(List<Path> closure, List<JarModuleInfo> selected,
                                         Map<Path, String> artifactIdByJar, Options options,
                                         Consumer<String> log) throws IOException {
        // Built once over the whole closure: a service-lookup helper is regularly in another jar
        // than the code that names the service type (langchain4j-core's ServiceHelper, called
        // from langchain4j), so the call graph has to be closure-wide to be seen whole. Reading
        // every class of the closure is not worth it when nothing is going to be patched.
        ServiceUsesScanner usesScanner = selected.isEmpty()
                ? ServiceUsesScanner.over(List.of(), log)
                : ServiceUsesScanner.over(closure, log);
        Map<Path, JarModuleInfo> infoByJar = new LinkedHashMap<>();
        Map<Path, String> moduleNames = new LinkedHashMap<>();
        Map<Path, ModuleDescriptor> descriptors = new LinkedHashMap<>();
        Map<Path, Set<String>> scannedServices = new LinkedHashMap<>();
        for (JarModuleInfo info : selected) {
            String artifactId = artifactIdOf(artifactIdByJar, info.jar());
            String name = options.moduleNames().getOrDefault(artifactId, info.moduleName());
            // Synthesized without any `uses` yet: which of them may legally be declared depends on
            // the `requires` this very pass derives, so the directives are only known once the whole
            // post-patch graph exists. Phase 7 synthesizes again with the legal ones.
            descriptors.put(info.jar(), synthesize(info.jar(), name, closure, options, Set.of(), log)
                    .descriptor());
            infoByJar.put(info.jar(), info);
            moduleNames.put(info.jar(), name);
            scannedServices.put(info.jar(), usesScanner.scan(info.jar()));
        }
        return new Generated(infoByJar, moduleNames, descriptors, scannedServices);
    }

    /**
     * One descriptor, with the notes the synthesis produced routed to the build log — a package no
     * module of the closure owns, a service file naming a provider the jar does not contain.
     */
    private static ModuleDescriptorSynthesizer.Result synthesize(
            Path jar, String moduleName, List<Path> closure, Options options, Set<String> uses,
            Consumer<String> log) throws IOException {
        ModuleDescriptorSynthesizer.Result result = ModuleDescriptorSynthesizer.synthesize(
                new ModuleDescriptorSynthesizer.Request(jar, moduleName, options.openModules(),
                        closure, uses, versionOf(jar)));
        result.notes().forEach(log);
        return result;
    }

    /**
     * Phase 6: keeps only the {@code uses} the module system will accept, and demotes back to
     * automatic the jars left with a lookup they cannot legally declare.
     */
    private static Legality resolveLegality(List<Path> closure, Generated generated,
                                            Options options) {
        Map<Path, ModuleDescriptor> descriptors = generated.descriptors();
        Map<Path, Set<String>> scannedServices = generated.scannedServices();
        Set<Path> candidates = new LinkedHashSet<>(descriptors.keySet());
        UsesLegality.Verdict verdict =
                UsesLegality.of(closure, candidates, descriptors).check(scannedServices);
        // Kept for the demotion messages: the second pass no longer analyses the demoted jars.
        UsesLegality.Verdict firstPass = verdict;
        List<Path> demoted = new ArrayList<>();
        if (!options.forceExplicit()) {
            for (Path jar : candidates) {
                if (verdict.hasUnreadableDrop(jar)) {
                    demoted.add(jar);
                }
            }
        }
        if (!demoted.isEmpty()) {
            candidates.removeAll(demoted);
            demoted.forEach(scannedServices::remove);
            // One extra pass is enough: an automatic module reads every module and exports every
            // package, so demoting a jar only ever relaxes the constraints on the others. No new
            // drop — and therefore no new demotion — can appear in the second pass. What makes that
            // airtight is that jdeps emits plain `requires` only: no still-patched jar reaches its
            // exporter *through* a demoted one, so demotion cannot take readability away. Should a
            // generated descriptor ever carry `requires transitive`, iterate to a fixed point.
            verdict = UsesLegality.of(closure, candidates, descriptors).check(scannedServices);
        }
        return new Legality(candidates, verdict, firstPass, demoted);
    }

    /** Phase 7: writes one patched jar per surviving candidate and appends its descriptor. */
    private static List<Path> write(Legality legality, Generated generated, List<Path> closure,
                                    Options options, Path outDir, StringBuilder report,
                                    Consumer<String> log) throws IOException {
        List<Path> patched = new ArrayList<>();
        for (Path jar : legality.candidates()) {
            JarModuleInfo info = generated.infoByJar().get(jar);
            String name = generated.moduleNames().get(jar);
            // An automatic module may consume any service; an explicit one may only consume what
            // it declares. Without these directives the very jar we just promoted fails its own
            // lookup with "module … does not declare 'uses'". They are known only now, which is
            // why the descriptor is synthesized a second time — same inputs, plus the legal `uses`.
            Set<String> uses = legality.verdict().legal().getOrDefault(jar, Set.of());
            ModuleDescriptorSynthesizer.Result synthesized =
                    synthesize(jar, name, closure, options, uses, log);
            Path out = ModuleDescriptorSynthesizer.writeJarWithDescriptor(
                    jar, synthesized.moduleInfo(), outDir);
            patched.add(out);
            report.append("\n## ").append(jar.getFileName()).append(" → module ").append(name)
                    .append(" (").append(info.kind()).append(")\n")
                    .append(render(synthesized.descriptor())).append('\n');
            log.accept("modularized " + jar.getFileName() + " as " + name);
        }
        return patched;
    }

    /**
     * The descriptor as a reader expects to see it. The report is read by humans deciding whether a
     * patched jar is right, so it shows module-info source rather than a {@code toString}.
     */
    private static String render(ModuleDescriptor descriptor) {
        StringBuilder sb = new StringBuilder();
        sb.append(descriptor.isOpen() ? "open module " : "module ").append(descriptor.name());
        descriptor.rawVersion().ifPresent(v -> sb.append("@").append(v));
        sb.append(" {\n");
        descriptor.requires().stream()
                .sorted(java.util.Comparator.comparing(ModuleDescriptor.Requires::name))
                .forEach(r -> {
                    sb.append("    requires ");
                    if (r.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)) {
                        sb.append("transitive ");
                    }
                    if (r.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)) {
                        sb.append("static ");
                    }
                    sb.append(r.name()).append(";\n");
                });
        descriptor.exports().stream().map(ModuleDescriptor.Exports::source).sorted()
                .forEach(p -> sb.append("    exports ").append(p).append(";\n"));
        descriptor.opens().stream().map(ModuleDescriptor.Opens::source).sorted()
                .forEach(p -> sb.append("    opens ").append(p).append(";\n"));
        descriptor.uses().stream().sorted()
                .forEach(u -> sb.append("    uses ").append(u).append(";\n"));
        descriptor.provides().stream()
                .sorted(java.util.Comparator.comparing(ModuleDescriptor.Provides::service))
                .forEach(p -> sb.append("    provides ").append(p.service()).append(" with ")
                        .append(String.join(", ", p.providers())).append(";\n"));
        return sb.append("}\n").toString();
    }

    /**
     * Phase 8: accounts for everything that was left alone, with the reason when there is one.
     *
     * @return every jar of the closure this run did not patch
     */
    private static List<Path> report(Selection selection, Legality legality, Generated generated,
                                     StringBuilder report, Consumer<String> log) {
        List<Path> demoted = legality.demoted();
        List<Path> allSkipped = new ArrayList<>(selection.skipped());
        allSkipped.addAll(demoted);
        for (Path jar : demoted) {
            List<UsesLegality.Drop> blocking = legality.firstPass().unreadableDrops(jar);
            String message = KEPT_AUTOMATIC_PREFIX + jar.getFileName() + " — ServiceLoader of "
                    + blocking.get(0).service() + " from " + generated.moduleNames().get(jar)
                    + " cannot be declared (module cycle); jlink will reject it, dev mode works";
            report.append('\n').append(message).append('\n');
            log.accept("WARN " + message);
            // One jar regularly loses several services the same way; naming only the first would
            // hide how much of its lookup surface the cycle costs.
            for (UsesLegality.Drop drop : blocking) {
                String detail = "dropped uses: " + jar.getFileName() + " — " + drop.service()
                        + " (" + drop.reason() + ")";
                report.append(detail).append('\n');
                log.accept("WARN " + detail);
            }
        }
        for (UsesLegality.Drop drop : legality.verdict().drops()) {
            String message = "dropped uses: " + drop.jar().getFileName() + " — " + drop.service()
                    + " (" + drop.reason() + ")";
            report.append('\n').append(message).append('\n');
            log.accept("WARN " + message);
        }
        for (Path s : selection.skipped()) {
            report.append("\nskipped: ").append(s.getFileName()).append('\n');
        }
        return allSkipped;
    }

    /**
     * Two automatic jars sharing a package cannot both become named modules, and the
     * module system would reject them side by side anyway — fail early with the names.
     */
    private static void assertNoSplitPackage(List<JarModuleInfo> infos) throws IOException {
        List<ModuleAnalysisResult> analyses = new ArrayList<>();
        for (JarModuleInfo info : infos) {
            if (info.isAutomatic()) {
                analyses.add(ModuleAnalyzer.analyze(info.jar()));
            }
        }
        List<SplitPackage> splits = ModuleAnalyzer.detectSplitPackages(analyses);
        if (splits.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("vauban:modularize — split package(s) between dependency jars, "
                + "the module system cannot host them together:\n");
        for (SplitPackage sp : splits) {
            sb.append("  ").append(sp.packageName()).append(" in ")
                    .append(String.join(", ", sp.jarFiles())).append('\n');
        }
        // Not something <excludes> can silence: the guard covers the whole automatic closure,
        // and leaving one side unpatched would not make the packages any less split.
        sb.append("Remove one side from the dependency graph (Maven <exclusions>) "
                + "or wait for an upstream fix.");
        throw new IllegalStateException(sb.toString());
    }

    /** The configured artifact id for {@code jar}, falling back to its file name. */
    private static String artifactIdOf(Map<Path, String> artifactIdByJar, Path jar) {
        return artifactIdByJar.getOrDefault(jar, jar.getFileName().toString());
    }

    /** Empties {@code dir} of its regular files (patched jars and the previous report). */
    private static void deleteFilesIn(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path p : entries.filter(Files::isRegularFile).toList()) {
                Files.delete(p);
            }
        }
    }

    /** The module version to stamp, read off the file name; {@code null} when there is none. */
    private static String versionOf(Path jar) {
        Matcher m = VERSION_IN_FILE_NAME.matcher(jar.getFileName().toString());
        return m.find() ? m.group(1) : null;
    }
}
