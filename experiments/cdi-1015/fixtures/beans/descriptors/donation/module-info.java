/** Existing Vauban donation path: the generated provider grants its lookup; no opens. */
module experiment.beans {
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;
    requires microprofile.fault.tolerance.api;
    requires io.vidocq.vauban.api;
    requires io.vidocq.vauban.core;

    exports io.vidocq.vauban.experiment.beans;

    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.vauban.experiment.beans._VaubanComponents;
}
