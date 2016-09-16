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
import com.aoindustries.util.Sequence;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides a central executor service for use by any number of projects.
 * These executors use daemon threads and will not keep the JVM alive.
 * The executors are automatically shutdown using shutdown hooks.  The
 * executors are also immediately shutdown when the last instance is disposed.
 *
 * Also allows for delayed execution of tasks using an internal Timer.
 */
final public class ExecutorService implements Disposable {

	private static final Logger logger = Logger.getLogger(ExecutorService.class.getName());

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
	 * Keeps track of which threads are running from the per-processor executor.
	 * TRUE if from per-processor, FALSE if from unbounded, or null a thread is not from this ExecutorService.
	 */
	private static final ThreadLocal<Boolean> isPerProcessor = new ThreadLocal<Boolean>();

	/**
	 * Persist threads are named with these prefixes.
	 */
	private static final String
		UNBOUNDED_PREFIX = ExecutorService.class.getName()+".unboundedExecutorService-thread-",
		PER_PROCESSOR_PREFIX = ExecutorService.class.getName()+".perProcessorExecutorService-thread-"
	;

	/*
	 * The thread factories are created once so each thread gets a unique
	 * identifier independent of creation and destruction of executors.
	 */
	static class PrefixThreadFactory implements ThreadFactory {

		// The thread group management was not compatible with an Applet environment
		// final ThreadGroup group;
		final Sequence threadNumber = new AtomicSequence();
		final String namePrefix;
		final int priority;

		PrefixThreadFactory(String namePrefix, int priority) {
			SecurityManager s = System.getSecurityManager();
			//this.group = (s != null)? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
			this.namePrefix = namePrefix;
			this.priority = priority;
		}

		@Override
		public Thread newThread(Runnable target) {
			Thread t = new Thread(/*group, */target, namePrefix + threadNumber.getNextSequenceValue());
			t.setPriority(priority);
			t.setDaemon(DAEMON_THREADS);
			return t;
		}
	}

	private static final ThreadFactory unboundedThreadFactory = new PrefixThreadFactory(UNBOUNDED_PREFIX, Thread.NORM_PRIORITY) {
		@Override
		public Thread newThread(final Runnable target) {
			return super.newThread(
				new Runnable() {
					@Override
					public void run() {
						isPerProcessor.set(Boolean.FALSE);
						target.run();
					}
				}
			);
		}
	};

	private static final ThreadFactory perProcessorThreadFactory = new PrefixThreadFactory(PER_PROCESSOR_PREFIX, Thread.NORM_PRIORITY) {
		@Override
		public Thread newThread(final Runnable target) {
			if(isPerProcessor.get()!=null) throw new AssertionError(); // If from either executor, request should be redirected to unbounded executor.
			return super.newThread(
				new Runnable() {
					@Override
					public void run() {
						isPerProcessor.set(Boolean.TRUE);
						target.run();
					}
				}
			);
		}
	};

	private static final Object privateLock = new Object();

	/**
	 * The number of active executors is tracked, will shutdown when gets to zero.
	 */
	private static int activeCount = 0;

	private static java.util.concurrent.ExecutorService unboundedExecutorService;
	private static Thread unboundedShutdownHook;

	private static java.util.concurrent.ExecutorService perProcessorExecutorService;
	private static Thread perProcessorShutdownHook;

	private static Timer timer;

	/**
	 * <p>
	 * Gets a new instance of the executor service.  <code>dispose()</code> must be called
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
	public static ExecutorService newInstance() {
		synchronized(privateLock) {
			if(activeCount<0) throw new AssertionError();
			if(activeCount==Integer.MAX_VALUE) throw new IllegalStateException();
			// Allocate before increment just in case of OutOfMemoryError
			ExecutorService newInstance = new ExecutorService();
			activeCount++;
			if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "activeCount={0}", activeCount);
			return newInstance;
		}
	}

	/**
	 * Gets the unbounded concurrency executor service.  activeCount must be
	 * greater than zero.
	 *
	 * Must be holding privateLock.
	 */
	private static java.util.concurrent.ExecutorService getUnboundedExecutorService() {
		assert Thread.holdsLock(privateLock);
		if(activeCount<1) throw new IllegalStateException();
		if(unboundedExecutorService==null) {
			java.util.concurrent.ExecutorService newExecutorService = Executors.newCachedThreadPool(unboundedThreadFactory);
			Thread newShutdownHook = new ExecutorServiceShutdownHook(
				newExecutorService,
				UNBOUNDED_PREFIX+"shutdownHook"
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
	 * Resolves the correct executor to use for a per-processor request.
	 * If current thread is from either the unbounded or per-processor, will
	 * return unbounded.  Otherwise, will return per-processor.
	 *
	 * Must be holding privateLock.
	 */
	private static java.util.concurrent.ExecutorService getPerProcessorExecutorService() {
		assert Thread.holdsLock(privateLock);
		if(isPerProcessor.get()!=null) {
			if(logger.isLoggable(Level.FINE)) logger.fine("Using unbounded executor instead of per-processor executor to avoid potential deadlock.");
			return getUnboundedExecutorService();
		} else {
			if(logger.isLoggable(Level.FINE)) logger.fine("Using per-processor executor.");
			if(activeCount<1) throw new IllegalStateException();
			if(perProcessorExecutorService==null) {
				java.util.concurrent.ExecutorService newExecutorService = Executors.newFixedThreadPool(
					RuntimeUtils.getAvailableProcessors() * THREADS_PER_PROCESSOR,
					perProcessorThreadFactory
				);
				Thread newShutdownHook = new ExecutorServiceShutdownHook(
					newExecutorService,
					PER_PROCESSOR_PREFIX+"shutdownHook"
				);
				// Only keep instances once shutdown hook properly registered
				try {
					Runtime.getRuntime().addShutdownHook(newShutdownHook);
				} catch(SecurityException e) {
					logger.log(Level.WARNING, null, e);
					newShutdownHook = null;
				}
				perProcessorExecutorService = newExecutorService;
				perProcessorShutdownHook = newShutdownHook;
			}
			return perProcessorExecutorService;
		}
	}

	/**
	 * Gets the timer.  activeCount must be greater than zero.
	 *
	 * Must be holding privateLock.
	 */
	private static Timer getTimer() {
		assert Thread.holdsLock(privateLock);
		if(activeCount<1) throw new IllegalStateException();
		if(timer==null) timer = new Timer(DAEMON_THREADS);
		return timer;
	}

	/**
	 * Set to true when dispose called.
	 */
	private boolean disposed = false;

	/**
	 * @see  #newInstance()
	 */
	private ExecutorService() {
	}

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

		final Long incompleteFutureId;
		protected final Object incompleteLock = new Object();
		boolean canceled = false;
		Future<V> future; // Only available once submitted

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
			return f==null ? canceled : f.isDone();
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
					if(nanosRemaining<0) throw new TimeoutException();
					incompleteLock.wait(nanosRemaining / 1000000, (int)(nanosRemaining % 1000000));
				}
				f = future;
			}
			// Wait until completed
			return f.get(waitUntil - System.nanoTime(), TimeUnit.NANOSECONDS);
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

	// <editor-fold defaultstate="collapsed" desc="Unbounded">
	/**
	 * Submits to an unbounded executor service.
	 * This is most appropriate for I/O bound tasks, especially higher latency
	 * I/O like wide area networks.
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	public <T> Future<T> submitUnbounded(Callable<T> task) throws DisposedException {
		synchronized(privateLock) {
			if(disposed) throw new DisposedException();
			return submit(getUnboundedExecutorService(), task);
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
			return submit(getUnboundedExecutorService(), task, delay);
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
			return submit(getUnboundedExecutorService(), task);
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
			return submit(getUnboundedExecutorService(), task, delay);
		}
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Per-processor">
	/**
	 * Submits to an executor service that will execute at most two tasks per processor.
	 * This should be used for CPU-bound tasks that generally operate non-blocking.
	 * If a thread blocks or deadlocks, it can starve the system entirely - use this
	 * cautiously.
	 *
	 * If a task is submitted by a thread that is already part of the per-processor executor,
	 * the task will be executed on the unbounded executor to avoid potential deadlock.
	 *
	 * To avoid the potential deadlock caused by the loop caller -> per-processor -> unbounded -> per-processor (deadlock),
	 * any request from the unbounded executor to the per-processor executor will also
	 * be redirected to the unbounded.  Thus, the system has a tendency toward unbounded
	 * operation in complex setups.  The per-processor executor is most effectively applied
	 * at the highest possible point of task distribution.
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	public <T> Future<T> submitPerProcessor(final Callable<T> task) throws DisposedException {
		synchronized(privateLock) {
			if(disposed) throw new DisposedException();
			return submit(getPerProcessorExecutorService(), task);
		}
	}

	/**
	 * Submits to an executor service that will execute at most two tasks per processor.
	 * This should be used for CPU-bound tasks that generally operate non-blocking.
	 * If a thread blocks or deadlocks, it can starve the system entirely - use this
	 * cautiously.
	 *
	 * If a task is submitted by a thread that is already part of the per-processor executor,
	 * the task will be executed on the unbounded executor to avoid potential deadlock.
	 *
	 * To avoid the potential deadlock caused by the loop caller -> per-processor -> unbounded -> per-processor (deadlock),
	 * any request from the unbounded executor to the per-processor executor will also
	 * be redirected to the unbounded.  Thus, the system has a tendency toward unbounded
	 * operation in complex setups.  The per-processor executor is most effectively applied
	 * at the highest possible point of task distribution.
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	public Future<?> submitPerProcessor(final Runnable task) throws DisposedException {
		synchronized(privateLock) {
			if(disposed) throw new DisposedException();
			return submit(getPerProcessorExecutorService(), task);
		}
	}

	/**
	 * Submits to an executor service that will execute at most two tasks per processor after the provided delay.
	 * This should be used for CPU-bound tasks that generally operate non-blocking.
	 * If a thread blocks or deadlocks, it can starve the system entirely - use this
	 * cautiously.
	 *
	 * If a task is submitted by a thread that is already part of the per-processor executor,
	 * the task will be executed on the unbounded executor to avoid potential deadlock.
	 *
	 * To avoid the potential deadlock caused by the loop caller -> per-processor -> unbounded -> per-processor (deadlock),
	 * any request from the unbounded executor to the per-processor executor will also
	 * be redirected to the unbounded.  Thus, the system has a tendency toward unbounded
	 * operation in complex setups.  The per-processor executor is most effectively applied
	 * at the highest possible point of task distribution.
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	public Future<?> submitPerProcessor(Runnable task, long delay) throws DisposedException {
		synchronized(privateLock) {
			if(disposed) throw new DisposedException();
			return submit(getPerProcessorExecutorService(), task, delay);
		}
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="Dispose">
	/**
	 * Disposes of this executor service instance.  Once disposed, no additional
	 * tasks may be submitted.
	 *
	 * If this is the last active executor, the underlying threads will also be shutdown.
	 * This shutdown may wait up to 200 milliseconds for clean termination of all threads.
	 *
	 * If already disposed, no action will be taken and no exception thrown.
	 */
	@Override
	public void dispose() {
		final List<Future<?>> waitFutures;
		synchronized(privateLock) {
			if(!disposed) {
				disposed = true;
				if(activeCount<=0) throw new AssertionError();
				--activeCount;
				if(logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "activeCount={0}", activeCount);

				if(activeCount==0) {
					final java.util.concurrent.ExecutorService ues = unboundedExecutorService;
					if(ues!=null) {
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
									if(ues.awaitTermination(DISPOSE_WAIT_NANOS, TimeUnit.NANOSECONDS)) {
										if(ush!=null) {
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
						if(isPerProcessor.get()!=null) {
							new Thread(uesShutdown).start();
						} else {
							// OK to use current thread directly
							uesShutdown.run();
						}
					}
					final java.util.concurrent.ExecutorService ppes = perProcessorExecutorService;
					if(ppes!=null) {
						final Thread ppsh = perProcessorShutdownHook;
						Runnable ppesShutdown = new Runnable() {
							@Override
							public void run() {
								try {
									ppes.shutdown();
								} catch(SecurityException e) {
									logger.log(Level.WARNING, null, e);
								}
								try {
									if(ppes.awaitTermination(DISPOSE_WAIT_NANOS, TimeUnit.NANOSECONDS)) {
										if(ppsh!=null) {
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
						perProcessorExecutorService = null;
						perProcessorShutdownHook = null;
						// Never wait for own thread (causes stall every time)
						if(isPerProcessor.get()!=null) {
							new Thread(ppesShutdown).start();
						} else {
							// OK to use current thread directly
							ppesShutdown.run();
						}
					}
					if(timer!=null) {
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
		if(
			waitFutures!=null
			// Never wait for own thread (causes stall every time)
			&& isPerProcessor.get()==null
		) {
			final long waitUntil = System.nanoTime() + DISPOSE_WAIT_NANOS;
			// Wait for our incomplete tasks to complete.
			// This is done while not holding privateLock to avoid deadlock.
			for(Future<?> future : waitFutures) {
				long nanosRemaining = waitUntil - System.nanoTime();
				if(nanosRemaining>=0) {
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
					future.cancel(true);
				}
			}
		}
	}

	/**
	 * Don't rely on the finalizer - this is just in case something is way off
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
