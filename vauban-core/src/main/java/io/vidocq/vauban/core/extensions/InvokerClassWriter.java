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

import io.vidocq.vauban.core.codegen.GeneratedClassFile;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.Map;

/**
 * Writes, with the Class-File API, the class of an invoker the build describes (BUG-20261118-37): a public class in
 * the package of the bean class that implements {@code BiFunction<Object, Object[], Object>} and calls the method by
 * name, {@code invokevirtual}, {@code invokeinterface} or {@code invokestatic}. It casts the instance and each argument
 * to the type the method declares, unboxes a primitive argument, boxes a primitive return value and returns
 * {@code null} for {@code void}; an exception of the method is thrown as it is. The arguments a primitive parameter
 * takes by widening are widened before the call, by {@link VaubanInvoker}.
 */
final class InvokerClassWriter {

    /** What sets the class of a generated invoker apart, after the name of the bean class. */
    static final String SUFFIX = "$$VaubanInvoker$";

    private static final ClassDesc BI_FUNCTION = ClassDesc.of("java.util.function.BiFunction");
    private static final MethodTypeDesc APPLY = MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object,
            ConstantDescs.CD_Object);
    private static final ClassDesc OBJECTS = ConstantDescs.CD_Object.arrayType();
    private static final int ARGUMENTS = 3;

    /** By primitive name, its wrapper class, as {@code valueOf} and the {@code xxxValue()} methods name it. */
    private static final Map<String, ClassDesc> WRAPPERS = Map.of(
            "boolean", ConstantDescs.CD_Boolean, "byte", ConstantDescs.CD_Byte, "char", ConstantDescs.CD_Character,
            "short", ConstantDescs.CD_Short, "int", ConstantDescs.CD_Integer, "long", ConstantDescs.CD_Long,
            "float", ConstantDescs.CD_Float, "double", ConstantDescs.CD_Double);
    private static final Map<String, ClassDesc> PRIMITIVES = Map.of(
            "boolean", ConstantDescs.CD_boolean, "byte", ConstantDescs.CD_byte, "char", ConstantDescs.CD_char,
            "short", ConstantDescs.CD_short, "int", ConstantDescs.CD_int, "long", ConstantDescs.CD_long,
            "float", ConstantDescs.CD_float, "double", ConstantDescs.CD_double, "void", ConstantDescs.CD_void);

    private InvokerClassWriter() {
    }

    /**
     * The name of the class of an invoker of {@code target}, in the package of its bean class: the same method of the
     * same bean gets the same name, whatever its lookups, which the caller of the class does.
     */
    static String className(InvokerTarget target) {
        var key = target.declaringClass() + "." + target.name() + target.parameterTypes();
        return target.beanClass() + SUFFIX + target.name() + "$" + Integer.toHexString(key.hashCode());
    }

    /** The class file of {@code className}, which calls the method of {@code target} as {@code shape} says. */
    static byte[] write(String className, InvokerTarget target, InvokerShape shape) {
        return GeneratedClassFile.build(ClassDesc.of(className), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC)
                .withInterfaceSymbols(BI_FUNCTION)
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_())
                .withMethodBody("apply", APPLY, ClassFile.ACC_PUBLIC, code -> call(code, target, shape)));
    }

    private static void call(CodeBuilder code, InvokerTarget target, InvokerShape shape) {
        var owner = ClassDesc.of(shape.owner());
        var parameters = target.parameterTypes().stream().map(InvokerClassWriter::descriptor).toList();
        var method = MethodTypeDesc.of(descriptor(shape.returnType()), parameters);
        if (!shape.isStatic()) {
            code.aload(1).checkcast(owner);
        }
        if (!parameters.isEmpty()) {
            code.aload(2).checkcast(OBJECTS).astore(ARGUMENTS);
        }
        for (int i = 0; i < parameters.size(); i++) {
            code.aload(ARGUMENTS).loadConstant(i).aaload();
            argument(code, target.parameterTypes().get(i), parameters.get(i));
        }
        if (shape.isStatic()) {
            code.invokestatic(owner, target.name(), method, shape.ownerInterface());
        } else if (shape.ownerInterface()) {
            code.invokeinterface(owner, target.name(), method);
        } else {
            code.invokevirtual(owner, target.name(), method);
        }
        result(code, shape.returnType());
        code.areturn();
    }

    /** Casts the argument on the stack to the parameter's type, unboxed for a primitive. */
    private static void argument(CodeBuilder code, String name, ClassDesc parameter) {
        var wrapper = WRAPPERS.get(name);
        if (wrapper != null) {
            code.checkcast(wrapper).invokevirtual(wrapper, name + "Value", MethodTypeDesc.of(parameter));
        } else if (!parameter.equals(ConstantDescs.CD_Object)) {
            code.checkcast(parameter);
        }
    }

    /** The return value on the stack as an {@code Object}: boxed for a primitive, {@code null} for none. */
    private static void result(CodeBuilder code, String returnType) {
        if ("void".equals(returnType)) {
            code.aconst_null();
            return;
        }
        var wrapper = WRAPPERS.get(returnType);
        if (wrapper != null) {
            code.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, PRIMITIVES.get(returnType)));
        }
    }

    /** The descriptor of a type {@link Class#getName()} names: {@code int}, {@code a.B}, {@code [La.B;}. */
    static ClassDesc descriptor(String name) {
        var primitive = PRIMITIVES.get(name);
        if (primitive != null) {
            return primitive;
        }
        return name.startsWith("[") ? ClassDesc.ofDescriptor(name.replace('.', '/')) : ClassDesc.of(name);
    }
}
