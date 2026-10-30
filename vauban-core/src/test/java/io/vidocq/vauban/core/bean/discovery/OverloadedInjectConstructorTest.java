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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The injection points of an {@code @Inject} constructor are typed from that constructor, not from another one with
 * the same number of parameters. Discovery matched the reflected constructor on its arity alone: with an overload
 * {@code (Config, Part)} declared before {@code @Inject (Config, Instance<Part>)}, it typed the second parameter
 * {@code Part}, and two {@code Part} beans failed the deployment as ambiguous, although an {@code Instance} point is
 * never validated (VAU-DISC-003, Grimm's {@code GrimmModelCache}).
 */
@DisplayName("Bean discovery — overloaded @Inject constructor")
class OverloadedInjectConstructorTest {

    public interface Part {
        String id();
    }

    @Dependent
    public static class First implements Part {
        @Override
        public String id() {
            return "first";
        }
    }

    @Dependent
    public static class Second implements Part {
        @Override
        public String id() {
            return "second";
        }
    }

    @Dependent
    public static class Config {
    }

    @Dependent
    public static class Holder {
        final List<String> ids;

        /** Same arity as the injected constructor, declared first: the one discovery used to pick. */
        @SuppressWarnings("java:S1172") // the signature is the case under test
        public Holder(Config config, Part part) {
            this.ids = List.of(part.id());
        }

        @Inject
        @SuppressWarnings("java:S1172") // the signature is the case under test
        public Holder(Config config, Instance<Part> parts) {
            this.ids = parts.stream().map(Part::id).sorted().toList();
        }
    }

    @Test
    @DisplayName("an Instance parameter of the @Inject constructor is not validated as its element type")
    void theInjectConstructorTypesItsInjectionPoints() {
        try (var container = SeContainerInitializer.newInstance()
                .addBeanClasses(First.class, Second.class, Config.class, Holder.class)
                .initialize()) {
            assertEquals(List.of("first", "second"), container.select(Holder.class).get().ids);
        }
    }
}
