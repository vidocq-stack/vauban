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
import java.io.StringWriter;
import java.io.UncheckedIOException;
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
 * {@code Class#parameter#name(descriptor)#position}. Descriptors distinguish overloaded methods.
 */
public final class EnhancementPatchSerializer {

    public static final String PATCH_PATH = "META-INF/vauban-enhancements.properties";

    private EnhancementPatchSerializer() {}

    /** The additions to classes, fields, methods and parameters, with every annotation member kept. */
    public static Map<String, List<AnnotationInfo>> additions(
            Map<DotName, List<VaubanClassConfig>> modifications) {
        var patch = new TreeMap<String, List<AnnotationInfo>>();
        modifications.forEach((name, configs) -> {
            for (var config : configs) {
                append(patch, name.value(), config.getAddedAnnotationsIndexed());
                for (var field : config.getFieldConfigs()) {
                    append(patch, name.value() + "#field#" + field.info().name(),
                            annotations(field.getAddedAnnotations(), field.getAddedAnnotationInfos(),
                                    field.getAddedAnnotationInstances()));
                }
                for (var method : config.getMethodConfigs()) {
                    var key = name.value() + "#method#" + methodKey(method);
                    append(patch, key, annotations(method.getAddedAnnotations(),
                            method.getAddedAnnotationInfos(), method.getAddedAnnotationInstances()));
                    for (int i = 0; i < method.getParameterConfigs().size(); i++) {
                        var parameter = method.getParameterConfigs().get(i);
                        append(patch, name.value() + "#parameter#" + methodKey(method) + "#" + i,
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

    /** Restores member additions for the descriptor enhancer; class additions are applied to the index. */
    public static Map<DotName, List<VaubanClassConfig>> memberConfigurations(
            Map<DotName, List<AnnotationInfo>> patch, VaubanIndex index) {
        var configs = new LinkedHashMap<DotName, VaubanClassConfig>();
        var lookup = new IndexLookup(index);
        patch.forEach((target, annotations) -> {
            var parts = target.value().split("#", -1);
            if (parts.length == 1) return;
            var name = owner(target);
            var indexedClass = index.getClassByName(name).orElseThrow(() ->
                    new IllegalStateException("Enhanced class is missing from the index: " + name));
            var config = configs.computeIfAbsent(name, _ -> new VaubanClassConfig(
                    new io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo(indexedClass, lookup)));
            if (parts[1].equals("field")) {
                var field = config.getFieldConfigs().stream().filter(candidate -> candidate.info().name().equals(parts[2]))
                        .findFirst().orElseThrow(() -> new IllegalStateException("Enhanced field is missing: " + target));
                annotations.forEach(annotation -> field.addAnnotation(new VaubanAnnotationInfo(annotation, lookup)));
            } else {
                var method = config.getMethodConfigs().stream().filter(candidate -> methodKey(candidate).equals(parts[2]))
                        .findFirst().orElseThrow(() -> new IllegalStateException("Enhanced method is missing: " + target));
                if (parts[1].equals("method")) {
                    annotations.forEach(annotation -> method.addAnnotation(new VaubanAnnotationInfo(annotation, lookup)));
                } else if (parts[1].equals("parameter")) {
                    var parameter = method.getParameterConfigs().get(Integer.parseInt(parts[3]));
                    annotations.forEach(annotation -> parameter.addAnnotation(new VaubanAnnotationInfo(annotation, lookup)));
                } else {
                    throw new IllegalStateException("Unknown enhancement target: " + target);
                }
            }
        });
        var result = new LinkedHashMap<DotName, List<VaubanClassConfig>>();
        configs.forEach((name, config) -> result.put(name, List.of(config)));
        return result;
    }

    private static String methodKey(VaubanMethodConfig method) {
        return method.info().name() + InjectionPointInfo.methodDescriptor(method.info().parameters().stream()
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
            var value = new StringBuilder();
            for (var annotation : annotations) {
                var encoded = SyntheticParamCodec.encodeAnnotation(annotation);
                value.append(encoded.length()).append(':').append(encoded);
            }
            writer.write(line(entry.getKey(), value.toString()));
        }
        writer.flush();
    }

    /** One {@code key=value} line, escaped as {@link Properties#store} escapes it. */
    private static String line(String key, String value) {
        var one = new Properties();
        one.setProperty(key, value);
        var out = new StringWriter();
        try {
            one.store(out, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var result = new StringBuilder();
        for (var stored : out.toString().split("\n")) {
            if (!stored.startsWith("#")) result.append(stored.strip()).append('\n');
        }
        return result.toString();
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
            var annotations = parse(props.getProperty(name).strip());
            if (!annotations.isEmpty()) {
                patch.put(name, annotations);
            }
        }
        return patch;
    }

    private static List<AnnotationInfo> parse(String value) {
        var out = new ArrayList<AnnotationInfo>();
        if (value.isEmpty()) return out;
        if (!Character.isDigit(value.charAt(0))) {
            // The form of earlier versions: names only, comma-separated.
            for (var part : value.split(",")) {
                var stripped = part.strip();
                if (!stripped.isEmpty()) out.add(new AnnotationInfo(DotName.of(stripped), Map.of()));
            }
            return out;
        }
        int position = 0;
        while (position < value.length()) {
            int colon = value.indexOf(':', position);
            int start = colon + 1;
            int end = start + Integer.parseInt(value.substring(position, colon));
            out.add(SyntheticParamCodec.decodeAnnotation(value.substring(start, end)));
            position = end;
        }
        return out;
    }
}
