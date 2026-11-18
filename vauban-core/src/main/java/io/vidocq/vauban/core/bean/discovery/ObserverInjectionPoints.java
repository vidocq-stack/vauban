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
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.TypeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The parameters of the observer methods a bean class has that its own injection points leave out: the ones of a
 * static observer it declares, and of an observer it inherits from a superclass the index holds, read as the bean
 * class gives that superclass's type variables. The start resolves them all ({@code VaubanContainer}'s observer
 * check); the build resolved the instance observers the bean class declares only (BUG-20261118-11).
 */
final class ObserverInjectionPoints {

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
        for (var method : beanClass.methods()) {
            if (method.isStatic() && isObserver(method)) {
                addParameters(beanClass, method, Map.of(), points);
            }
        }
        for (var inherited : InheritedMethods.methods(beanClass,
                name -> host.index.getClassByName(name).or(() -> outside.apply(name)))) {
            if (isObserver(inherited.method())) {
                addParameters(beanClass, inherited.method(), inherited.given(), points);
            }
        }
        return points;
    }

    private void addParameters(ClassInfo beanClass, MethodInfo method, Map<String, TypeInfo> given,
            List<InjectionPointInfo> points) {
        var parameters = method.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            var type = InheritedMethods.given(parameters.get(i).type(), given);
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

    private static boolean hasTypeVariable(TypeInfo type) {
        return switch (type) {
            case TypeInfo.TypeVariable _ -> true;
            case TypeInfo.ParameterizedType(_, var arguments, var owner) ->
                    arguments.stream().anyMatch(ObserverInjectionPoints::hasTypeVariable)
                            || boundHasTypeVariable(owner);
            case TypeInfo.ArrayType(var component, _) -> hasTypeVariable(component);
            case TypeInfo.WildcardType(var upper, var lower) ->
                    boundHasTypeVariable(upper) || boundHasTypeVariable(lower);
            default -> false;
        };
    }

    /** A wildcard's bound or a member type's owner, {@code null} when it has none (BUG-20261118-54). */
    private static boolean boundHasTypeVariable(TypeInfo bound) {
        return bound != null && hasTypeVariable(bound);
    }
}
