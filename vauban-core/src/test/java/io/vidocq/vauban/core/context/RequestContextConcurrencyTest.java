/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.RequestScoped;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Threads that share one request (the subtasks of a {@code StructuredTaskScope} inherit the
 * request state) must share one instance of a {@code @RequestScoped} bean (Vidocq/vauban#280,
 * BUG-20261013-03).
 */
@DisplayName("RequestContext: threads sharing one request")
class RequestContextConcurrencyTest {

    @RequestScoped
    public static class Slow {
        static final AtomicInteger created = new AtomicInteger();
        static final AtomicInteger destroyed = new AtomicInteger();
        static volatile CountDownLatch bothCreating = new CountDownLatch(2);

        private int id;

        @PostConstruct
        void init() throws InterruptedException {
            id = created.incrementAndGet();
            bothCreating.countDown();
            // Hold the creating thread until a second one is creating too: it never comes when
            // the context lets one creation at a time, hence the bounded wait.
            bothCreating.await(300, TimeUnit.MILLISECONDS);
        }

        @PreDestroy
        void done() {
            destroyed.incrementAndGet();
        }

        public int id() {
            return id;
        }
    }

    private VaubanContainer container;

    @BeforeEach
    void start() {
        Slow.created.set(0);
        Slow.destroyed.set(0);
        Slow.bothCreating = new CountDownLatch(2);
        container = VaubanContainer.builder().addBeanClass(Slow.class).build();
    }

    @AfterEach
    void stop() {
        container.close();
    }

    @Test
    @DisplayName("two threads using a request-scoped bean for the first time share one instance")
    void oneInstancePerRequest() {
        var slow = container.select(Slow.class);
        var ids = new int[2];
        var requestContext = container.requestContext();
        requestContext.runInScope(() -> {
            var threads = new Thread[2];
            for (int i = 0; i < threads.length; i++) {
                int slot = i;
                threads[i] = Thread.ofVirtual().unstarted(
                        requestContext.inCurrentRequest(() -> ids[slot] = slow.id()));
            }
            for (var thread : threads) {
                thread.start();
            }
            for (var thread : threads) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        assertEquals(ids[0], ids[1], "both threads reached the same instance");
        assertEquals(1, Slow.created.get(), "one instance created");
        assertEquals(1, Slow.destroyed.get(), "the instance is destroyed with the request");
    }
}
