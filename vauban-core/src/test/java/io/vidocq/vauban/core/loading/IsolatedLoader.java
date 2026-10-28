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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;

/**
 * Defines one class that no other loader of the tests can see, the way an application module layer or a TCK archive
 * holds the deployment's classes out of {@code vauban-core}'s reach.
 */
public final class IsolatedLoader extends ClassLoader {

    private static final MethodTypeDesc NO_ARG = MethodTypeDesc.of(ConstantDescs.CD_void);

    private final String name;
    private final byte[] bytes;

    private IsolatedLoader(String name, byte[] bytes) {
        super(IsolatedLoader.class.getClassLoader());
        this.name = name;
        this.bytes = bytes;
    }

    /** An empty public class with a public no-arg constructor. */
    public static IsolatedLoader ofClass(String name) {
        return new IsolatedLoader(name, ClassFile.of().build(ClassDesc.of(name), clb -> clb
                .withFlags(ClassFile.ACC_PUBLIC)
                .withMethodBody(ConstantDescs.INIT_NAME, NO_ARG, ClassFile.ACC_PUBLIC, cb -> cb
                        .aload(0)
                        .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, NO_ARG)
                        .return_())));
    }

    /** A runtime-retained annotation type, annotated with the given meta-annotations (which have no members). */
    @SafeVarargs
    public static IsolatedLoader ofAnnotation(String name,
                                              Class<? extends java.lang.annotation.Annotation>... metaAnnotations) {
        var annotations = new ArrayList<Annotation>();
        annotations.add(Annotation.of(ClassDesc.of(Retention.class.getName()),
                AnnotationElement.of("value", AnnotationValue.ofEnum(
                        ClassDesc.of(RetentionPolicy.class.getName()), RetentionPolicy.RUNTIME.name()))));
        for (var meta : metaAnnotations) {
            annotations.add(Annotation.of(ClassDesc.of(meta.getName())));
        }
        return new IsolatedLoader(name, ClassFile.of().build(ClassDesc.of(name), clb -> clb
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
                        | ClassFile.ACC_ANNOTATION)
                .withInterfaceSymbols(ClassDesc.of(java.lang.annotation.Annotation.class.getName()))
                .with(RuntimeVisibleAnnotationsAttribute.of(annotations))));
    }

    /** The binary name of the class this loader defines. */
    public String name() {
        return name;
    }

    @Override
    protected Class<?> findClass(String className) throws ClassNotFoundException {
        if (!name.equals(className)) {
            throw new ClassNotFoundException(className);
        }
        return defineClass(className, bytes, 0, bytes.length);
    }
}
