/*
 * ao-concurrent - Concurrent programming utilities.
 * Copyright (C) 2016, 2020, 2021, 2022  AO Industries, Inc.
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

	@Override
	void execute(Runnable command);

	/**
	 * Submits to the executor.
	 *
	 * @see  Executors#wrap(java.util.concurrent.Callable)
	 *
	 * @exception  IllegalStateException  if already closed.
	 */
	<T> Future<T> submit(Callable<? extends T> task) throws IllegalStateException;

	/**
	 * Calls all of the tasks concurrently, waiting for them to all complete.
	 * If there is only one task, it is called on the current thread.
	 * Rather than just have the current thread waiting, the last task is called by the current thread.
	 * 
	 * @param  tasks  Only iterated once in this implementation
	 * 
	 * @see  Executors#wrap(java.util.concurrent.Callable)
	 *
	 * @exception  IllegalStateException  if already closed.
	 */
	<T> List<T> callAll(Collection<? extends Callable<? extends T>> tasks) throws IllegalStateException, InterruptedException, ExecutionException;

	/**
	 * Submits to the executor after the provided delay.
	 * 
	 * @see  Executors#wrap(java.util.concurrent.Callable)
	 *
	 * @exception  IllegalStateException  if already closed.
	 */
	<T> Future<T> submit(Callable<? extends T> task, long delay) throws IllegalStateException;

	/**
	 * Submits to the executor,
	 * returning the provided value on success.
	 *
	 * @see  Executors#wrap(java.lang.Runnable)
	 *
	 * @exception  IllegalStateException  if already closed.
	 */
	<T> Future<T> submit(Runnable task, T result) throws IllegalStateException;

	/**
	 * Submits to the executor.
	 *
	 * @see  Executors#wrap(java.lang.Runnable)
	 *
	 * @exception  IllegalStateException  if already closed.
	 */
	Future<?> submit(Runnable task) throws IllegalStateException;

	/**
	 * Runs all of the tasks concurrently, waiting for them to all complete.
	 * If there is only one task, it is ran on the current thread.
	 * Rather than just have the current thread waiting, the last task is ran by the current thread.
	 * 
	 * @param  tasks  Only iterated once in this implementation
	 * 
	 * @see  Executors#wrap(java.lang.Runnable)
	 *
	 * @exception  IllegalStateException  if already closed.
	 */
	void runAll(Collection<? extends Runnable> tasks) throws IllegalStateException, InterruptedException, ExecutionException;

	/**
	 * Submits to the executor after the provided delay,
	 * returning the provided value on success.
	 * 
	 * @see  Executors#wrap(java.lang.Runnable)
	 *
	 * @exception  IllegalStateException  if already closed.
	 */
	<T> Future<T> submit(Runnable task, T result, long delay) throws IllegalStateException;

	/**
	 * Submits to the executor after the provided delay.
	 * 
	 * @see  Executors#wrap(java.lang.Runnable)
	 *
	 * @exception  IllegalStateException  if already closed.
	 */
	Future<?> submit(Runnable task, long delay) throws IllegalStateException;
}
