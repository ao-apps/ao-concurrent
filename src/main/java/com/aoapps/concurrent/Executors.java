/*
 * ao-concurrent - Concurrent programming utilities.
 * Copyright (C) 2011, 2012, 2013, 2014, 2015, 2016, 2019, 2020, 2021, 2022  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of ao-concurrent.
 *
 * ao-concurrent is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ao-concurrent is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ao-concurrent.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoapps.concurrent;

import com.aoapps.hodgepodge.i18n.I18nThreadLocalCallable;
import com.aoapps.hodgepodge.i18n.I18nThreadLocalRunnable;
import com.aoapps.lang.RuntimeUtils;
import com.aoapps.lang.concurrent.ThreadLocalCallable;
import com.aoapps.lang.concurrent.ThreadLocalRunnable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>
 * Provides a central set of executors for use by any number of projects.
 * These executors use daemon threads and will not keep the JVM alive.
 * The executors are automatically shutdown using shutdown hooks.  The
 * executors are also immediately shutdown when the last instance is closed.
 * </p>
 * <p>
 * Also allows for delayed execution of tasks using an internal Timer.
 * </p>
 */
public class Executors implements AutoCloseable {

  private static final Logger logger = Logger.getLogger(Executors.class.getName());

  /**
   * The daemon flag for all threads.
   */
  private static final boolean DAEMON_THREADS = true;

  /**
   * The maximum number of nanoseconds that will be waited for during close (0.1 seconds).
   */
  private static final long CLOSE_WAIT_NANOS = 100L * 1000L * 1000L; // Was one minute: 60L * 1000L * 1000L * 1000L;

  /**
   * The number of threads per processor for per-processor executor.
   * <p>
   * Note: Tried this at a value of one and did not benchmark any significant
   * difference.  Favoring this as a multiple of CPU cores to be able to bully
   * around lock contention or keep busy in the chance of occasional I/O.
   * </p>
   */
  private static final int THREADS_PER_PROCESSOR = 2;

  // <editor-fold defaultstate="collapsed" desc="Thread Factories">
  /**
   * Tracks which ThreadFactory created the current thread, if any.
   * This is used to avoid shutting down an executor from a thread created by
   * it's factory.
   * This threadLocal is not passed from thread to sub tasks.
   */
  private static final ThreadLocal<ThreadFactory> currentThreadFactory = new ThreadLocal<>();

  /**
   * The thread factories are created once so each thread gets a unique
   * identifier independent of creation and destruction of executors.
   */
  private static class PrefixThreadFactory implements ThreadFactory {

    // The thread group management was not compatible with an Applet environment
    // final ThreadGroup group;
    final String namePrefix;
    final AtomicInteger threadNumber = new AtomicInteger(1);
    final int priority;

    private PrefixThreadFactory(String namePrefix, int priority) {
      //SecurityManager s = System.getSecurityManager();
      //this.group = (s != null)? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
      this.namePrefix = namePrefix;
      this.priority = priority;
    }

    @Override
    public Thread newThread(final Runnable target) {
      String name = namePrefix + threadNumber.getAndIncrement();
      if (logger.isLoggable(Level.FINER)) {
        logger.log(Level.FINER, "newThread={0}", name);
      }
      Thread t = new Thread(
          //group,
          () -> {
            currentThreadFactory.set(PrefixThreadFactory.this);
            target.run();
          },
          name
      );
      t.setPriority(priority);
      t.setDaemon(DAEMON_THREADS);
      return t;
    }
  }
  // </editor-fold>

  // <editor-fold defaultstate="collapsed" desc="Instance Management">
  /**
   * The number of active executors is tracked, will shutdown when gets to zero.
   */
  private static final AtomicInteger activeCount = new AtomicInteger();

  /**
   * Creates a new executors.
   *
   * @deprecated  Just use constructor directly.  Subclasses are now allowed, too.
   */
  @Deprecated
  public static Executors newInstance() {
    return new Executors();
  }

  /**
   * Set to true when close called.
   */
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * <p>
   * Create a new instance of the executor service.  {@link #close()} must be called
   * when done with the instance.  This should be done in a try-with-resources, try-finally, or strong
   * equivalent, such as <code>Servlet.destroy()</code>.
   * </p>
   * <p>
   * Internally, threads are shared between executor instances.  The threads are only
   * shutdown when the last executor is closed.
   * </p>
   *
   * @see  #close()
   */
  public Executors() {
    int availableProcessors = RuntimeUtils.getAvailableProcessors();
    // Always one for single CPU system
    preferredConcurrency = availableProcessors == 1 ? 1 : (availableProcessors * THREADS_PER_PROCESSOR);
    // Increment activeCount while looking for wraparound
    assert activeCount.get() >= 0;
    int newActiveCount = activeCount.incrementAndGet();
    if (newActiveCount < 0) {
      activeCount.decrementAndGet();
      throw new IllegalStateException("activeCount integer wraparound detected");
    }
    if (logger.isLoggable(Level.FINE)) {
      logger.log(Level.FINE, "activeCount={0}", newActiveCount);
    }
    perProcessor = new PerProcessorExecutor(this);
  }
  // </editor-fold>

  // <editor-fold defaultstate="collapsed" desc="Preferred Concurrency">
  private final int preferredConcurrency;

  /**
   * <p>
   * Gets the preferred concurrency for this executor.  Does not change for the life
   * of the executor, but will be updated should the last executor be closed
   * and another created.
   * </p>
   * <p>
   * This will always be {@code 1} on a single-CPU system, not a multiple of CPU count.
   * </p>
   */
  public int getPreferredConcurrency() {
    return preferredConcurrency;
  }
  // </editor-fold>

  // <editor-fold defaultstate="collapsed" desc="Timer">
  private static final AtomicReference<Timer> timer = new AtomicReference<>();

  /**
   * Gets the timer.  activeCount must be greater than zero.
   */
  private static Timer getTimer() {
    assert activeCount.get() > 0;
    Timer t = timer.get();
    if (t == null) {
      t = new Timer(DAEMON_THREADS);
      if (!timer.compareAndSet(null, t)) {
        // Another thread created one, cancel this one
        t.cancel();
        t = timer.get();
        // And now another thread closed it (crazy)
        if (t == null) {
          throw new IllegalStateException();
        }
      }
    }
    return t;
  }

  // </editor-fold>

  // <editor-fold defaultstate="collapsed" desc="Incomplete Futures">
  /**
   * Tracks which thread factory a future will be executed on.
   */
  private static interface ThreadFactoryFuture<V> extends Future<V> {

    /**
     * Gets the thread factory that is associated with the executor service this
     * future will be executed on.
     */
    ThreadFactory getThreadFactory();
  }

  /**
   * Keeps track of all tasks scheduled but not yet completed by this executor so the tasks
   * may be completed or canceled during close.
   */
  private static final AtomicLong nextIncompleteFutureId = new AtomicLong(1);
  private static final ConcurrentMap<Long, ThreadFactoryFuture<?>> incompleteFutures = new ConcurrentHashMap<>();

  private static class IncompleteFuture<V> implements ThreadFactoryFuture<V> {

    private final ThreadFactory threadFactory;
    private final Long incompleteFutureId;
    private final Future<V> future;

    private IncompleteFuture(ThreadFactory threadFactory, Long incompleteFutureId, Future<V> future) {
      this.threadFactory = threadFactory;
      this.incompleteFutureId = incompleteFutureId;
      this.future = future;
    }

    @Override
    public ThreadFactory getThreadFactory() {
      return threadFactory;
    }

    /**
     * Remove from incomplete when canceled.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      try {
        return future.cancel(mayInterruptIfRunning);
      } finally {
        incompleteFutures.remove(incompleteFutureId);
      }
    }

    @Override
    public boolean isCancelled() {
      return future.isCancelled();
    }

    @Override
    public boolean isDone() {
      return future.isDone();
    }

    @Override
    public V get() throws InterruptedException, ExecutionException {
      return future.get();
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
      return future.get(timeout, unit);
    }
  }

  private static interface SimpleExecutorService {

    <T> Future<T> submit(Callable<T> task);

    <T> Future<T> submit(Runnable task, T result);
  }

  private static class ExecutorServiceWrapper implements SimpleExecutorService {

    final ExecutorService executorService;

    /**
     * Shutdown hook is created, but not submitted.
     */
    private final Thread shutdownHook;

    ExecutorServiceWrapper(
        ExecutorService executorService,
        String shutdownHookThreadName
    ) {
      this.executorService = executorService;
      Thread newShutdownHook = new ExecutorServiceShutdownHook(
          executorService,
          shutdownHookThreadName
      );
      // Only keep instances once shutdown hook properly registered
      try {
        Runtime.getRuntime().addShutdownHook(newShutdownHook);
      } catch (SecurityException e) {
        logger.log(Level.WARNING, null, e);
        newShutdownHook = null;
      }
      shutdownHook = newShutdownHook;
    }

    void removeShutdownHook() {
      if (shutdownHook != null) {
        try {
          Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
          // System shutting down, can't remove hook
        } catch (SecurityException e) {
          logger.log(Level.WARNING, null, e);
        }
      }
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      return executorService.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      return executorService.submit(task, result);
    }
  }

  private abstract static class IncompleteTimerTask<V> extends TimerTask implements ThreadFactoryFuture<V> {

    protected final ThreadFactory threadFactory;
    protected final SimpleExecutorService executorService;
    protected final Long incompleteFutureId;

    private static class IncompleteLock {
      // Empty lock class to help heap profile
    }

    private final IncompleteLock incompleteLock = new IncompleteLock();
    private boolean canceled;
    private IncompleteFuture<V> future; // Only available once submitted

    private IncompleteTimerTask(
        ThreadFactory threadFactory,
        SimpleExecutorService executorService,
        Long incompleteFutureId
    ) {
      this.threadFactory = threadFactory;
      this.executorService = executorService;
      this.incompleteFutureId = incompleteFutureId;
    }

    @Override
    public ThreadFactory getThreadFactory() {
      return threadFactory;
    }

    /**
     * Sets the future that was obtained after submission to the executor.
     * Notifies all threads waiting for the future.
     */
    protected void setFuture(IncompleteFuture<V> future) {
      synchronized (incompleteLock) {
        this.future = future;
        incompleteLock.notifyAll();
      }
    }

    /**
     * Cancels this TimerTask, but does not cancel the task itself
     * if already submitted to the executor.
     * Notifies all threads waiting for the future.
     */
    @Override
    public boolean cancel() {
      try {
        synchronized (incompleteLock) {
          canceled = true;
          incompleteLock.notifyAll();
        }
        return super.cancel();
      } finally {
        incompleteFutures.remove(incompleteFutureId);
      }
    }

    /**
     * Cancels this Future, canceling either the TimerTask or the submitted
     * Task.
     * Notifies all threads waiting for the future.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      try {
        Future<?> f;
        synchronized (incompleteLock) {
          f = future;
          canceled = true;
          incompleteLock.notifyAll();
        }
        return f == null ? super.cancel() : f.cancel(mayInterruptIfRunning);
      } finally {
        incompleteFutures.remove(incompleteFutureId);
      }
    }

    @Override
    public boolean isCancelled() {
      boolean c;
      Future<?> f;
      synchronized (incompleteLock) {
        c = canceled;
        f = future;
      }
      return f == null ? c : f.isCancelled();
    }

    @Override
    public boolean isDone() {
      boolean c;
      Future<?> f;
      synchronized (incompleteLock) {
        c = canceled;
        f = future;
      }
      return f == null ? c : f.isDone();
    }

    @Override
    public V get() throws InterruptedException, CancellationException, ExecutionException {
      // Wait until submitted
      Future<V> f;
      synchronized (incompleteLock) {
        while (future == null) {
          if (canceled) {
            throw new CancellationException();
          }
          incompleteLock.wait();
        }
        f = future;
      }
      // Wait until completed
      return f.get();
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, CancellationException, ExecutionException, TimeoutException {
      final long waitUntil = System.nanoTime() + unit.toNanos(timeout);
      // Wait until submitted
      Future<V> f;
      synchronized (incompleteLock) {
        while (future == null) {
          if (canceled) {
            throw new CancellationException();
          }
          long nanosRemaining = waitUntil - System.nanoTime();
          if (nanosRemaining <= 0) {
            throw new TimeoutException();
          }
          incompleteLock.wait(nanosRemaining / 1000000, (int) (nanosRemaining % 1000000));
        }
        f = future;
      }
      // Wait until completed
      return f.get(
          Math.max(
              0,
              waitUntil - System.nanoTime()
          ),
          TimeUnit.NANOSECONDS
      );
    }
  }

  /**
   * Submits to the executor service.
   */
  private static <T> IncompleteFuture<T> incompleteFutureSubmit(
      ThreadFactory threadFactory,
      SimpleExecutorService executorService,
      final Callable<? extends T> task
  ) {
    final Long incompleteFutureId = nextIncompleteFutureId.getAndIncrement();
    final Future<T> future = executorService.submit(
        // Remove from incomplete when call completed.
        () -> {
          try {
            return task.call();
          } finally {
            incompleteFutures.remove(incompleteFutureId);
          }
        }
    );
    IncompleteFuture<T> incompleteFuture = new IncompleteFuture<>(threadFactory, incompleteFutureId, future);
    incompleteFutures.put(incompleteFutureId, incompleteFuture);
    return incompleteFuture;
  }

  /**
   * Submits to the executor service.
   */
  private static <T> IncompleteFuture<T> incompleteFutureSubmit(
      ThreadFactory threadFactory,
      SimpleExecutorService executorService,
      final Runnable task,
      T result
  ) {
    final Long incompleteFutureId = nextIncompleteFutureId.getAndIncrement();
    final Future<T> submitted = executorService.submit(
        // Remove from incomplete when run finished.
        () -> {
          try {
            task.run();
          } finally {
            incompleteFutures.remove(incompleteFutureId);
          }
        },
        result
    );
    IncompleteFuture<T> future = new IncompleteFuture<>(threadFactory, incompleteFutureId, submitted);
    incompleteFutures.put(incompleteFutureId, future);
    return future;
  }

  private static class IncompleteCallableTimerTask<V> extends IncompleteTimerTask<V> {

    final Callable<? extends V> task;

    IncompleteCallableTimerTask(
        ThreadFactory threadFactory,
        SimpleExecutorService executorService,
        Long incompleteFutureId,
        Callable<? extends V> task
    ) {
      super(threadFactory, executorService, incompleteFutureId);
      this.task = task;
    }

    /**
     * Remove from incomplete once submitted to executorService.
     */
    @Override
    public void run() {
      try {
        setFuture(incompleteFutureSubmit(threadFactory, executorService, task));
      } finally {
        incompleteFutures.remove(incompleteFutureId);
      }
    }
  }

  private static class IncompleteRunnableTimerTask<T> extends IncompleteTimerTask<T> {

    final Runnable task;
    final T result;

    IncompleteRunnableTimerTask(
        ThreadFactory threadFactory,
        SimpleExecutorService executorService,
        Long incompleteFutureId,
        Runnable task,
        T result
    ) {
      super(threadFactory, executorService, incompleteFutureId);
      this.task = task;
      this.result = result;
    }

    /**
     * Remove from incomplete once submitted to executorService.
     */
    @Override
    public void run() {
      try {
        setFuture(incompleteFutureSubmit(threadFactory, executorService, task, result));
      } finally {
        incompleteFutures.remove(incompleteFutureId);
      }
    }
  }

  /**
   * Adds to the timer.
   */
  private static <T> Future<T> incompleteFutureSubmit(
      ThreadFactory threadFactory,
      SimpleExecutorService executorService,
      Callable<? extends T> task,
      long delay
  ) {
    final Long incompleteFutureId = nextIncompleteFutureId.getAndIncrement();
    IncompleteCallableTimerTask<T> timerTask = new IncompleteCallableTimerTask<>(threadFactory, executorService, incompleteFutureId, task);
    getTimer().schedule(timerTask, delay);
    incompleteFutures.put(incompleteFutureId, timerTask);
    return timerTask;
  }

  /**
   * Adds to the timer.
   */
  private static <T> Future<T> incompleteFutureSubmit(
      ThreadFactory threadFactory,
      SimpleExecutorService executorService,
      Runnable task,
      T result,
      long delay
  ) {
    final Long incompleteFutureId = nextIncompleteFutureId.getAndIncrement();
    IncompleteRunnableTimerTask<T> timerTask = new IncompleteRunnableTimerTask<>(threadFactory, executorService, incompleteFutureId, task, result);
    getTimer().schedule(timerTask, delay);
    incompleteFutures.put(incompleteFutureId, timerTask);
    return timerTask;
  }

  // </editor-fold>

  // <editor-fold defaultstate="collapsed" desc="Task Wrappers">
  /**
   * Wraps the task with any other necessary tasks to prepare or cleanup for the task.
   *
   * @see  I18nThreadLocalCallable This default implementation maintains internationalization context.
   */
  protected <T> Callable<T> wrap(Callable<T> task) {
    return new I18nThreadLocalCallable<>(task);
  }

  /**
   * Wraps the task with any other necessary tasks to prepare or cleanup for the task.
   *
   * @see  I18nThreadLocalRunnable This default implementation maintains internationalization context.
   */
  protected Runnable wrap(Runnable task) {
    return new I18nThreadLocalRunnable(task);
  }

  // </editor-fold>

  // <editor-fold defaultstate="collapsed" desc="Executor">
  private abstract static class ExecutorImpl implements Executor {

    /**
     * Using private field in static inner class to have more control over where implicit "this" would have been used.
     */
    protected final Executors executors;

    protected ExecutorImpl(Executors executors) {
      this.executors = executors;
    }

    @Override
    public void execute(Runnable command) {
      submit(command);
    }

    /**
     * Gets the thread factory that will be used for the executor.
     */
    abstract ThreadFactory getThreadFactory();

    /**
     * Gets the executor service.
     *
     * @see  #getThreadFactory()  The executor must be using the same thread factory
     */
    protected abstract SimpleExecutorService getExecutorService();

    /**
     * Wraps the given callable.
     *
     * @see  ExecutorService#wrap(java.util.concurrent.Callable)
     */
    protected <T> Callable<T> wrap(Callable<T> task) {
      return executors.wrap(task);
    }

    /**
     * Wraps the given runnable.
     *
     * @see  ExecutorService#wrap(java.lang.Runnable)
     */
    protected Runnable wrap(Runnable task) {
      return executors.wrap(task);
    }

    @Override
    public <T> Future<T> submit(Callable<? extends T> task) throws IllegalStateException {
      if (executors.closed.get()) {
        throw new IllegalStateException();
      }
      return incompleteFutureSubmit(
          getThreadFactory(),
          getExecutorService(),
          wrap(task)
      );
    }

    @Override
    @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
    public <T> List<T> callAll(Collection<? extends Callable<? extends T>> tasks) throws IllegalStateException, InterruptedException, ExecutionException {
      if (executors.closed.get()) {
        throw new IllegalStateException();
      }
      int size = tasks.size();
      if (size == 0) {
        return Collections.emptyList();
      } else if (size == 1) {
        try {
          return Collections.singletonList(tasks.iterator().next().call());
        } catch (ThreadDeath | InterruptedException e) {
          throw e;
        } catch (Throwable t) {
          throw new ExecutionException(t);
        }
      } else {
        List<? extends Callable<? extends T>> taskList;
        if (tasks instanceof List) {
          taskList = (List<? extends Callable<? extends T>>) tasks;
        } else {
          taskList = new ArrayList<>(tasks);
        }
        List<Future<T>> futures = new ArrayList<>(size - 1); // Last one called by current thread
        List<T> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
          Callable<? extends T> task = taskList.get(i);
          if (i < (size - 1)) {
            // Call concurrently
            futures.add(submit(task));
            results.add(null);
          } else {
            // Last one on current thread
            try {
              results.add(task.call());
            } catch (ThreadDeath td) {
              throw td;
            } catch (Throwable t) {
              throw new ExecutionException(t);
            }
          }
        }
        // Gather concurrent results
        for (int i = 0; i < (size - 1); i++) {
          results.set(i, futures.get(i).get());
        }
        return Collections.unmodifiableList(results);
      }
    }

    @Override
    public <T> Future<T> submit(Callable<? extends T> task, long delay) throws IllegalStateException {
      if (executors.closed.get()) {
        throw new IllegalStateException();
      }
      return incompleteFutureSubmit(
          getThreadFactory(),
          getExecutorService(),
          wrap(task),
          delay
      );
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) throws IllegalStateException {
      if (executors.closed.get()) {
        throw new IllegalStateException();
      }
      return incompleteFutureSubmit(
          getThreadFactory(),
          getExecutorService(),
          wrap(task),
          result
      );
    }

    @Override
    public Future<?> submit(Runnable task) throws IllegalStateException {
      return submit(task, null);
    }

    @Override
    public void runAll(Collection<? extends Runnable> tasks) throws IllegalStateException, InterruptedException, ExecutionException {
      if (executors.closed.get()) {
        throw new IllegalStateException();
      }
      int size = tasks.size();
      if (size == 0) {
        // Nothing to do
      } else if (size == 1) {
        try {
          tasks.iterator().next().run();
        } catch (ThreadDeath td) {
          throw td;
        } catch (Throwable t) {
          throw new ExecutionException(t);
        }
      } else {
        List<? extends Runnable> taskList;
        if (tasks instanceof List) {
          taskList = (List<? extends Runnable>) tasks;
        } else {
          taskList = new ArrayList<>(tasks);
        }
        List<Future<?>> futures = new ArrayList<>(size - 1); // Last one ran by current thread
        for (int i = 0; i < size; i++) {
          Runnable task = taskList.get(i);
          if (i < (size - 1)) {
            // Run concurrently
            futures.add(submit(task));
          } else {
            // Last one on current thread
            try {
              task.run();
            } catch (ThreadDeath td) {
              throw td;
            } catch (Throwable t) {
              throw new ExecutionException(t);
            }
          }
        }
        // Wait for concurrent to finish
        for (int i = 0; i < (size - 1); i++) {
          futures.get(i).get();
        }
      }
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result, long delay) throws IllegalStateException {
      if (executors.closed.get()) {
        throw new IllegalStateException();
      }
      return incompleteFutureSubmit(
          getThreadFactory(),
          getExecutorService(),
          wrap(task),
          result,
          delay
      );
    }

    @Override
    public Future<?> submit(Runnable task, long delay) throws IllegalStateException {
      return submit(task, null, delay);
    }
  }

  // </editor-fold>

  // <editor-fold defaultstate="collapsed" desc="Unbounded">
  private static class UnboundedExecutor extends ExecutorImpl {
    private static final AtomicReference<ExecutorServiceWrapper> unboundedExecutorService = new AtomicReference<>();

    private static final String THREAD_FACTORY_NAME_PREFIX = Executors.class.getName() + ".unbounded-thread-";

    private static final ThreadFactory unboundedThreadFactory = new PrefixThreadFactory(
        THREAD_FACTORY_NAME_PREFIX,
        Thread.NORM_PRIORITY
    );

    private static void close() {
      final ExecutorServiceWrapper ues = unboundedExecutorService.getAndSet(null);
      if (ues != null) {
        Runnable uesShutdown = () -> {
          try {
            ues.executorService.shutdown();
          } catch (SecurityException e) {
            logger.log(Level.WARNING, null, e);
          }
          try {
            if (logger.isLoggable(Level.FINE)) {
              logger.log(Level.FINE, "awaiting termination of unboundedExecutorService");
            }
            if (ues.executorService.awaitTermination(CLOSE_WAIT_NANOS, TimeUnit.NANOSECONDS)) {
              ues.removeShutdownHook();
            }
          } catch (InterruptedException e) {
            logger.log(Level.WARNING, null, e);
            // Restore the interrupted status
            Thread.currentThread().interrupt();
          }
        };
        // Never wait for own thread (causes stall every time)
        ThreadFactory tf = currentThreadFactory.get();
        if (tf != null && tf == unboundedThreadFactory) {
          new Thread(uesShutdown).start();
        } else {
          // OK to use current thread directly
          uesShutdown.run();
        }
      }
    }

    private UnboundedExecutor(Executors executors) {
      super(executors);
    }

    @Override
    ThreadFactory getThreadFactory() {
      return unboundedThreadFactory;
    }

    @Override
    protected SimpleExecutorService getExecutorService() {
      assert activeCount.get() > 0;
      ExecutorServiceWrapper ues = unboundedExecutorService.get();
      if (ues == null) {
        ues = new ExecutorServiceWrapper(
            java.util.concurrent.Executors.newCachedThreadPool(unboundedThreadFactory),
            THREAD_FACTORY_NAME_PREFIX + "shutdownHook"
        );
        if (!unboundedExecutorService.compareAndSet(null, ues)) {
          // Another thread created one, cancel this one
          ues.executorService.shutdown();
          ues.removeShutdownHook();
          ues = unboundedExecutorService.get();
          // And now another thread closed it (crazy)
          if (ues == null) {
            throw new IllegalStateException();
          }
        }
      }
      return ues;
    }

    /**
     * Avoid deadlock: Maintain which perProcessor thread pool this task came from.
     */
    @Override
    protected <T> Callable<T> wrap(Callable<T> task) {
      return super.wrap(new ThreadLocalCallable<>(task, PerProcessorExecutor.currentThreadPerProcessorIndex));
    }

    /**
     * Avoid deadlock: Maintain which perProcessor thread pool this task came from.
     */
    @Override
    protected Runnable wrap(Runnable task) {
      return super.wrap(new ThreadLocalRunnable(task, PerProcessorExecutor.currentThreadPerProcessorIndex));
    }
  }

  private final UnboundedExecutor unbounded = new UnboundedExecutor(this);

  /**
   * Gets the unbounded executor.
   * This is most appropriate for I/O bound tasks, especially higher latency
   * I/O like wide area networks.
   */
  public Executor getUnbounded() {
    return unbounded;
  }

  // </editor-fold>

  // <editor-fold defaultstate="collapsed" desc="Per-processor">
  private static class PerProcessorExecutor extends ExecutorImpl {

    /**
     * <p>
     * {@code null} when a thread is not from this ExecutorService or is not yet per-processor pool bound.
     * </p>
     * <p>
     * Keeps track of which per-processor thread pool the current thread is running
     * either directly, or indirect, under.  Any subsequent per-processor pool access
     * will use the next higher pool index.
     * </p>
     * <p>
     * A thread may be in both currentThreadUnbounded and currentThreadPerProcessorIndex, which will
     * happen when an unbounded task is added from a per-processor thread.
     * </p>
     */
    private static final ThreadLocal<Integer> currentThreadPerProcessorIndex = new ThreadLocal<>();

    private static final List<PrefixThreadFactory> threadFactories = new CopyOnWriteArrayList<>();

    private static PrefixThreadFactory getThreadFactory(int index) {
      PrefixThreadFactory perProcessorThreadFactory;
      if (index < threadFactories.size()) {
        perProcessorThreadFactory = threadFactories.get(index);
      } else {
        perProcessorThreadFactory = null;
      }
      if (perProcessorThreadFactory == null) {
        final Integer indexObj = index;
        if (logger.isLoggable(Level.FINEST)) {
          logger.log(Level.FINEST, "new perProcessorThreadFactory: {0}", index);
        }
        perProcessorThreadFactory = new PrefixThreadFactory(
            Executors.class.getName() + ".perProcessor-" + index + "-thread-",
            Thread.NORM_PRIORITY
        ) {
          @Override
          public Thread newThread(final Runnable target) {
            return super.newThread(() -> {
              assert currentThreadPerProcessorIndex.get() == null;
              currentThreadPerProcessorIndex.set(indexObj);
              target.run();
            });
          }
        };
        while (threadFactories.size() <= index) {
          threadFactories.add(null);
        }
        threadFactories.set(index, perProcessorThreadFactory);
      }
      return perProcessorThreadFactory;
    }

    private static class PerProcessorExecutorServicesLock {
      // Empty lock class to help heap profile
    }

    private static final PerProcessorExecutorServicesLock perProcessorExecutorServicesLock = new PerProcessorExecutorServicesLock();
    private static final List<ExecutorServiceWrapper> perProcessorExecutorServices = new ArrayList<>();

    private static void close() {
      synchronized (perProcessorExecutorServicesLock) {
        // Going backwards, to clean up deepest depth tasks first, giving other tasks a chance to finish during cleanup
        for (int i = perProcessorExecutorServices.size() - 1; i >= 0; i--) {
          final int index = i;
          final ExecutorServiceWrapper ppes = perProcessorExecutorServices.get(index);
          if (ppes != null) {
            Runnable ppesShutdown = () -> {
              try {
                ppes.executorService.shutdown();
              } catch (SecurityException e) {
                logger.log(Level.WARNING, null, e);
              }
              try {
                if (logger.isLoggable(Level.FINE)) {
                  logger.log(Level.FINE, "awaiting termination of perProcessorExecutorServices[{0}]", index);
                }
                if (ppes.executorService.awaitTermination(CLOSE_WAIT_NANOS, TimeUnit.NANOSECONDS)) {
                  ppes.removeShutdownHook();
                }
              } catch (InterruptedException e) {
                logger.log(Level.WARNING, null, e);
                // Restore the interrupted status
                Thread.currentThread().interrupt();
              }
            };
            perProcessorExecutorServices.set(index, null);
            // Never wait for own thread (causes stall every time)
            ThreadFactory tf = currentThreadFactory.get();
            if (tf != null && tf == threadFactories.get(index)) {
              new Thread(ppesShutdown).start();
            } else {
              // OK to use current thread directly
              ppesShutdown.run();
            }
          }
        }
      }
    }

    private PerProcessorExecutor(Executors executors) {
      super(executors);
    }

    @Override
    ThreadFactory getThreadFactory() {
      int index;
        {
          Integer perProcessorIndex = currentThreadPerProcessorIndex.get();
          if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "perProcessorIndex={0}", perProcessorIndex);
          }
          index = (perProcessorIndex == null) ? 0 : (perProcessorIndex + 1);
          if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "index={0}", index);
          }
        }
      return getThreadFactory(index);
    }

    @Override
    protected SimpleExecutorService getExecutorService() {
      assert activeCount.get() > 0;
      int index;
        {
          Integer perProcessorIndex = currentThreadPerProcessorIndex.get();
          if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "perProcessorIndex={0}", perProcessorIndex);
          }
          index = (perProcessorIndex == null) ? 0 : (perProcessorIndex + 1);
          if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "index={0}", index);
          }
        }
      synchronized (perProcessorExecutorServicesLock) {
        ExecutorServiceWrapper perProcessorExecutorService = index < perProcessorExecutorServices.size() ? perProcessorExecutorServices.get(index) : null;
        if (perProcessorExecutorService == null) {
          PrefixThreadFactory perProcessorThreadFactory = getThreadFactory(index);
          int numThreads = executors.preferredConcurrency;
          if (logger.isLoggable(Level.FINEST)) {
            logger.log(
                Level.FINEST,
                "new perProcessorExecutorService: index={0}, numThreads={1}",
                new Object[]{
                    index,
                    numThreads
                }
            );
          }
          ExecutorService executorService;
          if (index == 0) {
            // Top level perProcessor will keep all threads going
            executorService = java.util.concurrent.Executors.newFixedThreadPool(
                numThreads,
                perProcessorThreadFactory
            );
          } else {
            // Other levels will shutdown threads after 60 seconds of inactivity
            ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
                numThreads, numThreads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                perProcessorThreadFactory
            );
            threadPool.allowCoreThreadTimeOut(true);
            executorService = threadPool;
          }
          perProcessorExecutorService = new ExecutorServiceWrapper(
              executorService,
              perProcessorThreadFactory.namePrefix + "shutdownHook"
          );
          while (perProcessorExecutorServices.size() <= index) {
            perProcessorExecutorServices.add(null);
          }
          if (perProcessorExecutorServices.set(index, perProcessorExecutorService) != null) {
            throw new AssertionError();
          }
        }
        return perProcessorExecutorService;
      }
    }
  }

  private final PerProcessorExecutor perProcessor;

  /**
   * <p>
   * An executor service that will execute at most two tasks per processor on
   * multi-CPU systems or one task on single-CPU systems.
   * </p>
   * <p>
   * This should be used for CPU-bound tasks that generally operate non-blocking.
   * If a thread blocks or deadlocks, it can starve the system entirely - use this
   * cautiously.  For example, do not use it for things like disk I/O or network
   * I/O (including writes - they can block, too, once the network buffers are
   * full).
   * </p>
   * <p>
   * When a task is submitted by a thread that is already part of a per-processor executor,
   * it will be invoked on a different per-processor executor to avoid potential deadlock.
   * This means the total number of threads can exceed two tasks per processor, but it will
   * remain bounded as a function of:
   * </p>
   * <pre>
   * maxThreads = maxPerProcessorDepth * numProcessors * 2
   * </pre>
   * <p>
   * Where maxPerProcessorDepth is a function of the number of times a per-processor task adds
   * a per-processor task of its own.
   * </p>
   *
   * @see  #getPreferredConcurrency()  to determine how many threads may be allocated per executor.
   */
  public Executor getPerProcessor() {
    return perProcessor;
  }

  // </editor-fold>

  // <editor-fold defaultstate="collapsed" desc="Sequential">
  private static class SequentialExecutor extends ExecutorImpl {

    private static class SequentialFuture<V> implements Future<V> {

      private static class Lock {
        // Empty lock class to help heap profile
      }

      private final Lock lock = new Lock();
      private final Callable<V> task;
      private final UnboundedExecutor unboundedExecutor;
      private boolean canceled;
      private boolean done;
      private Thread gettingThread;
      private V result;
      private Throwable exception;

      /**
       * Creates a new sequential future.
       *
       * @param unboundedExecutor  Only used for get timeout implementation.
       */
      private SequentialFuture(
          Callable<V> task,
          UnboundedExecutor unboundedExecutor
      ) {
        this.task = task;
        this.unboundedExecutor = unboundedExecutor;
      }

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        synchronized (lock) {
          if (canceled) {
            return false;
          }
          if (done) {
            return false;
          }
          canceled = true;
          done = true;
          if (mayInterruptIfRunning && gettingThread != null) {
            gettingThread.interrupt();
          }
          lock.notifyAll();
          return true;
        }
      }

      @Override
      public boolean isCancelled() {
        synchronized (lock) {
          return canceled;
        }
      }

      @Override
      public boolean isDone() {
        synchronized (lock) {
          return done;
        }
      }

      @Override
      @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
      public V get() throws InterruptedException, CancellationException, ExecutionException {
        synchronized (lock) {
          while (true) {
            if (canceled) {
              throw new CancellationException();
            }
            if (done) {
              if (exception != null) {
                throw new ExecutionException(exception);
              } else {
                return result;
              }
            }
            if (gettingThread == null) {
              gettingThread = Thread.currentThread();
              break;
            } else {
              lock.wait();
            }
          }
        }
        try {
          V r = task.call();
          synchronized (lock) {
            gettingThread = null;
            done = true;
            result = r;
            lock.notifyAll();
          }
          return r;
        } catch (ThreadDeath | InterruptedException e) {
          throw e;
        } catch (Throwable t) {
          synchronized (lock) {
            gettingThread = null;
            done = true;
            exception = t;
            lock.notifyAll();
          }
          throw new ExecutionException(t);
        }
      }

      /**
       * {@inheritDoc}
       *
       * @see  #getUnbounded()  Delegates to unboundedExecutor to provide timeout functionality.
       */
      @Override
      public V get(long timeout, TimeUnit unit) throws InterruptedException, CancellationException, ExecutionException, TimeoutException {
        synchronized (lock) {
          if (canceled) {
            throw new CancellationException();
          }
          if (done) {
            if (exception != null) {
              throw new ExecutionException(exception);
            } else {
              return result;
            }
          }
        }
        try {
          return unboundedExecutor.submit((Callable<V>) SequentialFuture.this::get).get(
              timeout,
              unit
          );
        } catch (ExecutionException e) {
          // Unwrap exception to hide the fact this is delegating to another executor
          // For example, calling code might assume they will not get back ExecutionException with cause of ExecutionException.
          Throwable cause = e.getCause();
          if (cause instanceof InterruptedException) {
            throw (InterruptedException) cause;
          }
          if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
          }
          if (cause instanceof ExecutionException) {
            throw (ExecutionException) cause;
          }
          throw e;
        }
      }
    }

    private SequentialExecutor(Executors executors) {
      super(executors);
    }

    /**
     * Runs the command immediately on the caller thread.
     */
    @Override
    public void execute(Runnable command) {
      command.run();
    }

    private static final ThreadFactory sequentialThreadFactory = (Runnable r) -> {
      throw new IllegalStateException("No threads should be created by the sequential executor");
    };

    @Override
    ThreadFactory getThreadFactory() {
      return sequentialThreadFactory;
    }

    private final SimpleExecutorService sequentialExecutorService = new SimpleExecutorService() {

      @Override
      public <T> Future<T> submit(Callable<T> task) {
        return new SequentialFuture<>(task, executors.unbounded);
      }

      @Override
      public <T> Future<T> submit(final Runnable task, final T result) {
        return submit(() -> {
          task.run();
          return result;
        });
      }
    };

    @Override
    protected SimpleExecutorService getExecutorService() {
      assert activeCount.get() > 0;
      return sequentialExecutorService;
    }

    @Override
    @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
    public <T> List<T> callAll(Collection<? extends Callable<? extends T>> tasks) throws InterruptedException, ExecutionException {
      int size = tasks.size();
      if (size == 0) {
        return Collections.emptyList();
      } else {
        try {
          if (size == 1) {
            return Collections.singletonList(
                tasks.iterator().next().call()
            );
          } else {
            List<T> results = new ArrayList<>(size);
            for (Callable<? extends T> task : tasks) {
              results.add(task.call());
            }
            return Collections.unmodifiableList(results);
          }
        } catch (ThreadDeath | InterruptedException e) {
          throw e;
        } catch (Throwable t) {
          throw new ExecutionException(t);
        }
      }
    }

    @Override
    public void runAll(Collection<? extends Runnable> tasks) throws ExecutionException {
      try {
        for (Runnable task : tasks) {
          task.run();
        }
      } catch (ThreadDeath td) {
        throw td;
      } catch (Throwable t) {
        throw new ExecutionException(t);
      }
    }
  }

  private final SequentialExecutor sequential = new SequentialExecutor(this);

  /**
   * <p>
   * A sequential implementation of executor that performs no concurrent processing.
   * All tasks are performed on the thread calling {@link Future#get()}.
   * </p>
   * <p>
   * <b>Important:</b> A task submitted to this
   * sequential executor is only processed when {@link Future#get()} is called.
   * Thus, a task submitted without a call to {@link Future#get()} is never
   * executed.  This is in stark contrast to other executors, which will
   * complete the task regardless.
   * </p>
   * <p>
   * <b>Note:</b> Timeout not implemented
   * </p>
   */
  public Executor getSequential() {
    return sequential;
  }

  // </editor-fold>

  // <editor-fold defaultstate="collapsed" desc="AutoCloseable">
  /**
   * <p>
   * Closes this executor service instance.  Once closed, no additional
   * tasks may be submitted.  Any overriding method must call super.close().
   * </p>
   * <p>
   * If this is the last active executor, the underlying threads will also be shutdown.
   * This shutdown may wait up to <code>(1 + numPerProcessorPools) * CLOSE_WAIT_NANOS</code>
   * for clean termination of all threads.
   * </p>
   * <p>
   * If already closed, no action will be taken and no exception thrown.
   * </p>
   *
   * @see  #CLOSE_WAIT_NANOS
   */
  @Override
  public void close() {
    boolean alreadyClosed = closed.getAndSet(true);
    if (!alreadyClosed) {
      assert activeCount.get() > 0;
      int newActiveCount = activeCount.decrementAndGet();
      if (logger.isLoggable(Level.FINE)) {
        logger.log(Level.FINE, "activeCount={0}", newActiveCount);
      }
      if (newActiveCount == 0) {
        UnboundedExecutor.close();
        PerProcessorExecutor.close();
        // Stop timer
        Timer t = timer.getAndSet(null);
        if (t != null) {
          if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "Canceling timer");
          }
          t.cancel();
        }
        // No need to wait, since everything already shutdown
        incompleteFutures.clear();
      } else {
        // Build list of tasks that should be waited for.
        final List<ThreadFactoryFuture<?>> waitFutures = new ArrayList<>(incompleteFutures.values());
        incompleteFutures.clear();
        ThreadFactory tf = currentThreadFactory.get();
        final long waitUntil = System.nanoTime() + CLOSE_WAIT_NANOS;
        // Wait for our incomplete tasks to complete.
        // This is done while not holding privateLock to avoid deadlock.
        List<ThreadFactoryFuture<?>> ownThreadFactoryWaitFutures = null;
        for (int i = 0, size = waitFutures.size(); i < size; i++) {
          ThreadFactoryFuture<?> future = waitFutures.get(i);
          // Never wait for own thread (causes stall every time)
          if (tf != future.getThreadFactory()) {
            // Queue to call cancel at end, can't wait from this thread
            if (ownThreadFactoryWaitFutures == null) {
              ownThreadFactoryWaitFutures = new ArrayList<>(size);
            }
            ownThreadFactoryWaitFutures.add(future);
          } else {
            long nanosRemaining = waitUntil - System.nanoTime();
            if (nanosRemaining >= 0) {
              if (logger.isLoggable(Level.FINE)) {
                logger.log(
                    Level.FINE,
                    "Waiting on waitFuture[{0}], {1} ns remaining",
                    new Object[]{
                        i,
                        nanosRemaining
                    }
                );
              }
              try {
                future.get(nanosRemaining, TimeUnit.NANOSECONDS);
              } catch (CancellationException | ExecutionException e) {
                // OK on shutdown
              } catch (InterruptedException e) {
                // OK on shutdown
                // Restore the interrupted status
                Thread.currentThread().interrupt();
                break;
              } catch (TimeoutException e) {
                // Cancel after timeout
                //logger.log(Level.WARNING, null, e);
                future.cancel(true);
              }
            } else {
              // No time remaining, just cancel
              if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "No time left, canceling waitFuture[{0}]", i);
              }
              future.cancel(true);
            }
          }
        }
        if (ownThreadFactoryWaitFutures != null && !Thread.currentThread().isInterrupted()) {
          final List<ThreadFactoryFuture<?>> waitOnOtherThreads = ownThreadFactoryWaitFutures;
          // Cancel all from our thread factory on a different thread to avoid deadlock
          new Thread(() -> {
            for (int i = 0, size = waitOnOtherThreads.size(); i < size; i++) {
              ThreadFactoryFuture<?> future = waitOnOtherThreads.get(i);
              long nanosRemaining = waitUntil - System.nanoTime();
              if (nanosRemaining >= 0) {
                if (logger.isLoggable(Level.FINE)) {
                  logger.log(
                      Level.FINE,
                      "Waiting on waitOnOtherThreads[{0}], {1} ns remaining",
                      new Object[]{
                          i,
                          nanosRemaining
                      }
                  );
                }
                try {
                  future.get(nanosRemaining, TimeUnit.NANOSECONDS);
                } catch (CancellationException | ExecutionException e) {
                  // OK on shutdown
                } catch (InterruptedException e) {
                  // OK on shutdown
                  // Restore the interrupted status
                  Thread.currentThread().interrupt();
                  break;
                } catch (TimeoutException e) {
                  // Cancel after timeout
                  //logger.log(Level.WARNING, null, e);
                  future.cancel(true);
                }
              } else {
                // No time remaining, just cancel
                if (logger.isLoggable(Level.FINE)) {
                  logger.log(Level.FINE, "No time left, canceling waitOnOtherThreads[{0}]", i);
                }
                future.cancel(true);
              }
            }
          }).start();
        }
      }
    }
  }
  // </editor-fold>
}
