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
package io.vidocq.vauban.core.container;

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.plan.IndexCreationPlans;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The creation plan of a bean class taken from the index that describes it (VAU-PERF-003 step 2): the injection
 * order and the lifecycle callbacks come from {@link IndexCreationPlans}, and each planned member is then looked up
 * directly ({@code getDeclaredField(name)}, {@code getDeclaredMethod(name, types)}) instead of scanning every member
 * and annotation of the hierarchy. CDI hands the {@code Field}/{@code Method} to {@code InjectionPoint.getMember()},
 * so the member objects themselves remain.
 *
 * <p>The container registers, at boot, the index of each bean class, keyed by the {@code Class} itself: a class
 * reloaded under the same name is another {@code Class} and never sees a stale plan. Empty — and the caller reads
 * the class by reflection, as before — when the class is not registered, a superclass is not in the index, a
 * planned member cannot be found, or a Build Compatible Extension added an injection point the index does not show.
 */
final class IndexPlans {

    private static final ClassValue<AtomicReference<VaubanIndex>> INDEXES = new ClassValue<>() {
        @Override
        protected AtomicReference<VaubanIndex> computeValue(Class<?> type) {
            return new AtomicReference<>();
        }
    };

    private IndexPlans() {
    }

    /** Records that {@code index} describes {@code beanClass}. */
    static void register(Class<?> beanClass, VaubanIndex index) {
        INDEXES.get(beanClass).set(index);
    }

    static Optional<List<Member>> injectionOrder(Class<?> beanClass, BeanDescriptor descriptor) {
        if (descriptor != null && !descriptor.enhancedInjectionMethods().isEmpty()) {
            return Optional.empty();
        }
        var index = INDEXES.get(beanClass).get();
        if (index == null) {
            return Optional.empty();
        }
        var plan = index.getClassByName(DotName.of(beanClass.getName()))
                .flatMap(info -> IndexCreationPlans.injectionOrder(info, index));
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        var members = resolve(beanClass, plan.get());
        if (members.isEmpty() || descriptor != null && !coversFieldPoints(members.get(), descriptor)) {
            return Optional.empty();
        }
        return members;
    }

    static Optional<List<Method>> lifecycleCallbacks(Class<?> beanClass, Class<? extends Annotation> annotation) {
        var index = INDEXES.get(beanClass).get();
        if (index == null) {
            return Optional.empty();
        }
        return index.getClassByName(DotName.of(beanClass.getName()))
                .flatMap(info -> IndexCreationPlans.lifecycleCallbacks(info, index, DotName.of(annotation.getName())))
                .flatMap(plan -> resolve(beanClass, plan))
                .map(members -> members.stream().map(Method.class::cast).toList());
    }

    /** An {@code @Enhancement} may have made a field injected: then the descriptor has a point the plan lacks. */
    private static boolean coversFieldPoints(List<Member> members, BeanDescriptor descriptor) {
        long fieldPoints = descriptor.injectionPoints().stream()
                .filter(p -> p.kind() == InjectionPointInfo.InjectionKind.FIELD)
                .count();
        long covered = members.stream()
                .filter(Field.class::isInstance)
                .filter(f -> QualifierHelper.fieldPoint(descriptor.injectionPoints(), (Field) f) != null)
                .count();
        return covered == fieldPoints;
    }

    private static Optional<List<Member>> resolve(Class<?> beanClass, List<IndexCreationPlans.Member> plan) {
        var members = new ArrayList<Member>(plan.size());
        try {
            for (var planned : plan) {
                var owner = declaringClass(beanClass, planned.declaringClass());
                if (owner == null) {
                    return Optional.empty();
                }
                if (planned.field()) {
                    members.add(owner.getDeclaredField(planned.name()));
                } else {
                    var types = MethodType.fromMethodDescriptorString(planned.parameters() + "V",
                            owner.getClassLoader()).parameterArray();
                    members.add(owner.getDeclaredMethod(planned.name(), types));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError _) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(members));
    }

    private static Class<?> declaringClass(Class<?> beanClass, String name) {
        for (var type = beanClass; type != null; type = type.getSuperclass()) {
            if (type.getName().equals(name)) {
                return type;
            }
        }
        return null;
    }
}
