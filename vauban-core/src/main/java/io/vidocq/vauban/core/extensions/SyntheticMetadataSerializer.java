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

import java.io.*;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Serializes and deserializes synthetic bean/observer metadata to a properties-based
 * text format. Zero external dependencies — uses only JDK APIs.
 *
 * <h2>Format</h2>
 * <pre>
 * bean.0.beanClass=com.example.MyBean
 * bean.0.creator=com.example.MyCreator
 * bean.0.scope=jakarta.enterprise.context.ApplicationScoped
 * bean.0.types=com.example.MyBean,java.lang.Object
 * bean.0.param.key=S:hello
 * observer.0.eventType=com.example.MyEvent
 * observer.0.observer=com.example.MyObserver
 * </pre>
 *
 * <h2>Param encoding</h2>
 * Values are prefixed with a type tag:
 * {@code S:} (String), {@code I:} (int), {@code L:} (long), {@code D:} (double),
 * {@code B:} (boolean), {@code C:} (Class FQCN), {@code E:type:value} (Enum).
 */
public final class SyntheticMetadataSerializer {

    public static final String METADATA_PATH = "META-INF/vauban-synthetic-metadata.properties";
    public static final String BCE_PROCESSED_MARKER = "META-INF/vauban-bce-processed";

    private SyntheticMetadataSerializer() {}

    // ---- Write ----

    public static void write(List<VaubanSyntheticBeanBuilder<?>> beans,
                             List<VaubanSyntheticObserverBuilder<?>> observers,
                             OutputStream os) throws IOException {
        var props = new Properties();

        props.setProperty("bean.count", String.valueOf(beans.size()));
        for (int i = 0; i < beans.size(); i++) {
            writeBeanBuilder(props, "bean." + i, beans.get(i));
        }

        props.setProperty("observer.count", String.valueOf(observers.size()));
        for (int i = 0; i < observers.size(); i++) {
            writeObserverBuilder(props, "observer." + i, observers.get(i));
        }

        props.store(new OutputStreamWriter(os, StandardCharsets.UTF_8),
                "Vauban synthetic metadata — generated at compile time by APT");
    }

    private static void writeBeanBuilder(Properties props, String prefix,
                                          VaubanSyntheticBeanBuilder<?> builder) {
        props.setProperty(prefix + ".beanClass", builder.getBeanClass().getName());
        if (builder.getCreatorClass() != null) {
            props.setProperty(prefix + ".creator", builder.getCreatorClass().getName());
        }
        if (builder.getDisposerClass() != null) {
            props.setProperty(prefix + ".disposer", builder.getDisposerClass().getName());
        }
        if (builder.getScopeAnnotation() != null) {
            props.setProperty(prefix + ".scope", builder.getScopeAnnotation().getName());
        }

        var typeNames = new ArrayList<String>();
        for (var type : builder.getTypes()) {
            if (type instanceof Class<?> cls) {
                typeNames.add(cls.getName());
            }
        }
        // Types given through the language model: at build time, the only way to name a class the
        // compilation is still producing. A class type is written by name and loaded at run time like
        // the others (BUG-20261008-01); this format has no notation for a parameterized type yet.
        for (var type : builder.getIndexTypes()) {
            if (type instanceof io.vidocq.vauban.indexer.model.TypeInfo.ClassType ct
                    && !typeNames.contains(ct.name().value())) {
                typeNames.add(ct.name().value());
            }
        }
        props.setProperty(prefix + ".types", String.join(",", typeNames));

        var qualifierNames = new ArrayList<String>();
        for (var q : builder.getQualifiers()) {
            qualifierNames.add(q.annotationType().getName());
        }
        if (!qualifierNames.isEmpty()) {
            props.setProperty(prefix + ".qualifiers", String.join(",", qualifierNames));
        }

        if (builder.getName() != null) {
            props.setProperty(prefix + ".name", builder.getName());
        }
        props.setProperty(prefix + ".alternative", String.valueOf(builder.isAlternative()));
        props.setProperty(prefix + ".priority", String.valueOf(builder.getPriority()));

        writeParams(props, prefix, builder.getParams());
    }

    private static void writeObserverBuilder(Properties props, String prefix,
                                              VaubanSyntheticObserverBuilder<?> builder) {
        var eventType = builder.getEventType();
        if (eventType instanceof Class<?> cls) {
            props.setProperty(prefix + ".eventType", cls.getName());
        } else {
            props.setProperty(prefix + ".eventType", eventType.getTypeName());
        }

        if (builder.getObserverClass() != null) {
            props.setProperty(prefix + ".observer", builder.getObserverClass().getName());
        }

        var qualifierNames = new ArrayList<String>();
        for (var q : builder.getQualifiers()) {
            qualifierNames.add(q.annotationType().getName());
        }
        if (!qualifierNames.isEmpty()) {
            props.setProperty(prefix + ".qualifiers", String.join(",", qualifierNames));
        }

        props.setProperty(prefix + ".priority", String.valueOf(builder.getPriority()));
        props.setProperty(prefix + ".async", String.valueOf(builder.isAsync()));

        writeParams(props, prefix, builder.getParams());
    }

    private static void writeParams(Properties props, String prefix, Map<String, Object> params) {
        for (var entry : params.entrySet()) {
            var encoded = encodeParam(entry.getValue());
            if (encoded != null) {
                props.setProperty(prefix + ".param." + entry.getKey(), encoded);
            }
        }
    }

    public static String encodeParam(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return "S:" + s;
        if (value instanceof Boolean b) return "B:" + b;
        if (value instanceof Integer i) return "I:" + i;
        if (value instanceof Long l) return "L:" + l;
        if (value instanceof Double d) return "D:" + d;
        if (value instanceof Class<?> c) return "C:" + c.getName();
        if (value instanceof Enum<?> e) return "E:" + e.getClass().getName() + ":" + e.name();
        return null; // unsupported param type — skipped
    }

    // ---- Read ----

    public static List<SyntheticBeanDescriptor> readBeans(InputStream is) throws IOException {
        var props = new Properties();
        props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        return readBeans(props);
    }

    public static List<SyntheticBeanDescriptor> readBeans(Properties props) {
        int count = Integer.parseInt(props.getProperty("bean.count", "0"));
        var beans = new ArrayList<SyntheticBeanDescriptor>();

        for (int i = 0; i < count; i++) {
            var prefix = "bean." + i;
            beans.add(new SyntheticBeanDescriptor(
                    props.getProperty(prefix + ".beanClass"),
                    props.getProperty(prefix + ".creator"),
                    props.getProperty(prefix + ".disposer"),
                    props.getProperty(prefix + ".scope"),
                    parseList(props.getProperty(prefix + ".types", "")),
                    parseList(props.getProperty(prefix + ".qualifiers", "")),
                    readParams(props, prefix),
                    Boolean.parseBoolean(props.getProperty(prefix + ".alternative", "false")),
                    Integer.parseInt(props.getProperty(prefix + ".priority", "0")),
                    props.getProperty(prefix + ".name")
            ));
        }

        return beans;
    }

    public static List<SyntheticObserverDescriptor> readObservers(Properties props) {
        int count = Integer.parseInt(props.getProperty("observer.count", "0"));
        var observers = new ArrayList<SyntheticObserverDescriptor>();

        for (int i = 0; i < count; i++) {
            var prefix = "observer." + i;
            observers.add(new SyntheticObserverDescriptor(
                    props.getProperty(prefix + ".eventType"),
                    props.getProperty(prefix + ".observer"),
                    parseList(props.getProperty(prefix + ".qualifiers", "")),
                    readParams(props, prefix),
                    Integer.parseInt(props.getProperty(prefix + ".priority",
                            String.valueOf(jakarta.interceptor.Interceptor.Priority.APPLICATION))),
                    Boolean.parseBoolean(props.getProperty(prefix + ".async", "false"))
            ));
        }

        return observers;
    }

    public static Object decodeParam(String encoded) {
        if (encoded == null || encoded.length() < 2) return null;
        char type = encoded.charAt(0);
        var value = encoded.substring(2); // skip "X:"
        return switch (type) {
            case 'S' -> value;
            case 'B' -> Boolean.parseBoolean(value);
            case 'I' -> Integer.parseInt(value);
            case 'L' -> Long.parseLong(value);
            case 'D' -> Double.parseDouble(value);
            case 'C' -> value; // Class name as string, resolved by consumer
            case 'E' -> value; // "type:name" as string, resolved by consumer
            default -> null;
        };
    }

    private static Map<String, String> readParams(Properties props, String prefix) {
        var params = new LinkedHashMap<String, String>();
        var paramPrefix = prefix + ".param.";
        for (var key : props.stringPropertyNames()) {
            if (key.startsWith(paramPrefix)) {
                var paramName = key.substring(paramPrefix.length());
                params.put(paramName, props.getProperty(key));
            }
        }
        return params;
    }

    private static List<String> parseList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
