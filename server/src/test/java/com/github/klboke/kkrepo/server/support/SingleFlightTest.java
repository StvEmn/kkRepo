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
    Object shared = new Object();
    int callers = 20;
    // Deterministic rendezvous: every caller signals just before entering execute(), and the
    // leader's work blocks until all of them have — so the leader only completes once every
    // other caller is at (or parked on) the in-flight entry, never by sleeping a fixed delay.
    CountDownLatch allArrived = new CountDownLatch(callers);

    try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
      List<Future<Object>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        results.add(executor.submit(() -> {
          allArrived.countDown();
          return singleFlight.execute("same-key", () -> {
            executions.incrementAndGet();
            await(allArrived);
            return shared;
          });
        }));
      }

      for (Future<Object> result : results) {
        assertSame(shared, result.get(20, TimeUnit.SECONDS));
      }
    }
    assertEquals(1, executions.get(), "concurrent callers must not re-run the work");
  }

  @Test
  void leaderFailureDeliversTheSameExceptionInstanceToEveryWaiter() throws Exception {
    SingleFlight singleFlight = new SingleFlight();
    AtomicInteger executions = new AtomicInteger();
    List<RuntimeException> received = new CopyOnWriteArrayList<>();
    int callers = 6;
    CountDownLatch allArrived = new CountDownLatch(callers);

    try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        results.add(executor.submit(() -> {
          allArrived.countDown();
          try {
            singleFlight.execute("failing-key", () -> {
              executions.incrementAndGet();
              await(allArrived);
              throw new IllegalStateException("leader boom");
            });
          } catch (RuntimeException e) {
            received.add(e);
          }
          return null;
        }));
      }
      for (Future<?> result : results) {
        result.get(20, TimeUnit.SECONDS);
      }
    }

    assertEquals(callers, received.size(), "every caller must observe the failure");
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
      assertTrue(latch.await(20, TimeUnit.SECONDS), "timed out waiting for the latch");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
