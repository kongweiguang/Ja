// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.support;

import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定 Provider loopback 支撑类自身依赖的 Header 与取消并发契约。 */
class ModelAdapterTestSupportTest {
    /** 避免重复 Header 时 OkHttp 的末值 API 让协议断言观察到错误值。 */
    @Test
    void returnsFirstValueForRepeatedRequestHeader() {
        ModelAdapterTestSupport.RequestHeaders headers = new ModelAdapterTestSupport.RequestHeaders(
                new Headers.Builder().add("X-Test", "first").add("X-Test", "last").build());

        assertEquals("first", headers.getFirst("x-test"));
    }

    /**
     * 将多批注册与单次取消同时释放，覆盖取消遍历和注册后复查之间的竞态，并检查回调引用最终解除。
     */
    @Test
    void invokesEachCancellationCallbackAtMostOnceWhenRegistrationRacesCancel() throws Exception {
        int workerCount = 4;
        int callbacksPerWorker = 256;
        int registeredBeforeRace = 64;
        int callbackCount = workerCount * callbacksPerWorker;
        ModelAdapterTestSupport.TestCancellation cancellation = new ModelAdapterTestSupport.TestCancellation();
        AtomicInteger executions = new AtomicInteger();
        List<CancellationToken.Registration> registrations = new CopyOnWriteArrayList<>();
        CountDownLatch firstBatchReady = new CountDownLatch(workerCount);
        CountDownLatch raceStart = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(workerCount + 1)) {
            List<Future<?>> workers = new java.util.ArrayList<>();
            for (int worker = 0; worker < workerCount; worker++) {
                workers.add(executor.submit(() -> {
                    for (int index = 0; index < callbacksPerWorker; index++) {
                        registrations.add(cancellation.onCancellation(executions::incrementAndGet));
                        if (index + 1 == registeredBeforeRace) {
                            firstBatchReady.countDown();
                            raceStart.await();
                        }
                    }
                    return null;
                }));
            }
            boolean ready = firstBatchReady.await(5, TimeUnit.SECONDS);
            if (!ready) {
                raceStart.countDown();
                assertTrue(false, "registration workers must reach the race gate");
            }
            Future<?> cancel = executor.submit(() -> {
                raceStart.await();
                cancellation.cancel();
                return null;
            });
            raceStart.countDown();

            for (Future<?> worker : workers) {
                worker.get(10, TimeUnit.SECONDS);
            }
            cancel.get(10, TimeUnit.SECONDS);
        }

        assertEquals(callbackCount, registrations.size());
        assertEquals(callbackCount, executions.get());
        registrations.forEach(CancellationToken.Registration::close);
        assertEquals(0, cancellation.callbackCount());
    }
}
