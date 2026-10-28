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
package io.vidocq.vauban.core.loading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Class#forName(String)} resolves a name through the loader of the class that calls it, {@code vauban-core}'s
 * own here. A deployment's classes live in other loaders (an application module layer, the TCK's archive loader), so
 * a class of the deployment loaded that way is not found, and the container silently takes the fallback branch
 * (BUG-20261028-01: a custom scope became {@code @Dependent}).
 *
 * <p>Every one-argument call left in {@code vauban-core} is a deliberate last resort, listed here with its reason.
 */
@DisplayName("vauban-core never loads a deployment class through its own loader by mistake")
class SingleArgumentForNameTest {

    private static final MethodTypeDesc FOR_NAME = MethodTypeDesc.of(ConstantDescs.CD_Class, ConstantDescs.CD_String);

    /** Callers allowed to use {@code Class.forName(String)}, as {@code SimpleClassName.method}. */
    private static final Set<String> DELIBERATE = Set.of(
            // No thread context class loader at all: the caller's loader is the only one left.
            "DeploymentClasses.load",
            // Tried the context loader first; the second attempt is the fallback.
            "QualifierResolver.loadWithFallback",
            // The bean's loader first; vauban-core's own loader for the built-in scopes it ships.
            "ManagedBean.scopeAnnotationClass",
            // An optional module of the Vauban family, resolved next to vauban-core.
            "ContainerScanner.getPluginContext",
            // The interceptor's own loader, then the context loader, then this one.
            "InterceptorManager.getOrCreateInstance");

    @Test
    @DisplayName("every one-argument Class.forName call is a deliberate fallback")
    void onlyDeliberateCallers() throws IOException, URISyntaxException {
        var classes = Path.of(DeploymentClasses.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var callers = new TreeSet<String>();
        try (Stream<Path> files = Files.walk(classes)) {
            files.filter(f -> f.toString().endsWith(".class")).forEach(f -> collectCallers(f, callers));
        }
        callers.removeAll(DELIBERATE);

        assertEquals(Set.of(), callers);
    }

    private static void collectCallers(Path classFile, Set<String> callers) {
        ClassModel model;
        try {
            model = ClassFile.of().parse(classFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var internalName = model.thisClass().asInternalName();
        var simpleName = internalName.substring(internalName.lastIndexOf('/') + 1);
        for (var method : model.methods()) {
            var code = method.findAttribute(java.lang.classfile.Attributes.code());
            if (code.isEmpty() || !callsForName(code.get())) {
                continue;
            }
            callers.add(simpleName + "." + enclosingMethod(method.methodName().stringValue()));
        }
    }

    private static boolean callsForName(CodeModel code) {
        for (var element : code) {
            if (element instanceof InvokeInstruction invoke
                    && invoke.owner().asInternalName().equals("java/lang/Class")
                    && invoke.name().equalsString("forName")
                    && invoke.typeSymbol().equals(FOR_NAME)) {
                return true;
            }
        }
        return false;
    }

    /** {@code lambda$load$3} is the body of a lambda written in {@code load}. */
    private static String enclosingMethod(String name) {
        if (name.startsWith("lambda$")) {
            var end = name.indexOf('$', "lambda$".length());
            return end < 0 ? name : name.substring("lambda$".length(), end);
        }
        return name;
    }
}
