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

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CDI 4.1 gives the {@code BeanInfo} of a producer bean the method or the field that declares it. The container gives
 * {@code null} for both (BUG-20261117-13).
 */
@DisplayName("@Registration with a BeanInfo parameter gives a producer bean its method or field (BUG-20261117-13)")
class BceRegistrationProducerInfoTest {

    @Dependent
    public static class Producers {

        @Produces
        Long level = 1L;

        @Produces
        String name() {
            return "name";
        }
    }

    /** Records the member that declares each producer bean of a {@code String} or a {@code Long} it is given. */
    public static class ProducerDescriber implements BuildCompatibleExtension {

        static final List<String> SEEN = new CopyOnWriteArrayList<>();

        @Registration(types = {String.class, Long.class})
        public void producers(BeanInfo bean) {
            if (bean.isProducerMethod()) {
                SEEN.add("method " + bean.producerMethod().name());
            } else if (bean.isProducerField()) {
                SEEN.add("field " + bean.producerField().name());
            }
        }
    }

    @Test
    @Disabled("BUG-20261117-13: the BeanInfo of a producer bean has no producer method and no producer field")
    @DisplayName("the BeanInfo of a producer bean gives its producer method or field")
    void theBeanInfoOfAProducerGivesItsMethodOrField() {
        ProducerDescriber.SEEN.clear();

        try (var _ = SeContainerInitializer.newInstance()
                .addBeanClasses(Producers.class, ProducerDescriber.class).initialize()) {
            assertEquals(List.of("field level", "method name"), ProducerDescriber.SEEN.stream().sorted().toList());
        }
    }
}
