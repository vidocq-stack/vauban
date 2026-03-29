package fr.vidocq.vauban.tck;

import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.CreationalContext;
import org.jboss.cdi.tck.spi.Contextuals;

public class VaubanContextuals implements Contextuals {

    @Override
    public <T> Contextuals.Inspectable<T> create(T instance, Context context) {
        return new Contextuals.Inspectable<>() {
            private CreationalContext<T> creationalContextPassedToCreate;
            private T instancePassedToDestroy;
            private CreationalContext<T> creationalContextPassedToDestroy;

            @Override
            public T create(CreationalContext<T> creationalContext) {
                this.creationalContextPassedToCreate = creationalContext;
                return instance;
            }

            @Override
            public void destroy(T inst, CreationalContext<T> creationalContext) {
                this.instancePassedToDestroy = inst;
                this.creationalContextPassedToDestroy = creationalContext;
            }

            @Override
            public CreationalContext<T> getCreationalContextPassedToCreate() {
                return creationalContextPassedToCreate;
            }

            @Override
            public T getInstancePassedToDestroy() {
                return instancePassedToDestroy;
            }

            @Override
            public CreationalContext<T> getCreationalContextPassedToDestroy() {
                return creationalContextPassedToDestroy;
            }
        };
    }
}
