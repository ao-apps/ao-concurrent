/*
 * ao-concurrent - Concurrent programming utilities.
 * Copyright (C) 2011, 2012, 2013, 2014, 2015, 2016  AO Industries, Inc.
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
 * along with ao-concurrent.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.util.concurrent;

import com.aoindustries.lang.Disposable;
import com.aoindustries.lang.DisposedException;
import com.aoindustries.lang.RuntimeUtils;
import com.aoindustries.util.AtomicSequence;
import com.aoindustries.util.AutoGrowArrayList;
import com.aoindustries.util.Sequence;
import com.aoindustries.util.i18n.I18nThreadLocalCallable;
import com.aoindustries.util.i18n.I18nThreadLocalRunnable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>
 * Provides a central executor service for use by any number of projects.
 * These executors use daemon threads and will not keep the JVM alive.
 * The executors are automatically shutdown using shutdown hooks.  The
 * executors are also immediately shutdown when the last instance is disposed.
 * </p>
 * <p>
 * Also allows for delayed execution of tasks using an internal Timer.
 * </p>
 */
public class ExecutorService implements Disposable {

	private static final Logger logger = Logger.getLogger(ExecutorService.class.getName());
	static {
		logger.setLevel(Level.ALL); // TODO: Remove for production
	}

	/**
	 * The number of threads per processor for per-processor executor.
	 */
	private static final int THREADS_PER_PROCESSOR = 2;

	/**
	 * The daemon flag for all threads.
	 */
	private static final boolean DAEMON_THREADS = true;

	/**
	 * The maximum number of nanoseconds that will be waited for during dispose (0.1 seconds).
	 */
	private static final long DISPOSE_WAIT_NANOS = 100L * 1000L * 1000L; // Was one minute: 60L * 1000L * 1000L * 1000L;

	/**
	 * Lock used for static fields access.
	 */
	private static final Object privateLock = new Object();

	// <editor-fold defaultstate="collapsed" desc="ThreadFactory">
	/*
	 * The thread factories are created once so each thread gets a unique
	 * identifier independent of creation and destruction of executors.
	 */
	static class PrefixThreadFactory implements ThreadFactory {

		// The thread group management was not compatible with an Applet environment
		// final ThreadGroup group;
		final String namePrefix;
		final Sequence threadNumber = new AtomicSequence();
		final int priority;

		PrefixThreadFactory(String namePrefix, int priority) {
			//SecurityManager s = System.getSecurityManager();
			//this.group = (s != null)? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
			this.namePrefix = namePrefix;
			this.priority = priority;
		}

		@Override
		public Thread newThread(Runnable target) {
			String name = namePrefix + threadNumber.getNextSequenceValue();
			if(logger.isLoggable(Level.FINER)) logger.log(Level.FINER, "newThread={0}", name);
			Thread t = new Thread(
				/*group, */
				target,
				name
			);
			t.setPriority(priority);
			t.setDaemon(DAEMON_THREADS);
			return t;
		}
	}

	/**
	 * Keeps track of which threads are running under unbounded thread pool.
	 */
	private static final ThreadLocal<Boolean> currentThreadUnbounded = new ThreadLocal<Boolean>() {
		@Override
		protected Boolean initialValue() {
			return Boolean.FALSE;
		}
	};

	private static final PrefixThreadFactory unboundedThreadFactory = new PrefixThreadFactory(
		ExecutorService.class.getName()+".unbounded-thread-",
		Thread.NORM_PRIORITY
	) {
		@Override
		public Thread newThread(Runnable target) {
			currentThreadUnbounded.set(Boolean.TRUE);
			return super.newThread(target);
		}
	};

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
	private static final ThreadLocal<Integer> currentThreadPerProcessorIndex = new ThreadLocal<Integer>();

	private static final List<PrefixThreadFactory> perProcessorThreadFactories = new AutoGrowArrayList<PrefixThreadFactory>();

	private static PrefixThreadFactory getPerProcessorThreadFactory(int index) {
		assert Thread.holdsLock(privateLock);
		PrefixThreadFactory perProcessorThreadFactory;
		if(index < perProcessorThreadFactories.size()) {
			perProcessorThreadFactory = perProcessorThreadFactories.get(index);
		} else {
			perProcessorThreadFactory = null;
		}
		if(perProcessorThreadFactory == null) {
			final Integer indexObj = index;
			if(logger.isLoggable(Level.FINEST)) logger.log(Level.FINEST, "new perProcessorThreadFactory: {0}", index);
			perProcessorThreadFactory = new PrefixThreadFactory(
				ExecutorService.class.getName()+".perProcessor-" + index + "-thread-",
				Thread.NORM_PRIORITY
			) {
				@Override
				public Thread newThread(Runnable target) {
					currentThreadPerProcessorIndex.set(indexObj);
					return super.newThread(target);
				}
			};
			perProcessorThreadFactories.set(index, perProcessorThreadFactory);
		}
		return perProcessorThreadFactory;
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Instance Management">
	/**
	 * The number of active executors is tracked, will shutdown when gets to zero.
	 */
	private static int activeCount = 0;

	/**
	 * @deprecated  Just use constructor directly.  Subclasses are now allowed, too.
	 */
	@Deprecated
	public static ExecutorService newInstance() {
		return new ExecutorService();
	}

	/**
	 * <p>
	 * Create a new instance of the executor service.  <code>dispose()</code> must be called
	 * when done with the instance.  This should be done in a try-finally or strong
	 * equivalent, such as <code>Servlet.destroy()</code>.
	 * </p>
	 * <p>
	 * Internally, threads are shared between executor instances.  The threads are only
	 * shutdown when the last executor is disposed.
	 * </p>
	 *
	 * @see  #dispose()
	 */
	public ExecutorService() {
		synchronized(privateLock) {
			if(activeCount < 0) throw new AssertionError();
			if(activeCount == Integer.MAX_VALUE) throw new IllegalStateException();
			activeCount++;
			if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "activeCount={0}", activeCount);
		}
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="ExecutorService">
	private static java.util.concurrent.ExecutorService unboundedExecutorService;
	private static Thread unboundedShutdownHook;

	/**
	 * Gets the unbounded concurrency executor service.  activeCount must be
	 * greater than zero.
	 *
	 * Must be holding privateLock.
	 */
	private static java.util.concurrent.ExecutorService getUnboundedExecutorService() {
		assert Thread.holdsLock(privateLock);
		if(activeCount < 1) throw new IllegalStateException();
		if(unboundedExecutorService == null) {
			java.util.concurrent.ExecutorService newExecutorService = Executors.newCachedThreadPool(unboundedThreadFactory);
			Thread shutdownHook = new ExecutorServiceShutdownHook(
				newExecutorService,
				unboundedThreadFactory.namePrefix + "shutdownHook"
			);
			// Only keep instances once shutdown hook properly registered
			try {
				Runtime.getRuntime().addShutdownHook(shutdownHook);
			} catch(SecurityException e) {
				logger.log(Level.WARNING, null, e);
				shutdownHook = null;
			}
			unboundedExecutorService = newExecutorService;
			unboundedShutdownHook = shutdownHook;
		}
		return unboundedExecutorService;
	}

	private static final AutoGrowArrayList<java.util.concurrent.ExecutorService> perProcessorExecutorServices = new AutoGrowArrayList<java.util.concurrent.ExecutorService>();
	private static final AutoGrowArrayList<Thread> perProcessorShutdownHooks = new AutoGrowArrayList<Thread>();

	/**
	 * Resolves the correct executor to use for a per-processor request.
	 *
	 * Must be holding privateLock.
	 */
	private static java.util.concurrent.ExecutorService getPerProcessorExecutorService() {
		assert Thread.holdsLock(privateLock);
		if(activeCount < 1) throw new IllegalStateException();
		int index;
		{
			Integer perProcessorIndex = currentThreadPerProcessorIndex.get();
			if(logger.isLoggable(Level.FINEST)) logger.log(Level.FINEST, "perProcessorIndex={0}", perProcessorIndex);
			index = (perProcessorIndex == null) ? 0 : (perProcessorIndex + 1);
			if(logger.isLoggable(Level.FINEST)) logger.log(Level.FINEST, "index={0}", index);
		}
		java.util.concurrent.ExecutorService perProcessorExecutorService = index < perProcessorExecutorServices.size() ? perProcessorExecutorServices.get(index) : null;
		if(perProcessorExecutorService == null) {
			PrefixThreadFactory perProcessorThreadFactory = getPerProcessorThreadFactory(index);
			int numThreads = RuntimeUtils.getAvailableProcessors() * THREADS_PER_PROCESSOR;
			if(logger.isLoggable(Level.FINEST)) {
				logger.log(
					Level.FINEST,
					"new perProcessorExecutorService: index={0}, numThreads={1}",
					new Object[] {
						index,
						numThreads
					}
				);
			}
			if(index == 0) {
				// Top level perProcessor will keep all threads going
				perProcessorExecutorService = Executors.newFixedThreadPool(
					numThreads,
					perProcessorThreadFactory
				);
			} else {
				// Other levels will shutdown threads after 60 seconds of inactivity
				perProcessorExecutorService = new ThreadPoolExecutor(
					0, numThreads,
					60L, TimeUnit.SECONDS,
					new LinkedBlockingQueue<Runnable>(),
					perProcessorThreadFactory
				);
			}
			Thread shutdownHook = new ExecutorServiceShutdownHook(
				perProcessorExecutorService,
				perProcessorThreadFactory + "shutdownHook"
			);
			// Only keep instances once shutdown hook properly registered
			try {
				Runtime.getRuntime().addShutdownHook(shutdownHook);
			} catch(SecurityException e) {
				logger.log(Level.WARNING, null, e);
				shutdownHook = null;
			}
			if(perProcessorExecutorServices.set(index, perProcessorExecutorService) != null) throw new AssertionError();
			if(perProcessorShutdownHooks.set(index, shutdownHook) != null) throw new AssertionError();
		}
		return perProcessorExecutorService;
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Timer">
	private static Timer timer;

	/**
	 * Gets the timer.  activeCount must be greater than zero.
	 *
	 * Must be holding privateLock.
	 */
	private static Timer getTimer() {
		assert Thread.holdsLock(privateLock);
		if(activeCount <= 0) throw new IllegalStateException();
		if(timer==null) timer = new Timer(DAEMON_THREADS);
		return timer;
	}
	// </editor-fold>

	/**
	 * Set to true when dispose called.
	 */
	private boolean disposed = false;

	// <editor-fold defaultstate="collapsed" desc="Incomplete Futures">
	/**
	 * Keeps track of all tasks scheduled but not yet completed by this executor so the tasks
	 * may be completed or canceled during dispose.
	 */
	private long nextIncompleteFutureId = 1;
	private final Map<Long,Future<?>> incompleteFutures = new HashMap<Long,Future<?>>();

	class IncompleteFuture<V> implements Future<V> {

		private final Long incompleteFutureId;
		private final Future<V> future;

		IncompleteFuture(Long incompleteFutureId, Future<V> future) {
			this.incompleteFutureId = incompleteFutureId;
			this.future = future;
		}

		/**
		 * Remove from incomplete when canceled.
		 */
		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			try {
				return future.cancel(mayInterruptIfRunning);
			} finally {
				synchronized(privateLock) {
					incompleteFutures.remove(incompleteFutureId);
				}
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

	/**
	 * Submits to an executor service.
	 *
	 * Must be holding privateLock.
	 */
	private <T> Future<T> submit(java.util.concurrent.ExecutorService executor, final Callable<T> task) {
		assert Thread.holdsLock(privateLock);
		final Long incompleteFutureId = nextIncompleteFutureId++;
		final Future<T> future = executor.submit(
			new Callable<T>() {
				/**
				 * Remove from incomplete when call completed.
				 */
				@Override
				public T call() throws Exception {
					try {
						return task.call();
					} finally {
						synchronized(privateLock) {
							incompleteFutures.remove(incompleteFutureId);
						}
					}
				}
			}
		);
		Future<T> incompleteFuture = new IncompleteFuture<T>(incompleteFutureId, future);
		incompleteFutures.put(incompleteFutureId, incompleteFuture);
		return incompleteFuture;
	}

	/**
	 * Submits to an executor service.
	 *
	 * Must be holding privateLock.
	 */
	private Future<Object> submit(java.util.concurrent.ExecutorService executor, final Runnable task) {
		assert Thread.holdsLock(privateLock);
		final Long incompleteFutureId = nextIncompleteFutureId++;
		final Future<Object> submitted = executor.submit(
			new Runnable() {
				/**
				 * Remove from incomplete when run finished.
				 */
				@Override
				public void run() {
					try {
						task.run();
					} finally {
						synchronized(privateLock) {
							incompleteFutures.remove(incompleteFutureId);
						}
					}
				}
			},
			(Object)null
		);
		Future<Object> future = new IncompleteFuture<Object>(incompleteFutureId, submitted);
		incompleteFutures.put(incompleteFutureId, future);
		return future;
	}

	abstract class IncompleteTimerTask<V> extends TimerTask implements Future<V> {

		protected final Long incompleteFutureId;
		private final Object incompleteLock = new Object();
		private boolean canceled = false;
		private Future<V> future; // Only available once submitted

		IncompleteTimerTask(Long incompleteFutureId) {
			this.incompleteFutureId = incompleteFutureId;
		}

		/**
		 * Sets the future that was obtained after submission to the executor.
		 * Notifies all threads waiting for the future.
		 */
		protected void setFuture(Future<V> future) {
			synchronized(incompleteLock) {
				this.future = future;
				incompleteLock.notifyAll();
			}
		}

		/**
		 * Cancels this TimerTask, but does not cancel the task itself
		 * if already submitted to the executor.
		 */
		@Override
		public boolean cancel() {
			try {
				synchronized(incompleteLock) {
					canceled = true;
				}
				return super.cancel();
			} finally {
				synchronized(privateLock) {
					incompleteFutures.remove(incompleteFutureId);
				}
			}
		}

		/**
		 * Cancels this Future, canceling either the TimerTask or the submitted
		 * Task.
		 */
		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			try {
				Future<?> f;
				synchronized(incompleteLock) {
					f = future;
					canceled = true;
				}
				return f==null ? super.cancel() : f.cancel(mayInterruptIfRunning);
			} finally {
				synchronized(privateLock) {
					incompleteFutures.remove(incompleteFutureId);
				}
			}
		}

		@Override
		public boolean isCancelled() {
			boolean c;
			Future<?> f;
			synchronized(incompleteLock) {
				c = canceled;
				f = future;
			}
			return f==null ? c : f.isCancelled();
		}

		@Override
		public boolean isDone() {
			boolean c;
			Future<?> f;
			synchronized(incompleteLock) {
				c = canceled;
				f = future;
			}
			return f==null ? c : f.isDone();
		}

		@Override
		public V get() throws InterruptedException, ExecutionException {
			// Wait until submitted
			Future<V> f;
			synchronized(incompleteLock) {
				while(future==null) {
					incompleteLock.wait();
				}
				f = future;
			}
			// Wait until completed
			return f.get();
		}

		@Override
		public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
			final long waitUntil = System.nanoTime() + unit.toNanos(timeout);
			// Wait until submitted
			Future<V> f;
			synchronized(incompleteLock) {
				while(future==null) {
					long nanosRemaining = waitUntil - System.nanoTime();
					if(nanosRemaining < 0) throw new TimeoutException();
					incompleteLock.wait(nanosRemaining / 1000000, (int)(nanosRemaining % 1000000));
				}
				f = future;
			}
			// Wait until completed
			return f.get(waitUntil - System.nanoTime(), TimeUnit.NANOSECONDS);
		}
	}

	class IncompleteCallableTimerTask<V> extends IncompleteTimerTask<V> {

		final java.util.concurrent.ExecutorService executor;
		final Callable<V> task;

		IncompleteCallableTimerTask(Long incompleteFutureId, java.util.concurrent.ExecutorService executor, Callable<V> task) {
			super(incompleteFutureId);
			this.executor = executor;
			this.task = task;
		}

		/**
		 * Remove from incomplete once submitted to executorService.
		 */
		@Override
		public void run() {
			synchronized(privateLock) {
				try {
					setFuture(submit(executor, task));
				} finally {
					incompleteFutures.remove(incompleteFutureId);
				}
			}
		}
	}

	class IncompleteRunnableTimerTask extends IncompleteTimerTask<Object> {

		final java.util.concurrent.ExecutorService executor;
		final Runnable task;

		IncompleteRunnableTimerTask(Long incompleteFutureId, java.util.concurrent.ExecutorService executor, Runnable task) {
			super(incompleteFutureId);
			this.executor = executor;
			this.task = task;
		}

		/**
		 * Remove from incomplete once submitted to executorService.
		 */
		@Override
		public void run() {
			synchronized(privateLock) {
				try {
					setFuture(submit(executor, task));
				} finally {
					incompleteFutures.remove(incompleteFutureId);
				}
			}
		}
	}

	/**
	 * Adds to the timer.
	 *
	 * Must be holding privateLock.
	 */
	private <T> Future<T> submit(java.util.concurrent.ExecutorService executor, Callable<T> task, long delay) {
		assert Thread.holdsLock(privateLock);
		final Long incompleteFutureId = nextIncompleteFutureId++;
		final IncompleteCallableTimerTask<T> timerTask = new IncompleteCallableTimerTask<T>(incompleteFutureId, executor, task);
		getTimer().schedule(timerTask, delay);
		incompleteFutures.put(incompleteFutureId, timerTask);
		return timerTask;
	}

	/**
	 * Adds to the timer.
	 *
	 * Must be holding privateLock.
	 */
	private Future<?> submit(java.util.concurrent.ExecutorService executor, Runnable task, long delay) {
		assert Thread.holdsLock(privateLock);
		final Long incompleteFutureId = nextIncompleteFutureId++;
		final IncompleteRunnableTimerTask timerTask = new IncompleteRunnableTimerTask(incompleteFutureId, executor, task);
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
		return new I18nThreadLocalCallable<T>(task);
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
	
	// <editor-fold defaultstate="collapsed" desc="Unbounded">
	/**
	 * Avoid deadlock: Maintain which perProcessor thread pool this task came from
	 */
	private static class PerProcessorCallable<T> extends ThreadLocalCallable<T> {
		private PerProcessorCallable(Callable<T> task) {
			super(task, currentThreadPerProcessorIndex);
		}
	}

	/**
	 * Avoid deadlock: Maintain which perProcessor thread pool this task came from
	 */
	private static class PerProcessorRunnable extends ThreadLocalRunnable {
		private PerProcessorRunnable(Runnable task) {
			super(task, currentThreadPerProcessorIndex);
		}
	}

	/**
	 * Submits to an unbounded executor service.
	 * This is most appropriate for I/O bound tasks, especially higher latency
	 * I/O like wide area networks.
	 * 
	 * @see  I18nThreadLocalCallable  Maintains internationalization context.
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	public <T> Future<T> submitUnbounded(Callable<T> task) throws DisposedException {
		synchronized(privateLock) {
			if(disposed) throw new DisposedException();
			return submit(getUnboundedExecutorService(), wrap(new PerProcessorCallable<T>(task)));
		}
	}

	/**
	 * Submits to an unbounded executor service after the provided delay.
	 * This is most appropriate for I/O bound tasks, especially higher latency
	 * I/O like wide area networks.
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	public <T> Future<T> submitUnbounded(Callable<T> task, long delay) throws DisposedException {
		synchronized(privateLock) {
			if(disposed) throw new DisposedException();
			return submit(getUnboundedExecutorService(), wrap(new PerProcessorCallable<T>(task)), delay);
		}
	}

	/**
	 * Submits to an unbounded executor service.
	 * This is most appropriate for I/O bound tasks, especially higher latency
	 * I/O like wide area networks.
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	public Future<?> submitUnbounded(Runnable task) throws DisposedException {
		synchronized(privateLock) {
			if(disposed) throw new DisposedException();
			return submit(getUnboundedExecutorService(), wrap(new PerProcessorRunnable(task)));
		}
	}

	/**
	 * Submits to an unbounded executor service after the provided delay.
	 * This is most appropriate for I/O bound tasks, especially higher latency
	 * I/O like wide area networks.
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	public Future<?> submitUnbounded(Runnable task, long delay) throws DisposedException {
		synchronized(privateLock) {
			if(disposed) throw new DisposedException();
			return submit(getUnboundedExecutorService(), wrap(new PerProcessorRunnable(task)), delay);
		}
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Per-processor">
	/**
	 * <p>
	 * Submits to an executor service that will execute at most two tasks per processor.
	 * This should be used for CPU-bound tasks that generally operate non-blocking.
	 * If a thread blocks or deadlocks, it can starve the system entirely - use this
	 * cautiously.
	 * </p>
	 * <p>
	 * If a task is submitted by a thread that is already part of a per-processor executor,
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
	 * @exception  DisposedException  if already disposed.
	 */
	public <T> Future<T> submitPerProcessor(Callable<T> task) throws DisposedException {
		synchronized(privateLock) {
			if(disposed) throw new DisposedException();
			return submit(getPerProcessorExecutorService(), wrap(task));
		}
	}

	/**
	 * <p>
	 * Submits to an executor service that will execute at most two tasks per processor after the provided delay.
	 * This should be used for CPU-bound tasks that generally operate non-blocking.
	 * If a thread blocks or deadlocks, it can starve the system entirely - use this
	 * cautiously.
	 * </p>
	 * <p>
	 * If a task is submitted by a thread that is already part of a per-processor executor,
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
	 * @exception  DisposedException  if already disposed.
	 */
	public <T> Future<T> submitPerProcessor(Callable<T> task, long delay) throws DisposedException {
		synchronized(privateLock) {
			if(disposed) throw new DisposedException();
			return submit(getPerProcessorExecutorService(), wrap(task), delay);
		}
	}

	/**
	 * <p>
	 * Submits to an executor service that will execute at most two tasks per processor.
	 * This should be used for CPU-bound tasks that generally operate non-blocking.
	 * If a thread blocks or deadlocks, it can starve the system entirely - use this
	 * cautiously.
	 * </p>
	 * <p>
	 * If a task is submitted by a thread that is already part of a per-processor executor,
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
	 * @exception  DisposedException  if already disposed.
	 */
	public Future<?> submitPerProcessor(Runnable task) throws DisposedException {
		synchronized(privateLock) {
			if(disposed) throw new DisposedException();
			return submit(getPerProcessorExecutorService(), wrap(task));
		}
	}

	/**
	 * <p>
	 * Submits to an executor service that will execute at most two tasks per processor after the provided delay.
	 * This should be used for CPU-bound tasks that generally operate non-blocking.
	 * If a thread blocks or deadlocks, it can starve the system entirely - use this
	 * cautiously.
	 * </p>
	 * <p>
	 * If a task is submitted by a thread that is already part of a per-processor executor,
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
	 * @exception  DisposedException  if already disposed.
	 */
	public Future<?> submitPerProcessor(Runnable task, long delay) throws DisposedException {
		synchronized(privateLock) {
			if(disposed) throw new DisposedException();
			return submit(getPerProcessorExecutorService(), wrap(task), delay);
		}
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Dispose">
	/**
	 * <p>
	 * Disposes of this executor service instance.  Once disposed, no additional
	 * tasks may be submitted.  Any overriding method must call super.dispose().
	 * </p>
	 * <p>
	 * If this is the last active executor, the underlying threads will also be shutdown.
	 * This shutdown may wait up to <code>(1 + numPerProcessorPools) * DISPOSE_WAIT_NANOS</code>
	 * for clean termination of all threads.
	 * </p>
	 * <p>
	 * If already disposed, no action will be taken and no exception thrown.
	 * </p>
	 *
	 * @see  DISPOSE_WAIT_NANOS
	 */
	@Override
	public void dispose() {
		final List<Future<?>> waitFutures;
		synchronized(privateLock) {
			if(!disposed) {
				disposed = true;
				if(activeCount <= 0) throw new AssertionError();
				--activeCount;
				if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "activeCount={0}", activeCount);

				if(activeCount == 0) {
					final java.util.concurrent.ExecutorService ues = unboundedExecutorService;
					if(ues != null) {
						final Thread ush = unboundedShutdownHook;
						Runnable uesShutdown = new Runnable() {
							@Override
							public void run() {
								try {
									ues.shutdown();
								} catch(SecurityException e) {
									logger.log(Level.WARNING, null, e);
								}
								try {
									if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "awaiting termination of unboundedExecutorService");
									if(ues.awaitTermination(DISPOSE_WAIT_NANOS, TimeUnit.NANOSECONDS)) {
										if(ush != null) {
											try {
												Runtime.getRuntime().removeShutdownHook(ush);
											} catch(IllegalStateException e) {
												// System shutting down, can't remove hook
											} catch(SecurityException e) {
												logger.log(Level.WARNING, null, e);
											}
										}
									}
								} catch(InterruptedException e) {
									logger.log(Level.WARNING, null, e);
								}
							}
						};
						unboundedExecutorService = null;
						unboundedShutdownHook = null;
						// Never wait for own thread (causes stall every time)
						if(currentThreadUnbounded.get()) {
							new Thread(uesShutdown).start();
						} else {
							// OK to use current thread directly
							uesShutdown.run();
						}
					}
					// Going backwards, to clean up deepest depth tasks first, giving other tasks a chance to finish during cleanup
					for(int i = perProcessorExecutorServices.size()-1; i >= 0; --i) {
						final int index = i;
						final java.util.concurrent.ExecutorService ppes = perProcessorExecutorServices.get(index);
						if(ppes != null) {
							final Thread ppsh = perProcessorShutdownHooks.get(index);
							Runnable ppesShutdown = new Runnable() {
								@Override
								public void run() {
									try {
										ppes.shutdown();
									} catch(SecurityException e) {
										logger.log(Level.WARNING, null, e);
									}
									try {
										if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "awaiting termination of perProcessorExecutorServices[{0}]", index);
										if(ppes.awaitTermination(DISPOSE_WAIT_NANOS, TimeUnit.NANOSECONDS)) {
											if(ppsh != null) {
												try {
													Runtime.getRuntime().removeShutdownHook(ppsh);
												} catch(IllegalStateException e) {
													// System shutting down, can't remove hook
												} catch(SecurityException e) {
													logger.log(Level.WARNING, null, e);
												}
											}
										}
									} catch(InterruptedException e) {
										logger.log(Level.WARNING, null, e);
									}
								}
							};
							perProcessorExecutorServices.set(index, null);
							perProcessorShutdownHooks.set(index, null);
							// Never wait for own thread (causes stall every time)
							Integer currentThreadIndex = currentThreadPerProcessorIndex.get();
							if(currentThreadIndex != null && currentThreadIndex == index) {
								new Thread(ppesShutdown).start();
							} else {
								// OK to use current thread directly
								ppesShutdown.run();
							}
						}
					}
					// All elements of list are null now, trim size to be tidy after disposal.
					perProcessorExecutorServices.clear();
					perProcessorExecutorServices.trimToSize();
					perProcessorShutdownHooks.clear();
					perProcessorShutdownHooks.trimToSize();
					// Stop timer
					if(timer != null) {
						if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "Canceling timer");
						timer.cancel();
						timer = null;
					}
					// No need to wait, since everything already shutdown
					waitFutures = null;
					incompleteFutures.clear();
				} else {
					// Build list of tasks that should be waited for.
					waitFutures = new ArrayList<Future<?>>(incompleteFutures.values());
					incompleteFutures.clear();
				}
			} else {
				// Already disposed, nothing to wait for
				waitFutures = null;
				incompleteFutures.clear();
			}
		}
		if(waitFutures != null) {
			// Never wait for own thread (causes stall every time)
			// TODO: Is it worth a more specific avoidance of skipping waiting only for incomplete tasks exactly matching the current thread?
			if(currentThreadUnbounded.get()) {
				if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "Skipping waitFutures from thread of unboundedExecutorService");
			} else if(currentThreadPerProcessorIndex.get() == null) {
				if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "Skipping waitFutures from thread of perProcessorExecutorServices[{0}]", currentThreadPerProcessorIndex.get());
			} else {
				final long waitUntil = System.nanoTime() + DISPOSE_WAIT_NANOS;
				// Wait for our incomplete tasks to complete.
				// This is done while not holding privateLock to avoid deadlock.
				for(int i=0, size=waitFutures.size(); i<size; i++) {
					Future<?> future = waitFutures.get(i);
					long nanosRemaining = waitUntil - System.nanoTime();
					if(nanosRemaining >= 0) {
						if(logger.isLoggable(Level.FINE)) logger.log(
							Level.FINE,
							"Waiting on waitFuture[{0}], {1} ns remaining",
							new Object[] {
								i,
								nanosRemaining
							}
						);
						try {
							future.get(nanosRemaining, TimeUnit.NANOSECONDS);
						} catch(CancellationException e) {
							// OK on shutdown
						} catch(ExecutionException e) {
							// OK on shutdown
						} catch(InterruptedException e) {
							// OK on shutdown
						} catch(TimeoutException e) {
							// Cancel after timeout
							//logger.log(Level.WARNING, null, e);
							future.cancel(true);
						}
					} else {
						// No time remaining, just cancel
						if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "No time left, canceling waitFuture[{0}]", i);
						future.cancel(true);
					}
				}
			}
		}
	}

	/**
	 * Do not rely on the finalizer - this is just in case something is way off
	 * and the calling code doesn't correctly dispose their instances.
	 */
	@Override
	protected void finalize() throws Throwable {
		try {
			dispose();
		} finally {
			super.finalize();
		}
	}
	// </editor-fold>
}
