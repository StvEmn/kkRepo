package com.github.klboke.kkrepo.server.support;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Collapses concurrent identical work into a single in-flight execution ("singleflight").
 *
 * <p>The first caller for a key becomes the leader and runs {@code work}; concurrent callers for
 * the same key block on the leader's future instead of re-running the work. A leader failure is
 * delivered to every waiter as the original exception. Results are never cached — the moment the
 * leader finishes, the key is removed from the in-flight table, so subsequent calls re-execute;
 * caching remains the responsibility of the business cache layers.
 *
 * <p>Callers must use fully distinguishing keys (for example
 * {@code format + ":" + repositoryId + ":" + path}), otherwise unrelated requests would queue
 * behind each other.
 *
 * @author zhangtianbin
 */
@Component
public final class SingleFlight {
  private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

  /**
   * Runs {@code work} for {@code key}, joining an already-running execution when one exists.
   *
   * @param key  fully distinguishing identity of the work
   * @param work the work to execute at most once concurrently
   * @return the leader's result, shared by every waiter
   */
  @SuppressWarnings("unchecked")
  public <T> T execute(String key, Supplier<T> work) {
    CompletableFuture<Object> mine = new CompletableFuture<>();
    CompletableFuture<Object> leader = inFlight.putIfAbsent(key, mine);
    if (leader == null) {
      try {
        mine.complete(work.get());
      } catch (Throwable failure) {
        mine.completeExceptionally(failure);
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new CompletionException(failure);
      } finally {
        inFlight.remove(key, mine);
      }
      return (T) mine.join();
    }
    try {
      return (T) leader.join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (e.getCause() instanceof Error error) {
        throw error;
      }
      throw e;
    }
  }
}
