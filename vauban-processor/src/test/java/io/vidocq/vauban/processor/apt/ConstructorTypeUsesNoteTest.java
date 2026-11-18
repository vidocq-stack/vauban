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
package io.vidocq.vauban.processor.apt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261118-06: a compiler without {@code com.sun.source} trees gives no source to read a constructor's
 * type-use-only annotations from; the processor says so in a note, once, instead of leaving them out silently.
 */
@DisplayName("BUG-20261118-06 - a note says when a constructor's type-use-only annotations are not read")
class ConstructorTypeUsesNoteTest {

    @Test
    @DisplayName("a compiler without trees gets the note")
    void aCompilerWithoutTreesGetsTheNote() {
        var messager = new RecordingMessager();

        ElementScanner.noteWhenConstructorTypeUsesAreUnread(new TreelessEnvironment(messager));

        assertEquals(List.of("NOTE " + ConstructorTypeUses.UNREAD), messager.printed);
    }

    @Test
    @DisplayName("javac gets no such note")
    void javacGetsNoNote(@TempDir Path dir) throws IOException {
        var result = ProcessorCompiler.compile(dir, Map.of("app/Greeter.java", """
                package app;
                @jakarta.enterprise.context.Dependent
                public class Greeter {
                }
                """));

        assertTrue(result.success(), () -> String.join("\n", result.messages()));
        assertTrue(result.messages().stream().noneMatch(message -> message.contains("no com.sun.source trees")),
                () -> String.join("\n", result.messages()));
    }

    /** Records what is printed, by kind. */
    private static final class RecordingMessager implements Messager {

        private final List<String> printed = new ArrayList<>();

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence message) {
            printed.add(kind + " " + message);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence message, Element element) {
            printMessage(kind, message);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence message, Element element,
                AnnotationMirror annotation) {
            printMessage(kind, message);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence message, Element element,
                AnnotationMirror annotation, AnnotationValue value) {
            printMessage(kind, message);
        }
    }

    /** A processing environment of a compiler that is not javac: {@code Trees.instance} refuses it. */
    private record TreelessEnvironment(Messager messager) implements ProcessingEnvironment {

        @Override
        public Map<String, String> getOptions() {
            return Map.of();
        }

        @Override
        public Messager getMessager() {
            return messager;
        }

        @Override
        public Filer getFiler() {
            return null;
        }

        @Override
        public Elements getElementUtils() {
            return null;
        }

        @Override
        public Types getTypeUtils() {
            return null;
        }

        @Override
        public SourceVersion getSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public Locale getLocale() {
            return Locale.ROOT;
        }
    }
}
