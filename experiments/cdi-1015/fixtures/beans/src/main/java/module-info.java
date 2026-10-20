/**
 * Default relay variant. The runner substitutes the descriptors in {@code descriptors/}.
 *
 * The generated provider and interception subclass still depend on Vauban. This fixture
 * compares access mechanisms; it does not establish portability of generated bean binaries.
 */
module experiment.beans {
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;
    requires microprofile.fault.tolerance.api;
    requires io.vidocq.vauban.api;
    // Generated interception subclasses and factories refer to container runtime types.
    requires io.vidocq.vauban.core;

    exports io.vidocq.vauban.experiment.beans;
    opens io.vidocq.vauban.experiment.beans to jakarta.cdi;

    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.vauban.experiment.beans.RelayComponents;
}
