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
package io.vidocq.vauban.junit;

import io.vidocq.vauban.core.container.VaubanContainer;

import org.junit.jupiter.api.extension.*;

import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * JUnit 6 extension that bootstraps a Vauban CDI container for test classes.
 * <p>
 * Usage:
 * <pre>
 * &#64;VaubanTest
 * &#64;AddBeans({MyService.class, MyRepository.class})
 * class MyServiceTest {
 *     &#64;Inject MyService service;
 *
 *     &#64;Test void shouldWork() { ... }
 * }
 * </pre>
 */
public final class VaubanExtension
        implements BeforeAllCallback, AfterAllCallback, TestInstancePostProcessor {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(VaubanExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        containerOf(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        var container = context.getStore(NS).get(context.getRequiredTestClass(), VaubanContainer.class);
        if (container != null) {
            container.close();
        }
    }

    /**
     * Injects the {@code @Inject} fields of the test instance. With the per-class lifecycle, JUnit calls this
     * before {@link #beforeAll}, so the container is started here when it is not yet.
     */
    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
        injectFields(testInstance, containerOf(context));
    }

    /**
     * The container of the test class, started on first use and kept in the class-level store, so that it lives
     * until {@link #afterAll} whichever callback asks first. It is keyed by the test class: a {@code @Nested}
     * class gets its own container, not the one of its enclosing class that the store would inherit.
     */
    private static VaubanContainer containerOf(ExtensionContext context) {
        var classContext = context;
        while (classContext.getTestMethod().isPresent()) {
            classContext = classContext.getParent().orElseThrow();
        }
        var testClass = classContext.getRequiredTestClass();
        return classContext.getStore(NS).computeIfAbsent(testClass, VaubanExtension::start, VaubanContainer.class);
    }

    private static VaubanContainer start(Class<?> testClass) {
        var builder = VaubanContainer.builder();
        var addBeans = testClass.getAnnotation(AddBeans.class);
        if (addBeans != null) {
            for (var beanClass : addBeans.value()) {
                builder.addBeanClass(beanClass);
            }
        }
        return builder.build();
    }

    @SuppressWarnings("java:S3011") // CDI spec requires reflective access
    private void injectFields(Object instance, VaubanContainer container) throws IllegalAccessException {
        var lookup = container.getVaubanLookup();
        var fields = collectInjectableFields(instance.getClass());
        for (var field : fields) {
            var bean = container.select(field.getType());
            lookup.makeAccessible(field);
            field.set(instance, bean);
        }
    }

    private java.util.List<Field> collectInjectableFields(Class<?> clazz) {
        var fields = new ArrayList<Field>();
        var current = clazz;
        while (current != null && current != Object.class) {
            for (var field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.inject.Inject.class)) {
                    fields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }
}
