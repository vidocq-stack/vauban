package io.vidocq.vauban.tck;

import jakarta.enterprise.context.spi.Context;
import org.jboss.cdi.tck.spi.Contexts;

public class VaubanContexts implements Contexts<Context> {
    @Override
    public void setActive(Context context) {
        // Activate the context if it's our RequestContext
        if (context instanceof io.vidocq.vauban.core.context.RequestContext rc) {
            rc.activate();
        }
    }

    @Override
    public void setInactive(Context context) {
        if (context instanceof io.vidocq.vauban.core.context.RequestContext rc) {
            rc.deactivate();
        }
    }

    @Override
    public Context getRequestContext() {
        var container = io.vidocq.vauban.core.container.VaubanContainer.current();
        if (container != null) {
            return container.requestContext();
        }
        // Fallback to ContainerHolder
        var holder = ContainerHolder.get();
        if (holder != null) {
            return holder.requestContext();
        }
        throw new IllegalStateException("No Vauban container is running");
    }

    @Override
    public Context getDependentContext() {
        return new io.vidocq.vauban.core.context.DependentContext();
    }

    @Override
    public void destroyContext(Context context) {
        if (context instanceof io.vidocq.vauban.core.context.ApplicationContext ac) {
            ac.deactivate();
        } else if (context instanceof io.vidocq.vauban.core.context.RequestContext rc) {
            rc.deactivate();
        }
    }
}
