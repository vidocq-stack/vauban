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

import io.vidocq.vauban.core.bean.model.BeanDescriptor;
import io.vidocq.vauban.core.bean.model.BeanId;
import io.vidocq.vauban.core.bean.model.QualifierInstance;
import io.vidocq.vauban.core.bean.model.ScopeInfo;
import io.vidocq.vauban.indexer.model.DotName;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The scope a {@link ManagedBean} reports is the annotation type its own class loader sees: a custom scope declared in
 * an application layer is not visible to {@code vauban-core}'s loader, and must not turn into {@code @Dependent}.
 */
@DisplayName("ManagedBean: scope annotation type")
class ManagedBeanScopeTest {

    private static final String ISOLATED_SCOPE = "io.vidocq.vauban.core.container.isolated.IsolatedScope";

    public static class Owner {
    }

    /** Defines {@link #ISOLATED_SCOPE}, an annotation type no other loader of the test can see. */
    private static final class IsolatedScopeLoader extends ClassLoader {

        IsolatedScopeLoader() {
            super(ManagedBeanScopeTest.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!ISOLATED_SCOPE.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            byte[] bytes = ClassFile.of().build(ClassDesc.of(ISOLATED_SCOPE), clb -> clb
                    .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
                            | ClassFile.ACC_ANNOTATION)
                    .withInterfaceSymbols(ClassDesc.of(Annotation.class.getName())));
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static ManagedBean<Object> bean(ScopeInfo scope, ClassLoader loader) {
        var descriptor = new BeanDescriptor(new BeanId("scoped-bean"), DotName.of(Owner.class.getName()),
                BeanDescriptor.BeanKind.MANAGED, Set.of(), Set.of(QualifierInstance.DEFAULT, QualifierInstance.ANY),
                scope, false, 0, List.of(), null);
        return new ManagedBean<>(descriptor, Owner::new, loader, null);
    }

    @Test
    @DisplayName("a built-in scope resolves to its annotation type")
    void builtInScope() {
        assertSame(ApplicationScoped.class,
                bean(ScopeInfo.APPLICATION, ManagedBeanScopeTest.class.getClassLoader()).getScope());
    }

    @Test
    @DisplayName("a scope only the bean's class loader can see resolves through that loader, not to @Dependent")
    void scopeVisibleOnlyToTheBeanLoader() throws ClassNotFoundException {
        var loader = new IsolatedScopeLoader();
        var scope = new ScopeInfo(DotName.of(ISOLATED_SCOPE), true);

        var resolved = bean(scope, loader).getScope();

        assertEquals(ISOLATED_SCOPE, resolved.getName());
        assertSame(loader.loadClass(ISOLATED_SCOPE), resolved);
    }
}
