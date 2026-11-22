package com.shengjian.player;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Runs independent source searches without adding one queued task for each source. */
final class ReaderSearchQueue implements AutoCloseable {
    static final int HTTP_LIMIT = 48;
    static final int BROWSER_LIMIT = 2;
    static final long DEFAULT_TIMEOUT_MILLIS = 10_000;
    private static final long HTTP_RECOVERY_MILLIS = 4_000;
    private static final int SITE_LIMIT = 2;

    static final class Route {
        static final Route UNRESTRICTED = new Route("", false);
        final String site;
        final boolean browser;

        Route(String site, boolean browser) {
            this.site = site;
            this.browser = browser;
        }
    }

    interface Work<T, R> {
        R run(T source) throws Exception;
    }

    interface Start<T> {
        /** Runs on a worker just before the source is queried. */
        void onStart(T source);
    }

    interface Listener<T, R> {
        /** Runs on a worker. The caller should check its own generation when applying UI updates. */
        void onResult(T source, R result, Exception error);
    }

    private final int workers;
    private final long sourceTimeoutMillis;
    private final ThreadPoolExecutor pool;
    private Run active;

    ReaderSearchQueue(int workers) {
        this(workers, DEFAULT_TIMEOUT_MILLIS);
    }

    static ReaderSearchQueue forSources() {
        return new ReaderSearchQueue(HTTP_LIMIT + BROWSER_LIMIT);
    }

    int capacity() {
        return workers;
    }

    ReaderSearchQueue(int workers, long sourceTimeoutMillis) {
        if (sourceTimeoutMillis <= 0) throw new IllegalArgumentException("Search timeout must be positive");
        this.workers = workers;
        this.sourceTimeoutMillis = sourceTimeoutMillis;
        AtomicInteger number = new AtomicInteger();
        pool = new ThreadPoolExecutor(workers, workers, 20, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), task -> {
                    Thread thread = new Thread(task, "reader-search-" + number.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
    }

    <T, R> Run start(List<T> sources, Work<T, R> work, Listener<T, R> listener) {
        return start(sources, work, source -> {}, listener);
    }

    <T, R> Run start(List<T> sources, Work<T, R> work, Start<T> started, Listener<T, R> listener) {
        return start(sources, work, started, listener, sourceTimeoutMillis);
    }

    <T, R> Run start(List<T> sources, Work<T, R> work, Start<T> started,
                     Listener<T, R> listener, long timeoutMillis) {
        return start(sources, source -> Route.UNRESTRICTED, work, started, listener, timeoutMillis);
    }

    synchronized <T, R> Run start(List<T> sources, Function<T, Route> route, Work<T, R> work,
                                 Start<T> started, Listener<T, R> listener, long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Search timeout must be positive");
        if (pool.isShutdown()) throw new IllegalStateException("Reader search queue is closed");
        if (active != null) active.cancel();
        Schedule<T> schedule = new Schedule<>(sources, route);
        Run run = new Run(pool, schedule);
        active = run;
        for (int i = 0; i < Math.min(workers, sources.size()); i++) {
            run.tasks.add(pool.submit(() -> drain(schedule, run, work, started, listener, timeoutMillis)));
        }
        return run;
    }

    private <T, R> void drain(Schedule<T> schedule, Run run, Work<T, R> work,
                              Start<T> started, Listener<T, R> listener, long timeoutMillis) {
        while (!run.cancelled && !Thread.currentThread().isInterrupted()) {
            ScheduledSource<T> job;
            try {
                job = schedule.take();
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return;
            }
            if (job == null) return;
            try {
                search(job, run, work, started, listener, timeoutMillis);
            } finally {
                schedule.release(job.route);
            }
        }
    }

    private <T, R> void search(ScheduledSource<T> job, Run run, Work<T, R> work, Start<T> started,
                               Listener<T, R> listener, long timeoutMillis) {
        T source = job.source;
        R result = null;
        Exception failure = null;
        RequestBudget budget = new RequestBudget(timeoutMillis, !job.route.browser);
        if (!run.addBudget(budget)) return;
        try {
            budget.check();
            started.onStart(source);
            budget.check();
            R matches = work.run(source);
            budget.check();
            result = matches;
        } catch (Exception error) {
            failure = budget.failure(error);
        } catch (StackOverflowError | LinkageError error) {
            failure = ruleExecutionFailure(error);
        } finally {
            budget.close();
            run.removeBudget(budget);
        }
        if (!run.cancelled) listener.onResult(source, result, failure);
    }

    private static final class ScheduledSource<T> {
        final T source;
        final Route route;

        ScheduledSource(T source, Route route) {
            this.source = source;
            this.route = route;
        }
    }

    private static final class Schedule<T> {
        private final LinkedList<ScheduledSource<T>> pending = new LinkedList<>();
        private final Map<String, Integer> sites = new HashMap<>();
        private int http;
        private int browser;
        private boolean cancelled;

        Schedule(List<T> sources, Function<T, Route> route) {
            for (T source : sources) pending.add(new ScheduledSource<>(source, route.apply(source)));
        }

        synchronized ScheduledSource<T> take() throws InterruptedException {
            while (!cancelled && !pending.isEmpty()) {
                Iterator<ScheduledSource<T>> iterator = pending.iterator();
                while (iterator.hasNext()) {
                    ScheduledSource<T> job = iterator.next();
                    Route route = job.route;
                    if (route != Route.UNRESTRICTED && !available(route)) continue;
                    iterator.remove();
                    if (route != Route.UNRESTRICTED) {
                        if (route.browser) browser++;
                        else http++;
                        sites.merge(route.site, 1, Integer::sum);
                    }
                    return job;
                }
                wait();
            }
            return null;
        }

        private boolean available(Route route) {
            return (route.browser ? browser < BROWSER_LIMIT : http < HTTP_LIMIT)
                    && sites.getOrDefault(route.site, 0) < SITE_LIMIT;
        }

        synchronized void release(Route route) {
            if (route == Route.UNRESTRICTED) return;
            if (route.browser) browser--;
            else http--;
            sites.computeIfPresent(route.site, (site, count) -> count == 1 ? null : count - 1);
            notifyAll();
        }

        synchronized void cancel() {
            cancelled = true;
            pending.clear();
            notifyAll();
        }
    }

    static IOException ruleExecutionFailure(Error error) {
        if (error instanceof LinkageError) {
            return new IOException("阅读组件初始化失败，请更新应用后重试。", error);
        }
        if (error instanceof StackOverflowError) {
            return new IOException("来源规则嵌套过深，无法完成解析。", error);
        }
        throw error;
    }

    @Override public synchronized void close() {
        if (active != null) {
            active.cancel();
            active = null;
        }
        pool.shutdownNow();
    }

    static final class Run {
        private final ThreadPoolExecutor pool;
        private final Schedule<?> schedule;
        private final List<Future<?>> tasks = new ArrayList<>();
        private final Set<RequestBudget> requests = new HashSet<>();
        private volatile boolean cancelled;

        private Run(ThreadPoolExecutor pool, Schedule<?> schedule) {
            this.pool = pool;
            this.schedule = schedule;
        }

        synchronized void cancel() {
            if (cancelled) return;
            cancelled = true;
            schedule.cancel();
            for (RequestBudget request : requests) request.cancel();
            for (Future<?> task : tasks) task.cancel(true);
            tasks.clear();
            pool.purge();
        }

        synchronized boolean addBudget(RequestBudget budget) {
            if (cancelled) {
                budget.close();
                return false;
            }
            requests.add(budget);
            return true;
        }

        synchronized void removeBudget(RequestBudget budget) {
            requests.remove(budget);
        }
    }

    /** A search source shares one wall-clock budget across response reads and redirects. */
    static final class RequestBudget {
        private static final String TIMEOUT_MESSAGE = "查询超时";
        private static final ThreadLocal<RequestBudget> CURRENT = new ThreadLocal<>();
        private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1,
                task -> daemon(task, "reader-search-deadline"));
        private static final ThreadPoolExecutor DISCONNECTS = new ThreadPoolExecutor(0, HTTP_LIMIT + BROWSER_LIMIT,
                15, TimeUnit.SECONDS, new SynchronousQueue<>(),
                task -> daemon(task, "reader-search-disconnect"), new ThreadPoolExecutor.DiscardPolicy());

        static {
            DEADLINES.setRemoveOnCancelPolicy(true);
        }

        private volatile long deadline;
        private Future<?> timer;
        private final long httpRecoveryNanos;
        private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
        private volatile boolean cancelled;
        private boolean retried;

        private RequestBudget(long millis, boolean ordinarySearch) {
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            httpRecoveryNanos = ordinarySearch && millis == DEFAULT_TIMEOUT_MILLIS
                    ? TimeUnit.MILLISECONDS.toNanos(HTTP_RECOVERY_MILLIS) : 0L;
            timer = scheduleDeadline(deadline);
            CURRENT.set(this);
        }

        static RequestBudget current() {
            return CURRENT.get();
        }

        void check() throws InterruptedIOException {
            checkCancellation();
            if (remainingNanos() <= 0) {
                disconnect();
                throw timeout();
            }
        }

        int connectTimeoutMillis() throws InterruptedIOException {
            return (int) Math.min(6_000, remainingMillis());
        }

        long remainingMillis() throws InterruptedIOException {
            check();
            return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos()));
        }

        synchronized boolean claimRetry(boolean recoverTimedOutHttp) throws InterruptedIOException {
            checkCancellation();
            if (retried) return false;
            long minimum = TimeUnit.MILLISECONDS.toNanos(2_000);
            long remaining = remainingNanos();
            if (remaining < minimum) {
                if (!recoverTimedOutHttp || httpRecoveryNanos == 0 || remaining + httpRecoveryNanos < minimum) {
                    return false;
                }
                deadline += httpRecoveryNanos;
                timer.cancel(false);
                timer = scheduleDeadline(deadline);
            }
            retried = true;
            return true;
        }

        private void checkCancellation() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("搜索已取消");
            }
        }

        private Future<?> scheduleDeadline(long expires) {
            return DEADLINES.schedule(() -> expire(expires), Math.max(0L, expires - System.nanoTime()),
                    TimeUnit.NANOSECONDS);
        }

        private synchronized void expire(long expires) {
            if (expires == deadline) disconnect();
        }

        void attach(HttpURLConnection request) throws InterruptedIOException {
            connection.set(request);
            check();
        }

        boolean detach(HttpURLConnection request) {
            return connection.compareAndSet(request, null);
        }

        InputStream monitor(InputStream input) {
            return new FilterInputStream(input) {
                @Override public int read() throws IOException {
                    check();
                    int value = in.read();
                    check();
                    return value;
                }

                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    check();
                    int count = in.read(bytes, offset, length);
                    check();
                    return count;
                }
            };
        }

        Exception failure(Exception error) {
            if (error instanceof SocketTimeoutException || remainingNanos() <= 0) {
                if (error instanceof SocketTimeoutException && TIMEOUT_MESSAGE.equals(error.getMessage())) {
                    return error;
                }
                SocketTimeoutException timedOut = timeout();
                timedOut.initCause(error);
                return timedOut;
            }
            return error;
        }

        private long remainingNanos() {
            return deadline - System.nanoTime();
        }

        private static SocketTimeoutException timeout() {
            return new SocketTimeoutException(TIMEOUT_MESSAGE);
        }

        private void cancel() {
            cancelled = true;
            disconnect();
        }

        private void disconnect() {
            HttpURLConnection request = connection.get();
            if (request != null) DISCONNECTS.execute(() -> {
                if (detach(request)) request.disconnect();
            });
        }

        private synchronized void close() {
            timer.cancel(false);
            CURRENT.remove();
        }

        private static Thread daemon(Runnable task, String name) {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
