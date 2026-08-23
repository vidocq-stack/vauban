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
package io.vidocq.vauban.weaver;

import com.sun.tools.attach.VirtualMachine;

/**
 * Child-process attacher: the JVM cannot attach an agent to itself by default
 * ({@code jdk.attach.allowAttachSelf} is {@code false}), but any process of the same user
 * may attach to it. The container therefore spawns
 * {@code java -cp vauban-weaver.jar io.vidocq.vauban.weaver.AttachBack <pid> <agent-jar> <plan>}
 * (equivalently {@code java -jar}, see {@code Main-Class}); this program attaches back to
 * the parent, loads {@link WeavingAgent} with the plan path as agent argument, and exits.
 *
 * <p>Run as an unnamed-module class-path application on purpose: {@code jdk.attach} is a
 * default root module there, so no {@code --add-modules} is needed.
 */
public final class AttachBack {

    private AttachBack() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: AttachBack <pid> <agent-jar> <plan-file>");
            System.exit(2);
        }
        var vm = VirtualMachine.attach(args[0]);
        try {
            vm.loadAgent(args[1], args[2]);
        } finally {
            vm.detach();
        }
    }
}
