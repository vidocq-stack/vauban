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

import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
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
import java.util.Set;
import java.util.function.Function;

/**
 * The parameters of the observer methods a bean class has that its own injection points leave out: the ones of a
 * static observer it declares, and of an observer it inherits from a superclass the index holds, read as the bean
 * class gives that superclass's type variables. The start resolves them all ({@code VaubanContainer}'s observer
 * check); the build resolved the instance observers the bean class declares only (BUG-20261118-11).
 */
final class ObserverInjectionPoints {

    private static final DotName OBJECT = DotName.of("java.lang.Object");
    private static final DotName EVENT_METADATA = DotName.of("jakarta.enterprise.inject.spi.EventMetadata");

    private final BeanDiscovery host;

    /** A superclass the index does not hold, described: from the compiler's elements at build. */
    private final Function<DotName, Optional<ClassInfo>> outside;

    ObserverInjectionPoints(BeanDiscovery host, Function<DotName, Optional<ClassInfo>> outside) {
        this.host = host;
        this.outside = outside;
    }

    /**
     * The injection points of {@code beanClass}'s static observers and of the observers it inherits, found as the start
     * finds them ({@code ObserverDisposerDiscovery.getAllInheritedMethods}): a private or static superclass method is
     * not inherited (BUG-20261118-51), and a method a class below declares with the same name and erased parameters,
     * the type arguments substituted, overrides it, an observer or not. A parameter the bean class leaves a type
     * variable is a definition error the definition rules report.
     */
    List<InjectionPointInfo> of(ClassInfo beanClass) {
        var points = new ArrayList<InjectionPointInfo>();
        var signatures = new HashSet<String>();
        for (var method : beanClass.methods()) {
            signatures.add(signature(method, Map.of()));
            if (method.isStatic() && isObserver(method)) {
                addParameters(beanClass, method, Map.of(), points);
            }
        }
        Map<String, TypeInfo> given = Map.of();
        var below = beanClass;
        boolean own = true;
        for (var superName = beanClass.superName(); superName != null && !superName.equals(OBJECT);
                superName = below.superName()) {
            var name = superName;
            var superClass = host.index.getClassByName(name).or(() -> outside.apply(name)).orElse(null);
            if (superClass == null) {
                break;
            }
            given = IndexClassValidator.typeArguments(superClass, below.genericSuperclass(), given, own);
            own = false;
            addInherited(beanClass, superClass, given, signatures, points);
            below = superClass;
        }
        return points;
    }

    private void addInherited(ClassInfo beanClass, ClassInfo superClass, Map<String, TypeInfo> given,
            Set<String> signatures, List<InjectionPointInfo> points) {
        for (var method : superClass.methods()) {
            if (!method.isPrivate() && !method.isStatic() && !method.isConstructor()
                    && signatures.add(signature(method, given))
                    && isObserver(method)) {
                addParameters(beanClass, method, given, points);
            }
        }
    }

    /**
     * The name and erased parameter types of {@code method}, its class's type variables replaced by what the bean
     * class gives them, as the compiler compares an override. The start compares the declared methods' erasures,
     * javac's bridges among them: {@code on(Pung, Tool)} overriding {@code Base<Tool>.on(Pung, T)} has the bridge
     * {@code on(Pung, Object)}. The compiler's elements have no bridge, so the build substitutes before erasing.
     */
    private static String signature(MethodInfo method, Map<String, TypeInfo> given) {
        var signature = new StringBuilder(method.name()).append(':');
        for (var parameter : method.parameters()) {
            signature.append(BeanDiscovery.erasedName(given(parameter.type(), given))).append(',');
        }
        return signature.toString();
    }

    private void addParameters(ClassInfo beanClass, MethodInfo method, Map<String, TypeInfo> given,
            List<InjectionPointInfo> points) {
        var parameters = method.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            var type = given(parameters.get(i).type(), given);
            if (!BeanDiscovery.hasObserverAnnotation(parameters.get(i).annotations()) && !hasTypeVariable(type)
                    && !(type instanceof TypeInfo.ClassType(var name) && name.equals(EVENT_METADATA))) {
                points.add(host.parameterInjectionPoint(beanClass, method, method.name(), i, type,
                        InjectionPointInfo.InjectionKind.METHOD_PARAMETER));
            }
        }
    }

    private static boolean isObserver(MethodInfo method) {
        return !method.isConstructor()
                && method.parameters().stream().anyMatch(p -> BeanDiscovery.hasObserverAnnotation(p.annotations()));
    }

    /** {@code type} with the type variables {@code given} names replaced by what they are given. */
    static TypeInfo given(TypeInfo type, Map<String, TypeInfo> given) {
        return switch (type) {
            case TypeInfo.TypeVariable(var name, _) -> given.getOrDefault(name, type);
            case TypeInfo.ParameterizedType(var raw, var arguments) -> new TypeInfo.ParameterizedType(raw,
                    arguments.stream().map(argument -> given(argument, given)).toList());
            case TypeInfo.ArrayType(var component, var dimensions) ->
                    new TypeInfo.ArrayType(given(component, given), dimensions);
            case TypeInfo.WildcardType(var upper, var lower) -> new TypeInfo.WildcardType(
                    upper == null ? null : given(upper, given), lower == null ? null : given(lower, given));
            default -> type;
        };
    }

    private static boolean hasTypeVariable(TypeInfo type) {
        return switch (type) {
            case TypeInfo.TypeVariable _ -> true;
            case TypeInfo.ParameterizedType(_, var arguments) ->
                    arguments.stream().anyMatch(ObserverInjectionPoints::hasTypeVariable);
            case TypeInfo.ArrayType(var component, _) -> hasTypeVariable(component);
            case TypeInfo.WildcardType(var upper, var lower) ->
                    boundHasTypeVariable(upper) || boundHasTypeVariable(lower);
            default -> false;
        };
    }

    /** A wildcard's bound, {@code null} when it has none. */
    private static boolean boundHasTypeVariable(TypeInfo bound) {
        return bound != null && hasTypeVariable(bound);
    }
}
