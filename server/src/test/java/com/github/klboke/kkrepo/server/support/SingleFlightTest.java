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

  /**
   * A late caller whose countDown() lands after the flight completed re-runs the work and its
   * leader produces a fresh result/exception instance — correct behavior for a component that
   * never caches results. The property under test is "one execution per flight", so a round
   * where stragglers opened a second flight is retried with fresh state instead of failing.
   */
  private static final int CONCURRENT_ATTEMPTS = 10;

  @Test
  void twentyConcurrentCallersRunTheWorkOnceAndShareTheResult() throws Exception {
    int callers = 20;
    for (int attempt = 1; ; attempt++) {
      Object shared = new Object();
      int executions = runSharedResultRound(callers, shared);
      if (executions == 1) {
        return;
      }
      assertTrue(attempt < CONCURRENT_ATTEMPTS,
          "the work ran " + executions + " times in " + CONCURRENT_ATTEMPTS
              + " attempts; concurrent callers must not re-run the work within one flight");
    }
  }

  private static int runSharedResultRound(int callers, Object shared) throws Exception {
    SingleFlight singleFlight = new SingleFlight();
    AtomicInteger executions = new AtomicInteger();
    // Rendezvous best effort: every caller signals just before entering execute(), and the
    // leader's work blocks until all of them have — stragglers that only reach the in-flight
    // entry after the flight completed re-run the work, which the retry wrapper tolerates.
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
    return executions.get();
  }

  @Test
  void leaderFailureDeliversTheSameExceptionInstanceToEveryWaiter() throws Exception {
    int callers = 6;
    for (int attempt = 1; ; attempt++) {
      List<RuntimeException> received = runFailureRound(callers);
      if (oneExceptionInstance(received)) {
        return;
      }
      assertTrue(attempt < CONCURRENT_ATTEMPTS,
          "a late caller re-ran the work in " + CONCURRENT_ATTEMPTS + " attempts; within one "
              + "flight every waiter must receive the leader's exception instance");
    }
  }

  /** Only meaningful for a single flight: each extra flight throws its own instance. */
  private static boolean oneExceptionInstance(List<RuntimeException> received) {
    if (received.isEmpty()) return false;
    return received.stream().allMatch(received.getFirst()::equals);
  }

  private static List<RuntimeException> runFailureRound(int callers) throws Exception {
    SingleFlight singleFlight = new SingleFlight();
    List<RuntimeException> received = new CopyOnWriteArrayList<>();
    CountDownLatch allArrived = new CountDownLatch(callers);

    try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        results.add(executor.submit(() -> {
          allArrived.countDown();
          try {
            singleFlight.execute("failing-key", () -> {
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
    // The work always throws, so every caller observes a failure no matter how many flights ran.
    assertEquals(callers, received.size(), "every caller must observe the failure");
    return received;
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
