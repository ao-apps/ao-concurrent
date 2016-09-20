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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
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
 * Provides a central set of executors for use by any number of projects.
 * These executors use daemon threads and will not keep the JVM alive.
 * The executors are automatically shutdown using shutdown hooks.  The
 * executors are also immediately shutdown when the last instance is disposed.
 * </p>
 * <p>
 * Also allows for delayed execution of tasks using an internal Timer.
 * </p>
 */
public class Executors implements Disposable {

	private static final Logger logger = Logger.getLogger(Executors.class.getName());

	/**
	 * The daemon flag for all threads.
	 */
	private static final boolean DAEMON_THREADS = true;

	/**
	 * The maximum number of nanoseconds that will be waited for during dispose (0.1 seconds).
	 */
	private static final long DISPOSE_WAIT_NANOS = 100L * 1000L * 1000L; // Was one minute: 60L * 1000L * 1000L * 1000L;

	/**
	 * The number of threads per processor for per-processor executor.
	 */
	private static final int THREADS_PER_PROCESSOR = 2;

	/**
	 * Lock used for static fields access.
	 */
	private static final Object privateLock = new Object();

	// <editor-fold defaultstate="collapsed" desc="Thread Factories">
	/**
	 * Tracks which ThreadFactory created the current thread, if any.
	 * This is used to avoid shutting down an executor from a thread created by
	 * it's factory.
	 * This threadLocal is not passed from thread to sub tasks.
	 */
	private static final ThreadLocal<PrefixThreadFactory> currentThreadFactory = new ThreadLocal<PrefixThreadFactory>();

	/*
	 * The thread factories are created once so each thread gets a unique
	 * identifier independent of creation and destruction of executors.
	 */
	private static class PrefixThreadFactory implements ThreadFactory {

		// The thread group management was not compatible with an Applet environment
		// final ThreadGroup group;
		final String namePrefix;
		final Sequence threadNumber = new AtomicSequence();
		final int priority;

		private PrefixThreadFactory(String namePrefix, int priority) {
			//SecurityManager s = System.getSecurityManager();
			//this.group = (s != null)? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
			this.namePrefix = namePrefix;
			this.priority = priority;
		}

		@Override
		public Thread newThread(final Runnable target) {
			String name = namePrefix + threadNumber.getNextSequenceValue();
			if(logger.isLoggable(Level.FINER)) logger.log(Level.FINER, "newThread={0}", name);
			Thread t = new Thread(
				/*group, */
				new Runnable() {
					@Override
					public void run() {
						currentThreadFactory.set(PrefixThreadFactory.this);
						target.run();
					}
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
	private static int activeCount = 0;

	/**
	 * @deprecated  Just use constructor directly.  Subclasses are now allowed, too.
	 */
	@Deprecated
	public static Executors newInstance() {
		return new Executors();
	}

	/**
	 * Set to true when dispose called.
	 */
	private boolean disposed = false;

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
	public Executors() {
		preferredConcurrency = RuntimeUtils.getAvailableProcessors() * THREADS_PER_PROCESSOR;
		synchronized(privateLock) {
			if(activeCount < 0) throw new AssertionError();
			if(activeCount == Integer.MAX_VALUE) throw new IllegalStateException();
			activeCount++;
			if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "activeCount={0}", activeCount);
		}
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Preferred Concurrency">
	private final int preferredConcurrency;

	/**
	 * Gets the preferred concurrency for this executor.  Does not change for the life
	 * of the executor, but will be updated should the last executor be disposed
	 * and another created.
	 */
	public int getPreferredConcurrency() {
		return preferredConcurrency;
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

	// <editor-fold defaultstate="collapsed" desc="Incomplete Futures">
	/**
	 * Tracks which thread factory a future will be executed on.
	 */
	private static interface ThreadFactoryFuture<V> extends Future<V> {

		/**
		 * Gets the thread factory that is associated with the executor service this
		 * future will be executed on.
		 */
		PrefixThreadFactory getThreadFactory();
	}

	/**
	 * Keeps track of all tasks scheduled but not yet completed by this executor so the tasks
	 * may be completed or canceled during dispose.
	 */
	private static long nextIncompleteFutureId = 1;
	private static final Map<Long,ThreadFactoryFuture<?>> incompleteFutures = new HashMap<Long,ThreadFactoryFuture<?>>();

	private static class IncompleteFuture<V> implements ThreadFactoryFuture<V> {

		private final PrefixThreadFactory threadFactory;
		private final Long incompleteFutureId;
		private final Future<V> future;

		private IncompleteFuture(PrefixThreadFactory threadFactory, Long incompleteFutureId, Future<V> future) {
			this.threadFactory = threadFactory;
			this.incompleteFutureId = incompleteFutureId;
			this.future = future;
		}

		@Override
		public PrefixThreadFactory getThreadFactory() {
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

	private static abstract class IncompleteTimerTask<V> extends TimerTask implements ThreadFactoryFuture<V> {

		protected final PrefixThreadFactory threadFactory;
		protected final ExecutorService executorService;
		protected final Long incompleteFutureId;
		private final Object incompleteLock = new Object();
		private boolean canceled = false;
		private IncompleteFuture<V> future; // Only available once submitted

		private IncompleteTimerTask(
			PrefixThreadFactory threadFactory,
			ExecutorService executorService,
			Long incompleteFutureId
		) {
			this.threadFactory = threadFactory;
			this.executorService = executorService;
			this.incompleteFutureId = incompleteFutureId;
		}

		@Override
		public PrefixThreadFactory getThreadFactory() {
			return threadFactory;
		}

		/**
		 * Sets the future that was obtained after submission to the executor.
		 * Notifies all threads waiting for the future.
		 */
		protected void setFuture(IncompleteFuture<V> future) {
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

	/**
	 * Submits to the executor service.
	 *
	 * Must be holding privateLock.
	 */
	private static <T> IncompleteFuture<T> incompleteFutureSubmit(
		PrefixThreadFactory threadFactory,
		ExecutorService executorService,
		final Callable<T> task
	) {
		assert Thread.holdsLock(privateLock);
		final Long incompleteFutureId = nextIncompleteFutureId++;
		final Future<T> future = executorService.submit(
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
		IncompleteFuture<T> incompleteFuture = new IncompleteFuture<T>(threadFactory, incompleteFutureId, future);
		incompleteFutures.put(incompleteFutureId, incompleteFuture);
		return incompleteFuture;
	}

	/**
	 * Submits to the executor service.
	 *
	 * Must be holding privateLock.
	 */
	private static IncompleteFuture<Object> incompleteFutureSubmit(
		PrefixThreadFactory threadFactory,
		ExecutorService executorService,
		final Runnable task
	) {
		assert Thread.holdsLock(privateLock);
		final Long incompleteFutureId = nextIncompleteFutureId++;
		final Future<Object> submitted = executorService.submit(
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
		IncompleteFuture<Object> future = new IncompleteFuture<Object>(threadFactory, incompleteFutureId, submitted);
		incompleteFutures.put(incompleteFutureId, future);
		return future;
	}

	private static class IncompleteCallableTimerTask<V> extends IncompleteTimerTask<V> {

		final Callable<V> task;

		IncompleteCallableTimerTask(
			PrefixThreadFactory threadFactory,
			ExecutorService executorService,
			Long incompleteFutureId,
			Callable<V> task
		) {
			super(threadFactory, executorService, incompleteFutureId);
			this.task = task;
		}

		/**
		 * Remove from incomplete once submitted to executorService.
		 */
		@Override
		public void run() {
			synchronized(privateLock) {
				try {
					setFuture(incompleteFutureSubmit(threadFactory, executorService, task));
				} finally {
					incompleteFutures.remove(incompleteFutureId);
				}
			}
		}
	}

	private static class IncompleteRunnableTimerTask extends IncompleteTimerTask<Object> {

		final Runnable task;

		IncompleteRunnableTimerTask(
			PrefixThreadFactory threadFactory,
			ExecutorService executorService,
			Long incompleteFutureId,
			Runnable task
		) {
			super(threadFactory, executorService, incompleteFutureId);
			this.task = task;
		}

		/**
		 * Remove from incomplete once submitted to executorService.
		 */
		@Override
		public void run() {
			synchronized(privateLock) {
				try {
					setFuture(incompleteFutureSubmit(threadFactory, executorService, task));
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
	private static <T> Future<T> incompleteFutureSubmit(
		PrefixThreadFactory threadFactory,
		ExecutorService executorService,
		Callable<T> task,
		long delay
	) {
		assert Thread.holdsLock(privateLock);
		final Long incompleteFutureId = nextIncompleteFutureId++;
		IncompleteCallableTimerTask<T> timerTask = new IncompleteCallableTimerTask<T>(threadFactory, executorService, incompleteFutureId, task);
		getTimer().schedule(timerTask, delay);
		incompleteFutures.put(incompleteFutureId, timerTask);
		return timerTask;
	}

	/**
	 * Adds to the timer.
	 *
	 * Must be holding privateLock.
	 */
	private static Future<?> incompleteFutureSubmit(
		PrefixThreadFactory threadFactory,
		ExecutorService executorService,
		Runnable task,
		long delay
	) {
		assert Thread.holdsLock(privateLock);
		final Long incompleteFutureId = nextIncompleteFutureId++;
		IncompleteRunnableTimerTask timerTask = new IncompleteRunnableTimerTask(threadFactory, executorService, incompleteFutureId, task);
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

	// <editor-fold defaultstate="collapsed" desc="Executor">
	private static abstract class ExecutorImpl implements Executor {

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
		 * Caller must be holding privateLock.
		 */
		abstract PrefixThreadFactory getThreadFactory();

		/**
		 * Gets the executor service.
		 * Caller must be holding privateLock
		 *
		 * @see  #getThreadFactory()  The executor must be using the same thread factory
		 */
		protected abstract ExecutorService getExecutorService();

		/**
		 * @see  ExecutorService#wrap(java.util.concurrent.Callable)
		 */
		protected <T> Callable<T> wrap(Callable<T> task) {
			return executors.wrap(task);
		}

		/**
		 * @see  ExecutorService#wrap(java.lang.Runnable)
		 */
		protected Runnable wrap(Runnable task) {
			return executors.wrap(task);
		}

		@Override
		public <T> Future<T> submit(Callable<T> task) throws DisposedException {
			synchronized(privateLock) {
				if(executors.disposed) throw new DisposedException();
				return incompleteFutureSubmit(
					getThreadFactory(),
					getExecutorService(),
					wrap(task)
				);
			}
		}

		@Override
		public <T> List<T> callAll(Collection<Callable<T>> tasks) throws DisposedException, InterruptedException, ExecutionException {
			synchronized(privateLock) {
				if(executors.disposed) throw new DisposedException();
			}
			int size = tasks.size();
			if(size == 0) {
				return Collections.emptyList();
			} else if(size == 1) {
				try {
					return Collections.singletonList(tasks.iterator().next().call());
				} catch(Exception e) {
					throw new ExecutionException(e);
				}
			} else {
				List<Callable<T>> taskList;
				if(tasks instanceof List) {
					taskList = (List<Callable<T>>)tasks;
				} else {
					taskList = new ArrayList<Callable<T>>(tasks);
				}
				List<Future<T>> futures = new ArrayList<Future<T>>(size - 1); // Last one called by current thread
				List<T> results = new ArrayList<T>(size);
				for(int i=0; i<size; i++) {
					Callable<T> task = taskList.get(i);
					if(i < (size - 1)) {
						// Call concurrently
						futures.add(submit(task));
						results.add(null);
					} else {
						// Last one on current thread
						try {
							results.add(task.call());
						} catch(Exception e) {
							throw new ExecutionException(e);
						}
					}
				}
				// Gather concurrent results
				for(int i=0; i<(size-1); i++) {
					results.set(i, futures.get(i).get());
				}
				return Collections.unmodifiableList(results);
			}
		}

		@Override
		public <T> Future<T> submit(Callable<T> task, long delay) throws DisposedException {
			synchronized(privateLock) {
				if(executors.disposed) throw new DisposedException();
				return incompleteFutureSubmit(
					getThreadFactory(),
					getExecutorService(),
					wrap(task),
					delay
				);
			}
		}

		@Override
		public Future<?> submit(Runnable task) throws DisposedException {
			synchronized(privateLock) {
				if(executors.disposed) throw new DisposedException();
				return incompleteFutureSubmit(
					getThreadFactory(),
					getExecutorService(),
					wrap(task)
				);
			}
		}

		@Override
		public void runAll(Collection<Runnable> tasks) throws DisposedException, InterruptedException, ExecutionException {
			synchronized(privateLock) {
				if(executors.disposed) throw new DisposedException();
			}
			int size = tasks.size();
			if(size == 0) {
				// Nothing to do
			} else if(size == 1) {
				try {
					tasks.iterator().next().run();
				} catch(Exception e) {
					throw new ExecutionException(e);
				}
			} else {
				List<Runnable> taskList;
				if(tasks instanceof List) {
					taskList = (List<Runnable>)tasks;
				} else {
					taskList = new ArrayList<Runnable>(tasks);
				}
				List<Future<?>> futures = new ArrayList<Future<?>>(size - 1); // Last one ran by current thread
				for(int i=0; i<size; i++) {
					Runnable task = taskList.get(i);
					if(i < (size - 1)) {
						// Run concurrently
						futures.add(submit(task));
					} else {
						// Last one on current thread
						try {
							task.run();
						} catch(Exception e) {
							throw new ExecutionException(e);
						}
					}
				}
				// Wait for concurrent to finish
				for(int i=0; i<(size-1); i++) {
					futures.get(i).get();
				}
			}
		}

		@Override
		public Future<?> submit(Runnable task, long delay) throws DisposedException {
			synchronized(privateLock) {
				if(executors.disposed) throw new DisposedException();
				return incompleteFutureSubmit(
					getThreadFactory(),
					getExecutorService(),
					wrap(task),
					delay
				);
			}
		}
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Unbounded">
	private static class UnboundedExecutor extends ExecutorImpl {
		private static ExecutorService unboundedExecutorService;
		private static Thread unboundedShutdownHook;

		private static final PrefixThreadFactory unboundedThreadFactory = new PrefixThreadFactory(
			Executors.class.getName()+".unbounded-thread-",
			Thread.NORM_PRIORITY
		);

		private static void dispose() {
			assert Thread.holdsLock(privateLock);
			final ExecutorService ues = unboundedExecutorService;
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
				ThreadFactory tf = currentThreadFactory.get();
				if(tf != null && tf == unboundedThreadFactory) {
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
		PrefixThreadFactory getThreadFactory() {
			assert Thread.holdsLock(privateLock);
			return unboundedThreadFactory;
		}

		@Override
		protected ExecutorService getExecutorService() {
			assert Thread.holdsLock(privateLock);
			if(activeCount < 1) throw new IllegalStateException();
			if(unboundedExecutorService == null) {
				ExecutorService newExecutorService = java.util.concurrent.Executors.newCachedThreadPool(unboundedThreadFactory);
				Thread newShutdownHook = new ExecutorServiceShutdownHook(
					newExecutorService,
					unboundedThreadFactory.namePrefix + "shutdownHook"
				);
				// Only keep instances once shutdown hook properly registered
				try {
					Runtime.getRuntime().addShutdownHook(newShutdownHook);
				} catch(SecurityException e) {
					logger.log(Level.WARNING, null, e);
					newShutdownHook = null;
				}
				unboundedExecutorService = newExecutorService;
				unboundedShutdownHook = newShutdownHook;
			}
			return unboundedExecutorService;
		}

		/**
		 * Avoid deadlock: Maintain which perProcessor thread pool this task came from
		 */
		@Override
		protected <T> Callable<T> wrap(Callable<T> task) {
			return super.wrap(new ThreadLocalCallable<T>(task, PerProcessorExecutor.currentThreadPerProcessorIndex));
		}

		/**
		 * Avoid deadlock: Maintain which perProcessor thread pool this task came from
		 */
		@Override
		protected Runnable wrap(Runnable task) {
			return super.wrap(new ThreadLocalRunnable(task, PerProcessorExecutor.currentThreadPerProcessorIndex));
		}
	};

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
		private static final ThreadLocal<Integer> currentThreadPerProcessorIndex = new ThreadLocal<Integer>();

		private static final AutoGrowArrayList<ExecutorService> perProcessorExecutorServices = new AutoGrowArrayList<ExecutorService>();
		private static final AutoGrowArrayList<Thread> perProcessorShutdownHooks = new AutoGrowArrayList<Thread>();

		private static final List<PrefixThreadFactory> threadFactories = new AutoGrowArrayList<PrefixThreadFactory>();

		private static PrefixThreadFactory getThreadFactory(int index) {
			assert Thread.holdsLock(privateLock);
			PrefixThreadFactory perProcessorThreadFactory;
			if(index < threadFactories.size()) {
				perProcessorThreadFactory = threadFactories.get(index);
			} else {
				perProcessorThreadFactory = null;
			}
			if(perProcessorThreadFactory == null) {
				final Integer indexObj = index;
				if(logger.isLoggable(Level.FINEST)) logger.log(Level.FINEST, "new perProcessorThreadFactory: {0}", index);
				perProcessorThreadFactory = new PrefixThreadFactory(
					Executors.class.getName()+".perProcessor-" + index + "-thread-",
					Thread.NORM_PRIORITY
				) {
					@Override
					public Thread newThread(final Runnable target) {
						return super.newThread(
							new Runnable() {
								@Override
								public void run() {
									assert currentThreadPerProcessorIndex.get() == null;
									currentThreadPerProcessorIndex.set(indexObj);
									target.run();
								}
							}
						);
					}
				};
				threadFactories.set(index, perProcessorThreadFactory);
			}
			return perProcessorThreadFactory;
		}

		private static void dispose() {
			assert Thread.holdsLock(privateLock);
			// Going backwards, to clean up deepest depth tasks first, giving other tasks a chance to finish during cleanup
			for(int i = perProcessorExecutorServices.size()-1; i >= 0; --i) {
				final int index = i;
				final ExecutorService ppes = perProcessorExecutorServices.get(index);
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
					PrefixThreadFactory tf = currentThreadFactory.get();
					if(tf != null && tf == threadFactories.get(index)) {
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
		}

		private PerProcessorExecutor(Executors executors) {
			super(executors);
		}

		@Override
		PrefixThreadFactory getThreadFactory() {
			assert Thread.holdsLock(privateLock);
			int index;
			{
				Integer perProcessorIndex = currentThreadPerProcessorIndex.get();
				if(logger.isLoggable(Level.FINEST)) logger.log(Level.FINEST, "perProcessorIndex={0}", perProcessorIndex);
				index = (perProcessorIndex == null) ? 0 : (perProcessorIndex + 1);
				if(logger.isLoggable(Level.FINEST)) logger.log(Level.FINEST, "index={0}", index);
			}
			return getThreadFactory(index);
		}

		@Override
		protected ExecutorService getExecutorService() {
			assert Thread.holdsLock(privateLock);
			if(activeCount < 1) throw new IllegalStateException();
			int index;
			{
				Integer perProcessorIndex = currentThreadPerProcessorIndex.get();
				if(logger.isLoggable(Level.FINEST)) logger.log(Level.FINEST, "perProcessorIndex={0}", perProcessorIndex);
				index = (perProcessorIndex == null) ? 0 : (perProcessorIndex + 1);
				if(logger.isLoggable(Level.FINEST)) logger.log(Level.FINEST, "index={0}", index);
			}
			ExecutorService perProcessorExecutorService = index < perProcessorExecutorServices.size() ? perProcessorExecutorServices.get(index) : null;
			if(perProcessorExecutorService == null) {
				PrefixThreadFactory perProcessorThreadFactory = getThreadFactory(index);
				int numThreads = executors.preferredConcurrency;
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
					perProcessorExecutorService = java.util.concurrent.Executors.newFixedThreadPool(
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
	};

	private final PerProcessorExecutor perProcessor = new PerProcessorExecutor(this);

	/**
	 * <p>
	 * An executor service that will execute at most two tasks per processor.
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
	 * @see  #getPreferredConcurrency()  to determine how many threads may be allocated per executor.
	 */
	public Executor getPerProcessor() {
		return perProcessor;
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
		final List<ThreadFactoryFuture<?>> waitFutures;
		synchronized(privateLock) {
			if(!disposed) {
				disposed = true;
				if(activeCount <= 0) throw new AssertionError();
				--activeCount;
				if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "activeCount={0}", activeCount);

				if(activeCount == 0) {
					UnboundedExecutor.dispose();
					PerProcessorExecutor.dispose();
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
					waitFutures = new ArrayList<ThreadFactoryFuture<?>>(incompleteFutures.values());
					incompleteFutures.clear();
				}
			} else {
				// Already disposed, nothing to wait for
				waitFutures = null;
				incompleteFutures.clear();
			}
		}
		if(waitFutures != null) {
			PrefixThreadFactory tf = currentThreadFactory.get();
			final long waitUntil = System.nanoTime() + DISPOSE_WAIT_NANOS;
			// Wait for our incomplete tasks to complete.
			// This is done while not holding privateLock to avoid deadlock.
			List<ThreadFactoryFuture<?>> ownThreadFactoryWaitFutures = null;
			for(int i=0, size=waitFutures.size(); i<size; i++) {
				ThreadFactoryFuture<?> future = waitFutures.get(i);
				// Never wait for own thread (causes stall every time)
				if(tf != future.getThreadFactory()) {
					// Queue to call cancel at end, can't wait from this thread
					if(ownThreadFactoryWaitFutures == null) ownThreadFactoryWaitFutures = new ArrayList<ThreadFactoryFuture<?>>(size);
					ownThreadFactoryWaitFutures.add(future);
				} else {
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
			if(ownThreadFactoryWaitFutures != null) {
				final List<ThreadFactoryFuture<?>> waitOnOtherThreads = ownThreadFactoryWaitFutures;
				// Cancel all from our thread factory on a different thread to avoid deadlock
				new Thread(
					new Runnable() {
						@Override
						public void run() {
							for(int i=0, size=waitOnOtherThreads.size(); i<size; i++) {
								ThreadFactoryFuture<?> future = waitOnOtherThreads.get(i);
								long nanosRemaining = waitUntil - System.nanoTime();
								if(nanosRemaining >= 0) {
									if(logger.isLoggable(Level.FINE)) logger.log(
										Level.FINE,
										"Waiting on waitOnOtherThreads[{0}], {1} ns remaining",
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
									if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "No time left, canceling waitOnOtherThreads[{0}]", i);
									future.cancel(true);
								}
							}
						}
					}
				).start();
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
