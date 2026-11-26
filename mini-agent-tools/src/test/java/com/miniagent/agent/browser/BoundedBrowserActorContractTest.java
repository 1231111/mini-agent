package com.miniagent.agent.browser;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency contract for the Playwright owner actor.  These tests deliberately use
 * plain callables, so they validate the lifecycle boundary without starting Chromium.
 */
class BoundedBrowserActorContractTest {
    @Test
    void queueCapacityIsBoundedAndRejectedWorkNeverStarts() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        BoundedBrowserActor actor = actor();
        try {
            CompletableFuture<BoundedBrowserActor.Result<String>> first = CompletableFuture.supplyAsync(() ->
                    actor.invoke(() -> {
                        started.incrementAndGet();
                        firstStarted.countDown();
                        releaseFirst.await(5, TimeUnit.SECONDS);
                        return "first";
                    }, 5_000, () -> {}, () -> {}));
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<BoundedBrowserActor.Result<String>> queued = CompletableFuture.supplyAsync(() ->
                    actor.invoke(() -> {
                        started.incrementAndGet();
                        return "queued";
                    }, 5_000, () -> {}, () -> {}));
            awaitQueue(actor);

            BoundedBrowserActor.Result<String> rejected = actor.invoke(() -> {
                started.incrementAndGet();
                return "rejected";
            }, 200, () -> {}, () -> {});
            assertThat(rejected.status()).isEqualTo(BoundedBrowserActor.Status.QUEUE_FULL);
            releaseFirst.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).value()).isEqualTo("first");
            assertThat(queued.get(2, TimeUnit.SECONDS).value()).isEqualTo("queued");
            assertThat(started).hasValue(2);
        } finally {
            releaseFirst.countDown();
            actor.quarantine();
        }
    }

    @Test
    void runningTimeoutQuarantinesActorAndCleansUpOnOwnerThreadExactlyOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        AtomicInteger aborted = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicReference<Thread> operationThread = new AtomicReference<>();
        AtomicReference<Thread> cleanupThread = new AtomicReference<>();
        BoundedBrowserActor actor = new BoundedBrowserActor("browser-timeout-test", 1,
                aborted::incrementAndGet, () -> {
                    cleanupThread.set(Thread.currentThread());
                    cleaned.countDown();
                });
        try {
            AtomicReference<BoundedBrowserActor.Result<String>> result = new AtomicReference<>();
            Thread caller = new Thread(() -> result.set(actor.invoke(() -> {
                operationThread.set(Thread.currentThread());
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                return "late";
            }, 200, () -> {}, completed::incrementAndGet)), "browser-timeout-caller");
            caller.start();
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            caller.join(2_000);
            assertThat(result.get().status()).isEqualTo(BoundedBrowserActor.Status.EXPIRED_RUNNING);
            assertThat(actor.isQuarantined()).isTrue();
            assertThat(aborted).hasValue(1);

            BoundedBrowserActor.Result<String> unavailable = actor.invoke(() -> "must-not-run",
                    100, () -> {}, completed::incrementAndGet);
            assertThat(unavailable.status()).isEqualTo(BoundedBrowserActor.Status.UNAVAILABLE);
            release.countDown();
            assertThat(cleaned.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cleanupThread.get()).isEqualTo(operationThread.get());
            assertThat(completed).hasValue(2);
        } finally {
            release.countDown();
            actor.quarantine();
        }
    }

    @Test
    void queuedCancellationDoesNotQuarantineAHealthyOwner() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        AtomicReference<BoundedBrowserActor.Result<String>> secondResult = new AtomicReference<>();
        AtomicInteger secondCallback = new AtomicInteger();
        BoundedBrowserActor actor = actor();
        try {
            CompletableFuture<BoundedBrowserActor.Result<String>> first = CompletableFuture.supplyAsync(() ->
                    actor.invoke(() -> {
                        firstStarted.countDown();
                        releaseFirst.await(5, TimeUnit.SECONDS);
                        return "first";
                    }, 5_000, () -> {}, () -> {}));
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            Thread queuedCaller = new Thread(() -> secondResult.set(actor.invoke(() -> "must-not-run",
                    5_000, () -> {}, () -> {
                        secondCallback.incrementAndGet();
                        secondCompleted.countDown();
                    })), "browser-cancel-caller");
            queuedCaller.start();
            awaitQueue(actor);
            queuedCaller.interrupt();
            queuedCaller.join(2_000);
            assertThat(secondResult.get().status()).isEqualTo(BoundedBrowserActor.Status.CANCELLED_QUEUED);
            assertThat(secondCompleted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondCallback).hasValue(1);
            assertThat(actor.isQuarantined()).isFalse();
            releaseFirst.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).status()).isEqualTo(BoundedBrowserActor.Status.COMPLETED);
        } finally {
            releaseFirst.countDown();
            actor.quarantine();
        }
    }

    @Test
    void queuedTimeoutRemovesWorkAndReleasesCapacityWithoutAbortingRunningWork() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger expiredStarts = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        BoundedBrowserActor actor = actor();
        try {
            CompletableFuture<BoundedBrowserActor.Result<String>> first = CompletableFuture.supplyAsync(() ->
                    actor.invoke(() -> {
                        firstStarted.countDown();
                        releaseFirst.await(5, TimeUnit.SECONDS);
                        return "first";
                    }, 5_000, () -> {}, () -> {}));
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            BoundedBrowserActor.Result<String> expired = actor.invoke(() -> {
                expiredStarts.incrementAndGet();
                return "expired";
            }, 30, () -> {}, completed::incrementAndGet);
            assertThat(expired.status()).isEqualTo(BoundedBrowserActor.Status.EXPIRED_QUEUED);
            assertThat(actor.queuedTaskCount()).isZero();
            assertThat(completed).hasValue(1);
            assertThat(actor.isQuarantined()).isFalse();
            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            assertThat(actor.invoke(() -> "next", 500, () -> {}, () -> {}).value()).isEqualTo("next");
            assertThat(expiredStarts).hasValue(0);
        } finally {
            releaseFirst.countDown();
            actor.quarantine();
        }
    }

    @Test
    void runningCancellationKeepsPendingWorkTrackedUntilOwnerActuallyExits() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicBoolean interruptionRestored = new AtomicBoolean();
        AtomicReference<BoundedBrowserActor.Result<String>> result = new AtomicReference<>();
        BoundedBrowserActor actor = actor();
        try {
            Thread caller = new Thread(() -> {
                result.set(actor.invoke(() -> {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return "late";
                }, 5_000, () -> stopped.set(true), completed::countDown));
                interruptionRestored.set(Thread.currentThread().isInterrupted());
            }, "browser-running-cancel-caller");
            caller.start();
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2_000);
            assertThat(result.get().status()).isEqualTo(BoundedBrowserActor.Status.CANCELLED_RUNNING);
            assertThat(stopped).isTrue();
            assertThat(interruptionRestored).isTrue();
            assertThat(actor.isQuarantined()).isTrue();
            assertThat(completed.getCount()).isEqualTo(1);
            release.countDown();
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            actor.quarantine();
        }
    }

    @Test
    void nativeAbortFailureCannotEscapeTheOperationTimeout() {
        CountDownLatch release = new CountDownLatch(1);
        BoundedBrowserActor actor = new BoundedBrowserActor("browser-abort-failure-test", 1,
                () -> { throw new IllegalStateException("native process unavailable"); }, () -> {});
        try {
            BoundedBrowserActor.Result<String> result = actor.invoke(() -> {
                release.await(5, TimeUnit.SECONDS);
                return "late";
            }, 50, () -> {}, () -> {});
            assertThat(result.status()).isEqualTo(BoundedBrowserActor.Status.EXPIRED_RUNNING);
        } finally {
            release.countDown();
            actor.quarantine();
        }
    }

    @Test
    void quarantineAdmissionIsAtomicUnderConcurrentSubmitters() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean quarantineReturned = new AtomicBoolean();
        AtomicInteger startedAfterQuarantine = new AtomicInteger();
        BoundedBrowserActor actor = actor();
        try {
            CompletableFuture<BoundedBrowserActor.Result<String>> first = CompletableFuture.supplyAsync(() ->
                    actor.invoke(() -> {
                        firstStarted.countDown();
                        releaseFirst.await(5, TimeUnit.SECONDS);
                        return "first";
                    }, 5_000, () -> {}, () -> {}));
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<?>[] submitters = new CompletableFuture<?>[8];
            for (int i = 0; i < submitters.length; i++) {
                submitters[i] = CompletableFuture.runAsync(() -> {
                    for (int n = 0; n < 100; n++) {
                        actor.invoke(() -> {
                            if (quarantineReturned.get()) startedAfterQuarantine.incrementAndGet();
                            return "late";
                        }, 500, () -> {}, () -> {});
                    }
                });
            }
            actor.quarantine();
            quarantineReturned.set(true);
            releaseFirst.countDown();
            CompletableFuture.allOf(submitters).get(5, TimeUnit.SECONDS);
            first.get(2, TimeUnit.SECONDS);
            assertThat(startedAfterQuarantine).hasValue(0);
        } finally {
            releaseFirst.countDown();
            actor.quarantine();
        }
    }

    @Test
    void independentSessionActorsContinueWhenOneSessionIsBlocked() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BoundedBrowserActor first = actor();
        BoundedBrowserActor second = actor();
        try {
            CompletableFuture<BoundedBrowserActor.Result<String>> pending = CompletableFuture.supplyAsync(() ->
                    first.invoke(() -> {
                        blocked.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return "blocked";
                    }, 5_000, () -> {}, () -> {}));
            assertThat(blocked.await(1, TimeUnit.SECONDS)).isTrue();
            BoundedBrowserActor.Result<String> independent = second.invoke(() -> "independent",
                    500, () -> {}, () -> {});
            assertThat(independent.status()).isEqualTo(BoundedBrowserActor.Status.COMPLETED);
            assertThat(independent.value()).isEqualTo("independent");
            release.countDown();
            assertThat(pending.get(2, TimeUnit.SECONDS).value()).isEqualTo("blocked");
        } finally {
            release.countDown();
            first.quarantine();
            second.quarantine();
        }
    }

    private static BoundedBrowserActor actor() {
        return new BoundedBrowserActor("browser-actor-test", 1, () -> {}, () -> {});
    }

    private static void awaitQueue(BoundedBrowserActor actor) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (actor.queuedTaskCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(actor.queuedTaskCount()).isPositive();
    }
}
