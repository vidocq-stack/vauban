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
package io.vidocq.vauban.core.container;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.Extension;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class VaubanSeContainerInitializer extends SeContainerInitializer {

    private final List<Class<?>> beanClasses = new ArrayList<>();
    private final List<String> packages = new ArrayList<>();
    private ClassLoader classLoader;
    private boolean discoveryDisabled = false;
    private final Map<String, Object> properties = new HashMap<>();

    @Override
    public SeContainerInitializer addBeanClasses(Class<?>... classes) {
        beanClasses.addAll(List.of(classes));
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(Class<?>... packageClasses) {
        for (var c : packageClasses) {
            packages.add(c.getPackageName());
        }
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(boolean scanRecursively, Class<?>... packageClasses) {
        // scanPackage already scans recursively
        return addPackages(packageClasses);
    }

    @Override
    public SeContainerInitializer addPackages(Package... pkgs) {
        for (var p : pkgs) {
            packages.add(p.getName());
        }
        return this;
    }

    @Override
    public SeContainerInitializer addPackages(boolean scanRecursively, Package... pkgs) {
        return addPackages(pkgs);
    }

    @Override
    public SeContainerInitializer addExtensions(Extension... extensions) {
        // Portable extensions not supported — BCE only
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SeContainerInitializer addExtensions(Class<? extends Extension>... extensions) {
        return this;
    }

    @Override
    public SeContainerInitializer enableInterceptors(Class<?>... interceptorClasses) {
        beanClasses.addAll(List.of(interceptorClasses));
        return this;
    }

    @Override
    public SeContainerInitializer enableDecorators(Class<?>... decoratorClasses) {
        return this;
    }

    @Override
    public SeContainerInitializer selectAlternatives(Class<?>... alternativeClasses) {
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SeContainerInitializer selectAlternativeStereotypes(Class<? extends Annotation>... alternativeStereotypeClasses) {
        return this;
    }

    @Override
    public SeContainerInitializer addProperty(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    @Override
    public SeContainerInitializer setProperties(Map<String, Object> properties) {
        this.properties.clear();
        this.properties.putAll(properties);
        return this;
    }

    @Override
    public SeContainerInitializer disableDiscovery() {
        this.discoveryDisabled = true;
        return this;
    }

    @Override
    public SeContainerInitializer setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
        return this;
    }

    @Override
    public SeContainer initialize() {
        var builder = VaubanContainer.builder();

        var cl = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
        builder.classLoader(cl);

        if (discoveryDisabled) {
            builder.beanArchive(false);
        } else if (!packages.isEmpty()) {
            for (var pkg : packages) {
                builder.scanPackage(pkg);
            }
        } else if (beanClasses.isEmpty()) {
            // Default SE discovery: scan all bean archives (JARs/dirs with META-INF/beans.xml)
            builder.scanBeanArchivesFromClasspath();
        }

        for (var cls : beanClasses) {
            builder.addBeanClass(cls);
        }

        return new VaubanSeContainer(builder.build());
    }
}
