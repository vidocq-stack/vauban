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
package io.vidocq.vauban.core.types;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("java:S3776") // CDI container logic has inherent complexity
public class TypeHierarchyResolver {

    private TypeHierarchyResolver() {}

    public static Set<Type> resolveAllSupertypes(Type type) {
        Set<Type> result = new LinkedHashSet<>();
        resolveInternal(type, new HashMap<>(), result);
        return result;
    }

    private static void resolveInternal(Type type, Map<String, Type> typeMap, Set<Type> result) {
        if (type == null || type == Object.class) return;
        
        Type resolvedType = substitute(type, typeMap, new HashSet<>());
        result.add(resolvedType);

        switch (resolvedType) {
            case Class<?> c -> {
                resolveInternal(c.getGenericSuperclass(), typeMap, result);
                for (Type gi : c.getGenericInterfaces()) {
                    resolveInternal(gi, typeMap, result);
                }
            }
            case ParameterizedType pt -> {
                Class<?> raw = (Class<?>) pt.getRawType();
                Map<String, Type> newMap = new HashMap<>(typeMap);
                TypeVariable<?>[] typeVars = raw.getTypeParameters();
                Type[] actualArgs = pt.getActualTypeArguments();
                for (int i = 0; i < typeVars.length; i++) {
                    newMap.put(System.identityHashCode(typeVars[i].getGenericDeclaration()) + "#" + typeVars[i].getName(), actualArgs[i]);
                }
                resolveInternal(raw.getGenericSuperclass(), newMap, result);
                for (Type gi : raw.getGenericInterfaces()) {
                    resolveInternal(gi, newMap, result);
                }
            }
            default -> { }
        }
    }

    private static Type substitute(Type type, Map<String, Type> typeMap, Set<String> seen) {
        return switch (type) {
            case TypeVariable<?> tv -> {
                String key = System.identityHashCode(tv.getGenericDeclaration()) + "#" + tv.getName();
                Type resolved = typeMap.get(key);
                if (resolved != null && !seen.contains(key) && resolved != tv) {
                    seen.add(key);
                    Type result = substitute(resolved, typeMap, seen);
                    seen.remove(key);
                    yield result;
                }
                yield tv;
            }
            case ParameterizedType pt -> {
                Type[] args = pt.getActualTypeArguments();
                boolean changed = false;
                Type[] newArgs = new Type[args.length];
                for (int i = 0; i < args.length; i++) {
                    newArgs[i] = substitute(args[i], typeMap, seen);
                    if (newArgs[i] != args[i]) changed = true;
                }
                yield changed
                        ? new SubstitutedParameterizedType((Class<?>) pt.getRawType(), newArgs, pt.getOwnerType())
                        : type;
            }
            case null, default -> type;
        };
    }

    private record SubstitutedParameterizedType(Class<?> rawType, Type[] typeArguments, Type ownerType)
            implements ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return typeArguments.clone(); }
        @Override public Type getRawType() { return rawType; }
        @Override public Type getOwnerType() { return ownerType; }

        @Override
        public boolean equals(Object o) {
            return o instanceof ParameterizedType other
                    && rawType.equals(other.getRawType())
                    && java.util.Arrays.equals(typeArguments, other.getActualTypeArguments())
                    && java.util.Objects.equals(ownerType, other.getOwnerType());
        }

        @Override
        public int hashCode() {
            // Match sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl
            return java.util.Arrays.hashCode(typeArguments)
                    ^ java.util.Objects.hashCode(ownerType)
                    ^ rawType.hashCode();
        }

        @Override
        public String toString() {
            return rawType.getTypeName() + "<"
                    + java.util.Arrays.stream(typeArguments).map(Type::getTypeName)
                            .reduce((a, b) -> a + "," + b).orElse("") + ">";
        }
    }
}
