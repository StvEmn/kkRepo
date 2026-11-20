package com.github.klboke.kkrepo.server.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SingleFlightTest {

  @Test
  void twentyConcurrentCallersRunTheWorkOnceAndShareTheResult() throws Exception {
    SingleFlight singleFlight = new SingleFlight();
    AtomicInteger executions = new AtomicInteger();
    CountDownLatch leaderStarted = new CountDownLatch(1);
    CountDownLatch releaseLeader = new CountDownLatch(1);
    Object shared = new Object();

    try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
      List<Future<Object>> results = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        results.add(executor.submit(() -> singleFlight.execute("same-key", () -> {
          executions.incrementAndGet();
          leaderStarted.countDown();
          await(releaseLeader);
          return shared;
        })));
      }
      assertTrue(leaderStarted.await(10, TimeUnit.SECONDS));
      // Give every waiter a moment to pile onto the leader's future before releasing it.
      TimeUnit.MILLISECONDS.sleep(200);
      releaseLeader.countDown();

      for (Future<Object> result : results) {
        assertSame(shared, result.get(10, TimeUnit.SECONDS));
      }
    }
    assertEquals(1, executions.get(), "concurrent callers must not re-run the work");
  }

  @Test
  void leaderFailureDeliversTheSameExceptionInstanceToEveryWaiter() throws Exception {
    SingleFlight singleFlight = new SingleFlight();
    CountDownLatch leaderStarted = new CountDownLatch(1);
    CountDownLatch releaseLeader = new CountDownLatch(1);
    AtomicInteger executions = new AtomicInteger();
    List<RuntimeException> received = new CopyOnWriteArrayList<>();

    try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        results.add(executor.submit(() -> {
          try {
            singleFlight.execute("failing-key", () -> {
              executions.incrementAndGet();
              leaderStarted.countDown();
              await(releaseLeader);
              throw new IllegalStateException("leader boom");
            });
          } catch (RuntimeException e) {
            received.add(e);
          }
        }));
      }
      assertTrue(leaderStarted.await(10, TimeUnit.SECONDS));
      TimeUnit.MILLISECONDS.sleep(200);
      releaseLeader.countDown();
      for (Future<?> result : results) {
        result.get(10, TimeUnit.SECONDS);
      }
    }

    assertEquals(6, received.size(), "every caller must observe the failure");
    assertEquals(1, executions.get());
    for (RuntimeException failure : received) {
      assertSame(received.getFirst(), failure, "waiters must receive the leader's exception");
    }
  }

  @Test
  void differentKeysDoNotBlockEachOther() throws Exception {
    SingleFlight singleFlight = new SingleFlight();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<String> first = executor.submit(() -> singleFlight.execute("slow-key", () -> {
        firstStarted.countDown();
        await(releaseFirst);
        return "first";
      }));
      assertTrue(firstStarted.await(10, TimeUnit.SECONDS));

      // While "slow-key" is still in flight, another key must complete without waiting.
      long start = System.nanoTime();
      Future<String> second = executor.submit(() -> singleFlight.execute("fast-key", () -> "second"));
      assertEquals("second", second.get(10, TimeUnit.SECONDS));
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5),
          "an unrelated key must not queue behind a busy one");

      releaseFirst.countDown();
      assertEquals("first", first.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void completedKeyIsNotCachedAndRunsAgain() {
    SingleFlight singleFlight = new SingleFlight();
    AtomicInteger executions = new AtomicInteger();

    assertEquals(1, singleFlight.execute("key", () -> executions.incrementAndGet()));
    assertEquals(2, singleFlight.execute("key", () -> executions.incrementAndGet()));

    assertEquals(2, executions.get(), "successful results must never be cached");
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS), "timed out waiting for the latch");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
