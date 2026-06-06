package io.vidocq.vauban.core.interceptor.fixtures;

/**
 * Golden fixture bean that exercises every TypeRef encoding path in
 * {@link io.vidocq.vauban.core.interceptor.InterceptorSubclassGenerator}:
 * <ul>
 *   <li>void no-arg method</li>
 *   <li>method with primitive params including {@code long} (category-2) and {@code double} (category-2)</li>
 *   <li>{@code int}-returning method</li>
 *   <li>{@code String[]}-array-param method</li>
 *   <li>two non-private constructors</li>
 *   <li>inherited method (from {@link GoldenBeanSuper}) exercised via the {@code getMethods()} path</li>
 * </ul>
 */
public class GoldenBean extends GoldenBeanSuper {

    /** No-arg constructor. */
    public GoldenBean() {}

    /** Constructor with params — tests multiple non-private constructors. */
    public GoldenBean(String name, int value) {}

    /** Void no-arg method. */
    public void voidNoArg() {}

    /**
     * Method with primitive params including {@code long} and {@code double} (category-2 types
     * that each occupy two local-variable slots — the critical slot-arithmetic test).
     */
    public void primitiveParams(boolean b, byte by, char c, short s, int i, long l, float f, double d) {}

    /** int-returning method. */
    public int intReturn(int x) {
        return x * 2;
    }

    /** String[]-array-param method — exercises the array TypeRef path. */
    public String arrayParam(String[] values) {
        return values.length > 0 ? values[0] : "";
    }
}
