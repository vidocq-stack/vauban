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
package io.vidocq.vauban.core.bean.discovery;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VAU-PERF-007: the member types the index reads from the {@code Signature} attributes are the ones
 * reflection gives, in the form discovery uses ({@link BeanDiscovery#asReflected}), for every shape a
 * producer or an injection point can take. Discovery then types them from the index, without reflection.
 */
@DisplayName("VAU-PERF-007 - index member types equal reflection's")
class IndexSignatureEquivalenceTest {

    interface Repo<E> {}

    @SuppressWarnings("java:S1172") // the parameters are the shapes under test
    static class Shapes<T extends Number & Comparable<T>, K> {
        String plain;
        int primitive;
        int[][] primitiveArray;
        String[] array;
        List<String> parameterized;
        List<?> unbounded;
        List<? extends Object> extendsObject;
        List<? extends T> upper;
        Set<? super Integer> lower;
        Map<K, List<T>> nested;
        T variable;
        K unboundedVariable;
        List<String>[] genericArray;
        T[] variableArray;
        Repo<Map<String, int[]>> deep;
        Supplier<? extends Repo<?>> wildcardOfWildcard;
        Map.Entry<String, T> nestedType;

        <U extends Comparable<U>> List<U> method(Set<? super T> a, int b, U[] c, Repo<K> d) { return null; }

        <V> V[] other(Map<V, ? super V> m) { return null; }

        T variableReturn() { return null; }

        int primitiveReturn() { return 0; }
    }

    static class Owner {
        class Inner {
            Inner(List<String> names, int count) { /* no behaviour needed */ }
        }
    }

    private static ClassInfo scan(Class<?> clazz) throws IOException {
        try (var in = clazz.getResourceAsStream("/" + clazz.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            return ClassFileScanner.scan(in.readAllBytes());
        }
    }

    @Test
    @DisplayName("fields")
    void fields() throws IOException {
        var info = scan(Shapes.class);
        assertTrue(info.genericMembers());
        for (var field : Shapes.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            var indexed = info.fields().stream().filter(f -> f.name().equals(field.getName())).findFirst().orElseThrow();
            assertEquals(BeanDiscovery.reflectTypeToTypeInfo(field.getGenericType()),
                    BeanDiscovery.asReflected(indexed.type()), field.getName());
        }
    }

    @Test
    @DisplayName("method parameters and return types")
    void methods() throws IOException {
        var info = scan(Shapes.class);
        for (var method : Shapes.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            var indexed = info.methods().stream().filter(m -> m.name().equals(method.getName())).findFirst()
                    .orElseThrow();
            assertEquals(BeanDiscovery.reflectTypeToTypeInfo(method.getGenericReturnType()),
                    BeanDiscovery.asReflected(indexed.returnType()), method.getName());
            var generic = method.getGenericParameterTypes();
            for (int i = 0; i < generic.length; i++) {
                assertEquals(BeanDiscovery.reflectTypeToTypeInfo(generic[i]),
                        BeanDiscovery.asReflected(indexed.parameters().get(i).type()), method.getName() + "#" + i);
            }
        }
    }

    @Test
    @DisplayName("an inner class constructor, after its outer instance")
    void innerConstructor() throws IOException {
        var ctor = Owner.Inner.class.getDeclaredConstructors()[0];
        var indexed = scan(Owner.Inner.class).methods().stream().filter(m -> m.name().equals("<init>")).findFirst()
                .orElseThrow();
        var generic = ctor.getGenericParameterTypes();
        var parameters = indexed.parameters();
        // Reflection may or may not list the outer instance: compare the trailing, declared parameters.
        for (int i = 1; i <= 2; i++) {
            assertEquals(BeanDiscovery.reflectTypeToTypeInfo(generic[generic.length - i]),
                    BeanDiscovery.asReflected(parameters.get(parameters.size() - i).type()));
        }
    }

    @Test
    @DisplayName("every non-synthetic member of a JDK class with rich generics")
    void jdkClass() throws IOException {
        var info = scan(java.util.concurrent.ConcurrentHashMap.class);
        var methods = Arrays.stream(java.util.concurrent.ConcurrentHashMap.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !m.isBridge() && Modifier.isPublic(m.getModifiers()))
                .sorted(Comparator.comparing(java.lang.reflect.Method::toGenericString))
                .toList();
        for (var method : methods) {
            var descriptor = java.lang.invoke.MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                    .toMethodDescriptorString();
            var indexed = info.methods().stream()
                    .filter(m -> m.name().equals(method.getName()) && erasedDescriptor(m).equals(descriptor))
                    .findFirst();
            if (indexed.isEmpty()) continue;
            assertEquals(BeanDiscovery.reflectTypeToTypeInfo(method.getGenericReturnType()),
                    BeanDiscovery.asReflected(indexed.get().returnType()), method.toGenericString());
            var generic = method.getGenericParameterTypes();
            for (int i = 0; i < generic.length; i++) {
                assertEquals(BeanDiscovery.reflectTypeToTypeInfo(generic[i]),
                        BeanDiscovery.asReflected(indexed.get().parameters().get(i).type()), method.toGenericString());
            }
        }
    }

    private static String erasedDescriptor(io.vidocq.vauban.indexer.model.MethodInfo method) {
        var sb = new StringBuilder("(");
        method.parameters().forEach(p -> sb.append(descriptor(p.type())));
        return sb.append(')').append(descriptor(method.returnType())).toString();
    }

    private static String descriptor(io.vidocq.vauban.indexer.model.TypeInfo type) {
        return switch (type) {
            case io.vidocq.vauban.indexer.model.TypeInfo.VoidType _ -> "V";
            case io.vidocq.vauban.indexer.model.TypeInfo.PrimitiveType(var kind) -> switch (kind) {
                case BOOLEAN -> "Z"; case BYTE -> "B"; case CHAR -> "C"; case SHORT -> "S";
                case INT -> "I"; case LONG -> "J"; case FLOAT -> "F"; case DOUBLE -> "D";
            };
            case io.vidocq.vauban.indexer.model.TypeInfo.ArrayType(var component, var dimensions) ->
                    "[".repeat(dimensions) + descriptor(component);
            case io.vidocq.vauban.indexer.model.TypeInfo.ClassType(var name) ->
                    "L" + name.value().replace('.', '/') + ";";
            case io.vidocq.vauban.indexer.model.TypeInfo.ParameterizedType(var raw, _) ->
                    "L" + raw.value().replace('.', '/') + ";";
            case io.vidocq.vauban.indexer.model.TypeInfo.TypeVariable(_, var bounds) ->
                    bounds.isEmpty() ? "Ljava/lang/Object;" : descriptor(bounds.getFirst());
            case io.vidocq.vauban.indexer.model.TypeInfo.WildcardType _ -> "Ljava/lang/Object;";
        };
    }

    // --- Discovery: the index first, reflection behind it ---

    public static class Consumer {
        @jakarta.inject.Inject
        public List<String> names;
    }

    private static final TypeInfo LIST_OF_STRING = new TypeInfo.ParameterizedType(DotName.of("java.util.List"),
            List.of(new TypeInfo.ClassType(DotName.of("java.lang.String"))));

    private static TypeInfo injectedType(String className, TypeInfo fieldType, boolean genericMembers) {
        var field = new FieldInfo("names", fieldType, 0x0001,
                List.of(new AnnotationInfo(DotName.of("jakarta.inject.Inject"), Map.of())));
        var ctor = new MethodInfo("<init>", new TypeInfo.VoidType(), List.of(), List.of(), 0x0001, List.of());
        var info = new ClassInfo(DotName.of(className), DotName.of("java.lang.Object"), List.of(), 0x0001,
                List.of(field), List.of(ctor),
                List.of(new AnnotationInfo(DotName.of("jakarta.enterprise.context.Dependent"), Map.of())),
                ClassInfo.ClassKind.CLASS, null, null, genericMembers);
        var builder = new IndexBuilder();
        builder.add(info);
        var bean = new BeanDiscovery(builder.build()).discoverBeans().stream()
                .filter(b -> b.kind() == BeanDescriptor.BeanKind.MANAGED).findFirst().orElseThrow();
        return bean.injectionPoints().getFirst().requiredType();
    }

    @Test
    @DisplayName("a class whose index read the signatures is typed from the index, without loading it")
    void indexFirst() {
        assertEquals(LIST_OF_STRING, injectedType("io.vidocq.vauban.core.bean.discovery.NoSuchBean", LIST_OF_STRING,
                true));
    }

    @Test
    @DisplayName("a class whose index holds erased types is still typed by reflection, as before")
    void reflectionFallback() {
        assertEquals(LIST_OF_STRING, injectedType(Consumer.class.getName(),
                new TypeInfo.ClassType(DotName.of("java.util.List")), false));
    }
}
