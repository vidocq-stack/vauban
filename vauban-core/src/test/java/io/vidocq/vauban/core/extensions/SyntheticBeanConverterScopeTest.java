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

import io.vidocq.vauban.core.bean.model.ScopeInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ConversationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Singleton;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.annotation.Annotation;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The scope {@link SyntheticBeanConverter} gives the descriptor of a synthetic bean, from the scope it declares. */
class SyntheticBeanConverterScopeTest {

    static Stream<Arguments> scopes() {
        return Stream.of(
                Arguments.of(ApplicationScoped.class, ScopeInfo.APPLICATION),
                Arguments.of(RequestScoped.class, ScopeInfo.REQUEST),
                Arguments.of(Singleton.class, ScopeInfo.SINGLETON),
                Arguments.of(SessionScoped.class, ScopeInfo.SESSION),
                Arguments.of(ConversationScoped.class, ScopeInfo.CONVERSATION),
                Arguments.of(Dependent.class, ScopeInfo.DEPENDENT),
                Arguments.of(null, ScopeInfo.DEPENDENT));
    }

    @ParameterizedTest
    @MethodSource("scopes")
    void aSyntheticBeanTakesTheScopeItDeclaresOrDependent(Class<? extends Annotation> scope, ScopeInfo expected) {
        var builder = new VaubanSyntheticBeanBuilder<>(String.class);
        if (scope != null) {
            builder.scope(scope);
        }

        assertEquals(expected, SyntheticBeanConverter.toBeanDescriptor(builder, 0).scope());
    }
}
