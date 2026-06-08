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

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public class TestTypesMatch {
    public static void main(String[] args) throws Exception {
        Method dummy = TestTypesMatch.class.getMethod("dummy");
        Type requiredType = dummy.getGenericReturnType();
        
        Method beanMethod = TestTypesMatch.class.getMethod("beanDao");
        Type beanType = beanMethod.getGenericReturnType();
        
        Method typesMatchM = VaubanBeanManager.class.getDeclaredMethod("typesMatch", Type.class, Type.class);
        typesMatchM.setAccessible(true);
        
        boolean match = (Boolean) typesMatchM.invoke(null, beanType, requiredType);
        System.out.println("Match? " + match);
    }
    
    public <T1 extends RuntimeException, T3> Dao<T1, T3> dummy() { return null; }
    public Dao<Integer, Integer> beanDao() { return null; }
    
    interface Dao<A, B> {}
}
