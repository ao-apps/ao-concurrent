/*
 * ao-concurrent - Concurrent programming utilities.
 * Copyright (C) 2013, 2015, 2016, 2019, 2020, 2021  AO Industries, Inc.
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

/**
 * Limits the concurrency to a resource identified by any arbitrary key object.
 * When a second thread tries to access the same resource as a previous thread,
 * it will share the results that are obtained by the previous thread.
 *
 * @deprecated  Please use either {@link KeyedConcurrencyReducer} or {@link ConcurrencyReducer}.
 */
@Deprecated
public final class ConcurrencyLimiter<K, R> extends KeyedConcurrencyReducer<K, R> {

}
