package fr.vidocq.vauban.tck;

import jakarta.enterprise.context.spi.Context;
import org.jboss.cdi.tck.spi.Contexts;

public class VaubanContexts implements Contexts<Context> {
    @Override
    public void setActive(Context context) {
        // Activate the context if it's our RequestContext
        if (context instanceof fr.vidocq.vauban.core.context.RequestContext rc) {
            rc.activate();
        }
    }

    @Override
    public void setInactive(Context context) {
        if (context instanceof fr.vidocq.vauban.core.context.RequestContext rc) {
            rc.deactivate();
        }
    }

    @Override
    public Context getRequestContext() {
        // This would need access to the current container
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Context getDependentContext() {
        return new fr.vidocq.vauban.core.context.DependentContext();
    }

    @Override
    public void destroyContext(Context context) {
        if (context instanceof fr.vidocq.vauban.core.context.ApplicationContext ac) {
            ac.deactivate();
        } else if (context instanceof fr.vidocq.vauban.core.context.RequestContext rc) {
            rc.deactivate();
        }
    }
}
