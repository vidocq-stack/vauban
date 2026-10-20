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
package io.vidocq.vauban.experiment.beans;

import io.vidocq.vauban.api.AnnotationTypeMetadata;
import io.vidocq.vauban.api.GeneratedCoverage;
import io.vidocq.vauban.api.VaubanComponentProvider;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Retains generated construction, injection and proxy support without donating a lookup.
 *
 * <p>The relay and closed module descriptors register only this provider. The generated
 * provider remains an implementation detail: registering it as a second service would
 * restore donation and invalidate the negative control.
 */
public final class RelayComponents implements VaubanComponentProvider {

    private final VaubanComponentProvider delegate = new _VaubanComponents();

    @Override
    public Object create(String className) {
        return delegate.create(className);
    }

    @Override
    public Object create(String className, Object[] args) {
        return delegate.create(className, args);
    }

    @Override
    public AnnotationTypeMetadata annotationMetadata(String annotationClassName) {
        return delegate.annotationMetadata(annotationClassName);
    }

    @Override
    public Map<String, Object> readAnnotation(Annotation annotation) {
        return delegate.readAnnotation(annotation);
    }

    @Override
    public Annotation annotationLiteral(String annotationClassName, Map<String, Object> members) {
        return delegate.annotationLiteral(annotationClassName, members);
    }

    @Override
    public boolean injectField(Object bean, String className, String fieldName, Object value) {
        return delegate.injectField(bean, className, fieldName, value);
    }

    @Override
    public Object createClientProxy(String proxyClassName, Supplier<?> contextualInstance) {
        return delegate.createClientProxy(proxyClassName, contextualInstance);
    }

    @Override
    public Object invoke(Object target, String className, String methodId, Object[] args) {
        return delegate.invoke(target, className, methodId, args);
    }

    @Override
    public GeneratedCoverage coverage() {
        return delegate.coverage();
    }

    // Keep VaubanComponentProvider.grantModuleLookup's default no-op.
}
