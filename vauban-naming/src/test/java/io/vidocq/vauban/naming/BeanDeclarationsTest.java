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
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A bean written as text, as a configuration file names it: a type and its qualifiers. */
class BeanDeclarationsTest {

    @Region("eu")
    @Tier(level = 2)
    @Primary
    static class Carrier {
    }

    @Region("a,b)@c")
    static class Punctuated {
    }

    @Region("say \"hi\"")
    static class Quoted {
    }

    private static final ClassLoader LOADER = BeanDeclarationsTest.class.getClassLoader();

    private static JndiTarget.Bean bean(String declaration) {
        return (JndiTarget.Bean) JndiTarget.bean(declaration, LOADER);
    }

    private static Set<Annotation> qualifiersOf(Class<?> carrier, Class<? extends Annotation>... types) {
        var qualifiers = new java.util.LinkedHashSet<Annotation>();
        for (var type : types) {
            qualifiers.add(carrier.getAnnotation(type));
        }
        return qualifiers;
    }

    private static void assertRefused(String declaration, String reason) {
        var failure = assertThrows(IllegalArgumentException.class, () -> JndiTarget.bean(declaration, LOADER));

        assertTrue(failure.getMessage().contains(declaration.strip()), failure.getMessage());
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }

    @Test
    void aTypeAloneIsTheDefaultBean() {
        var bean = bean("java.lang.String");

        assertEquals(String.class, bean.type());
        assertEquals(Set.of(), bean.qualifiers());
        assertEquals("java.lang.String", bean.declaration());
    }

    @Test
    void theShortNamesAreJakartaQualifiers() {
        assertEquals(Set.of(NamedLiteral.of("orders")), bean("javax.sql.DataSource @Named(\"orders\")").qualifiers());
        assertEquals(Set.of(NamedLiteral.of("orders")), bean("javax.sql.DataSource @Named(value = \"orders\")").qualifiers());
        assertEquals(Set.of(Default.Literal.INSTANCE), bean("java.lang.String @Default").qualifiers());
        assertEquals(Set.of(Any.Literal.INSTANCE), bean("java.lang.String @Any").qualifiers());
    }

    @Test
    void aFullyQualifiedQualifierWithMembers() {
        var bean = bean("java.lang.String @io.vidocq.vauban.naming.Region(\"eu\") @io.vidocq.vauban.naming.Tier(level = 2)"
                + " @io.vidocq.vauban.naming.Primary");

        assertEquals(qualifiersOf(Carrier.class, Region.class, Tier.class, Primary.class), bean.qualifiers());
    }

    @Test
    void spacesAroundTokensAreIgnored() {
        assertEquals(bean("java.lang.String @Named(\"x\")").qualifiers(),
                bean("  java.lang.String   @Named( \"x\" )  ").qualifiers());
        assertEquals(bean("java.lang.String @io.vidocq.vauban.naming.Tier(level=2)").qualifiers(),
                bean("java.lang.String @io.vidocq.vauban.naming.Tier( level = 2 )").qualifiers());
    }

    @Test
    void aStringMemberKeepsCommasParenthesesAtSignsAndEscapedQuotes() {
        assertEquals(qualifiersOf(Punctuated.class, Region.class),
                bean("java.lang.String @io.vidocq.vauban.naming.Region(\"a,b)@c\")").qualifiers());
        assertEquals(qualifiersOf(Quoted.class, Region.class),
                bean("java.lang.String @io.vidocq.vauban.naming.Region(\"say \\\"hi\\\"\")").qualifiers());
        assertEquals(Set.of(NamedLiteral.of("a\"b")), bean("java.lang.String @Named(\"a\\\"b\")").qualifiers());
    }

    @Test
    void aNestedClassIsWrittenAsInSource() {
        assertEquals(Carrier.class, bean("io.vidocq.vauban.naming.BeanDeclarationsTest.Carrier").type());
    }

    @Test
    void aBeanGivenAsAClassKeepsItsQualifiers() {
        var bean = (JndiTarget.Bean) JndiTarget.bean(String.class, NamedLiteral.of("x"));

        assertEquals(String.class, bean.type());
        assertEquals(Set.of(NamedLiteral.of("x")), bean.qualifiers());
        assertEquals("java.lang.String @Named(\"x\")", bean.declaration());
        assertEquals("java.lang.String @Default @Any",
                ((JndiTarget.Bean) JndiTarget.bean(String.class, Default.Literal.INSTANCE, Any.Literal.INSTANCE))
                        .declaration());
    }

    @Test
    void wrongDeclarationsAreRefusedWithTheirReason() {
        assertRefused("com.acme.Missing", "no class com.acme.Missing");
        assertRefused("java.lang.String @Named", "@Named takes a name");
        assertRefused("java.lang.String @Primary", "fully qualified name");
        assertRefused("java.lang.String @java.lang.Missing", "no annotation java.lang.Missing");
        assertRefused("java.lang.String @java.lang.String", "no annotation java.lang.String");
        assertRefused("java.lang.String @io.vidocq.vauban.naming.Region({\"a\"})", "arrays are not supported");
        assertRefused("java.lang.String @io.vidocq.vauban.naming.Region(\"eu)", "unbalanced");
        assertRefused("java.lang.String @io.vidocq.vauban.naming.Region(\"eu\"", "unbalanced");
        assertRefused("java.lang.String @io.vidocq.vauban.naming.Tier(level = \"two\")", "level");
        assertRefused("java.lang.String Named", "expected @");
        assertRefused("   ", "empty");
    }

    @Test
    void aLinkParsesItsName() {
        assertEquals(new JndiTarget.Link(new JndiName(Namespace.COMP, "env/jdbc/orders")),
                JndiTarget.link("java:comp/env/jdbc/orders"));
        assertThrows(IllegalArgumentException.class, () -> JndiTarget.link("jdbc/orders"));
    }
}
