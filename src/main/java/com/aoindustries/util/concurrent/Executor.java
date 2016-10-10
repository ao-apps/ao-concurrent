/*
 * ao-concurrent - Concurrent programming utilities.
 * Copyright (C) 2016  AO Industries, Inc.
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

import com.aoindustries.lang.DisposedException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * @see  Executors#getUnbounded()
 * @see  Executors#getPerProcessor()
 */
public interface Executor extends java.util.concurrent.Executor {

	// Java 1.8: default interface method
	@Override
	void execute(Runnable command);
	//{
	//	submit(command);
	//}

	/**
	 * Submits to the executor.
	 *
	 * @see  Executors#wrap(java.util.concurrent.Callable)
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	<T> Future<T> submit(Callable<? extends T> task) throws DisposedException;

	/**
	 * Calls all of the tasks concurrently, waiting for them to all complete.
	 * If there is only one task, it is called on the current thread.
	 * Rather than just have the current thread waiting, the last task is called by the current thread.
	 * 
	 * @param  tasks  Only iterated once in this implementation
	 * 
	 * @see  Executors#wrap(java.util.concurrent.Callable)
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	// Java 1.8: default interface method
	<T> List<T> callAll(Collection<? extends Callable<? extends T>> tasks) throws DisposedException, InterruptedException, ExecutionException;

	/**
	 * Submits to the executor after the provided delay.
	 * 
	 * @see  Executors#wrap(java.util.concurrent.Callable)
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	<T> Future<T> submit(Callable<? extends T> task, long delay) throws DisposedException;

	/**
	 * Submits to the executor,
	 * returning the provided value on success.
	 *
	 * @see  Executors#wrap(java.lang.Runnable)
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	<T> Future<T> submit(Runnable task, T result) throws DisposedException;

	/**
	 * Submits to the executor.
	 *
	 * @see  Executors#wrap(java.lang.Runnable)
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	Future<?> submit(Runnable task) throws DisposedException;

	/**
	 * Runs all of the tasks concurrently, waiting for them to all complete.
	 * If there is only one task, it is ran on the current thread.
	 * Rather than just have the current thread waiting, the last task is ran by the current thread.
	 * 
	 * @param  tasks  Only iterated once in this implementation
	 * 
	 * @see  Executors#wrap(java.lang.Runnable)
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	// Java 1.8: default interface method
	void runAll(Collection<? extends Runnable> tasks) throws DisposedException, InterruptedException, ExecutionException;

	/**
	 * Submits to the executor after the provided delay,
	 * returning the provided value on success.
	 * 
	 * @see  Executors#wrap(java.lang.Runnable)
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	<T> Future<T> submit(Runnable task, T result, long delay) throws DisposedException;

	/**
	 * Submits to the executor after the provided delay.
	 * 
	 * @see  Executors#wrap(java.lang.Runnable)
	 *
	 * @exception  DisposedException  if already disposed.
	 */
	Future<?> submit(Runnable task, long delay) throws DisposedException;
}
