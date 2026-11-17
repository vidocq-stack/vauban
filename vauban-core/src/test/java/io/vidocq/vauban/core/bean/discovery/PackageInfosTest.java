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

import io.vidocq.vauban.core.container.marked.MarkedBean;
import io.vidocq.vauban.core.container.vetoed.PackageVetoedBean;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PackageInfos — the package-info of a vetoed package (BUG-20261117-01)")
class PackageInfosTest {

    private static final ClassLoader LOADER = PackageInfosTest.class.getClassLoader();

    @Test
    @DisplayName("names the package-info class of a package")
    void namesThePackageInfoOfAPackage() {
        assertEquals(DotName.of("a.b.package-info"), PackageInfos.of("a.b"));
        assertTrue(PackageInfos.isPackageInfo("a.b.package-info"));
        assertFalse(PackageInfos.isPackageInfo("a.b.Info"));
        assertFalse(PackageInfos.isPackageInfo("a.package_info"));
    }

    @Test
    @DisplayName("reads the package-info of a vetoed package beside a class of it")
    void readsThePackageInfoOfAVetoedPackage() {
        var packageInfo = PackageInfos.vetoed(PackageVetoedBean.class.getName(), LOADER);

        assertEquals(Optional.of(PackageInfos.of(PackageVetoedBean.class.getPackageName())),
                packageInfo.map(ClassInfo::name));
    }

    @Test
    @DisplayName("finds nothing for a package another annotation marks, one with no package-info, or the unnamed one")
    void findsNothingForAPackageThatIsNotVetoed() {
        assertEquals(Optional.empty(), PackageInfos.vetoed(MarkedBean.class.getName(), LOADER), "another annotation");
        assertEquals(Optional.empty(), PackageInfos.vetoed(PackageInfosTest.class.getName(), LOADER),
                "no package-info");
        assertEquals(Optional.empty(), PackageInfos.vetoed("Unnamed", LOADER), "the unnamed package");
        assertEquals(Optional.empty(), PackageInfos.vetoed(PackageVetoedBean.class.getName(), null), "no loader");
    }

    @Test
    @DisplayName("finds nothing in a package-info it cannot read")
    void findsNothingInAPackageInfoItCannotRead() {
        ClassLoader garbled = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return new ByteArrayInputStream("not a class file".getBytes(StandardCharsets.UTF_8));
            }
        };

        assertEquals(Optional.empty(), PackageInfos.vetoed(PackageVetoedBean.class.getName(), garbled));
    }
}
