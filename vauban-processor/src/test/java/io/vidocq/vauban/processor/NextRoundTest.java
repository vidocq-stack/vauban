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
package io.vidocq.vauban.processor;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compilation unit that has javac start one more round (BUG-20261115-06): its name and content, and what happens
 * when it cannot be written.
 */
class NextRoundTest {

    private final List<String> messages = new ArrayList<>();

    @Test
    void aUnitOfTheUnnamedPackageHasNoPackageClause() {
        var filer = new RecordingFiler(null);

        NextRound.request(filer, messager(), "", null, 1);

        assertEquals("_VaubanRound1", filer.name);
        assertFalse(filer.content.toString().contains("package"), filer.content.toString());
        assertEquals(List.of(), messages);
    }

    @Test
    void aUnitOfAPackageDeclaresIt() {
        var filer = new RecordingFiler(null);

        NextRound.request(filer, messager(), "app.web", null, 2);

        assertEquals("app.web._VaubanRound2", filer.name);
        assertTrue(filer.content.toString().endsWith("package app.web;\n"), filer.content.toString());
    }

    /** A name the Filer refuses, as one a source of the compilation already declares: the last round, silently. */
    @Test
    void aNameTheFilerRefusesFallsBackSilently() {
        NextRound.request(new RecordingFiler(new FilerException("already created")), messager(), "app", null, 1);

        assertEquals(List.of(), messages);
    }

    /** Any other failure to write the unit is noted, then the classes go to the last round. */
    @Test
    void aUnitThatCannotBeWrittenIsNoted() {
        NextRound.request(new RecordingFiler(new IOException("no space left on device")), messager(), "app", null, 1);

        assertEquals(1, messages.size(), messages.toString());
        assertTrue(messages.getFirst().startsWith("NOTE: [Vauban]"), messages.toString());
        assertTrue(messages.getFirst().contains("app._VaubanRound1")
                && messages.getFirst().contains("no space left on device"), messages.toString());
    }

    private Messager messager() {
        return new Messager() {
            @Override
            public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
                messages.add(kind + ": " + msg);
            }

            @Override
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
                printMessage(kind, msg);
            }

            @Override
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
                printMessage(kind, msg);
            }

            @Override
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a,
                                     AnnotationValue v) {
                printMessage(kind, msg);
            }
        };
    }

    /**
     * Records the source file asked for and what is written to it; with {@code failure}, a {@link FilerException}
     * fails the creation, another exception the writing.
     */
    private static final class RecordingFiler implements Filer {

        private final IOException failure;
        private final StringWriter content = new StringWriter();
        private String name;

        RecordingFiler(IOException failure) {
            this.failure = failure;
        }

        @Override
        public JavaFileObject createSourceFile(CharSequence name, Element... originatingElements)
                throws IOException {
            if (failure instanceof FilerException) {
                throw failure;
            }
            this.name = name.toString();
            return new SimpleJavaFileObject(URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public Writer openWriter() throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    return content;
                }
            };
        }

        @Override
        public JavaFileObject createClassFile(CharSequence name, Element... originatingElements) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileObject createResource(JavaFileManager.Location location, CharSequence moduleAndPkg,
                                         CharSequence relativeName, Element... originatingElements) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileObject getResource(JavaFileManager.Location location, CharSequence moduleAndPkg,
                                      CharSequence relativeName) {
            throw new UnsupportedOperationException();
        }
    }
}
