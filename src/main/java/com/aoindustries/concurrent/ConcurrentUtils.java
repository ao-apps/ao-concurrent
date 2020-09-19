/*
 * ao-concurrent - Concurrent programming utilities.
 * Copyright (C) 2013, 2015, 2016, 2020  AO Industries, Inc.
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Concurrency utilities.
 */
final public class ConcurrentUtils {

	/**
	 * Make no instances.
	 */
	private ConcurrentUtils() {}

	/**
	 * Waits for all futures to complete, discarding any results.
	 * 
	 * Note: This method is cloned to IntegerRadixSort.java to avoid package dependency.
	 */
	public static void waitForAll(Iterable<? extends Future<?>> futures) throws InterruptedException, ExecutionException {
		for(Future<?> future : futures) {
			future.get();
		}
	}

	/**
	 * Gets all of the results of the futures, returning a modifiable list of the results.
	 */
	public static <E> List<E> getAll(Iterable<? extends Future<? extends E>> futures) throws InterruptedException, ExecutionException {
		int startSize = (futures instanceof Collection<?>) ? ((Collection<?>)futures).size() : 10;
		return getAll(futures, new ArrayList<>(startSize));
	}

	/**
	 * Gets all of the results of the futures into the provided collection.
	 *
	 * @return  the collection that was given
	 */
	public static <E,C extends Collection<E>> C getAll(Iterable<? extends Future<? extends E>> futures, C output) throws InterruptedException, ExecutionException {
		for(Future<? extends E> future : futures) {
			output.add(future.get());
		}
		return output;
	}

	/**
	 * Gets all of the results of the futures, returning a modifiable map of the results.
	 * The map will maintain the iteration order of the source.
	 */
	public static <K,V> Map<K,V> getAll(Map<? extends K,? extends Future<? extends V>> futures) throws InterruptedException, ExecutionException {
		return getAll(futures, AoCollections.newLinkedHashMap(futures.size()));
	}

	/**
	 * Gets all of the results of the futures into the provided map.
	 *
	 * @return  the map that was given
	 */
	public static <K,V,M extends Map<K,V>> M getAll(Map<? extends K,? extends Future<? extends V>> futures, M output) throws InterruptedException, ExecutionException {
		for(Map.Entry<? extends K,? extends Future<? extends V>> entry : futures.entrySet()) {
			output.put(entry.getKey(), entry.getValue().get());
		}
		return output;
	}
}
