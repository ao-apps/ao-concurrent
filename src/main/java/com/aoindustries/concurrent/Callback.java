/*
 * ao-concurrent - Concurrent programming utilities.
 * Copyright (C) 2014, 2015, 2019, 2020  AO Industries, Inc.
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

/**
 * A callback that can be registered for asynchronous operation results.
 */
@FunctionalInterface
public interface Callback<R> {

	// TODO: Allow all Throwable, or make a generic parent interface CallbackE<R,E>, that this extends as <R,RuntimeException>
	void call(R result);
}
