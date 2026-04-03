package fr.vidocq.vauban.core.types;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class TypeHierarchyResolver {
    
    public static Set<Type> resolveAllSupertypes(Type type) {
        Set<Type> result = new LinkedHashSet<>();
        resolveInternal(type, new HashMap<>(), result);
        return result;
    }

    private static void resolveInternal(Type type, Map<TypeVariable<?>, Type> typeMap, Set<Type> result) {
        if (type == null || type == Object.class) return;
        
        Type resolvedType = substitute(type, typeMap);
        result.add(resolvedType);
        
        if (resolvedType instanceof Class<?> c) {
            resolveInternal(c.getGenericSuperclass(), typeMap, result);
            for (Type gi : c.getGenericInterfaces()) {
                resolveInternal(gi, typeMap, result);
            }
        } else if (resolvedType instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            Map<TypeVariable<?>, Type> newMap = new HashMap<>(typeMap);
            TypeVariable<?>[] typeVars = raw.getTypeParameters();
            Type[] actualArgs = pt.getActualTypeArguments();
            for (int i = 0; i < typeVars.length; i++) {
                newMap.put(typeVars[i], actualArgs[i]);
            }
            resolveInternal(raw.getGenericSuperclass(), newMap, result);
            for (Type gi : raw.getGenericInterfaces()) {
                resolveInternal(gi, newMap, result);
            }
        }
    }

    private static Type substitute(Type type, Map<TypeVariable<?>, Type> typeMap) {
        if (type instanceof TypeVariable<?> tv) {
            Type resolved = typeMap.get(tv);
            if (resolved != null) {
                // recursively substitute in case it maps to another TypeVariable
                return substitute(resolved, typeMap);
            }
            return tv;
        }
        if (type instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            boolean changed = false;
            Type[] newArgs = new Type[args.length];
            for (int i = 0; i < args.length; i++) {
                newArgs[i] = substitute(args[i], typeMap);
                if (newArgs[i] != args[i]) changed = true;
            }
            if (changed) {
                return new ParameterizedType() {
                    @Override public Type[] getActualTypeArguments() { return newArgs; }
                    @Override public Type getRawType() { return pt.getRawType(); }
                    @Override public Type getOwnerType() { return pt.getOwnerType(); }
                    @Override public boolean equals(Object o) {
                        if (o instanceof ParameterizedType other) {
                            return pt.getRawType().equals(other.getRawType()) &&
                                   java.util.Arrays.equals(newArgs, other.getActualTypeArguments());
                        }
                        return false;
                    }
                    @Override public int hashCode() {
                        return java.util.Arrays.hashCode(newArgs) ^ pt.getRawType().hashCode();
                    }
                    @Override public String toString() {
                        return pt.getRawType().getTypeName() + "<" + 
                            java.util.Arrays.stream(newArgs).map(Type::getTypeName).reduce((a,b)->a+","+b).orElse("") + ">";
                    }
                };
            }
        }
        return type;
    }
}
