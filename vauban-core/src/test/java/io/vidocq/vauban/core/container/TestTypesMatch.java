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
