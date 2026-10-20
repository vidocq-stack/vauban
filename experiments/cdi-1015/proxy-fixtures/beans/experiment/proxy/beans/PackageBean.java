package experiment.proxy.beans;

/**
 * Exercises a constructor and virtual method that require package access.
 */
public class PackageBean {
    PackageBean() {
    }

    String packageMessage() {
        return "bean";
    }

    public String callMessage() {
        return packageMessage();
    }
}
