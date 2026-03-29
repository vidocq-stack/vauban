package fr.vidocq.vauban.tck;

import jakarta.enterprise.context.spi.Contextual;
import org.jboss.cdi.tck.spi.CreationalContexts;

public class VaubanCreationalContexts implements CreationalContexts {

    @Override
    public <T> CreationalContexts.Inspectable<T> create(Contextual<T> contextual) {
        return new CreationalContexts.Inspectable<>() {
            private boolean pushCalled = false;
            private boolean releaseCalled = false;
            private Object lastBeanPushed;

            @Override
            public void push(T incompleteInstance) {
                pushCalled = true;
                lastBeanPushed = incompleteInstance;
            }

            @Override
            public void release() {
                releaseCalled = true;
            }

            @Override
            public boolean isPushCalled() { return pushCalled; }

            @Override
            public Object getLastBeanPushed() { return lastBeanPushed; }

            @Override
            public boolean isReleaseCalled() { return releaseCalled; }
        };
    }
}
