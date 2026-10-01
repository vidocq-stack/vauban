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
package io.vidocq.vauban.maven.enhance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ModuleDesc;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage 2 (issue #42): the module-info.class rewrite adds the provider service and API requirement. */
class ModuleInfoRewriterTest {

    @Test
    @DisplayName("adds provides VaubanComponentProvider + requires io.vidocq.vauban.api, preserving the rest")
    void addsProvidesAndRequires() {
        byte[] base = ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of("test.mod"),
                mb -> mb.requires(ModuleRequireInfo.of(
                        ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null))));

        byte[] rewritten = ModuleInfoRewriter.addComponentProvider(
                base, List.of("test.pkg._VaubanComponents"));

        var attr = ClassFile.of().parse(rewritten)
                .findAttribute(Attributes.module()).orElseThrow();

        assertTrue(attr.requires().stream().anyMatch(
                        r -> r.requires().name().stringValue().equals("io.vidocq.vauban.api")),
                "requires io.vidocq.vauban.api must be added");
        assertTrue(attr.requires().stream().anyMatch(
                        r -> r.requires().name().stringValue().equals("java.base")),
                "the pre-existing requires java.base must be preserved");

        var spi = attr.provides().stream()
                .filter(p -> p.provides().asSymbol().descriptorString()
                        .equals("Lio/vidocq/vauban/api/VaubanComponentProvider;"))
                .findFirst().orElseThrow();
        assertTrue(spi.providesWith().stream().anyMatch(
                        w -> w.asSymbol().descriptorString().equals("Ltest/pkg/_VaubanComponents;")),
                "provides ... with test.pkg._VaubanComponents");
    }

    @Test
    @DisplayName("adds extra requires, and leaves provides alone when there is no provider")
    void addsExtraRequiresAndKeepsProvidesUntouchedWhenNoProvider() {
        byte[] original = ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of("lib.mod"), mb -> { }));

        byte[] rewritten = ModuleInfoRewriter.addComponentProvider(original, List.of(),
                Set.of("io.vidocq.vauban.core"));

        var attr = ClassFile.of().parse(rewritten).findAttribute(Attributes.module()).orElseThrow();
        assertEquals(Set.of("io.vidocq.vauban.api", "io.vidocq.vauban.core"), attr.requires().stream()
                .map(r -> r.requires().name().stringValue()).collect(Collectors.toSet()));
        assertEquals(List.of(), attr.provides());
    }
}
