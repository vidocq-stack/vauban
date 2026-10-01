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

import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.Collection;
import java.util.Set;

/**
 * The Vauban modules a set of generated class files call into, read from their constant pools: what a module that
 * receives those classes must {@code require}. {@code io.vidocq.vauban.api} is left out, since
 * {@link ModuleInfoRewriter} always requires it.
 */
public final class VaubanModuleReferences {

    private static final String CORE_PACKAGE = "io/vidocq/vauban/core/";
    private static final String CORE_MODULE = "io.vidocq.vauban.core";

    private VaubanModuleReferences() {}

    /** {@code io.vidocq.vauban.core} when one of {@code classFiles} names a type of it, else nothing. */
    public static Set<String> of(Collection<byte[]> classFiles) {
        for (byte[] bytes : classFiles) {
            for (PoolEntry entry : ClassFile.of().parse(bytes).constantPool()) {
                boolean names = switch (entry) {
                    case ClassEntry type -> type.asInternalName().startsWith(CORE_PACKAGE);
                    // A descriptor names a type without a class entry: (Lio/vidocq/vauban/core/…;)V
                    case Utf8Entry utf -> utf.stringValue().contains("L" + CORE_PACKAGE);
                    default -> false;
                };
                if (names) {
                    return Set.of(CORE_MODULE);
                }
            }
        }
        return Set.of();
    }
}
