/*
 * ao-concurrent - Concurrent programming utilities.
 * Copyright (C) 2014, 2015, 2019, 2020, 2021  AO Industries, Inc.
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
package com.aoindustries.concurrent;

import com.aoindustries.collections.AoCollections;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.NotImplementedException;

/**
 * Provides per-listener event queues, and fires off events concurrently across
 * listeners, but in-order per listener.
 */
public class ConcurrentListenerManager<L> implements AutoCloseable {

	private static final Logger logger = Logger.getLogger(ConcurrentListenerManager.class.getName());

	@FunctionalInterface
	public static interface Event<L> {
		/**
		 * Creates the Runnable that will call the event callback on the given listener.
		 */
		Runnable createCall(L listener);
	}

	private static class EventCall<L> {
		final Map<L, Boolean> unfinishedCalls;
		final Runnable call;
		EventCall(Map<L, Boolean> unfinishedCalls, Runnable call) {
			this.unfinishedCalls = unfinishedCalls;
			this.call = call;
		}
	}

	/**
	 * The object instance used to indicate that a listener is called synchronously.
	 */
	private final Queue<EventCall<L>> SYNC_DO_NOT_QUEUE = new Queue<EventCall<L>>() {

		private static final String MESSAGE = "This queue is a synchronous marker and none of its method should be called.";

		@Override
		public boolean add(EventCall<L> e) {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public boolean offer(EventCall<L> e) {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public EventCall<L> remove() {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public EventCall<L> poll() {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public EventCall<L> element() {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public EventCall<L> peek() {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public int size() {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public boolean isEmpty() {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public boolean contains(Object o) {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public Iterator<EventCall<L>> iterator() {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public Object[] toArray() {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public <T> T[] toArray(T[] a) {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public boolean remove(Object o) {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public boolean containsAll(Collection<?> c) {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public boolean addAll(Collection<? extends EventCall<L>> c) {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public boolean removeAll(Collection<?> c) {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public boolean retainAll(Collection<?> c) {
			throw new AssertionError(MESSAGE);
		}

		@Override
		public void clear() {
			throw new AssertionError(MESSAGE);
		}
	};

	/**
	 * A queue of events per listener.  When the queue is null, no executor is running for the listener.
	 * When the queue is non-null, even when empty, an executor is running for the listener.
	 */
	private final Map<L, Queue<EventCall<L>>> listeners = new IdentityHashMap<>();

	private final Executors executor = new Executors();

	public ConcurrentListenerManager() {
	}

	/**
	 * When no longer needed, close should be called to free resources.
	 */
	@Override
	public void close() {
		logger.log(Level.FINE, "Calling executor.close()");
		executor.close();
		logger.log(Level.FINE, "executor.close() finished");
	}

	/**
	 * Adds a listener.
	 *
	 * @param  synchronous  When true, listener will be called synchronously by the I/O thread;
	 *                      when false, listener will be called asynchronously by a separate thread.
	 *
	 * @throws IllegalStateException  If the listener has already been added
	 */
	public void addListener(L listener, boolean synchronous) throws IllegalStateException {
		synchronized(listeners) {
			if(listeners.containsKey(listener)) throw new IllegalStateException("listener already added");
			listeners.put(listener, synchronous ? SYNC_DO_NOT_QUEUE : null);
		}
	}

	/**
	 * Removes a listener.
	 *
	 * @return true if the listener was found
	 */
	public boolean removeListener(L listener) {
		synchronized(listeners) {
			if(!listeners.containsKey(listener)) {
				return false;
			} else {
				listeners.remove(listener);
				return true;
			}
		}
	}

	/**
	 * Enqueues a new event to all listener event queues.
	 * If the caller needs to wait until the event has been handled by each
	 * of the listeners, then call .get() on the returned Future.
	 */
	public Future<?> enqueueEvent(Event<? super L> event) {
		synchronized(listeners) {
			// The future is not finished until all individual calls have removed themselves from this map
			// and this map is empty.
			final Map<L, Boolean> unfinishedCalls = AoCollections.newIdentityHashMap(listeners.size());
			for(Map.Entry<L, Queue<EventCall<L>>> entry : listeners.entrySet()) {
				final L listener = entry.getKey();
				final Runnable call = event.createCall(listener);
				Queue<EventCall<L>> queue = entry.getValue();
				if(queue == SYNC_DO_NOT_QUEUE) {
					// Call synchronous listeners immediately
					try {
						call.run();
					} catch(ThreadDeath td) {
						throw td;
					} catch(Throwable t) {
						logger.log(Level.SEVERE, null, t);
					}
				} else {
					// Enqueue asynchronous calls
					boolean isFirst;
					if(queue == null) {
						queue = new LinkedList<>();
						entry.setValue(queue);
						isFirst = true;
					} else {
						isFirst = false;
					}
					unfinishedCalls.put(listener, Boolean.TRUE);
					queue.add(new EventCall<>(unfinishedCalls, call));
					if(isFirst) {
						// When the queue is first created, we submit the queue runner to the executor for queue processing
						// There is only one executor per queue, and on queue per listener
						executor.getUnbounded().submit(() -> {
							while(true) {
								// Invoke each of the events until the queue is empty
								EventCall<L> eventCall;
								synchronized(listeners) {
									Queue<EventCall<L>> queue1 = listeners.get(listener);
									if(queue1.isEmpty()) {
										// Remove the empty queue so a new executor will be submitted on next event
										listeners.remove(listener);
										break;
									} else {
										eventCall = queue1.remove();
									}
								}
								// Run the event without holding the listeners lock
								try {
									eventCall.call.run();
								} catch(ThreadDeath td) {
									throw td;
								} catch(Throwable t) {
									logger.log(Level.SEVERE, null, t);
								}
								// Remove this listener from unfinished calls
								synchronized(eventCall.unfinishedCalls) {
									Boolean removedValue = eventCall.unfinishedCalls.remove(listener);
									// Notify when the last call completes
									if(eventCall.unfinishedCalls.isEmpty()) {
										eventCall.unfinishedCalls.notify();
									}
									if(removedValue == null) throw new AssertionError();
								}
							}
						});
					}
				}
			}
			// This future will wait until unfinishedCalls is empty
			return new Future<Object>() {
				@Override
				public boolean cancel(boolean mayInterruptIfRunning) {
					// Not supported
					return false;
				}

				@Override
				public boolean isCancelled() {
					// Not supported
					return false;
				}

				@Override
				public boolean isDone() {
					synchronized(unfinishedCalls) {
						return unfinishedCalls.isEmpty();
					}
				}

				@Override
				public Object get() throws InterruptedException {
					synchronized(unfinishedCalls) {
						while(!unfinishedCalls.isEmpty()) {
							unfinishedCalls.wait();
						}
						return null;
					}
				}

				@Override
				public Object get(long timeout, TimeUnit unit) throws TimeoutException {
					throw new NotImplementedException("TODO");
				}
			};
		}
	}
}
