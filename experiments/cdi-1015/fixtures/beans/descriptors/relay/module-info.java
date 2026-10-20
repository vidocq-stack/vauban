/** Opening consent names the CDI API; the application authorizes subsequent relays. */
module experiment.beans {
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.interceptor;
    requires jakarta.annotation;
    requires microprofile.fault.tolerance.api;
    requires io.vidocq.vauban.api;
    // This read edge serves Vauban-generated code, independently of package openings.
    requires io.vidocq.vauban.core;

    exports io.vidocq.vauban.experiment.beans;
    opens io.vidocq.vauban.experiment.beans to jakarta.cdi;

    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.vauban.experiment.beans.RelayComponents;
}
