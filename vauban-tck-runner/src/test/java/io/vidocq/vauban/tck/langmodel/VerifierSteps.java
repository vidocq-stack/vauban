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
package io.vidocq.vauban.tck.langmodel;

import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.jboss.cdi.lang.model.tck.AnnotatedReceiverTypes;
import org.jboss.cdi.lang.model.tck.AnnotatedSuperTypes;
import org.jboss.cdi.lang.model.tck.AnnotatedThrowsTypes;
import org.jboss.cdi.lang.model.tck.AnnotatedTypes;
import org.jboss.cdi.lang.model.tck.AnnotationInstances;
import org.jboss.cdi.lang.model.tck.AnnotationMembers;
import org.jboss.cdi.lang.model.tck.BridgeMethods;
import org.jboss.cdi.lang.model.tck.DefaultConstructors;
import org.jboss.cdi.lang.model.tck.EnumMembers;
import org.jboss.cdi.lang.model.tck.Equality;
import org.jboss.cdi.lang.model.tck.InheritedAnnotations;
import org.jboss.cdi.lang.model.tck.InheritedFields;
import org.jboss.cdi.lang.model.tck.InheritedMethods;
import org.jboss.cdi.lang.model.tck.InterfaceMembers;
import org.jboss.cdi.lang.model.tck.JavaLangObjectMethods;
import org.jboss.cdi.lang.model.tck.LangModelUtils;
import org.jboss.cdi.lang.model.tck.LangModelVerifier;
import org.jboss.cdi.lang.model.tck.PlainClassMembers;
import org.jboss.cdi.lang.model.tck.PrimitiveTypes;
import org.jboss.cdi.lang.model.tck.RepeatableAnnotations;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The checks of the CDI language model TCK: {@code LangModelVerifier.verify} as the TCK runs it, then each of its
 * steps on its own, so that a failing step does not hide the steps after it. The type annotation checks are on.
 */
final class VerifierSteps {

    /** The whole verification, as the TCK asks an implementation to run it. */
    static final String VERIFY = "verify";

    private static final String TCK_PACKAGE = "org.jboss.cdi.lang.model.tck.";

    private VerifierSteps() {
    }

    /** What a check did: its name, and what it threw, {@code null} when it passed. */
    record Outcome(String check, Throwable failure) {

        /** The failure and the lines of the verifier it went through, innermost first. */
        String describe() {
            return failure + " at " + String.join(" <- ", tckLines());
        }

        /** The innermost line of the verifier the failure went through, as File.java:N, or "" if none. */
        String failedLine() {
            var lines = tckLines();
            return lines.isEmpty() ? "" : lines.getFirst();
        }

        private List<String> tckLines() {
            var frames = new ArrayList<String>();
            for (var frame : failure.getStackTrace()) {
                if (frame.getClassName().startsWith(TCK_PACKAGE) && frames.size() < 4) {
                    frames.add(frame.getFileName() + ":" + frame.getLineNumber());
                }
            }
            return frames;
        }
    }

    /** Runs every check on {@code verifier}, the language model of {@link LangModelVerifier}. */
    static List<Outcome> run(ClassInfo verifier) {
        // A static of the TCK, left on: the runner forks its own JVM, and every place of it runs the checks on.
        LangModelVerifier.RUN_TYPE_ANNOTATION_TESTS = true;
        var outcomes = new ArrayList<Outcome>();
        checks().forEach((name, check) -> outcomes.add(outcome(name, () -> check.accept(verifier))));
        return outcomes;
    }

    private static Outcome outcome(String check, Runnable run) {
        try {
            run.run();
            return new Outcome(check, null);
        } catch (AssertionError | RuntimeException failure) {
            return new Outcome(check, failure);
        }
    }

    /** The checks, by name: the whole verification, then the steps of {@code LangModelVerifier.verify}, in order. */
    private static Map<String, Consumer<ClassInfo>> checks() {
        var checks = new LinkedHashMap<String, Consumer<ClassInfo>>();
        checks.put(VERIFY, LangModelVerifier::verify);
        checks.put("ensureOnlyRuntimeAnnotations", clazz -> privateStep("ensureOnlyRuntimeAnnotations", clazz));
        checks.put("AnnotatedTypes", clazz -> AnnotatedTypes.verify(field(clazz, "annotatedTypes")));
        checks.put("AnnotatedSuperTypes", clazz -> AnnotatedSuperTypes.verify(field(clazz, "annotatedSuperTypes")));
        checks.put("AnnotatedThrowsTypes",
                clazz -> AnnotatedThrowsTypes.verify(field(clazz, "annotatedThrowsTypes")));
        checks.put("AnnotatedReceiverTypes",
                clazz -> AnnotatedReceiverTypes.verify(field(clazz, "annotatedReceiverTypes")));
        checks.put("AnnotationInstances", clazz -> AnnotationInstances.verify(field(clazz, "annotationInstances")));
        checks.put("PlainClassMembers",
                clazz -> PlainClassMembers.Verifier.verify(field(clazz, "plainClassMembers")));
        checks.put("InterfaceMembers", clazz -> InterfaceMembers.Verifier.verify(field(clazz, "interfaceMembers")));
        checks.put("AnnotationMembers",
                clazz -> AnnotationMembers.Verifier.verify(field(clazz, "annotationMembers")));
        checks.put("EnumMembers", clazz -> EnumMembers.Verifier.verify(field(clazz, "enumMembers")));
        checks.put("InheritedMethods", clazz -> InheritedMethods.Verifier.verify(field(clazz, "inheritedMethods")));
        checks.put("InheritedFields", clazz -> InheritedFields.Verifier.verify(field(clazz, "inheritedFields")));
        checks.put("InheritedAnnotations",
                clazz -> InheritedAnnotations.verify(field(clazz, "inheritedAnnotations")));
        checks.put("JavaLangObjectMethods",
                clazz -> JavaLangObjectMethods.Verifier.verify(field(clazz, "javaLangObjectMethods")));
        checks.put("PrimitiveTypes", clazz -> PrimitiveTypes.verify(field(clazz, "primitiveTypes")));
        checks.put("BridgeMethods", clazz -> BridgeMethods.verify(field(clazz, "bridgeMethods")));
        checks.put("RepeatableAnnotations",
                clazz -> RepeatableAnnotations.verify(field(clazz, "repeatableAnnotations")));
        checks.put("DefaultConstructors", clazz -> DefaultConstructors.verify(field(clazz, "defaultConstructors")));
        checks.put("Equality", clazz -> Equality.verify(field(clazz, "equality")));
        checks.put("verifyPackageAnnotation", clazz -> privateStep("verifyPackageAnnotation", clazz));
        return checks;
    }

    private static ClassInfo field(ClassInfo verifier, String name) {
        return LangModelUtils.classOfField(verifier, name);
    }

    /** A private step of {@code LangModelVerifier}, which only {@code verify} calls: reached by reflection. */
    private static void privateStep(String method, ClassInfo verifier) {
        try {
            var step = LangModelVerifier.class.getDeclaredMethod(method, ClassInfo.class);
            step.setAccessible(true);
            step.invoke(null, verifier);
        } catch (InvocationTargetException e) {
            switch (e.getCause()) {
                case AssertionError failed -> throw failed;
                case RuntimeException failed -> throw failed;
                default -> throw new IllegalStateException(e.getCause());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("LangModelVerifier." + method + " is out of reach", e);
        }
    }
}
