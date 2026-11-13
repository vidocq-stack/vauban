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
package io.vidocq.vauban.core.container;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.scanner.ClassFileScanner;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The fingerprint of a deployment")
class DeploymentFingerprintTest {

    private static final ClassDesc NAMED = ClassDesc.of("jakarta.inject.Named");
    private static final ClassDesc TAG = ClassDesc.of("a.Tag");

    /**
     * A class as javac writes it with its debug information: an annotation with a value, another with two members,
     * a method whose body returns a constant, and the source file, line numbers, local variables and parameter names.
     */
    private record Sample(String name, String named, String answer, boolean extraMethod, boolean membersReversed) {

        static Sample of(String name) {
            return new Sample(name, "one", "forty-two", false, false);
        }

        Sample named(String value) {
            return new Sample(name, value, answer, extraMethod, membersReversed);
        }

        Sample answering(String value) {
            return new Sample(name, named, value, extraMethod, membersReversed);
        }

        Sample withExtraMethod() {
            return new Sample(name, named, answer, true, membersReversed);
        }

        Sample withMembersReversed() {
            return new Sample(name, named, answer, extraMethod, true);
        }

        byte[] classFile() {
            ClassDesc self = ClassDesc.of(name);
            var x = AnnotationElement.ofInt("x", 1);
            var y = AnnotationElement.ofInt("y", 2);
            return ClassFile.of().build(self, type -> {
                type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                        .with(SourceFileAttribute.of("Sample.java"))
                        .with(RuntimeVisibleAnnotationsAttribute.of(
                                Annotation.of(NAMED, AnnotationElement.ofString("value", named)),
                                membersReversed ? Annotation.of(TAG, y, x) : Annotation.of(TAG, x, y)))
                        .withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, code -> {
                            var start = code.newBoundLabel();
                            code.lineNumber(10).aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void).return_();
                            code.localVariable(0, "this", self, start, code.newBoundLabel());
                        })
                        .withMethod("answer", MethodTypeDesc.of(CD_String, CD_String), ClassFile.ACC_PUBLIC,
                                method -> method
                                        .with(MethodParametersAttribute.of(
                                                MethodParameterInfo.ofParameter(Optional.of("question"), 0)))
                                        .withCode(code -> {
                                            var start = code.newBoundLabel();
                                            code.lineNumber(20).ldc(answer).areturn();
                                            var end = code.newBoundLabel();
                                            code.localVariable(0, "this", self, start, end);
                                            code.localVariable(1, "question", CD_String, start, end);
                                        }));
                if (extraMethod) {
                    type.withMethodBody("other", MTD_void, ClassFile.ACC_PUBLIC, CodeBuilder::return_);
                }
            });
        }

        ClassInfo model() {
            return ClassFileScanner.scan(classFile());
        }
    }

    private static String beans(String settings, Sample... samples) {
        var fingerprint = new DeploymentFingerprint(settings);
        for (Sample sample : samples) {
            fingerprint.add(sample.name(), sample.model());
        }
        return fingerprint.value();
    }

    private static String bean(byte[] classFile) {
        var fingerprint = new DeploymentFingerprint("s");
        fingerprint.add("a.A", ClassFileScanner.scan(classFile));
        return fingerprint.value();
    }

    private static String extension(byte[] classFile) {
        var fingerprint = new DeploymentFingerprint("s");
        fingerprint.addExtension("a.A", ClassFileScanner.scan(classFile), classFile);
        return fingerprint.value();
    }

    @Test
    @DisplayName("the same classes give the same fingerprint, whatever their order")
    void theOrderDoesNotCount() {
        assertEquals(beans("s", Sample.of("a.A"), Sample.of("b.B")), beans("s", Sample.of("b.B"), Sample.of("a.A")));
    }

    @Test
    @DisplayName("a class added, removed or renamed gives another fingerprint")
    void anyClassCounts() {
        String reference = beans("s", Sample.of("a.A"), Sample.of("b.B"));

        assertNotEquals(reference, beans("s", Sample.of("a.A"), Sample.of("b.B"), Sample.of("c.C")), "added");
        assertNotEquals(reference, beans("s", Sample.of("a.A")), "removed");
        assertNotEquals(reference, beans("s", Sample.of("a.A2"), Sample.of("b.B")), "renamed");
    }

    @Test
    @DisplayName("what the extensions see of a bean class counts: its annotations, its members")
    void whatTheExtensionsSeeCounts() {
        byte[] reference = Sample.of("a.A").classFile();

        assertNotEquals(bean(reference), bean(Sample.of("a.A").named("two").classFile()), "an annotation value");
        assertNotEquals(bean(reference), bean(Sample.of("a.A").withExtraMethod().classFile()), "a method");
    }

    @Test
    @DisplayName("the body of a bean's method does not count: no extension sees it")
    void aBeanMethodBodyDoesNotCount() {
        assertEquals(bean(Sample.of("a.A").classFile()), bean(Sample.of("a.A").answering("forty-three").classFile()));
    }

    @Test
    @DisplayName("the body of an extension's method counts: it is what the extension does")
    void anExtensionMethodBodyCounts() {
        assertNotEquals(extension(Sample.of("a.A").classFile()),
                extension(Sample.of("a.A").answering("forty-three").classFile()));
    }

    @Test
    @DisplayName("the order the members of an annotation were written in does not count")
    void theOrderOfAnnotationMembersDoesNotCount() {
        assertEquals(bean(Sample.of("a.A").classFile()), bean(Sample.of("a.A").withMembersReversed().classFile()));
    }

    @Test
    @DisplayName("a class stripped of its debug information, as jlink does, keeps its fingerprint (BUG-20261113-17)")
    void debugInformationDoesNotCount() {
        byte[] built = Sample.of("a.A").classFile();
        byte[] linked = JlinkStripping.strip(built);
        assertNotEquals(built.length, linked.length, "jlink's stripping changes the bytes");

        assertEquals(bean(built), bean(linked), "a bean class");
        assertEquals(extension(built), extension(linked), "an extension");
    }

    @Test
    @DisplayName("a class without a class file counts by its name")
    void aClassWithoutClassFile() {
        var none = new DeploymentFingerprint("s");
        none.add("a.A", null);
        var again = new DeploymentFingerprint("s");
        again.add("a.A", null);
        var other = new DeploymentFingerprint("s");
        other.add("b.B", null);

        assertEquals(none.value(), again.value());
        assertNotEquals(none.value(), other.value(), "another name");
        assertNotEquals(none.value(), new DeploymentFingerprint("s").value(), "no class at all");
        assertNotEquals(none.value(), beans("s", Sample.of("a.A")), "the same class with a class file");

        var extension = new DeploymentFingerprint("s");
        extension.addExtension("a.A", null, new byte[0]);
        assertEquals(none.value(), extension.value(), "an extension without a class file counts by its name too");
    }

    @Test
    @DisplayName("other discovery settings give another fingerprint")
    void theSettingsCount() {
        assertNotEquals(beans("beanArchive=true", Sample.of("a.A")), beans("beanArchive=false", Sample.of("a.A")));
    }
}
