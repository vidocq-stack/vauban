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

import io.vidocq.vauban.core.bean.validation.IndexClassValidator;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The walk the build makes up the superclasses of a bean class, read from the index, to find the members the class
 * inherits as the start finds them by reflection ({@code ObserverDisposerDiscovery.getAllInheritedMethods}): each
 * superclass up to {@code java.lang.Object} with what the bean class gives its type variables, and the methods no
 * class below overrides. The observers a bean inherits ({@code ObserverDisposerDiscovery}), their injection points
 * ({@link ObserverInjectionPoints}) and the injection points a bean inherits walk it.
 */
final class InheritedMethods {

    private static final DotName OBJECT = DotName.of("java.lang.Object");

    private InheritedMethods() {
    }

    /** A superclass, and what the bean class gives its type variables, by name. */
    record Superclass(ClassInfo type, Map<String, TypeInfo> given) {
    }

    /** A method a superclass declares that the bean class inherits, and what the bean class gives its variables. */
    record Inherited(MethodInfo method, Map<String, TypeInfo> given) {
    }

    /**
     * The superclasses of {@code beanClass}, nearest first, up to and excluding {@code java.lang.Object}, as far as
     * {@code find} describes them.
     *
     * @param find describes a class by name: from the index, or from outside it at build
     */
    static List<Superclass> superclasses(ClassInfo beanClass, Function<DotName, Optional<ClassInfo>> find) {
        var superclasses = new ArrayList<Superclass>();
        Map<String, TypeInfo> given = Map.of();
        var below = beanClass;
        boolean own = true;
        for (var name = beanClass.superName(); name != null && !name.equals(OBJECT); name = below.superName()) {
            var type = find.apply(name).orElse(null);
            if (type == null) {
                break;
            }
            given = IndexClassValidator.typeArguments(type, below.genericSuperclass(), given, own);
            own = false;
            superclasses.add(new Superclass(type, given));
            below = type;
        }
        return superclasses;
    }

    /**
     * The methods of the superclasses of {@code beanClass} it inherits, nearest first: neither private, static nor a
     * constructor, and not overridden, the start's rules (BUG-20261118-51). A method a class below declares with the
     * same name and erased parameters, the type arguments substituted, an observer or not, overrides it.
     */
    static List<Inherited> methods(ClassInfo beanClass, Function<DotName, Optional<ClassInfo>> find) {
        var inherited = new ArrayList<Inherited>();
        var signatures = new HashSet<String>();
        beanClass.methods().forEach(method -> signatures.add(signature(method, Map.of())));
        for (var superclass : superclasses(beanClass, find)) {
            for (var method : superclass.type().methods()) {
                if (!method.isPrivate() && !method.isStatic() && !method.isConstructor()
                        && signatures.add(signature(method, superclass.given()))) {
                    inherited.add(new Inherited(method, superclass.given()));
                }
            }
        }
        return inherited;
    }

    /**
     * The name and erased parameter types of {@code method}, its class's type variables replaced by what the bean
     * class gives them, as the compiler compares an override. The start compares the declared methods' erasures,
     * javac's bridges among them: {@code on(Pung, Tool)} overriding {@code Base<Tool>.on(Pung, T)} has the bridge
     * {@code on(Pung, Object)}. The compiler's elements have no bridge, so the build substitutes before erasing.
     */
    static String signature(MethodInfo method, Map<String, TypeInfo> given) {
        var signature = new StringBuilder(method.name()).append(':');
        for (var parameter : method.parameters()) {
            signature.append(BeanDiscovery.erasedName(given(parameter.type(), given))).append(',');
        }
        return signature.toString();
    }

    /** {@code type} with the type variables {@code given} names replaced by what they are given. */
    static TypeInfo given(TypeInfo type, Map<String, TypeInfo> given) {
        return switch (type) {
            case TypeInfo.TypeVariable(var name, _) -> given.getOrDefault(name, type);
            case TypeInfo.ParameterizedType(var raw, var arguments, var owner) -> new TypeInfo.ParameterizedType(raw,
                    arguments.stream().map(argument -> given(argument, given)).toList(),
                    owner == null ? null : given(owner, given));
            case TypeInfo.ArrayType(var component, var dimensions) ->
                    new TypeInfo.ArrayType(given(component, given), dimensions);
            case TypeInfo.WildcardType(var upper, var lower) -> new TypeInfo.WildcardType(
                    upper == null ? null : given(upper, given), lower == null ? null : given(lower, given));
            default -> type;
        };
    }
}
