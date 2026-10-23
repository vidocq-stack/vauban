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

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.ClassInfo;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BceTypeMatcher} matching {@code @Enhancement}/{@code @Registration} filters against members, the index's
 * class hierarchy and bean types — including classes whose members name a type absent at run time.
 */
class BceTypeMatcherMembersTest {

    private static final ClassLoader LOADER = BceTypeMatcherMembersTest.class.getClassLoader();

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marked {
    }

    @SuppressWarnings("unused")
    public static class MarkedField {
        @Marked
        String field;
    }

    public static class MarkedConstructor {
        @Marked
        public MarkedConstructor() {
            // the constructor carries the annotation
        }
    }

    public static class Plain {
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation>[] marked() {
        return new Class[] {Marked.class};
    }

    private static ClassInfo indexed(String name, String superName) {
        return new ClassInfo(DotName.of(name), superName == null ? null : DotName.of(superName), List.of(), 0x0001,
                List.of(), List.of(), List.of(), ClassInfo.ClassKind.CLASS);
    }

    @Test
    void anAnnotationOnAFieldOrAConstructorMatches() {
        assertTrue(BceTypeMatcher.matchesAnnotations(marked(), MarkedField.class));
        assertTrue(BceTypeMatcher.matchesAnnotations(marked(), MarkedConstructor.class));
        assertFalse(BceTypeMatcher.matchesAnnotations(marked(), Plain.class));
    }

    @Test
    void aClassWhoseMembersNameAnAbsentTypeMatchesThroughItsOtherMembersOnly() {
        assertFalse(BceTypeMatcher.matchesAnnotations(marked(), classNamingAnAbsentType()));
    }

    @Test
    void aSupertypeIsFoundUpTheIndexedHierarchy() {
        var child = indexed("com.acme.Child", Middle.class.getName());
        var lookup = new IndexLookup(new IndexBuilder()
                .add(child)
                .add(indexed(Middle.class.getName(), Root.class.getName()))
                .add(indexed(Root.class.getName(), null))
                .build());

        assertTrue(BceTypeMatcher.matchesClassByIndex(new Class<?>[] {Root.class}, true, child, lookup));
        assertFalse(BceTypeMatcher.matchesClassByIndex(new Class<?>[] {Plain.class}, true, child, lookup));
    }

    /** The top of the indexed hierarchy above; only its name takes part. */
    private static final class Root {
    }

    /** The middle of the indexed hierarchy above; only its name takes part. */
    private static final class Middle {
    }

    @Test
    void beanTypesThatNameNoClassNeverMatch() {
        var bean = new BeanDescriptor(BeanDescriptor.minimal(DotName.of("com.acme.Bean")).id(), DotName.of("com.acme.Bean"),
                BeanDescriptor.BeanKind.MANAGED,
                Set.of(new TypeInfo.TypeVariable("T", List.of()), new TypeInfo.ArrayType(TypeInfo.PrimitiveType.fromDescriptor('I'), 1)),
                Set.of(), null, false, 0, List.of(), null);

        assertFalse(BceTypeMatcher.matchesTypes(new Class<?>[] {Object.class}, bean, LOADER));
    }

    @Test
    void aParameterizedBeanTypeMatchesByItsRawType() {
        var bean = new BeanDescriptor(BeanDescriptor.minimal(DotName.of("com.acme.Bean")).id(), DotName.of("com.acme.Bean"),
                BeanDescriptor.BeanKind.MANAGED,
                Set.of(new TypeInfo.ParameterizedType(DotName.of("java.util.ArrayList"), List.of())),
                Set.of(), null, false, 0, List.of(), null);

        assertTrue(BceTypeMatcher.matchesTypes(new Class<?>[] {java.util.List.class}, bean, LOADER));
    }

    /**
     * A class, built here, whose only field and only constructor name {@code com.acme.Absent}: reading either
     * throws {@link NoClassDefFoundError}.
     */
    private static Class<?> classNamingAnAbsentType() {
        var absent = ClassDesc.of("com.acme.Absent");
        var self = ClassDesc.of("gen.NamesAbsent");
        byte[] bytes = ClassFile.of().build(self, clb -> clb
                .withFlags(AccessFlag.PUBLIC)
                .withField("absent", absent, ClassFile.ACC_PUBLIC)
                .withMethodBody(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, absent),
                        ClassFile.ACC_PUBLIC, code -> code
                                .aload(0)
                                .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                                .return_()));
        var loader = new ClassLoader(LOADER) {
            Class<?> define() {
                return defineClass("gen.NamesAbsent", bytes, 0, bytes.length);
            }
        };
        return loader.define();
    }
}
