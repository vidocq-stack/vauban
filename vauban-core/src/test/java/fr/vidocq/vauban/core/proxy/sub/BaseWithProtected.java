package fr.vidocq.vauban.core.proxy.sub;

/**
 * Classe parent utilisée pour les tests de proxy : expose une méthode
 * {@code protected} dans un package distinct de celui du proxy afin de
 * reproduire la contrainte JVMS §4.10.1.9 (invokevirtual rejected).
 *
 * <p>Cas typique : {@code jakarta.servlet.http.HttpServlet.doGet} est
 * {@code protected} ; un servlet {@code @ApplicationScoped} qui en hérite
 * ne peut être proxifié par {@code invokevirtual} classique et requiert
 * un dispatch par {@link java.lang.invoke.MethodHandle}.</p>
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
