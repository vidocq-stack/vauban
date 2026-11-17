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
package io.vidocq.vauban.core;

/**
 * For {@code vauban-processor} only: gives its module the packages vauban-core exports to it alone, where the
 * qualified exports of the module descriptor do not reach it. A qualified export reaches the modules of the exporting
 * module's layer, and of the layers below it, that the layer holds when it is defined. javac's
 * {@code --processor-module-path}, in a JVM whose module path holds vauban-core, defines the processor's module in a
 * layer above vauban-core's: no qualified export reaches it, and the processor's first use of such a package threw
 * {@link IllegalAccessError} (BUG-20261117-23). The processor calls {@link #grantTo(Module)} before it uses them. The
 * packages stay out of the public API: only the module named {@code io.vidocq.vauban.processor} gets them, as the
 * qualified exports name it.
 */
public final class ProcessorAccess {

    private static final String PROCESSOR = "io.vidocq.vauban.processor";

    private ProcessorAccess() {
    }

    /**
     * Exports to {@code processor} each package vauban-core's module descriptor exports to
     * {@code io.vidocq.vauban.processor} ({@code io.vidocq.vauban.core.codegen} and {@code io.vidocq.vauban.core.proxy}
     * today), as the qualified exports do to the processor's module of vauban-core's layer. Nothing changes where they
     * already reach it, where vauban-core is not a named module, whose packages are all exported, nor where the
     * processor is not one, on a class path, which no qualified export names.
     *
     * @param processor the module of the class that calls, {@code io.vidocq.vauban.processor}
     * @throws IllegalArgumentException if {@code processor} is a named module of another name
     */
    public static void grantTo(Module processor) {
        if (!processor.isNamed()) {
            return;
        }
        if (!PROCESSOR.equals(processor.getName())) {
            throw new IllegalArgumentException(PROCESSOR + " alone gets these packages, not " + processor.getName());
        }
        var core = ProcessorAccess.class.getModule();
        var descriptor = core.getDescriptor();
        if (descriptor == null) {
            return;
        }
        descriptor.exports().stream()
                .filter(export -> export.isQualified() && export.targets().contains(PROCESSOR))
                .forEach(export -> core.addExports(export.source(), processor));
    }
}
