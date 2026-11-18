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

import io.vidocq.vauban.core.langmodel.annotatedpackage.InAnnotatedPackage;
import io.vidocq.vauban.core.langmodel.annotatedpackage.PackageMark;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.PackageInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("A package of the language model shows the annotations of its package-info (BUG-20261117-57)")
class VaubanPackageInfoTest {

    private static final String PACKAGE_INFO = "io/vidocq/vauban/core/langmodel/annotatedpackage/package-info.class";

    /** The package of {@link InAnnotatedPackage}, with its {@code package-info} in the index or not. */
    private static PackageInfo packageOf(boolean withPackageInfo) throws IOException {
        var builder = new IndexBuilder();
        builder.add(LangModelTest.scanClass(InAnnotatedPackage.class));
        if (withPackageInfo) {
            try (var in = VaubanPackageInfoTest.class.getClassLoader().getResourceAsStream(PACKAGE_INFO)) {
                builder.add(ClassFileScanner.scan(in.readAllBytes()));
            }
        }
        var lookup = new IndexLookup(builder.build());
        return new VaubanClassInfo(LangModelTest.scanClass(InAnnotatedPackage.class), lookup).packageInfo();
    }

    @Test
    @DisplayName("a package shows the annotations of its package-info")
    void aPackageShowsTheAnnotationsOfItsPackageInfo() throws IOException {
        var pkg = packageOf(true);

        var mark = pkg.annotation(PackageMark.class);
        assertAll(() -> assertEquals(List.of(PackageMark.class.getName()),
                        pkg.annotations().stream().map(AnnotationInfo::name).toList(), "annotations()"),
                () -> assertTrue(pkg.hasAnnotation(PackageMark.class), "hasAnnotation(Class)"),
                () -> assertEquals("marked", mark.value().asString(), "annotation(Class)"),
                () -> assertEquals(1, pkg.repeatableAnnotation(PackageMark.class).size(), "repeatableAnnotation"),
                () -> assertEquals(1, pkg.annotations(AnnotationInfo::hasValue).size(),
                        "annotations(Predicate)"),
                () -> assertFalse(pkg.hasAnnotation(Deprecated.class), "an annotation it does not carry"));
    }

    @Test
    @DisplayName("a package whose package-info nothing describes has no annotation")
    void aPackageWithoutPackageInfoHasNoAnnotation() throws IOException {
        var pkg = packageOf(false);

        assertAll(() -> assertEquals(List.of(), pkg.annotations(), "annotations()"),
                () -> assertNull(pkg.annotation(PackageMark.class), "annotation(Class)"),
                () -> assertFalse(pkg.hasAnnotation(annotation -> true), "hasAnnotation(Predicate)"));
    }
}
