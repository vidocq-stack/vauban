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
package io.vidocq.vauban.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.literal.NamedLiteral;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The less common shapes of a bean written as text: char literals, several members, empty parentheses, escapes. */
class BeanDeclarationSyntaxTest {

    private static final ClassLoader LOADER = BeanDeclarationSyntaxTest.class.getClassLoader();
    private static final String STRING = "java.lang.String";
    private static final String BADGE = STRING + " @io.vidocq.vauban.naming.Badge";

    @Badge('=')
    static class EqualsBadge {
    }

    @Badge(value = ',', rank = 2)
    static class CommaBadge {
    }

    @Primary
    static class PrimaryCarrier {
    }

    private static Set<Annotation> qualifiersOf(String declaration) {
        return ((JndiTarget.Bean) JndiTarget.bean(declaration, LOADER)).qualifiers();
    }

    private static String refusal(String declaration) {
        return assertThrows(IllegalArgumentException.class, () -> JndiTarget.bean(declaration, LOADER)).getMessage();
    }

    @Test
    void aQualifierWithoutParenthesesIsFollowedByTheNextOne() {
        assertEquals(List.of(Default.Literal.INSTANCE, Any.Literal.INSTANCE),
                List.copyOf(qualifiersOf(STRING + " @Default @Any")));
    }

    @Test
    void aCharLiteralMayHoldAnEqualsSignOrAComma() {
        assertEquals(Set.of(EqualsBadge.class.getAnnotation(Badge.class)), qualifiersOf(BADGE + "('=')"));
        assertEquals(Set.of(CommaBadge.class.getAnnotation(Badge.class)),
                qualifiersOf(BADGE + "(value = ',', rank = 2)"));
    }

    @Test
    void emptyParenthesesAreNoMember() {
        assertEquals(Set.of(PrimaryCarrier.class.getAnnotation(Primary.class)),
                qualifiersOf(STRING + " @io.vidocq.vauban.naming.Primary()"));
    }

    @ParameterizedTest
    @MethodSource("refusedDeclarations")
    void aWrongDeclarationIsRefusedWithItsReason(String declaration, String reason) {
        var message = refusal(declaration);

        assertTrue(message.startsWith("Invalid bean declaration '" + declaration + "'"), message);
        assertTrue(message.contains(reason), message);
    }

    static Stream<Arguments> refusedDeclarations() {
        return Stream.of(
                Arguments.of(STRING + " @Named(value = \"a\", other = \"b\")", "@Named takes a name"),
                Arguments.of(STRING + " @Named(\"a\", \"b\")", "several members are written name = value"),
                Arguments.of(STRING + " @(\"a\")", "expected a name at '(\"a\")'"),
                Arguments.of(STRING + " @Named(x)", "a name is written between double quotes: x"),
                Arguments.of(STRING + " @Named(orders)", "a name is written between double quotes: orders"),
                Arguments.of(STRING + " @Named(\"a\" b)", "a name is written between double quotes: \"a\" b"));
    }

    @ParameterizedTest
    @MethodSource("escapes")
    void aNamedValueTakesEveryEscapeOfJava(String written, String name) {
        assertEquals(Set.of(NamedLiteral.of(name)), qualifiersOf(STRING + " @Named(\"" + written + "\")"));
    }

    static Stream<Arguments> escapes() {
        return Stream.of(
                Arguments.of("a\\rb", "a\rb"),
                Arguments.of("a\\bb", "a\bb"),
                Arguments.of("a\\fb", "a\fb"),
                Arguments.of("a\\0b", "a\0b"),
                Arguments.of("a\\nb", "a\nb"),
                Arguments.of("a\\'b", "a'b"));
    }
}
