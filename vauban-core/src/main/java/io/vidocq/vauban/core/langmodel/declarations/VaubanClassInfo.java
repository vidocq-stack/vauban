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
package io.vidocq.vauban.core.langmodel.declarations;

import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

public final class VaubanClassInfo implements jakarta.enterprise.lang.model.declarations.ClassInfo {

    private final io.vidocq.vauban.indexer.model.ClassInfo indexClass;
    private final IndexLookup lookup;

    public VaubanClassInfo(io.vidocq.vauban.indexer.model.ClassInfo indexClass, IndexLookup lookup) {
        this.indexClass = indexClass;
        this.lookup = lookup;
    }

    @Override
    public String name() {
        return indexClass.name().value();
    }

    @Override
    public String simpleName() {
        var full = indexClass.name().simpleName();
        int dollarIdx = full.lastIndexOf('$');
        return dollarIdx >= 0 ? full.substring(dollarIdx + 1) : full;
    }

    @Override
    public jakarta.enterprise.lang.model.declarations.PackageInfo packageInfo() {
        String pkg = indexClass.name().packageName();
        return pkg.isEmpty() ? null : new VaubanPackageInfo(pkg);
    }

    @Override
    public List<TypeVariable> typeParameters() {
        return List.of();
    }

    @Override
    public Type superClass() {
        if (indexClass.superName() == null) {
            return null;
        }
        return TypeMapper.map(new TypeInfo.ClassType(indexClass.superName()), lookup);
    }

    @Override
    public jakarta.enterprise.lang.model.declarations.ClassInfo superClassDeclaration() {
        if (indexClass.superName() == null) {
            return null;
        }
        return lookup.getClass(indexClass.superName())
                .map(c -> new VaubanClassInfo(c, lookup))
                .orElse(null);
    }

    @Override
    public List<Type> superInterfaces() {
        return indexClass.interfaces().stream()
                .map(i -> TypeMapper.map(new TypeInfo.ClassType(i), lookup))
                .toList();
    }

    @Override
    public List<jakarta.enterprise.lang.model.declarations.ClassInfo> superInterfacesDeclarations() {
        return indexClass.interfaces().stream()
                .flatMap(i -> lookup.getClass(i).stream())
                .map(c -> (jakarta.enterprise.lang.model.declarations.ClassInfo) new VaubanClassInfo(c, lookup))
                .toList();
    }

    @Override
    public boolean isPlainClass() {
        return indexClass.kind() == io.vidocq.vauban.indexer.model.ClassInfo.ClassKind.CLASS;
    }

    @Override
    public boolean isInterface() {
        return indexClass.kind() == io.vidocq.vauban.indexer.model.ClassInfo.ClassKind.INTERFACE;
    }

    @Override
    public boolean isEnum() {
        return indexClass.isEnum();
    }

    @Override
    public boolean isAnnotation() {
        return indexClass.isAnnotation();
    }

    @Override
    public boolean isRecord() {
        return indexClass.isRecord();
    }

    @Override
    public boolean isAbstract() {
        return indexClass.isAbstract();
    }

    @Override
    public boolean isFinal() {
        return indexClass.isFinal();
    }

    @Override
    public int modifiers() {
        return indexClass.accessFlags();
    }

    @Override
    public Collection<jakarta.enterprise.lang.model.declarations.MethodInfo> constructors() {
        return indexClass.methods().stream()
                .filter(io.vidocq.vauban.indexer.model.MethodInfo::isConstructor)
                .map(m -> (jakarta.enterprise.lang.model.declarations.MethodInfo) new VaubanMethodInfo(m, this, lookup))
                .toList();
    }

    @Override
    public Collection<jakarta.enterprise.lang.model.declarations.MethodInfo> methods() {
        var result = new java.util.ArrayList<jakarta.enterprise.lang.model.declarations.MethodInfo>();
        // Methods declared directly on this class
        var declaredMethodNames = new java.util.HashSet<String>();
        for (var m : indexClass.methods()) {
            if (!m.isConstructor() && !m.isStaticInitializer()) {
                result.add(new VaubanMethodInfo(m, this, lookup));
                declaredMethodNames.add(m.name());
            }
        }
        // Methods from superinterfaces (always included, even if overridden,
        // because BCE extensions may filter on declaringClass().isInterface())
        for (var ifaceName : indexClass.interfaces()) {
            var ifaceClassOpt = lookup.getClass(ifaceName);
            if (ifaceClassOpt.isPresent()) {
                var ifaceClass = ifaceClassOpt.get();
                var ifaceInfo = new VaubanClassInfo(ifaceClass, lookup);
                for (var m : ifaceClass.methods()) {
                    if (!m.isConstructor() && !m.isStaticInitializer()) {
                        result.add(new VaubanMethodInfo(m, ifaceInfo, lookup));
                    }
                }
            }
        }
        return result;
    }

    @Override
    public Collection<jakarta.enterprise.lang.model.declarations.FieldInfo> fields() {
        return indexClass.fields().stream()
                .map(f -> (jakarta.enterprise.lang.model.declarations.FieldInfo) new VaubanFieldInfo(f, this, lookup))
                .toList();
    }

    @Override
    public Collection<jakarta.enterprise.lang.model.declarations.RecordComponentInfo> recordComponents() {
        return List.of();
    }

    // -- AnnotationTarget --

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return indexClass.hasAnnotation(DotName.of(annotationType.getName()));
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().anyMatch(predicate);
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        return indexClass.annotation(DotName.of(annotationType.getName()))
                .map(a -> (AnnotationInfo) new VaubanAnnotationInfo(a, lookup))
                .orElse(null);
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        var ann = annotation(annotationType);
        return ann != null ? List.of(ann) : List.of();
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().filter(predicate).toList();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return indexClass.annotations().stream()
                .map(a -> (AnnotationInfo) new VaubanAnnotationInfo(a, lookup))
                .toList();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VaubanClassInfo other && name().equals(other.name());
    }

    @Override
    public int hashCode() {
        return name().hashCode();
    }

    @Override
    public String toString() {
        return "class " + name();
    }
}
