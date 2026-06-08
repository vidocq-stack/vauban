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
package io.vidocq.vauban.processor.codegen.factory;

import io.vidocq.vauban.processor.codegen.GeneratedClass;
import io.vidocq.vauban.indexer.model.ClassInfo;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.Signature.ClassTypeSig;
import java.lang.classfile.Signature.TypeArg;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

/**
 * Generates factory classes for managed beans using the JDK Class-File API.
 *
 * <p>Each generated factory implements {@code BeanFactory<T>} and creates
 * bean instances via direct constructor invocation (no reflection).</p>
 */
public final class BeanFactoryGenerator {

    private BeanFactoryGenerator() {}

    /**
     * Generates a factory class for a managed bean with a no-arg constructor.
     *
     * <p>The generated class looks like:</p>
     * <pre>
     * public final class MyBean_Factory implements BeanFactory&lt;MyBean&gt; {
     *     public MyBean_Factory() {}
     *     public MyBean create() { return new MyBean(); }
     *     // Bridge method for type erasure
     *     public Object create() { return create(); }
     * }
     * </pre>
     *
     * @param beanClass the class info for the bean to generate a factory for
     * @return a {@link GeneratedClass} containing the factory class name and bytecode
     */
    public static GeneratedClass generate(ClassInfo beanClass) {
        String beanClassName = beanClass.name().value();
        String factoryClassName = beanClassName + "_Factory";

        ClassDesc factoryCD = ClassDesc.of(factoryClassName);
        ClassDesc beanCD = ClassDesc.of(beanClassName);
        ClassDesc beanFactoryCD = ClassDesc.of("io.vidocq.vauban.core.BeanFactory");
        ClassDesc objectCD = ClassDesc.of("java.lang.Object");

        byte[] bytecode = ClassFile.of().build(factoryCD, clb -> {
            // Class: public final class MyBean_Factory implements BeanFactory
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            clb.withSuperclass(objectCD);
            clb.withInterfaceSymbols(beanFactoryCD);

            // Signature attribute for generics: implements BeanFactory<MyBean>
            clb.with(SignatureAttribute.of(ClassSignature.of(
                    ClassTypeSig.of(objectCD),
                    ClassTypeSig.of(beanFactoryCD, TypeArg.of(ClassTypeSig.of(beanCD)))
            )));

            // Constructor: public MyBean_Factory() { super(); }
            clb.withMethodBody(
                    ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC,
                    cob -> {
                        cob.aload(0);
                        cob.invokespecial(objectCD, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.return_();
                    });

            // Method: public MyBean create() { return new MyBean(); }
            clb.withMethodBody(
                    "create",
                    MethodTypeDesc.of(beanCD),
                    ClassFile.ACC_PUBLIC,
                    cob -> {
                        cob.new_(beanCD);
                        cob.dup();
                        cob.invokespecial(beanCD, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                        cob.areturn();
                    });

            // Bridge method: public Object create() { return this.create(); }
            // (needed due to type erasure of BeanFactory<T>)
            clb.withMethodBody(
                    "create",
                    MethodTypeDesc.of(objectCD),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC,
                    cob -> {
                        cob.aload(0);
                        cob.invokevirtual(factoryCD, "create", MethodTypeDesc.of(beanCD));
                        cob.areturn();
                    });
        });

        return new GeneratedClass(factoryClassName, bytecode);
    }
}
