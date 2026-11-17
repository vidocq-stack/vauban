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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The synthetic metadata file is the same bytes at every build of the same sources: no date, keys sorted
 * (BUG-20261117-12). A build tool that watches it sees a change at every compile otherwise.
 */
class SyntheticMetadataStableTest {

    /** The comment {@link Properties#store} writes: {@code #Tue Nov 17 10:03:20 CET 2026}. */
    private static final Pattern STORE_DATE =
            Pattern.compile("^#\\p{Alpha}{3} \\p{Alpha}{3} \\d{2} \\d{2}:\\d{2}:\\d{2} .*\\d{4}$");

    private static VaubanSyntheticBeanBuilder<String> greeting() {
        var bean = new VaubanSyntheticBeanBuilder<>(String.class);
        bean.type(CharSequence.class);
        bean.name("greeting");
        bean.withParam("zeta", "z");
        bean.withParam("alpha", "a");
        return bean;
    }

    private static byte[] write() throws IOException {
        var out = new ByteArrayOutputStream();
        SyntheticMetadataSerializer.write(List.of(greeting(), new VaubanSyntheticBeanBuilder<>(Integer.class)),
                List.of(new VaubanSyntheticObserverBuilder<>(String.class)), out);
        return out.toByteArray();
    }

    @Test
    void theFileHasNoDateLine() throws IOException {
        var text = new String(write(), StandardCharsets.UTF_8);

        var dated = text.lines().filter(line -> STORE_DATE.matcher(line).matches()).toList();

        assertEquals(List.of(), dated);
    }

    @Test
    void twoWritesGiveTheSameBytes() throws IOException {
        var first = write();
        var second = write();

        assertEquals(new String(first, StandardCharsets.UTF_8), new String(second, StandardCharsets.UTF_8));
    }

    @Test
    void theKeysAreSorted() throws IOException {
        var keys = new ArrayList<String>();
        for (var line : new String(write(), StandardCharsets.UTF_8).lines().toList()) {
            if (!line.startsWith("#")) {
                keys.add(line.substring(0, line.indexOf('=')));
            }
        }
        var sorted = new ArrayList<>(keys);
        sorted.sort(null);

        assertTrue(keys.size() > 10);
        assertEquals(sorted, keys);
    }

    @Test
    void aFileOfAnEarlierVersionReadsTheSame() throws IOException {
        var old = """
                #Vauban synthetic metadata \\u2014 generated at compile time by APT
                #Tue Nov 17 10:03:20 CET 2026
                observer.count=0
                bean.0.types=java.lang.CharSequence,java.lang.Object
                bean.0.beanClass=java.lang.String
                bean.0.name=greeting
                bean.0.param.zeta=S:z
                bean.0.param.alpha=S:a
                bean.count=1
                """;
        var fromOld = SyntheticMetadataSerializer.readBeans(
                new ByteArrayInputStream(old.getBytes(StandardCharsets.UTF_8)));

        var out = new ByteArrayOutputStream();
        SyntheticMetadataSerializer.write(List.of(greeting()), List.of(), out);
        var fromNew = SyntheticMetadataSerializer.readBeans(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(1, fromOld.size());
        assertEquals("greeting", fromOld.getFirst().name());
        assertEquals("java.lang.String", fromOld.getFirst().beanClassName());
        assertEquals(fromNew.getFirst().params(), fromOld.getFirst().params());
        assertEquals(List.of("java.lang.CharSequence", "java.lang.Object"), fromOld.getFirst().types());
        assertEquals("greeting", fromNew.getFirst().name());
    }

    @Test
    void valuesKeepTheirEscapesAndTheirTrailingSpaces() throws IOException {
        var properties = new Properties();
        properties.setProperty("a key", "tail  ");
        properties.setProperty("b", "line\none=two\\three é—");

        var text = PropertiesText.sorted("header", properties);
        var read = new Properties();
        read.load(new StringReader(text));

        assertFalse(text.contains("\n\n"));
        assertEquals(properties, read);
    }

    @Test
    void leadingSpacesAndSpecialCharactersReadBackUnchanged() throws IOException {
        var properties = new Properties();
        properties.setProperty(" leading key", " leading value");
        properties.setProperty("k#1!2:3=4", "v");
        properties.setProperty("hash", "#value");
        properties.setProperty("bang", "!value");
        properties.setProperty("colon", ":value");

        var read = new Properties();
        read.load(new StringReader(PropertiesText.sorted("header", properties)));

        assertEquals(properties, read);
        assertEquals(" leading value", read.getProperty(" leading key"));
        assertEquals("v", read.getProperty("k#1!2:3=4"));
        assertEquals("#value", read.getProperty("hash"));
        assertEquals("!value", read.getProperty("bang"));
    }
}
