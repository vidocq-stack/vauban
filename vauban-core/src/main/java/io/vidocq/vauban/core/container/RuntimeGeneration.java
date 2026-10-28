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
package io.vidocq.vauban.core.container;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The classes a container generates at run time because the build did not (vauban#227): an intercepted subclass or a
 * client proxy for a bean compiled without the Vauban annotation processor and not processed by the Maven plugin, or a
 * {@code java.lang.reflect.Proxy} for an interface no provider proxies. That fallback is kept for such beans, but it
 * is the exception: it costs boot time, needs deep access to the bean's package on the module path, and has no
 * ahead-of-time equivalent. So it is announced loudly: one warning at the end of the boot for everything the boot
 * generated or knows it will, then one warning for each class generated later that the boot did not announce.
 */
final class RuntimeGeneration {

    private static final System.Logger LOG = System.getLogger(RuntimeGeneration.class.getName());

    private static final String RULE = "=".repeat(100);

    /** What is generated at run time. */
    enum Kind {
        INTERCEPTED_SUBCLASS("intercepted subclass"),
        CLIENT_PROXY("client proxy"),
        INTERFACE_PROXY("interface client proxy (java.lang.reflect.Proxy)");

        private final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** One class generated at run time: what it is, and its name (the interface, for a {@code reflect.Proxy}). */
    record Generated(Kind kind, String className) {
        String line() {
            return "  - " + kind.label + " " + className;
        }
    }

    private final Set<Generated> atBoot = new LinkedHashSet<>();
    private final Set<Generated> announced = ConcurrentHashMap.newKeySet();
    private final Consumer<String> warn;
    /** Guarded by {@link #atBoot}. */
    private boolean booted;

    /** Warns through the {@code System.Logger} of this class. */
    RuntimeGeneration() {
        this(message -> LOG.log(System.Logger.Level.WARNING, message));
    }

    RuntimeGeneration(Consumer<String> warn) {
        this.warn = warn;
    }

    /**
     * Records a class generated, or about to be, at run time. During the boot it joins the boot's warning; after it,
     * it is announced at once, unless the boot already did.
     */
    void generated(Kind kind, String className) {
        var generated = new Generated(kind, className);
        synchronized (atBoot) {
            if (!booted) {
                atBoot.add(generated);
                return;
            }
        }
        if (announced.add(generated)) {
            warn.accept(warning(List.of(generated), "now, after the boot"));
        }
    }

    /** Ends the boot: warns once for everything recorded so far, then announces later classes one by one. */
    void bootCompleted() {
        List<Generated> generated;
        synchronized (atBoot) {
            generated = new ArrayList<>(atBoot);
            booted = true;
        }
        announced.addAll(generated);
        if (!generated.isEmpty()) {
            warn.accept(warning(generated, "at boot"));
        }
    }

    /** What was announced so far: the boot's classes, then each later one. */
    Set<Generated> announced() {
        return Set.copyOf(announced);
    }

    /** What the boot recorded. */
    List<Generated> recordedAtBoot() {
        synchronized (atBoot) {
            return List.copyOf(atBoot);
        }
    }

    /** The warning, framed so that it stands out of the boot log. */
    static String warning(Collection<Generated> generated, String when) {
        var sb = new StringBuilder("\n").append(RULE).append('\n');
        sb.append("VAUBAN — RUNTIME CODE GENERATION FALLBACK\n");
        sb.append(generated.size()).append(generated.size() == 1 ? " class is" : " classes are")
                .append(" generated ").append(when).append(" instead of at build time:\n");
        generated.forEach(g -> sb.append(g.line()).append('\n'));
        sb.append("""
                Their beans were compiled without the Vauban annotation processor (vauban-processor) and not
                processed by the vauban-maven-plugin. This fallback is exceptional: it slows the boot, needs deep
                access to the bean's package on the module path, and cannot run ahead of time (native image, CDS).
                Fix: add vauban-processor to the annotation processor path of the module that declares these beans.
                """);
        return sb.append(RULE).toString();
    }
}
