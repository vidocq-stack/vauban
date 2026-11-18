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

import io.vidocq.vauban.core.bean.model.InjectionPointInfo;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.langmodel.IndexLookup;
import io.vidocq.vauban.core.langmodel.VaubanAnnotationInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanClassInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanFieldInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanMethodInfo;
import io.vidocq.vauban.core.langmodel.declarations.VaubanParameterInfo;
import io.vidocq.vauban.core.langmodel.types.TypeMapper;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import jakarta.enterprise.lang.model.types.Type;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Adapts Vauban's internal {@link InjectionPointInfo} (model) to the BCE
 * {@link jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo} contract.
 *
 * <p>Cf. CDI Lite 4.1 §16.2 (Build Compatible Extensions). Required for
 * {@code @Registration} methods that read {@code BeanInfo.injectionPoints()}
 * — typical pattern of MicroProfile Config's {@code ConfigCdiExtension}
 * (Ravel) which collects every {@code @ConfigProperty} injection point
 * during {@code @Registration} and synthesizes a {@code SyntheticBean}
 * per type during {@code @Synthesis}.</p>
 */
public final class VaubanBceInjectionPointInfo
        implements jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo {

    /** {@code field <declaring simple name>.<field>}, as {@code InjectionPointInfo.fieldDescription} writes it. */
    private static final Pattern FIELD = Pattern.compile("field ([^.]+)\\.(.+)");
    /**
     * {@code parameter <position> of <declaring simple name>[.<method>]() (<descriptor>)V}, as
     * {@code InjectionPointInfo.parameterDescription} writes it.
     */
    private static final Pattern PARAMETER =
            Pattern.compile("parameter (\\d+) of ([^.(]+)(?:\\.([^(]+))?\\(\\) (\\(.*\\)V)");
    /** A bound on the superclasses walked, against a cycle the class files of a broken deployment could make. */
    private static final int MAX_DEPTH = 64;

    private final InjectionPointInfo ip;
    private final DotName declaringClassName;
    private final IndexLookup lookup;

    public VaubanBceInjectionPointInfo(InjectionPointInfo ip,
                                        DotName declaringClassName,
                                        IndexLookup lookup) {
        this.ip = ip;
        this.declaringClassName = declaringClassName;
        this.lookup = lookup;
    }

    @Override
    public Type type() {
        return TypeMapper.map(ip.requiredType(), lookup);
    }

    @Override
    public Collection<AnnotationInfo> qualifiers() {
        return ip.qualifiers().stream()
                .map(this::toAnnotationInfo)
                .toList();
    }

    /**
     * The field or the parameter the injection point is, found by its description in the bean class or the superclass
     * that declares it: a bean has the injection points its superclasses declare too (BUG-20261118-50). The
     * declaration is the member the superclass declares, with its own annotations: what an {@code @Enhancement}
     * config of a subclass changes on it shows in {@link #qualifiers()} only (BUG-20261118-48). An inherited field
     * or any parameter gave the bean class; the class stays the answer for a point it cannot find.
     */
    @Override
    public DeclarationInfo declaration() {
        var indexClass = lookup.getClass(declaringClassName).orElse(null);
        if (indexClass == null) return null;
        DeclarationInfo found = ip.kind() == InjectionPointInfo.InjectionKind.FIELD
                ? field(indexClass) : parameter(indexClass);
        return found != null ? found : new VaubanClassInfo(indexClass, lookup);
    }

    /** The field the description names, in {@code beanClass} or a superclass, or {@code null}. */
    private DeclarationInfo field(io.vidocq.vauban.indexer.model.ClassInfo beanClass) {
        var field = FIELD.matcher(ip.description());
        if (!field.matches()) return null;
        for (var type : declaringFirst(beanClass, field.group(1))) {
            for (var member : type.fields()) {
                if (member.name().equals(field.group(2))) {
                    return new VaubanFieldInfo(member, new VaubanClassInfo(type, lookup), lookup);
                }
            }
        }
        return null;
    }

    /** The parameter the description names, of a method of {@code beanClass} or a superclass, or {@code null}. */
    private DeclarationInfo parameter(io.vidocq.vauban.indexer.model.ClassInfo beanClass) {
        var parameter = PARAMETER.matcher(ip.description());
        if (!parameter.matches()) return null;
        var position = Integer.parseInt(parameter.group(1));
        for (var type : declaringFirst(beanClass, parameter.group(2))) {
            for (var method : type.methods()) {
                if (declares(method, parameter.group(3), parameter.group(4))) {
                    return new VaubanParameterInfo(method.parameters().get(position), position,
                            new VaubanMethodInfo(method, new VaubanClassInfo(type, lookup), lookup), lookup);
                }
            }
        }
        return null;
    }

    /**
     * {@code beanClass} and its superclasses, from the one of simple name {@code declaring} up: the class a
     * description names. A parameter of an observer a bean inherits names the bean class, and is found above it. When
     * the point names the class that declares its member, the chain starts at that class, not at the first of that
     * simple name (BUG-20261118-50, review round 5).
     */
    private java.util.List<io.vidocq.vauban.indexer.model.ClassInfo> declaringFirst(
            io.vidocq.vauban.indexer.model.ClassInfo beanClass, String declaring) {
        var chain = new java.util.ArrayList<io.vidocq.vauban.indexer.model.ClassInfo>();
        for (var type = beanClass; type != null && chain.size() < MAX_DEPTH;
                type = type.superName() == null ? null : lookup.find(type.superName()).orElse(null)) {
            chain.add(type);
        }
        for (int i = 0; i < chain.size(); i++) {
            if (ip.declaringClass() != null ? chain.get(i).name().equals(ip.declaringClass())
                    : chain.get(i).name().simpleName().equals(declaring)) {
                return chain.subList(i, chain.size());
            }
        }
        return chain;
    }

    /** Whether {@code method} is the method {@code name}, or the constructor for none, of {@code descriptor}. */
    private static boolean declares(io.vidocq.vauban.indexer.model.MethodInfo method, String name,
            String descriptor) {
        return (name == null ? method.isConstructor() : !method.isConstructor() && method.name().equals(name))
                && InjectionPointInfo.methodDescriptor(method.parameters().stream()
                        .map(io.vidocq.vauban.indexer.model.ParameterInfo::type).toList()).equals(descriptor);
    }

    private AnnotationInfo toAnnotationInfo(QualifierInstance q) {
        var indexAnnotation = new io.vidocq.vauban.indexer.model.AnnotationInfo(
                q.annotationName(), q.members());
        return new VaubanAnnotationInfo(indexAnnotation, lookup);
    }
}
