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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.vauban.indexer.model.AnnotationInfo;
import io.vidocq.vauban.indexer.model.DotName;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The record of the extensions frozen at build")
class FrozenExtensionsRecordTest {

    @TempDir
    Path output;

    private static FrozenExtensions aRecord(String fingerprint) {
        var stereotypes = new LinkedHashMap<String, List<String>>();
        stereotypes.put("com.acme.Shared", List.of("jakarta.enterprise.context.ApplicationScoped", "jakarta.inject.Named"));
        stereotypes.put("com.acme.Bare", List.of());
        var synthetic = new Properties();
        synthetic.setProperty("bean.count", "1");
        synthetic.setProperty("bean.0.beanClass", "com.acme.Greeting");
        synthetic.setProperty("bean.0.param.text", "S:hello = world: \\ é");
        return new FrozenExtensions(fingerprint,
                List.of("com.acme.StaticExtension", "com.acme.StartCheckExtension"),
                Set.of("com.acme.StartCheckExtension"),
                new FrozenExtensions.Discovery(List.of("com.acme.Extra"), List.of("com.acme.Tagged"),
                        List.of("com.acme.Logged"), stereotypes,
                        Map.of("com.acme.Tagged", List.of("comment", "weight")),
                        List.of(new FrozenContexts.Entry("com.acme.Cached", "com.acme.CachedContext", false))),
                Map.of("com.acme.Resource",
                        List.of(new AnnotationInfo(DotName.of("jakarta.enterprise.context.RequestScoped"), Map.of()))),
                synthetic);
    }

    private static ClassLoader loaderOf(Path... roots) throws IOException {
        var urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            urls[i] = roots[i].toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    @Test
    @DisplayName("what a build writes is what a start reads")
    void whatABuildWritesIsWhatAStartReads() throws IOException {
        aRecord("abc123").write(output);

        var found = FrozenExtensions.find(loaderOf(output));

        assertNull(found.note());
        var read = found.frozen();
        assertNotNull(read);
        assertEquals("abc123", read.fingerprint());
        assertEquals(List.of("com.acme.StaticExtension", "com.acme.StartCheckExtension"), read.extensions());
        assertEquals(Set.of("com.acme.StartCheckExtension"), read.startExtensions());
        assertEquals(aRecord("abc123").discovery(), read.discovery());
        assertEquals(aRecord("abc123").enhancements(), read.enhancements());
        assertEquals("S:hello = world: \\ é", read.synthetic().getProperty("bean.0.param.text"));
        assertEquals("com.acme.Greeting", read.synthetic().getProperty("bean.0.beanClass"));
    }

    @Test
    @DisplayName("the same record gives the same files: sorted, without a date")
    void theSameRecordGivesTheSameFiles() throws IOException {
        Path again = Files.createDirectory(output.resolve("again"));
        aRecord("abc123").write(output);
        aRecord("abc123").write(again);

        for (String path : List.of(FrozenExtensions.PATH, FrozenExtensions.ENHANCEMENTS_PATH,
                FrozenExtensions.SYNTHETIC_PATH)) {
            String written = Files.readString(output.resolve(path), StandardCharsets.UTF_8);
            assertEquals(written, Files.readString(again.resolve(path), StandardCharsets.UTF_8), path);
            assertFalse(written.matches("(?s).*\\b20\\d\\d\\b.*"), "no date in " + path + ": " + written);
        }
    }

    @Test
    @DisplayName("no record: nothing to use, nothing to say")
    void noRecord() throws IOException {
        var found = FrozenExtensions.find(loaderOf(output));

        assertNull(found.frozen());
        assertNull(found.note());
    }

    @Test
    @DisplayName("two records on the path: none is this deployment's for sure, none is used")
    void twoRecordsOnThePath() throws IOException {
        Path other = Files.createDirectory(output.resolve("other"));
        Path one = Files.createDirectory(output.resolve("one"));
        aRecord("abc123").write(one);
        aRecord("def456").write(other);

        var found = FrozenExtensions.find(loaderOf(one, other));

        assertNull(found.frozen());
        assertTrue(found.note().contains("2 records"), found.note());
    }

    @Test
    @DisplayName("one record a loader and its parent both see is one record")
    void oneRecordSeenThroughTwoLoaders() throws IOException {
        // BUG-20261113-17: in a jlink image, Vidocq layers the application module again over the boot layer that
        // holds it, and the layer's loader returns the parent's URL and its own, the same one.
        aRecord("abc123").write(output);
        URL root = output.toUri().toURL();
        var layer = new URLClassLoader(new URL[] {root}, new URLClassLoader(new URL[] {root}, null));

        var found = FrozenExtensions.find(layer);

        assertNull(found.note());
        assertNotNull(found.frozen());
        assertEquals("abc123", found.frozen().fingerprint());
    }

    @Test
    @DisplayName("a record of another format, or without one of its files, is not used and the note says why")
    void anUnreadableRecord() throws IOException {
        aRecord("abc123").write(output);
        Path main = output.resolve(FrozenExtensions.PATH);
        Files.writeString(main, Files.readString(main).replace("format=1", "format=2"));
        var otherFormat = FrozenExtensions.find(loaderOf(output));
        assertNull(otherFormat.frozen());
        assertTrue(otherFormat.note().contains("format 2"), otherFormat.note());

        aRecord("abc123").write(output);
        Files.delete(output.resolve(FrozenExtensions.SYNTHETIC_PATH));
        var incomplete = FrozenExtensions.find(loaderOf(output));
        assertNull(incomplete.frozen());
        assertTrue(incomplete.note().contains("cannot be read"), incomplete.note());
    }

    @Test
    @DisplayName("deleting a record removes its three files")
    void deletingARecord() throws IOException {
        aRecord("abc123").write(output);

        FrozenExtensions.delete(output);

        try (var files = Files.walk(output)) {
            assertEquals(List.of(), files.filter(Files::isRegularFile).toList());
        }
        FrozenExtensions.delete(output);
    }
}
