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

import jakarta.enterprise.inject.build.compatible.spi.InvokerInfo;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The invoker classes a build generates for the invokers its synthetic components hold (BUG-20261118-37), and which of
 * those invokers the synthetic metadata records by the name of its class. The start loads that class and calls the
 * method through it, with no {@code java.lang.reflect.Method}.
 *
 * <p>A class is generated for the invoker of a bean whose class the build writes, in the package of that class, when
 * the package can call the method by name. Any other invoker is recorded by the names of its method, and the start
 * resolves it reflectively, as it does an invoker an extension builds at start.
 */
public final class InvokerClasses {

    /** The classes of the metadata being written: {@link SyntheticParamCodec} reads them while it encodes. */
    static final ScopedValue<InvokerClasses> WRITING = ScopedValue.newInstance();

    private final Map<InvokerInfo, String> names = new IdentityHashMap<>();
    private final Map<String, byte[]> classes = new LinkedHashMap<>();
    private final Map<String, InvokerTarget> targets = new LinkedHashMap<>();

    private InvokerClasses() {
    }

    /**
     * The invoker classes of the invokers {@code beans} and {@code observers} hold as params.
     *
     * @param compiled whether the build writes the class file of the named bean class: the generated class lives in
     *                 its package, so it must ship with it
     */
    public static InvokerClasses of(Collection<VaubanSyntheticBeanBuilder<?>> beans,
                                    Collection<VaubanSyntheticObserverBuilder<?>> observers,
                                    Predicate<String> compiled) {
        var generated = new InvokerClasses();
        Stream.concat(beans.stream().map(VaubanSyntheticBeanBuilder::getParams),
                        observers.stream().map(VaubanSyntheticObserverBuilder::getParams))
                .flatMap(params -> params.values().stream())
                .flatMap(InvokerClasses::invokers)
                .forEach(invoker -> generated.add(invoker, compiled));
        return generated;
    }

    private static Stream<InvokerInfo> invokers(Object param) {
        return switch (param) {
            case InvokerInfo invoker -> Stream.of(invoker);
            case InvokerInfo[] array -> Stream.of(array);
            case null, default -> Stream.empty();
        };
    }

    private void add(InvokerInfo invoker, Predicate<String> compiled) {
        var target = targetOf(invoker);
        var shape = shapeOf(invoker);
        if (target == null || shape == null || !shape.direct() || !compiled.test(target.beanClass())
                || !shape.packageBound().stream().allMatch(compiled)
                || lookupsOf(invoker).stream().anyMatch(position -> shape.parameters().get(position).type() == null)) {
            return;
        }
        var name = InvokerClassWriter.className(target);
        for (int i = 2; targets.containsKey(name) && !targets.get(name).equals(target); i++) {
            name = InvokerClassWriter.className(target) + "_" + i;
        }
        if (targets.putIfAbsent(name, target) == null) {
            classes.put(name, InvokerClassWriter.write(name, target, shape));
        }
        names.put(invoker, name);
    }

    /** The generated classes, by binary name. */
    public Map<String, byte[]> classes() {
        return Map.copyOf(classes);
    }

    /** Runs {@code writer}, which writes the metadata naming the generated classes. */
    public <R, X extends Throwable> R writing(ScopedValue.CallableOp<R, X> writer) throws X {
        return ScopedValue.where(WRITING, this).call(writer);
    }

    /** The class generated for {@code invoker} by the metadata being written, {@code null} when none is. */
    static String generatedFor(InvokerInfo invoker) {
        return WRITING.isBound() ? WRITING.get().names.get(invoker) : null;
    }

    /** The shape of {@code invoker}, {@code null} when it was not built from the language model. */
    static InvokerShape shapeOf(InvokerInfo invoker) {
        return switch (invoker) {
            case VaubanInvoker vauban -> vauban.shape();
            case InvokerTarget.Described described -> described.shape();
            default -> null;
        };
    }

    private static java.util.Set<Integer> lookupsOf(InvokerInfo invoker) {
        return switch (invoker) {
            case VaubanInvoker vauban -> vauban.argumentLookups();
            case InvokerTarget.Described described -> described.argumentLookups();
            default -> java.util.Set.of();
        };
    }

    private static InvokerTarget targetOf(InvokerInfo invoker) {
        return switch (invoker) {
            case VaubanInvoker vauban -> vauban.target();
            case InvokerTarget.Described described -> described.target();
            default -> null;
        };
    }
}
