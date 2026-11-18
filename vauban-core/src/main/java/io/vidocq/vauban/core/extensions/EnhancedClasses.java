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
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.FieldInfo;
import io.vidocq.vauban.indexer.model.MethodInfo;
import io.vidocq.vauban.indexer.model.ParameterInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The classes of an index as {@code @Enhancement} left them: with the annotations it added to each class, field,
 * method and parameter, as {@link EnhancementPatchSerializer#additions} names them. The phases after
 * {@code @Enhancement} read them so: CDI 4.1 runs them on the classes as that phase left them, members included
 * (BUG-20261117-10, BUG-20261118-42). An annotation the target already has is not added twice.
 */
final class EnhancedClasses {

    private EnhancedClasses() {
    }

    /** {@code index} with {@code additions} applied to its classes. */
    static VaubanIndex withAdditions(VaubanIndex index, Map<String, List<AnnotationInfo>> additions) {
        var builder = new IndexBuilder();
        for (var classInfo : index.getKnownClasses()) {
            builder.add(withAdditions(classInfo, additions));
        }
        return builder.build();
    }

    private static ClassInfo withAdditions(ClassInfo classInfo, Map<String, List<AnnotationInfo>> additions) {
        var name = classInfo.name().value();
        var fields = classInfo.fields().stream()
                .map(field -> withAdditions(field, additions.get(name + "#field#" + field.name()))).toList();
        var methods = classInfo.methods().stream().map(method -> withAdditions(method, name, additions)).toList();
        return new ClassInfo(classInfo.name(), classInfo.superName(), classInfo.interfaces(),
                classInfo.accessFlags(), fields, methods, added(classInfo.annotations(), additions.get(name)),
                classInfo.kind(), classInfo.simpleName(), classInfo.typeParameters(), classInfo.genericMembers(),
                classInfo.genericSuperclass(), classInfo.genericInterfaces(), classInfo.typeAnnotations(),
                classInfo.enclosingClass());
    }

    private static FieldInfo withAdditions(FieldInfo field, List<AnnotationInfo> added) {
        return added == null ? field : new FieldInfo(field.name(), field.type(), field.accessFlags(),
                added(field.annotations(), added), field.typeAnnotations());
    }

    private static MethodInfo withAdditions(MethodInfo method, String className,
                                            Map<String, List<AnnotationInfo>> additions) {
        var key = method.name() + InjectionPointInfo.methodDescriptor(
                method.parameters().stream().map(ParameterInfo::type).toList());
        var parameters = new ArrayList<ParameterInfo>();
        for (int i = 0; i < method.parameters().size(); i++) {
            var parameter = method.parameters().get(i);
            var added = additions.get(className + "#parameter#" + key + "#" + i);
            parameters.add(added == null ? parameter
                    : new ParameterInfo(parameter.name(), parameter.type(), added(parameter.annotations(), added)));
        }
        return new MethodInfo(method.name(), method.returnType(), parameters, method.exceptionTypes(),
                method.accessFlags(), added(method.annotations(), additions.get(className + "#method#" + key)),
                method.defaultValue(), method.typeParameters(), method.typeAnnotations());
    }

    /** {@code annotations}, then those of {@code added} it does not hold. */
    private static List<AnnotationInfo> added(List<AnnotationInfo> annotations, List<AnnotationInfo> added) {
        if (added == null) {
            return annotations;
        }
        var result = new ArrayList<>(annotations);
        added.stream().filter(annotation -> !result.contains(annotation)).forEach(result::add);
        return result;
    }
}
