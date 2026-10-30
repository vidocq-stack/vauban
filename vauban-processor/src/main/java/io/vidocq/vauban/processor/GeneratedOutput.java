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

import io.vidocq.vauban.processor.codegen.GeneratedClass;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where the processor's generated classes go: the Filer, as source or as bytecode, and the build-time notes and
 * warnings about them.
 */
final class GeneratedOutput {

    private final ProcessingEnvironment processingEnv;
    /** The classes emitted as bytecode rather than source: the provider instantiates them through its lookup. */
    private final Set<String> bytecodeClasses = new LinkedHashSet<>();

    GeneratedOutput(ProcessingEnvironment processingEnv) {
        this.processingEnv = processingEnv;
    }

    /** The classes emitted as bytecode so far. */
    Set<String> bytecodeClasses() {
        return bytecodeClasses;
    }

    /** Every class this processor wrote, source or bytecode: the classes a later round sees and must not index. */
    private final Set<String> written = new LinkedHashSet<>();

    /** Whether this processor wrote {@code className}. */
    boolean wrote(String className) {
        return written.contains(className);
    }

    /** Records a class written through the Filer by an artefact that does not go through this output. */
    void recordWritten(String className) {
        written.add(className);
    }

    void generateClass(GeneratedClass generated) {
        try {
            var filer = processingEnv.getFiler();
            var fileObject = filer.createClassFile(generated.className());
            written.add(generated.className());
            try (var os = fileObject.openOutputStream()) {
                os.write(generated.bytecode());
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "[Vauban] Failed to write generated class " + generated.className() + ": " + e.getMessage());
        }
    }

    /**
     * A class emitted as bytecode because Java source in its package cannot declare the overrides
     * listed in {@code unnameable} (BUG-20261004-09): said at build time, as a note.
     */
    void noteBytecode(String className, List<String> unnameable, javax.lang.model.element.Element site) {
        noteBytecode(className, unnameable, List.of(), site);
    }

    /**
     * The same, for a client proxy that may also call {@code throughHandles} through a {@code MethodHandle}:
     * protected methods of a superclass in another package, which Java source cannot call on the delegate.
     */
    void noteBytecode(String className, List<String> unnameable, List<String> throughHandles,
            javax.lang.model.element.Element site) {
        bytecodeClasses.add(className);
        var why = new ArrayList<String>();
        if (!unnameable.isEmpty()) {
            why.add("Java source in its package cannot declare " + String.join(", ", unnameable)
                    + ", whose signature names a type the package cannot name (BUG-20261004-09)");
        }
        if (!throughHandles.isEmpty()) {
            why.add("it forwards " + String.join(", ", throughHandles)
                    + " through a MethodHandle, protected in a superclass of another package (JLS 6.6.2)");
        }
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "[Vauban] Generated " + className
                + " as bytecode: " + String.join("; ", why) + ".", site);
    }

    /**
     * A generated class leaves an inherited default method alone — not forwarded by the client
     * proxy, not intercepted by the subclass — because the rendered source cannot write it
     * (BUG-20261004-02, BUG-20261004-08): said at build time, as a warning, on the bean, or on the
     * producer method or field for a producer's proxy.
     */
    void warnOmitted(String omitted, javax.lang.model.element.Element site) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "[Vauban] " + omitted, site);
    }

    /** Write a generated Java source file (compiled by javac in a subsequent APT round). */
    void writeSourceFile(String className, String source) {
        try {
            var file = processingEnv.getFiler().createSourceFile(className);
            written.add(className);
            try (var w = file.openWriter()) {
                w.write(source);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "[Vauban] Failed to write generated source " + className + ": " + e.getMessage());
        }
    }
}
