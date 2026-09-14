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
package io.vidocq.vauban.core.annotation;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What Vauban may do when an annotation reaches it as an object rather than as index data:
 * {@code -Dvauban.annotations.reflection=allow|warn|forbid}.
 *
 * <p>Matching itself never reflects — it compares {@link AnnotationKey}s built from the index. Four
 * things still can: reading the members of an annotation instance, reading what an annotation type
 * declares when neither the index nor a class file describes it, building an instance to hand to
 * application code, and reading the annotations off a field or a parameter when no descriptor
 * describes that injection point. {@code warn} logs each one once, {@code forbid} makes it throw —
 * which is how a test pins what the generated path covers, and how a native-image user sees what is
 * left.
 *
 * <p>The SPI facades ({@code Annotated#getAnnotations()} and friends) are outside this: the
 * specification requires them to hand out the annotations themselves, so they are not a fallback.
 */
public enum AnnotationReflection {

    /** Reflect when needed. The default. */
    ALLOW,
    /** Reflect, and log the first time each type needs it. */
    WARN,
    /** Refuse to reflect. */
    FORBID;

    public static final String PROPERTY = "vauban.annotations.reflection";

    /**
     * What {@link #FORBID} throws. It has a type of its own so that no {@code catch (Exception)} on
     * the way out can swallow it: a mode whose whole purpose is to make a fallback visible would be
     * worthless if a caller turned it back into a silent one.
     */
    public static final class ForbiddenException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        ForbiddenException(String message) {
            super(message);
        }
    }

    /**
     * What to do about it, when there is something to do. A message that only says a fallback was
     * taken leaves the reader to work out whether it is their fault and what would remove it.
     */
    private static String remedy(String subject) {
        if (subject.startsWith("field ") || subject.startsWith("parameter ")) {
            return " No descriptor describes that injection point: it belongs to a class the index"
                    + " does not cover — an interceptor injected without one, or a type added at run"
                    + " time.";
        }
        return " Compile the module that declares " + subject + " with the Vauban annotation"
                + " processor and it ships what the container needs. If it is a third party's, the"
                + " module that uses it carries a literal instead — unless the type is not public,"
                + " which no other package can implement.";
    }

    private static final System.Logger LOG = System.getLogger(AnnotationReflection.class.getName());
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** The mode the system property asks for, read on each call so a test can set and restore it. */
    public static AnnotationReflection current() {
        var value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            return ALLOW;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "allow" -> ALLOW;
            case "warn" -> WARN;
            case "forbid" -> FORBID;
            default -> throw new IllegalStateException(
                    PROPERTY + " must be allow, warn or forbid, not " + value);
        };
    }

    /**
     * Called before reading the qualifiers off a parameter, when no descriptor describes it.
     * Every such read goes through here, so {@code forbid} names the exact injection point.
     */
    public static void checkParameter(java.lang.reflect.Parameter param) {
        var member = param.getDeclaringExecutable();
        var name = member instanceof java.lang.reflect.Constructor<?> ? "<init>" : member.getName();
        check("reading the qualifiers off", "parameter "
                + java.util.Arrays.asList(member.getParameters()).indexOf(param)
                + " of " + member.getDeclaringClass().getName() + "." + name + "()");
    }

    /** As {@link #checkParameter}, for a field. */
    public static void checkField(java.lang.reflect.Field field) {
        check("reading the qualifiers off",
                "field " + field.getDeclaringClass().getName() + "." + field.getName());
    }

    /**
     * Called by a fallback before it reflects.
     *
     * @param what what it is about to do, as a sentence opening: "reading the members of"
     * @param subject the annotation type, field or parameter it concerns
     * @throws ForbiddenException in {@link #FORBID}
     */
    public static void check(String what, String subject) {
        var mode = current();
        if (mode == ALLOW) {
            return;
        }
        var message = PROPERTY + "=" + mode.name().toLowerCase(Locale.ROOT)
                + ": " + what + " " + subject + " needs reflection." + remedy(subject);
        if (mode == FORBID) {
            throw new ForbiddenException(message);
        }
        if (WARNED.add(what + " " + subject)) {
            LOG.log(System.Logger.Level.WARNING, message);
        }
    }
}
