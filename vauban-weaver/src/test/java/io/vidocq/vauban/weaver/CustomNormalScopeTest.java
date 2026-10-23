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
package io.vidocq.vauban.weaver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A custom scope annotation counts as normal only when its own class file is meta-annotated {@code @NormalScope}. */
class CustomNormalScopeTest {

    private static final ClassDesc SCOPE = ClassDesc.of("app.TenantScoped");

    private static byte[] annotatedWith(String className, ClassDesc annotation, int flags) {
        return ClassFile.of().build(ClassDesc.of(className), clb -> {
            clb.withFlags(flags);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(annotation)));
        });
    }

    private static boolean normalScoped(byte[] scopeBytes) {
        var bean = annotatedWith("app.Bean", SCOPE, ClassFile.ACC_PUBLIC);
        Map<String, byte[]> classes = new HashMap<>();
        if (scopeBytes != null) {
            classes.put("app.TenantScoped", scopeBytes);
        }
        return BeanWeavingAnalysis.isNormalScopedBeanClass(bean, classes::get, new HashMap<>());
    }

    @Test
    void anAnnotationMetaAnnotatedNormalScopeIsANormalScope() {
        assertTrue(normalScoped(annotatedWith("app.TenantScoped", ClassDesc.of("jakarta.enterprise.context.NormalScope"),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT | ClassFile.ACC_ANNOTATION)));
    }

    @Test
    void anAnnotationWithOtherMetaAnnotationsIsNoScope() {
        assertFalse(normalScoped(annotatedWith("app.TenantScoped", ClassDesc.of("java.lang.Deprecated"),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT | ClassFile.ACC_ANNOTATION)));
    }

    @Test
    void anAnnotationWhoseClassFileIsUnreadableIsNoScope() {
        assertFalse(normalScoped(new byte[] {(byte) 0xCA, (byte) 0xFE, 0, 0}));
    }

    @Test
    void anAnnotationWhoseClassFileIsAbsentIsNoScope() {
        assertFalse(normalScoped(null));
    }
}
