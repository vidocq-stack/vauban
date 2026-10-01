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
package io.vidocq.vauban.core.container.coverage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The beans the codegen-coverage tests read; their keys are binary names, {@code CoverageFixtures$CoveredBean}. */
public final class CoverageFixtures {

    /** The binary-name prefix of every fixture. */
    public static final String PREFIX = CoverageFixtures.class.getName() + "$";

    private CoverageFixtures() {}

    @Dependent
    public static class Dependency {}

    @Dependent
    public static class CoveredBean {
        @Inject Dependency dependency;

        @Inject
        void setUp(Dependency dependency) {}

        @PostConstruct
        void init() {}
    }

    @Dependent
    public static class PrivateFieldBean {
        @Inject private Dependency dependency;
    }

    @Dependent
    public static class BareBean {}

    @ApplicationScoped
    public static class ScopedBean {
        public String hello() {
            return "hello";
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Audited {}

    @Audited @Interceptor @Priority(Interceptor.Priority.APPLICATION)
    public static class AuditInterceptor {
        @Inject Dependency dependency;

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    @Audited @Dependent
    public static class AuditedBean {
        public void work() {}
    }

    public static class Widget {}

    public static class Gadget {}

    @Dependent
    public static class Factory {
        @Produces Gadget gadget = new Gadget();

        @Produces
        Widget widget() {
            return new Widget();
        }

        void dispose(@Disposes Widget widget) {}
    }
}
