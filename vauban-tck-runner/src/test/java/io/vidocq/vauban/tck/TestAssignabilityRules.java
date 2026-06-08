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
package io.vidocq.vauban.tck;
import io.vidocq.vauban.core.bean.discovery.BeanDiscovery;
import io.vidocq.vauban.core.types.AssignabilityRules;
import io.vidocq.vauban.indexer.VaubanIndex;
import io.vidocq.vauban.indexer.model.DotName;
import io.vidocq.vauban.indexer.model.TypeInfo;
import java.lang.reflect.Type;
import java.util.List;

public class TestAssignabilityRules {
    public static void main(String[] args) throws Exception {
        java.lang.reflect.Constructor<VaubanIndex> ctor = VaubanIndex.class.getDeclaredConstructor(java.util.Map.class);
        ctor.setAccessible(true);
        VaubanIndex index = ctor.newInstance(java.util.Map.of());
        AssignabilityRules rules = new AssignabilityRules(index);
        
        java.lang.reflect.Method method = TestAssignabilityRules.class.getMethod("dummy");
        Type returnType = method.getGenericReturnType();
        
        // Reflection access to reflectTypeToTypeInfo
        java.lang.reflect.Method mReflect = BeanDiscovery.class.getDeclaredMethod("reflectTypeToTypeInfo", Type.class);
        mReflect.setAccessible(true);
        TypeInfo required = (TypeInfo) mReflect.invoke(null, returnType);
        
        java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) returnType;
        java.lang.reflect.TypeVariable<?> tv1 = (java.lang.reflect.TypeVariable<?>) pt.getActualTypeArguments()[0];
        System.out.println("T1 getBounds: " + java.util.Arrays.toString(tv1.getBounds()));
        
        TypeInfo beanArg1 = new TypeInfo.ClassType(DotName.of("java.lang.Integer"));
        TypeInfo beanArg2 = new TypeInfo.ClassType(DotName.of("java.lang.Integer"));
        TypeInfo daoInteger = new TypeInfo.ParameterizedType(DotName.of("Dao"), List.of(beanArg1, beanArg2));
        
        TypeInfo objArg1 = new TypeInfo.ClassType(DotName.of("java.lang.Object"));
        TypeInfo daoObject = new TypeInfo.ParameterizedType(DotName.of("Dao"), List.of(objArg1, objArg1));
        
        System.out.println("Required: " + required);
        System.out.println("Dao<Integer, Integer> assignable? " + rules.isAssignable(daoInteger, required));
        System.out.println("Dao<Object, Object> assignable? " + rules.isAssignable(daoObject, required));
    }
    
    public <T1 extends RuntimeException, T3> Dao<T1, T3> dummy() { return null; }
    interface Dao<A, B> {}
}
