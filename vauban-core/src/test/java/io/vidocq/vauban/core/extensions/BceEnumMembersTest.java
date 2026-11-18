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
package io.vidocq.vauban.core.extensions;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.ParameterInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The members of an enum, as an extension sees them at start: an enum's constructor has the parameters its source
 * declares, not the name and the ordinal javac adds (BUG-20261117-58).
 */
@DisplayName("The members of an enum at start")
class BceEnumMembersTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})
    public @interface Marked {
    }

    public enum Level {
        @Marked LOW("low", true),
        HIGH("high", false);

        private final String label;

        Level(@Marked String label, boolean flag) {
            this.label = flag ? label : label.toUpperCase();
        }

        public String label() {
            return label;
        }
    }

    static final List<String> SEEN = new CopyOnWriteArrayList<>();

    /** Records what {@link #describe} says of the class {@link #probed} names. */
    public static class Recorder implements BuildCompatibleExtension {

        static volatile String probed;
        static volatile Function<ClassInfo, List<String>> describe;

        @Synthesis
        public void describeProbed(Types types) {
            SEEN.addAll(describe.apply(types.ofClass(probed).declaration()));
        }
    }

    @Test
    @DisplayName("an enum's constructor has the parameters its source declares, with their annotations")
    void anEnumConstructorHasItsDeclaredParameters() {
        assertEquals(List.of("[java.lang.String @Marked, boolean]", "[@Marked java.lang.String, boolean]"),
                seenAtStart(Level.class, type -> type.constructors().stream()
                        .<String>mapMulti((constructor, lines) -> {
                            lines.accept(parameters(constructor, parameter -> parameter.type() + " "
                                    + marks(parameter.annotations().size())));
                            lines.accept(parameters(constructor, parameter -> marks(parameter.type().annotations()
                                    .size()) + parameter.type()));
                        }).toList()));
    }

    private static String parameters(MethodInfo constructor, Function<ParameterInfo, String> describe) {
        return constructor.parameters().stream().map(describe).map(String::strip).toList().toString();
    }

    private static String marks(int count) {
        return "@Marked ".repeat(count);
    }

    static List<String> seenAtStart(Class<?> probe, Function<ClassInfo, List<String>> describe) {
        SEEN.clear();
        Recorder.probed = probe.getName();
        Recorder.describe = describe;
        try (var _ = SeContainerInitializer.newInstance().disableDiscovery().addBeanClasses(Recorder.class)
                .initialize()) {
            return List.copyOf(SEEN);
        }
    }
}
