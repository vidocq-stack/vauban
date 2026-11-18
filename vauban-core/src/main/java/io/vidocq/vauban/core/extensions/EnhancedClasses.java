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

import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The classes of an index as {@code @Enhancement} left them: without the annotations it removed from each class,
 * field, method and parameter, and with those it added, as {@link EnhancementPatchSerializer#additions} names them.
 * The phases after {@code @Enhancement} read them so: CDI 4.1 runs them on the classes as that phase left them, members
 * included (BUG-20261117-10, BUG-20261118-42), removals included (BUG-20261118-45).
 *
 * <p>A removal applies to the annotations the target had, and an addition comes after it, as the container's
 * descriptors read them ({@code EnhancementApplier}). An annotation the target already has is not added twice: an
 * extension that adds a repeatable annotation twice on purpose, with the same members, gets it once.
 *
 * <p>A build never freezes a removal: an extension that removes an annotation runs again at each start, which sees
 * what the processor saw.
 */
final class EnhancedClasses {

    private EnhancedClasses() {
    }

    /** {@code index} with what {@code modifications} did to its classes. */
    static VaubanIndex of(VaubanIndex index, Map<DotName, List<VaubanClassConfig>> modifications) {
        var edits = new Edits(EnhancementPatchSerializer.additions(modifications), removals(modifications),
                new IndexLookup(index));
        var builder = new IndexBuilder();
        for (var classInfo : index.getKnownClasses()) {
            builder.add(edited(classInfo, edits));
        }
        return builder.build();
    }

    /** What the annotations of a target lose: all of them, or those a predicate matches. */
    private record Removal(boolean all, List<Predicate<jakarta.enterprise.lang.model.AnnotationInfo>> predicates) {

        Removal and(boolean removesAll, List<Predicate<jakarta.enterprise.lang.model.AnnotationInfo>> more) {
            var merged = new ArrayList<>(predicates);
            merged.addAll(more);
            return new Removal(all || removesAll, merged);
        }
    }

    /** The removals of {@code modifications}, by the target names {@link EnhancementPatchSerializer#additions} uses. */
    private static Map<String, Removal> removals(Map<DotName, List<VaubanClassConfig>> modifications) {
        var removals = new HashMap<String, Removal>();
        modifications.forEach((name, configs) -> {
            for (var config : configs) {
                remove(removals, name.value(), config.isAllAnnotationsRemoved(), config.getRemovePredicates());
                for (var field : config.getFieldConfigs()) {
                    // An inherited member names the class declaring it, as the additions do: the index holds it on that
                    // class, for every subclass, so the edited index leaves it as it is (BUG-20261118-48)
                    remove(removals, EnhancementPatchSerializer.target(name, config, field),
                            field.isAllAnnotationsRemoved(), field.getRemovePredicates());
                }
                for (var method : config.getMethodConfigs()) {
                    remove(removals, EnhancementPatchSerializer.target(name, config, method),
                            method.isAllAnnotationsRemoved(), method.getRemovePredicates());
                    for (int i = 0; i < method.getParameterConfigs().size(); i++) {
                        var parameter = method.getParameterConfigs().get(i);
                        remove(removals, EnhancementPatchSerializer.target(name, config, method, i),
                                parameter.isAllAnnotationsRemoved(), parameter.getRemovePredicates());
                    }
                }
            }
        });
        return removals;
    }

    private static void remove(Map<String, Removal> removals, String target, boolean all,
                               List<Predicate<jakarta.enterprise.lang.model.AnnotationInfo>> predicates) {
        if (all || !predicates.isEmpty()) {
            removals.merge(target, new Removal(all, List.copyOf(predicates)),
                    (first, second) -> first.and(second.all(), second.predicates()));
        }
    }

    /** The additions and the removals of {@code @Enhancement}, by target name. */
    private record Edits(Map<String, List<AnnotationInfo>> additions, Map<String, Removal> removals,
                         IndexLookup lookup) {

        boolean touch(String target) {
            return additions.containsKey(target) || removals.containsKey(target);
        }

        /** {@code annotations} without those {@code target} lost, then those it gained and did not hold. */
        List<AnnotationInfo> apply(String target, List<AnnotationInfo> annotations) {
            if (!touch(target)) {
                return annotations;
            }
            var result = new ArrayList<>(annotations);
            var removal = removals.get(target);
            if (removal != null) {
                result.removeIf(annotation -> removal.all() || removal.predicates().stream()
                        .anyMatch(predicate -> predicate.test(new VaubanAnnotationInfo(annotation, lookup))));
            }
            additions.getOrDefault(target, List.of()).stream().filter(annotation -> !result.contains(annotation))
                    .forEach(result::add);
            return result;
        }
    }

    private static ClassInfo edited(ClassInfo classInfo, Edits edits) {
        var name = classInfo.name().value();
        var fields = classInfo.fields().stream().map(field -> edited(field, name + "#field#" + field.name(), edits))
                .toList();
        var methods = classInfo.methods().stream().map(method -> edited(method, name, edits)).toList();
        return new ClassInfo(classInfo.name(), classInfo.superName(), classInfo.interfaces(),
                classInfo.accessFlags(), fields, methods, edits.apply(name, classInfo.annotations()),
                classInfo.kind(), classInfo.simpleName(), classInfo.typeParameters(), classInfo.genericMembers(),
                classInfo.genericSuperclass(), classInfo.genericInterfaces(), classInfo.typeAnnotations(),
                classInfo.enclosingClass());
    }

    private static FieldInfo edited(FieldInfo field, String target, Edits edits) {
        return edits.touch(target) ? new FieldInfo(field.name(), field.type(), field.accessFlags(),
                edits.apply(target, field.annotations()), field.typeAnnotations()) : field;
    }

    private static MethodInfo edited(MethodInfo method, String className, Edits edits) {
        var key = method.name() + InjectionPointInfo.methodDescriptor(
                method.parameters().stream().map(ParameterInfo::type).toList());
        var parameters = new ArrayList<ParameterInfo>();
        for (int i = 0; i < method.parameters().size(); i++) {
            var parameter = method.parameters().get(i);
            var target = className + "#parameter#" + key + "#" + i;
            parameters.add(edits.touch(target) ? new ParameterInfo(parameter.name(), parameter.type(),
                    edits.apply(target, parameter.annotations())) : parameter);
        }
        return new MethodInfo(method.name(), method.returnType(), parameters, method.exceptionTypes(),
                method.accessFlags(), edits.apply(className + "#method#" + key, method.annotations()),
                method.defaultValue(), method.typeParameters(), method.typeAnnotations());
    }
}
