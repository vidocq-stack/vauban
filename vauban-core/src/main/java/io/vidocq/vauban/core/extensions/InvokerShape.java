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

import io.vidocq.vauban.core.langmodel.LangModelAnnotations;
import io.vidocq.vauban.indexer.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import java.lang.classfile.ClassFile;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * How a class generated in the package of the bean class calls the method of an invoker, read from the language
 * model when the invoker is built, so that the build writes that class without loading anything (BUG-20261118-37).
 *
 * @param direct         whether such a class may call the method by name: it is not private, it is public or its
 *                       class is in the bean's package, and that package can name every parameter type; when not,
 *                       the invoker stays reflective
 * @param owner          the class the call names: the declaring class when the bean's package can name it, the bean
 *                       class otherwise, which inherits the method
 * @param ownerInterface whether {@code owner} is an interface
 * @param isStatic       whether the method is static
 * @param returnType     the erasure of the return type, as {@link Class#getName()} writes it, {@code void} for none
 * @param parameters     the declared type and the annotations of each parameter, for its lookup
 * @param packageBound   the classes a generated class reaches only as a member of their runtime package: the
 *                       declaring class of a method that is not public, a class that is not public; the same package
 *                       name is not enough, the build must also write them, so that one loader defines them with the
 *                       bean class (JVMS 5.3, 5.4.4): a split package of another archive would fail with an
 *                       {@code IllegalAccessError}
 */
record InvokerShape(boolean direct, String owner, boolean ownerInterface, boolean isStatic, String returnType,
                    List<Parameter> parameters, Set<String> packageBound) {

    InvokerShape {
        parameters = List.copyOf(parameters);
        packageBound = Set.copyOf(packageBound);
    }

    /**
     * A parameter as a lookup resolves it.
     *
     * @param type        its declared type, in the notation of the synthetic metadata; {@code null} when that notation
     *                    cannot write it, and the invoker then stays reflective if it looks the parameter up
     * @param annotations its annotations, its qualifiers among them
     * @param configured  whether {@code annotations} are not those the method declares: the configs of the bean class
     *                    changed them on a method it inherits, which reflection does not show (BUG-20261118-55)
     */
    record Parameter(String type, List<AnnotationInfo> annotations, boolean configured) {

        Parameter {
            annotations = List.copyOf(annotations);
        }
    }

    /** The shape of an invoker of {@code method} for a bean of class {@code bean}, no member configured. */
    static InvokerShape of(ClassInfo bean, MethodInfo method) {
        return of(bean, method, EnhancedClasses.InheritedParameters.NONE);
    }

    /**
     * The shape of an invoker of {@code method} for a bean of class {@code bean}: the parameters of a method the bean
     * class inherits have the annotations the {@code @Enhancement} configs of the bean class left them
     * ({@code inherited}), as the container injects them; they had those of the class that declares the method
     * (BUG-20261118-55).
     */
    static InvokerShape of(ClassInfo bean, MethodInfo method, EnhancedClasses.InheritedParameters inherited) {
        var beanPackage = packageOf(bean.name());
        var declaring = method.declaringClass();
        var bound = new HashSet<String>();
        var declaringNameable = nameable(declaring, beanPackage, bound);
        var callable = (method.modifiers() & ClassFile.ACC_PRIVATE) == 0
                && (isPublic(method.modifiers()) || inPackage(declaring, beanPackage, bound));
        var direct = callable && (declaringNameable || !method.isStatic())
                && method.parameters().stream().allMatch(parameter -> nameable(parameter.type(), beanPackage, bound));
        var ownedByDeclaring = declaringNameable || method.isStatic();
        return new InvokerShape(direct, ownedByDeclaring ? declaring.name() : bean.name(),
                ownedByDeclaring && declaring.isInterface(), method.isStatic(),
                method.returnType().isVoid() ? "void" : InvokerTarget.erasedName(method.returnType()),
                parameters(bean, method, inherited), bound);
    }

    private static List<Parameter> parameters(ClassInfo bean, MethodInfo method,
            EnhancedClasses.InheritedParameters inherited) {
        var inheritedMethod = !method.declaringClass().name().equals(bean.name());
        var parameters = new java.util.ArrayList<Parameter>();
        for (int i = 0; i < method.parameters().size(); i++) {
            var parameter = method.parameters().get(i);
            var declared = parameter.annotations().stream().map(LangModelAnnotations::toIndex).toList();
            var annotations = inheritedMethod ? inherited.of(bean.name(), method, i, declared) : declared;
            parameters.add(new Parameter(notation(parameter.type()), annotations, !annotations.equals(declared)));
        }
        return parameters;
    }

    private static String notation(Type type) {
        try {
            return ObservedTypeNotation.write(LangModelTypeMapper.toIndexType(type));
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    /**
     * Whether a class of {@code beanPackage} can name the erasure of {@code type} (JVMS 5.4.4); a class it names as a
     * member of its package joins {@code bound}.
     */
    private static boolean nameable(Type type, String beanPackage, Set<String> bound) {
        return switch (type) {
            case ArrayType array -> nameable(array.componentType(), beanPackage, bound);
            case ClassType classType -> nameable(classType.declaration(), beanPackage, bound);
            case ParameterizedType parameterized -> nameable(parameterized.genericClass().declaration(), beanPackage,
                    bound);
            case TypeVariable variable -> variable.bounds().isEmpty() || nameable(variable.bounds().getFirst(),
                    beanPackage, bound);
            default -> true;
        };
    }

    private static boolean nameable(ClassInfo type, String beanPackage, Set<String> bound) {
        return isPublic(type.modifiers()) || inPackage(type, beanPackage, bound);
    }

    /** Whether {@code type} is in {@code beanPackage}; it then joins {@code bound}. */
    private static boolean inPackage(ClassInfo type, String beanPackage, Set<String> bound) {
        if (!packageOf(type.name()).equals(beanPackage)) {
            return false;
        }
        bound.add(type.name());
        return true;
    }

    /** The modifiers of the language model are those of {@code java.lang.reflect.Modifier}, the class file's. */
    private static boolean isPublic(int modifiers) {
        return (modifiers & ClassFile.ACC_PUBLIC) != 0;
    }

    static String packageOf(String className) {
        var dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }
}
