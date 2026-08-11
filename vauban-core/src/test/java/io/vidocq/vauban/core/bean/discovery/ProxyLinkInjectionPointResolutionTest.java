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
package io.vidocq.vauban.core.bean.discovery;

import io.vidocq.vauban.api.ProxyLink;
import io.vidocq.vauban.indexer.IndexBuilder;
import io.vidocq.vauban.indexer.model.*;
import io.vidocq.vauban.indexer.model.ClassInfo.ClassKind;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the fifth {@code ProxyLink} exclusion (Vidocq/vauban#24), caught by
 * the Vidocq Arquillian IT: {@code resolveGenericTypeForMethodParameter} matches the
 * reflective constructor <em>by parameter count only</em>, and the {@code (ProxyLink)}
 * entry constructor has the same arity as most business constructors. Depending on
 * {@code getDeclaredConstructors()} order it hijacked the match and swapped the
 * injection-point type for {@code ProxyLink} — producing
 * {@code Unsatisfied dependency: … of type ProxyLink} at deployment validation.
 *
 * <p>The fixture class is loadable by the TCCL on purpose: the reflective resolution path
 * only runs when {@code Class.forName} succeeds (which is why an unloadable-FQN test
 * cannot cover it).
 */
@DisplayName("BeanDiscovery — the ProxyLink constructor never hijacks generic-type resolution")
class ProxyLinkInjectionPointResolutionTest {

    public static class Collaborator {
    }

    /** Marker first, so a declaration-ordered reflection walk meets it before the @Inject ctor. */
    public static class Bean {
        protected Bean(ProxyLink link) {
        }

        @Inject
        public Bean(Collaborator collaborator) {
        }
    }

    @Test
    @DisplayName("the @Inject constructor's parameter keeps its own type")
    void injectConstructorParameterKeepsItsType() {
        var beanFqn = Bean.class.getName();
        var collaboratorFqn = Collaborator.class.getName();

        var beanInfo = new ClassInfo(DotName.of(beanFqn), DotName.of("java.lang.Object"),
                List.of(), 0x0001, List.of(),
                List.of(
                        ctor(0x0004, ProxyLink.CLASS_NAME),
                        ctorWithInject(collaboratorFqn)),
                List.of(), ClassKind.CLASS);

        var builder = new IndexBuilder();
        builder.add(beanInfo);
        builder.add(new ClassInfo(DotName.of(collaboratorFqn), DotName.of("java.lang.Object"),
                List.of(), 0x0001, List.of(), List.of(ctor(0x0001)), List.of(), ClassKind.CLASS));
        var discovery = new BeanDiscovery(builder.build());

        var points = discovery.discoverInjectionPoints(beanInfo);

        assertEquals(1, points.size(), "exactly the @Inject constructor parameter: " + points);
        assertTrue(points.getFirst().requiredType() instanceof TypeInfo.ClassType ct
                        && ct.name().value().equals(collaboratorFqn),
                "the parameter type must stay " + collaboratorFqn + ", got: " + points);
    }

    private static MethodInfo ctor(int accessFlags, String... paramTypes) {
        var params = new java.util.ArrayList<ParameterInfo>();
        for (var type : paramTypes) {
            params.add(new ParameterInfo("p", new TypeInfo.ClassType(DotName.of(type)), List.of()));
        }
        return new MethodInfo("<init>", new TypeInfo.ClassType(DotName.of("void")),
                params, List.of(), accessFlags, List.of());
    }

    private static MethodInfo ctorWithInject(String paramType) {
        return new MethodInfo("<init>", new TypeInfo.ClassType(DotName.of("void")),
                List.of(new ParameterInfo("p", new TypeInfo.ClassType(DotName.of(paramType)), List.of())),
                List.of(), 0x0001,
                List.of(new AnnotationInfo(DotName.of("jakarta.inject.Inject"), java.util.Map.of())));
    }
}
