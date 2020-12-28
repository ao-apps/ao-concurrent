/*
 * ao-concurrent - Concurrent programming utilities.
 * Copyright (C) 2013, 2015, 2016, 2019, 2020  AO Industries, Inc.
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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/**
 * Limits the concurrency to a resource.
 * When a second thread tries to access the same resource as a previous thread,
 * it will share the results that are obtained by the previous thread.
 */
public class ConcurrencyReducer<R> {

	@SuppressWarnings("PackageVisibleField")
	static class ResultsCache<R> {
		int threadCount;
		boolean finished;
		R result;
		Throwable throwable;
	}

	private final ResultsCache<R> resultsCache = new ResultsCache<>();

	/**
	 * <p>
	 * Executes a callable at most once.  If a callable is
	 * in the process of being executed by a different thread,
	 * the current thread will wait and use the
	 * results obtained by the other thread.
	 * </p>
	 * <p>
	 * Consider the following scenario:
	 * </p>
	 * <ol>
	 *   <li>Thread A invokes MySQL: "CHECK TABLE example FAST QUICK"</li>
	 *   <li>Thread B invokes MySQL: "CHECK TABLE example FAST QUICK" before Thread A has finished</li>
	 *   <li>Thread B wait for results determined by Thread A</li>
	 *   <li>Thread A completes, passes results to Thread B</li>
	 *   <li>Threads A and B both return the results obtained only by Thread A</li>
	 * </ol>
	 */
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
	public R executeSerialized(Callable<? extends R> callable) throws InterruptedException, ExecutionException {
		final boolean isFirstThread;
		synchronized(resultsCache) {
			isFirstThread = (resultsCache.threadCount == 0);
			if(resultsCache.threadCount == Integer.MAX_VALUE) throw new IllegalStateException("threadCount == Integer.MAX_VALUE");
			resultsCache.threadCount++;
			if(isFirstThread) resultsCache.finished = false;
		}
		try {
			R result;
			Throwable throwable;
			if(isFirstThread) {
				// Invoke outside resultsCache lock, so other threads can wait
				try {
					result = callable.call();
					throwable = null;
				} catch(Throwable t) {
					result = null;
					throwable = t;
				}
				synchronized(resultsCache) {
					assert !resultsCache.finished;
					resultsCache.result = result;
					resultsCache.throwable = throwable;
					resultsCache.finished = true;
					resultsCache.notifyAll();
				}
			} else {
				synchronized(resultsCache) {
					// Wait for results from the first thread, including any exception
					while(!resultsCache.finished) {
						resultsCache.wait();
					}
					assert resultsCache.finished;
					result = resultsCache.result;
					throwable = resultsCache.throwable;
				}
			}
			if(throwable != null) {
				if(isFirstThread && throwable instanceof ThreadDeath) {
					// Propagate directly back to first thread
					throw (ThreadDeath)throwable;
				}
				throw new ExecutionException(resultsCache.throwable);
			}
			return result;
		} finally {
			synchronized(resultsCache) {
				assert resultsCache.threadCount > 0;
				resultsCache.threadCount--;
				if(resultsCache.threadCount == 0) {
					resultsCache.finished = false;
					resultsCache.result = null;
					resultsCache.throwable = null;
				}
			}
		}
	}
}
