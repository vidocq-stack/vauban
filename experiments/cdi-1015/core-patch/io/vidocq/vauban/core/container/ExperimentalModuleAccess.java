/*
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 * Copyright (c) 2026 Yann Blazart and the Vidocq contributors
 */
package io.vidocq.vauban.core.container;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experiment-only preparation for Vauban's runtime-generated interception subclasses.
 * The library declares no dependency on the implementation. A helper defined after
 * the opening is relayed adds the read needed by generated calls into Vauban core.
 */
final class ExperimentalModuleAccess {
    private static final AtomicLong NEXT_HELPER = new AtomicLong();
    private static final ClassDesc CD_MODULE = ClassDesc.of("java.lang.Module");

    private ExperimentalModuleAccess() {
    }

    static void prepare(Class<?> beanClass) {
        Module library = beanClass.getModule();
        Module provider = ExperimentalModuleAccess.class.getModule();
        if (library.canRead(provider)) {
            return;
        }
        provider.addReads(library);
        try {
            var lookup = MethodHandles.privateLookupIn(beanClass, MethodHandles.lookup());
            ClassDesc helperName = ClassDesc.of(beanClass.getPackageName()
                    + ".$VaubanModuleAccess$" + NEXT_HELPER.incrementAndGet());
            Class<?> helper = lookup.defineClass(helperBytes(helperName));
            lookup.findStatic(helper, "addRead", MethodType.methodType(void.class, Module.class))
                    .invokeExact(provider);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot prepare generated code access for "
                    + library.getName(), failure);
        }
        if (!library.canRead(provider)) {
            throw new IllegalStateException("The library still cannot read the provider");
        }
    }

    private static byte[] helperBytes(ClassDesc helperName) {
        return ClassFile.of().build(helperName, builder -> {
            builder.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            builder.withSuperclass(ConstantDescs.CD_Object);
            builder.withMethodBody("addRead", MethodTypeDesc.of(ConstantDescs.CD_void, CD_MODULE),
                    ClassFile.ACC_STATIC, code -> {
                        code.ldc(helperName);
                        code.invokevirtual(ConstantDescs.CD_Class, "getModule", MethodTypeDesc.of(CD_MODULE));
                        code.aload(0);
                        code.invokevirtual(CD_MODULE, "addReads", MethodTypeDesc.of(CD_MODULE, CD_MODULE));
                        code.pop();
                        code.return_();
                    });
        });
    }
}
