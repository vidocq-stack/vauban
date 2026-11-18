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
package io.vidocq.vauban.core.extensions;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.lang.annotation.Annotation;
import java.util.Set;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.LangModelAnnotations;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;

/**
 * Serializes and deserializes the <em>result</em> of {@code @Enhancement} phases — a patch
 * mapping each target class to the annotations the BCE added, with their member values.
 *
 * <p>Persisting this patch at build time lets the container apply the enhancement <strong>without
 * re-instantiating the BCE</strong> on the module path — which is what previously forced application
 * modules to {@code opens <pkg> to io.vidocq.vauban.core}.
 *
 * <p>Zero external dependencies — JDK only. The written form is sorted and timestamp-free
 * so builds stay reproducible (AOT/CDS friendly).
 *
 * <h2>Format</h2>
 * One line per enhanced class, the target's binary name, then its added annotations as frames of
 * {@link SyntheticParamCodec#encodeAnnotation}: a length, a colon, that many characters. Members are
 * kept, so an added {@code @Named("x")} or a qualifier member survives build time (BUG-20261008-05).
 * <pre>
 * com.example.HelloResource=69:39:jakarta.enterprise.context.RequestScoped...
 * </pre>
 * The form earlier versions wrote — annotation names, comma-separated, members lost — still reads.
 * Member targets use {@code Class#field#name}, {@code Class#method#name(descriptor)} and
 * {@code Class#parameter#name(descriptor)#position}. Descriptors distinguish overloaded methods. A member the class
 * inherits, configured through it, has a kind of its own, the class that declares it last:
 * {@code Class#inheritedField#name#Declaring}, {@code Class#inheritedMethod#name(descriptor)#Declaring} and
 * {@code Class#inheritedParameter#name(descriptor)#position#Declaring} (BUG-20261118-48). An earlier Vauban reads no
 * such kind, and fails the deployment, where it would have read the member by its name alone (BUG-20261118-52).
 */
public final class EnhancementPatchSerializer {

    public static final String PATCH_PATH = "META-INF/vauban-enhancements.properties";

    private static final String FIELD = "field";
    private static final String METHOD = "method";
    private static final String PARAMETER = "parameter";
    private static final String INHERITED_FIELD = "inheritedField";
    private static final String INHERITED_METHOD = "inheritedMethod";
    private static final String INHERITED_PARAMETER = "inheritedParameter";

    private EnhancementPatchSerializer() {}

    /** The additions to classes, fields, methods and parameters, with every annotation member kept. */
    public static Map<String, List<AnnotationInfo>> additions(
            Map<DotName, List<VaubanClassConfig>> modifications) {
        var patch = new TreeMap<String, List<AnnotationInfo>>();
        modifications.forEach((name, configs) -> {
            for (var config : configs) {
                append(patch, name.value(), config.getAddedAnnotationsIndexed());
                for (var field : config.getFieldConfigs()) {
                    append(patch, target(name, config, field),
                            annotations(field.getAddedAnnotations(), field.getAddedAnnotationInfos(),
                                    field.getAddedAnnotationInstances()));
                }
                for (var method : config.getMethodConfigs()) {
                    append(patch, target(name, config, method), annotations(method.getAddedAnnotations(),
                            method.getAddedAnnotationInfos(), method.getAddedAnnotationInstances()));
                    for (int i = 0; i < method.getParameterConfigs().size(); i++) {
                        var parameter = method.getParameterConfigs().get(i);
                        append(patch, target(name, config, method, i),
                                annotations(parameter.getAddedAnnotationClasses(),
                                        parameter.getAddedAnnotations(), parameter.getAddedAnnotationInstances()));
                    }
                }
            }
        });
        return patch;
    }

    private static List<AnnotationInfo> annotations(Set<Class<? extends Annotation>> classes,
            List<jakarta.enterprise.lang.model.AnnotationInfo> infos, List<Annotation> instances) {
        var added = new LinkedHashMap<DotName, AnnotationInfo>();
        classes.forEach(type -> added.put(DotName.of(type.getName()),
                new AnnotationInfo(DotName.of(type.getName()), Map.of())));
        infos.forEach(info -> added.put(DotName.of(info.name()), LangModelAnnotations.toIndex(info)));
        instances.forEach(instance -> added.put(DotName.of(instance.annotationType().getName()),
                io.vidocq.vauban.core.annotation.AnnotationValues.infoOf(instance)));
        return List.copyOf(added.values());
    }

    private static void append(Map<String, List<AnnotationInfo>> patch, String target, List<AnnotationInfo> annotations) {
        if (annotations.isEmpty()) return;
        var added = patch.computeIfAbsent(target, _ -> new ArrayList<>());
        annotations.forEach(annotation -> {
            if (!added.contains(annotation)) added.add(annotation);
        });
    }

    /** The class owning a class or member patch entry. */
    public static DotName owner(DotName target) {
        int separator = target.value().indexOf('#');
        return separator < 0 ? target : DotName.of(target.value().substring(0, separator));
    }

    /**
     * Restores member additions for the descriptor enhancer; class additions are applied to the index.
     *
     * <p>The patch is the one of every archive the class loader sees, the index the classes of this
     * deployment: an entry of a class the index does not hold is left out, as its class additions are
     * (BUG-20261111-02). A member the patch names and the indexed class does not have still fails.
     */
    public static Map<DotName, List<VaubanClassConfig>> memberConfigurations(
            Map<DotName, List<AnnotationInfo>> patch, VaubanIndex index) {
        var configs = new LinkedHashMap<DotName, VaubanClassConfig>();
        var lookup = new IndexLookup(index);
        patch.forEach((target, annotations) -> {
            var parts = target.value().split("#", -1);
            if (parts.length == 1) return;
            var name = owner(target);
            var indexed = index.getClassByName(name);
            if (indexed.isEmpty()) return;
            var indexedClass = indexed.get();
            var config = configs.computeIfAbsent(name, _ -> new VaubanClassConfig(
                    new io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo(indexedClass, lookup)));
            restore(config, parts, target, annotations, lookup);
        });
        var result = new LinkedHashMap<DotName, List<VaubanClassConfig>>();
        configs.forEach((name, config) -> result.put(name, List.of(config)));
        return result;
    }

    /**
     * Adds {@code annotations} to the member of {@code config} the key split into {@code parts} names: one the class
     * configures itself, or, for an inherited kind, one the class that the key names last declares.
     */
    private static void restore(VaubanClassConfig config, String[] parts, DotName target,
            List<AnnotationInfo> annotations, IndexLookup lookup) {
        switch (parts[1]) {
            case FIELD -> add(field(config, parts[2], null, target), annotations, lookup);
            case INHERITED_FIELD -> add(field(config, parts[2], last(parts, 4, target), target), annotations, lookup);
            case METHOD -> add(method(config, parts[2], null, target), annotations, lookup);
            case INHERITED_METHOD -> add(method(config, parts[2], last(parts, 4, target), target), annotations,
                    lookup);
            case PARAMETER -> add(method(config, parts[2], null, target).getParameterConfigs()
                    .get(Integer.parseInt(parts[3])), annotations, lookup);
            case INHERITED_PARAMETER -> add(method(config, parts[2], last(parts, 5, target), target)
                    .getParameterConfigs().get(Integer.parseInt(parts[3])), annotations, lookup);
            default -> throw new IllegalStateException("Unknown enhancement target: " + target);
        }
    }

    private static void add(jakarta.enterprise.inject.build.compatible.spi.DeclarationConfig member,
            List<AnnotationInfo> annotations, IndexLookup lookup) {
        annotations.forEach(annotation -> member.addAnnotation(new VaubanAnnotationInfo(annotation, lookup)));
    }

    /** The class a key of {@code size} parts names last: the one that declares the inherited member. */
    private static String last(String[] parts, int size, DotName target) {
        if (parts.length != size) {
            throw new IllegalStateException("Unknown enhancement target: " + target);
        }
        return parts[size - 1];
    }

    private static VaubanFieldConfig field(VaubanClassConfig config, String name, String declaring, DotName target) {
        return config.getFieldConfigs().stream()
                .filter(candidate -> candidate.info().name().equals(name) && declaredBy(
                        candidate.info().declaringClass(), config.isConfigurable(candidate), declaring))
                .findFirst().orElseThrow(() -> new IllegalStateException("Enhanced field is missing: " + target));
    }

    private static VaubanMethodConfig method(VaubanClassConfig config, String key, String declaring, DotName target) {
        return config.getMethodConfigs().stream()
                .filter(candidate -> methodKey(candidate).equals(key) && declaredBy(
                        candidate.info().declaringClass(), config.isConfigurable(candidate), declaring))
                .findFirst().orElseThrow(() -> new IllegalStateException("Enhanced method is missing: " + target));
    }

    /**
     * The key of {@code field}, configured through the class {@code name}: of the {@code field} kind for a member the
     * class configures itself, as earlier versions wrote every key, else of the {@code inheritedField} kind, with the
     * class that declares it (BUG-20261118-48, BUG-20261118-52).
     */
    static String target(DotName name, VaubanClassConfig config, VaubanFieldConfig field) {
        return config.isConfigurable(field) ? name.value() + "#" + FIELD + "#" + field.info().name()
                : name.value() + "#" + INHERITED_FIELD + "#" + field.info().name() + "#"
                        + field.info().declaringClass().name();
    }

    /** {@link #target(DotName, VaubanClassConfig, VaubanFieldConfig)} for a method. */
    static String target(DotName name, VaubanClassConfig config, VaubanMethodConfig method) {
        return config.isConfigurable(method) ? name.value() + "#" + METHOD + "#" + methodKey(method)
                : name.value() + "#" + INHERITED_METHOD + "#" + methodKey(method) + "#"
                        + method.info().declaringClass().name();
    }

    /** {@link #target(DotName, VaubanClassConfig, VaubanFieldConfig)} for a method's parameter {@code position}. */
    static String target(DotName name, VaubanClassConfig config, VaubanMethodConfig method, int position) {
        return config.isConfigurable(method) ? name.value() + "#" + PARAMETER + "#" + methodKey(method) + "#" + position
                : inheritedParameterTarget(name.value(), method.info(), position);
    }

    /**
     * The key of the parameter {@code position} of {@code method}, which the class {@code name} inherits, configured
     * through that class (BUG-20261118-52).
     */
    static String inheritedParameterTarget(String name, jakarta.enterprise.lang.model.declarations.MethodInfo method,
            int position) {
        return name + "#" + INHERITED_PARAMETER + "#" + methodKey(method) + "#" + position + "#"
                + method.declaringClass().name();
    }

    /**
     * Whether a member matches the class a key names last: a member the class configures itself for a key without
     * one, else a member that class declares.
     */
    private static boolean declaredBy(jakarta.enterprise.lang.model.declarations.ClassInfo declaringClass,
            boolean configurable, String declaring) {
        return declaring == null ? configurable : !configurable && declaringClass.name().equals(declaring);
    }

    /**
     * A method by its name and its descriptor; a constructor by {@code <init>}, as an earlier Vauban wrote it, though
     * its {@code MethodInfo.name()} is its class's (BUG-20261117-54), so that an earlier frozen record stays valid.
     */
    static String methodKey(VaubanMethodConfig method) {
        return methodKey(method.info());
    }

    /** {@link #methodKey(VaubanMethodConfig)} of a method of the language model. */
    static String methodKey(jakarta.enterprise.lang.model.declarations.MethodInfo method) {
        var name = method.isConstructor() ? "<init>" : method.name();
        return name + InjectionPointInfo.methodDescriptor(method.parameters().stream()
                .map(parameter -> LangModelTypeMapper.toIndexType(parameter.type())).toList());
    }

    /**
     * Writes the patch, targets sorted. Targets with no added annotation are skipped.
     */
    public static void write(Map<String, List<AnnotationInfo>> patch, OutputStream os) throws IOException {
        Writer writer = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        writer.write("# Vauban @Enhancement patch — target binary name = added annotations, members included\n");
        writer.write("# Generated at build time; applied at runtime without re-instantiating the BCE.\n");
        for (var entry : new TreeMap<>(patch).entrySet()) {
            var annotations = entry.getValue();
            if (annotations == null || annotations.isEmpty()) continue;
            writer.write(PropertiesText.entry(entry.getKey(), SyntheticParamCodec.encodeAnnotations(annotations)));
        }
        writer.flush();
    }

    /**
     * Reads the patch into {@code target binary name -> added annotations}. The annotation order
     * within each target is preserved; the target iteration order is not significant.
     */
    public static Map<String, List<AnnotationInfo>> read(InputStream is) throws IOException {
        var props = new Properties();
        props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        var patch = new LinkedHashMap<String, List<AnnotationInfo>>();
        for (var name : props.stringPropertyNames()) {
            // The names-only form of earlier versions still reads.
            var annotations = SyntheticParamCodec.decodeAnnotations(props.getProperty(name));
            if (!annotations.isEmpty()) {
                patch.put(name, annotations);
            }
        }
        return patch;
    }
}
