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
package io.vidocq.vauban.core.langmodel;

import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Provides index lookup for lang model implementations.
 */
@SuppressWarnings("java:S6206") // Cannot be a record: provides mutable lookup behavior
public final class IndexLookup {

    /**
     * Describes, while Vauban's annotation processor runs extensions and discovers beans, a class the index does not
     * hold: the processor sees every class of the compilation and of its class path, the index only the beans and the
     * classes a trigger annotation brings in (VAU-BCE-008, BUG-20261117-03). Unbound at run time.
     */
    private static final ScopedValue<Function<DotName, Optional<ClassInfo>>> UNINDEXED = ScopedValue.newInstance();

    /**
     * Describes, for the language model alone, a class outside the index that {@link #describe} does not: from its
     * class file at start and in {@code vauban:generate}, from the compiler's model in Vauban's annotation processor
     * (BUG-20261117-31). Unbound otherwise, where nothing describes such a class.
     */
    private static final ScopedValue<Function<DotName, Optional<ClassInfo>>> OUTSIDE = ScopedValue.newInstance();

    private static final DotName OBJECT = DotName.of("java.lang.Object");

    private final VaubanIndex index;

    public IndexLookup(VaubanIndex index) {
        this.index = Objects.requireNonNull(index);
    }

    public VaubanIndex index() {
        return index;
    }

    public Optional<ClassInfo> getClass(DotName name) {
        return index.getClassByName(name);
    }

    /**
     * The class as the index holds it or, when it does not and the caller runs under
     * {@link #whereUnindexedDescribedBy}, as that resolver describes it: for the language model, which shows a class's
     * members to an extension, and for the classes and interfaces above a class, which make a bean's types and what an
     * extension's types match; never a reason to treat the class as a bean.
     */
    public Optional<ClassInfo> describe(DotName name) {
        var indexed = index.getClassByName(name);
        return indexed.isPresent() || !UNINDEXED.isBound() ? indexed : UNINDEXED.get().apply(name);
    }

    /**
     * Runs {@code operation} with {@code resolver} describing the classes the index does not hold, for
     * {@link #describe}. For Vauban's annotation processor, around the deployment it processes: the extension phases
     * and the bean discovery.
     */
    public static <R, X extends Throwable> R whereUnindexedDescribedBy(
            Function<DotName, Optional<ClassInfo>> resolver, ScopedValue.CallableOp<? extends R, X> operation)
            throws X {
        return ScopedValue.where(UNINDEXED, Objects.requireNonNull(resolver)).call(operation);
    }

    /**
     * The class as the language model describes it: as {@link #describe} gives it or, for a class outside the
     * deployment, as the resolver of {@link #whereOutsideDescribedBy} describes it (BUG-20261117-31); empty when
     * nothing describes it. The bean discovery never reads it: a class described here is no bean, and adds nothing to
     * a bean's types. A class looked up outside the deployment is noted for the record of a freeze (BUG-20261118-30).
     */
    public Optional<ClassInfo> find(DotName name) {
        var described = describe(name);
        if (described.isPresent() || !OUTSIDE.isBound()) {
            return described;
        }
        ReadClasses.note(name);
        return OUTSIDE.get().apply(name);
    }

    /**
     * The declaration of a class as a type's {@code declaration()} shows it: as {@link #find} describes it, the first
     * time the language model needs it, or a {@link #stub} when nothing describes it, as before (BUG-20261117-31).
     */
    public ClassInfo declaration(DotName name) {
        return find(name).orElseGet(() -> stub(name));
    }

    /**
     * The declaration of the class an annotation or an enum value names, as {@link #find} describes it; when nothing
     * describes it, the {@code IllegalArgumentException} it threw before the language model described the classes
     * outside the index (BUG-20261117-31), which a consumer catches to leave such a class alone (Foy's
     * {@code FoyWebExtension}).
     */
    public ClassInfo requireDeclaration(DotName name) {
        return find(name).orElseThrow(() -> notFound(name));
    }

    /**
     * Runs {@code operation} with {@code resolver} describing, for {@link #declaration} alone, the classes outside the
     * index: at start and in {@code vauban:generate}, around the extension phases, from their class files
     * ({@link #classFiles}).
     */
    public static <R, X extends Throwable> R whereOutsideDescribedBy(Function<DotName, Optional<ClassInfo>> resolver,
            ScopedValue.CallableOp<? extends R, X> operation) throws X {
        return ScopedValue.where(OUTSIDE, Objects.requireNonNull(resolver)).call(operation);
    }

    /**
     * Runs {@code operation} with {@code unindexed} describing the classes the index does not hold for
     * {@link #describe}, and {@code outside} those it leaves out for {@link #declaration}: Vauban's annotation
     * processor, around the deployment it processes, describes the classes the compiler sees, and only to the language
     * model those of the JDK, which its bean discovery loads (BUG-20261117-31).
     */
    public static <R, X extends Throwable> R whereUnindexedDescribedBy(Function<DotName, Optional<ClassInfo>> unindexed,
            Function<DotName, Optional<ClassInfo>> outside, ScopedValue.CallableOp<? extends R, X> operation)
            throws X {
        return ScopedValue.where(UNINDEXED, Objects.requireNonNull(unindexed))
                .where(OUTSIDE, Objects.requireNonNull(outside)).call(operation);
    }

    /**
     * Describes a class from the class file {@code loader} finds as a resource, as the index describes a class it
     * holds, once per class: the resolver {@link #whereOutsideDescribedBy} takes at start and in
     * {@code vauban:generate} (BUG-20261117-31).
     */
    public static Function<DotName, Optional<ClassInfo>> classFiles(ClassLoader loader) {
        return new ClassFileDeclarations(loader);
    }

    /**
     * What a type's {@code declaration()} shows of a class nothing describes: its name, the superclass
     * {@code java.lang.Object}, no interface, no type parameter, no member and no annotation (VAU-BCE-001). The stub of
     * {@code java.lang.Object} has no superclass, so that a walk up the superclasses ends.
     */
    public static ClassInfo stub(DotName name) {
        return new ClassInfo(name, OBJECT.equals(name) ? null : OBJECT, List.of(), java.lang.reflect.Modifier.PUBLIC,
                List.of(), List.of(), List.of(), ClassInfo.ClassKind.CLASS);
    }

    public ClassInfo requireClass(DotName name) {
        return index.getClassByName(name).orElseThrow(() -> notFound(name));
    }

    private static IllegalArgumentException notFound(DotName name) {
        return new IllegalArgumentException("Class not found in index: " + name);
    }
}
