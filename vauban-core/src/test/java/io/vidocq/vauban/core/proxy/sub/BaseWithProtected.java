package io.vidocq.vauban.core.proxy.sub;

/**
 * Parent class used for the proxy tests: it exposes a {@code protected} method
 * in a package distinct from the proxy's in order to reproduce the JVMS
 * §4.10.1.9 constraint (invokevirtual rejected).
 *
 * <p>Typical case: {@code jakarta.servlet.http.HttpServlet.doGet} is
 * {@code protected}; an {@code @ApplicationScoped} servlet that inherits from it
 * cannot be proxied via classic {@code invokevirtual} and requires a dispatch
 * through a {@link java.lang.invoke.MethodHandle}.</p>
 */
public class BaseWithProtected {

    protected String protectedEcho(String value) {
        return "base:" + value;
    }

    protected int protectedSum(int a, int b) {
        return a + b;
    }

    public String publicGreeting(String name) {
        return "hello " + name;
    }
}
