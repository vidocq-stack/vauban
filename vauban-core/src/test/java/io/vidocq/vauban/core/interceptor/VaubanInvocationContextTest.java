package io.vidocq.vauban.core.interceptor;

import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link VaubanInvocationContext#proceed()} contract.
 *
 * <p>Verifies in particular that the position in the chain is no longer mutable
 * instance state - a critical point for composition with {@code @Asynchronous}
 * interceptors that continue execution on a virtual thread after the initial caller
 * has returned.</p>
 */
@DisplayName("VaubanInvocationContext.proceed()")
class VaubanInvocationContextTest {

    static final class Target {
        int calls;
        public String hello() {
            calls++;
            return "ok";
        }
    }

    static VaubanInvocationContext.InterceptorInvocation interceptorAt(Object holder, String name)
            throws NoSuchMethodException {
        Method m = holder.getClass().getDeclaredMethod(name, InvocationContext.class);
        return new VaubanInvocationContext.InterceptorInvocation(holder, m);
    }

    // --- 1. Basic progression: one interceptor, one proceed() ---

    static final class Identity {
        public Object around(InvocationContext ctx) throws Exception {
            return ctx.proceed();
        }
    }

    @Test
    @DisplayName("single interceptor → forwards to target method")
    void singleInterceptorForwards() throws Exception {
        Target target = new Target();
        Method m = Target.class.getDeclaredMethod("hello");
        var ctx = new VaubanInvocationContext(target, m, new Object[0],
                List.of(interceptorAt(new Identity(), "around")));

        assertEquals("ok", ctx.proceed());
        assertEquals(1, target.calls);
    }

    // --- 2. multiple proceed() calls from the same interceptor (@Retry semantics) ---

    static final class RetryThrice {
        int proceedCalls;
        public Object around(InvocationContext ctx) throws Exception {
            Object last = null;
            for (int i = 0; i < 3; i++) {
                last = ctx.proceed();
                proceedCalls++;
            }
            return last;
        }
    }

    @Test
    @DisplayName("multiple proceed() calls from same interceptor each invoke the target (retry semantics)")
    void retrySemanticsPreserved() throws Exception {
        Target target = new Target();
        Method m = Target.class.getDeclaredMethod("hello");
        var retry = new RetryThrice();
        var ctx = new VaubanInvocationContext(target, m, new Object[0],
                List.of(interceptorAt(retry, "around")));

        assertEquals("ok", ctx.proceed());
        assertEquals(3, retry.proceedCalls);
        assertEquals(3, target.calls);
    }

    // --- 3. proceed() from another thread after caller returns (@Asynchronous scenario) ---

    static final class AsyncForker {
        CompletableFuture<Object> future = new CompletableFuture<>();
        public Object around(InvocationContext ctx) {
            // Reproduces Heisenberg's AsynchronousEngine pattern:
            // forks a virtual thread that will call ctx.proceed() AFTER this
            // method has returned. If the chain position were shared through an
            // instance field and restored in a finally block, the vthread would re-enter
            // this interceptor (infinite cascade).
            Thread.ofVirtual().start(() -> {
                try {
                    future.complete(ctx.proceed());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future;
        }
    }

    @Test
    @DisplayName("ctx.proceed() invoked from a vthread after caller returned still bypasses the chain")
    void crossThreadProceedDoesNotReenter() throws Exception {
        Target target = new Target();
        Method m = Target.class.getDeclaredMethod("hello");
        var forker = new AsyncForker();
        var ctx = new VaubanInvocationContext(target, m, new Object[0],
                List.of(interceptorAt(forker, "around")));

        Object outer = ctx.proceed();
        assertTrue(outer instanceof CompletableFuture<?>, "expected async stage from interceptor");

        Object actual = ((CompletableFuture<?>) outer).get();
        assertEquals("ok", actual);
        // If the chain had re-entered, target.calls would be > 1.
        assertEquals(1, target.calls, "target must be invoked exactly once even when proceed() is cross-thread");
    }

    // --- 4. concurrent proceed() from 2 vthreads sharing the same ctx ---

    static final class ConcurrentForker {
        public Object around(InvocationContext ctx) throws Exception {
            var f1 = CompletableFuture.supplyAsync(() -> safe(ctx),
                    r -> Thread.ofVirtual().start(r));
            var f2 = CompletableFuture.supplyAsync(() -> safe(ctx),
                    r -> Thread.ofVirtual().start(r));
            return CompletableFuture.allOf(f1, f2).thenApply(v -> f1.join() + ":" + f2.join());
        }
        private static Object safe(InvocationContext ctx) {
            try { return ctx.proceed(); } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    @Test
    @DisplayName("concurrent proceed() calls from multiple vthreads each invoke the target exactly once")
    void concurrentProceedCalls() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        Object target = new Object() {
            public String exec() { counter.incrementAndGet(); return "x"; }
        };
        Method m = target.getClass().getDeclaredMethod("exec");
        var ctx = new VaubanInvocationContext(target, m, new Object[0],
                List.of(interceptorAt(new ConcurrentForker(), "around")));

        var stage = (CompletableFuture<?>) ctx.proceed();
        assertEquals("x:x", stage.get());
        assertEquals(2, counter.get());
    }
}
