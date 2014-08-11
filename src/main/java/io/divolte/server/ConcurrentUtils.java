package io.divolte.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

@ParametersAreNonnullByDefault
final class ConcurrentUtils {
    private static final Logger logger = LoggerFactory.getLogger(ConcurrentUtils.class);
    private static final int MAX_BATCH_SIZE = 128;

    private ConcurrentUtils() {
        throw new UnsupportedOperationException("Singleton; do not instantiate.");
    }

    @Nullable
    public static <E> E pollQuietly(final BlockingQueue<E> queue, final long timeout, final TimeUnit unit) {
        try {
            return queue.poll(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public static <T> boolean offerQuietly(final BlockingQueue<T> queue,
                                           final T item,
                                           final long timeout,
                                           final TimeUnit unit) {
        try {
            return queue.offer(item, timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static ThreadFactory createThreadFactory(final ThreadGroup group, final String nameFormat) {
        return new ThreadFactoryBuilder()
            .setNameFormat(nameFormat)
            .setThreadFactory((runnable) -> new Thread(group, runnable))
            .build();
    }

    public static <T> Runnable microBatchingQueueDrainerWithHeartBeat(final BlockingQueue<T> queue,
                                                                      final Consumer<T> consumer,
                                                                      final Runnable heartBeatAction) {
        return () -> {
            final List<T> batch = new ArrayList<>(MAX_BATCH_SIZE);
            while (!queue.isEmpty() || !Thread.currentThread().isInterrupted()) {
                queue.drainTo(batch, MAX_BATCH_SIZE);
                if (batch.isEmpty()) {
                    // If the batch was empty, block on the queue for some time until something is available.
                    final T polled;
                    if (null != (polled = pollQuietly(queue, 1, TimeUnit.SECONDS))) {
                        consumer.accept(polled);
                    } else {
                        heartBeatAction.run();
                    }
                } else {
                    batch.forEach(consumer);
                    batch.clear();
                }
            }
        };
    }

    public static <T> Runnable microBatchingQueueDrainer(final BlockingQueue<T> queue,
                                                         final Consumer<T> consumer) {
        return microBatchingQueueDrainerWithHeartBeat(queue, consumer, () -> {});
    }

    public static void scheduleQueueReaderWithCleanup(final ExecutorService es,
                                                      final Runnable reader,
                                                      final Runnable cleanup) {
        CompletableFuture
            .runAsync(reader, es)
            .whenComplete((voidValue, error) -> {
                cleanup.run();

                // In case the reader for some reason escapes its loop with an exception,
                // log any uncaught exceptions and reschedule
                if (error != null) {
                    logger.warn("Uncaught exception in incoming queue reader thread.", error);
                    scheduleQueueReaderWithCleanup(es, reader, cleanup);
                }
            });
    }

    public static void scheduleQueueReader(final ExecutorService es, final Runnable reader) {
        scheduleQueueReaderWithCleanup(es, reader, () ->
            logger.debug("Unhandled cleanup for thread: {}", Thread.currentThread().getName()));
    }

    public static <K,V> Cache<K, V> buildSizeBoundCacheFromLoadingFunction(Function<K, V> loader, int size) {
        return CacheBuilder
                .newBuilder()
                .maximumSize(size)
                .initialCapacity(size)
                .build(new CacheLoader<K, V>() {
                    @Override
                    public V load(K key) throws Exception {
                        return loader.apply(key);
                    }
                });
    }

    @FunctionalInterface
    public interface IOExceptionThrower {
        public abstract void run() throws IOException;
    }

    public static boolean throwsIoException(final IOExceptionThrower r) {
        try {
            r.run();
            return false;
        } catch (final IOException ioe) {
            return true;
        }
    }
}
