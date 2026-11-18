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

import io.vidocq.vauban.core.bean.discovery.PackageInfos;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.ReadClasses;
import io.vidocq.vauban.core.langmodel.RepeatableAnnotations;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.indexer.model.ClassInfo;
import jakarta.enterprise.lang.model.AnnotationInfo;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A package, whose annotations are those of its {@code package-info} class, as the index holds it, or as the language
 * model describes it otherwise: from its class file at start and frozen, from the compiler's model in the processor
 * (BUG-20261117-57). A package with no {@code package-info} has no annotation.
 */
public final class VaubanPackageInfo implements jakarta.enterprise.lang.model.declarations.PackageInfo {

    private final String packageName;
    private final IndexLookup lookup;

    public VaubanPackageInfo(String packageName, IndexLookup lookup) {
        this.packageName = packageName;
        this.lookup = Objects.requireNonNull(lookup);
    }

    @Override
    public String name() {
        return packageName;
    }

    // -- AnnotationTarget: the annotations of the package-info class --

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return annotation(annotationType) != null;
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().anyMatch(predicate);
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        return annotations().stream().filter(annotation -> annotation.name().equals(annotationType.getName()))
                .findFirst().orElse(null);
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        return RepeatableAnnotations.of(annotations(), annotationType);
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return annotations().stream().filter(predicate).toList();
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        var packageInfo = PackageInfos.of(packageName);
        ReadClasses.note(packageInfo);
        return lookup.find(packageInfo).map(ClassInfo::annotations).orElse(List.of()).stream()
                .<AnnotationInfo>map(annotation -> new VaubanAnnotationInfo(annotation, lookup))
                .toList();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VaubanPackageInfo other && packageName.equals(other.packageName);
    }

    @Override
    public int hashCode() {
        return packageName.hashCode();
    }

    @Override
    public String toString() {
        return "package " + packageName;
    }
}
