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
package io.vidocq.vauban.core.bean.model;

import io.vidocq.vauban.indexer.model.DotName;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

/**
 * Description of a discovered CDI interceptor.
 */
public record InterceptorDescriptor(
        DotName interceptorClass,
        Set<DotName> bindings,        // @InterceptorBinding annotations on the interceptor
        String aroundInvokeMethod,    // method name annotated with @AroundInvoke (null if none)
        String aroundConstructMethod, // method name annotated with @AroundConstruct (null if none)
        int priority,                 // @Priority value
        boolean enabled,              // true if @Priority annotation is present
        List<Annotation> bindingAnnotations  // actual annotation instances for member comparison
) {

    public InterceptorDescriptor(DotName interceptorClass, Set<DotName> bindings,
            String aroundInvokeMethod, String aroundConstructMethod, int priority) {
        this(interceptorClass, bindings, aroundInvokeMethod, aroundConstructMethod, priority, priority > 0, List.of());
    }

    public InterceptorDescriptor(DotName interceptorClass, Set<DotName> bindings,
            String aroundInvokeMethod, int priority) {
        this(interceptorClass, bindings, aroundInvokeMethod, null, priority, priority > 0, List.of());
    }

    public InterceptorDescriptor {
        bindings = Set.copyOf(bindings);
        bindingAnnotations = List.copyOf(bindingAnnotations);
    }
}
