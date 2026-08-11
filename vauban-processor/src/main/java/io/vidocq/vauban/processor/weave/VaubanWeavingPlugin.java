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
package io.vidocq.vauban.processor.weave;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

/**
 * Auto-started javac plugin executing the {@linkplain WeavePlan weave plans} published by
 * {@code VaubanProcessor} — the javac tier of the Vidocq/vauban#24 weaving architecture.
 *
 * <p>Shipping in the same jar as the annotation processor, it is discovered through
 * {@code ServiceLoader} on the processor path and started by its {@link #autoStart()}
 * contract: wherever the Vauban APT runs — Maven, Gradle, IntelliJ's internal build, bare
 * {@code javac} — the {@code (ProxyLink)} entry constructor is woven into normal-scoped
 * beans at the end of the compilation, with no build-tool plugin and no application
 * change. Only supported public API is used ({@code com.sun.source.util}); compilers
 * without javac plugins (ECJ) fall back to the other tiers.
 *
 * <p>The work happens once, at {@code COMPILATION finished} — after javac wrote every
 * class file of the task, including the APT-generated proxies compiled in later rounds.
 */
public final class VaubanWeavingPlugin implements Plugin {

    @Override
    public String getName() {
        return "VaubanWeaving";
    }

    @Override
    public boolean autoStart() {
        return true;
    }

    @Override
    public void init(JavacTask task, String... args) {
        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.COMPILATION) {
                    WeavePlan.executeRegisteredPlans();
                }
            }
        });
    }
}
