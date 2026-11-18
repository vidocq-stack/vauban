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

import io.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.ObserverDescriptor;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanMethodInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.event.Reception;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

public final class VaubanBceObserverInfo implements ObserverInfo {

    private static final String OBSERVES = "jakarta.enterprise.event.Observes";
    private static final String OBSERVES_ASYNC = "jakarta.enterprise.event.ObservesAsync";

    private final ObserverDescriptor descriptor;
    private final IndexLookup lookup;
    /** The bean that declares the observer method, or {@code null} when none does. */
    private final BeanDescriptor bean;

    public VaubanBceObserverInfo(ObserverDescriptor descriptor, IndexLookup lookup) {
        this(descriptor, null, lookup);
    }

    /** @param bean the managed bean that declares the observer method, or {@code null} for none */
    public VaubanBceObserverInfo(ObserverDescriptor descriptor, BeanDescriptor bean, IndexLookup lookup) {
        this.descriptor = descriptor;
        this.bean = bean;
        this.lookup = lookup;
    }

    @Override
    public Type eventType() {
        return TypeMapper.map(descriptor.eventType(), lookup);
    }

    /**
     * The qualifiers the event parameter declares; those the descriptor names when the method is not found. It gave
     * none (BUG-20261117-09).
     */
    @Override
    public Collection<AnnotationInfo> qualifiers() {
        var parameter = eventParameter();
        if (parameter == null) {
            return descriptor.qualifiers().stream()
                    .map(q -> (AnnotationInfo) new SimpleAnnotationInfo(q.annotationName().value(), java.util.Map.of()))
                    .toList();
        }
        return parameter.annotations(annotation -> annotation.name().equals(jakarta.inject.Named.class.getName())
                || annotation.declaration().hasAnnotation(jakarta.inject.Qualifier.class));
    }

    @Override
    public ClassInfo declaringClass() {
        var indexClass = lookup.getClass(descriptor.declaringClass()).orElse(null);
        if (indexClass != null) {
            return new VaubanClassInfo(indexClass, lookup);
        }
        return null;
    }

    /**
     * The observer method: the method of the declaring class with the descriptor's name and an {@code @Observes} or
     * {@code @ObservesAsync} parameter of the observed event type, its erasure when no type is equal, declared by the
     * class or inherited (BUG-20261118-44); {@code null} for a synthetic observer. It was {@code null} for every
     * observer (BUG-20261117-09).
     */
    @Override
    public MethodInfo observerMethod() {
        return observed().map(Observed::method).orElse(null);
    }

    /** The parameter of the observer method that carries {@code @Observes} or {@code @ObservesAsync}. */
    @Override
    public ParameterInfo eventParameter() {
        return observed().map(observed -> observed.method().parameters().get(observed.position())).orElse(null);
    }

    /** The bean that declares the observer method; {@code null} for a synthetic observer (BUG-20261117-09). */
    @Override
    public BeanInfo bean() {
        return bean == null ? null : new VaubanBceBeanInfo(bean, lookup);
    }

    @Override
    public boolean isSynthetic() {
        return descriptor.isSynthetic();
    }

    /** An observer method of the index, and the position of its event parameter. */
    private record Observed(MethodInfo method, int position) {
    }

    /**
     * The observer method, in the declaring class or, for one the bean inherits, in the nearest superclass that has
     * it: a bean class need not declare it. It was looked up in the declaring class alone (BUG-20261118-44).
     */
    private Optional<Observed> observed() {
        var declaring = descriptor.isSynthetic() ? null : lookup.getClass(descriptor.declaringClass()).orElse(null);
        for (var current = declaring; current != null; current = superclassOf(current)) {
            var found = observedIn(current);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * The superclass of {@code type} as the language model describes it, inside the index or not; {@code null} for
     * none.
     */
    private io.vidocq.vauban.indexer.model.ClassInfo superclassOf(io.vidocq.vauban.indexer.model.ClassInfo type) {
        return type.superName() == null ? null : lookup.find(type.superName()).orElse(null);
    }

    /** The observer method {@code declaring} declares itself. */
    private Optional<Observed> observedIn(io.vidocq.vauban.indexer.model.ClassInfo declaring) {
        var declaringInfo = new VaubanClassInfo(declaring, lookup);
        Observed erasedMatch = null;
        var erasedEvent = BeanDiscovery.erasedName(descriptor.eventType());
        for (var method : declaring.methods()) {
            int position = isNamedMethod(method) ? observedPosition(method) : -1;
            if (position < 0) {
                continue;
            }
            var type = method.parameters().get(position).type();
            var candidate = new Observed(new VaubanMethodInfo(method, declaringInfo, lookup), position);
            // Known erased parameter types name one method: an inherited event type may be a type variable the bean
            // class gives a type argument, which the descriptor holds substituted (BUG-20261118-44)
            if (!descriptor.parameterTypes().isEmpty() || type.equals(descriptor.eventType())) {
                return Optional.of(candidate);
            }
            if (erasedMatch == null && Objects.equals(erasedEvent, BeanDiscovery.erasedName(type))) {
                erasedMatch = candidate;
            }
        }
        return Optional.ofNullable(erasedMatch);
    }

    /**
     * Whether {@code method} has the observer's name and, when the descriptor knows them, its erased parameter types:
     * two overloads may observe one event type (BUG-20261118-41).
     */
    private boolean isNamedMethod(io.vidocq.vauban.indexer.model.MethodInfo method) {
        if (!method.name().equals(descriptor.methodName())) {
            return false;
        }
        var expected = descriptor.parameterTypes();
        return expected.isEmpty() || expected.equals(BeanDiscovery.erasedParameterTypes(method));
    }

    /** The position of the {@code @Observes} or {@code @ObservesAsync} parameter of {@code method}; -1 for none. */
    private static int observedPosition(io.vidocq.vauban.indexer.model.MethodInfo method) {
        var parameters = method.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (isObserved(parameters.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isObserved(io.vidocq.vauban.indexer.model.ParameterInfo parameter) {
        return parameter.annotations().stream().map(annotation -> annotation.name().value())
                .anyMatch(name -> name.equals(OBSERVES) || name.equals(OBSERVES_ASYNC));
    }

    @Override
    public int priority() {
        return descriptor.priority();
    }

    @Override
    public boolean isAsync() {
        return descriptor.async();
    }

    @Override
    public Reception reception() {
        return Reception.valueOf(descriptor.reception());
    }

    @Override
    public TransactionPhase transactionPhase() {
        return TransactionPhase.valueOf(descriptor.transactionPhase());
    }
}
