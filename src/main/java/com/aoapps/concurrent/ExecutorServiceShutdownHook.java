/*
 * ao-concurrent - Concurrent programming utilities.
 * Copyright (C) 2011, 2012, 2014, 2015, 2016, 2020, 2021, 2022  AO Industries, Inc.
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The threads of executor services can cause a JVM to keep running if they are not
 * daemon threads.  Additionally, daemon threads can be interrupted by an unclean
 * shutdown if no shutdown hook is provided.  By using this implementation of a
 * shutdown hook you get the best of both - threads stop at the correct time, not
 * too soon and not too late.
 *
 * @author  AO Industries, Inc.
 */
// TODO: 5.0.0: Make package-private
public class ExecutorServiceShutdownHook extends Thread {

  private static final Logger logger = Logger.getLogger(ExecutorServiceShutdownHook.class.getName());

  /**
   * The default thread name.
   */
  private static final String DEFAULT_THREAD_NAME = ExecutorServiceShutdownHook.class.getName();

  /**
   * The default amount of time to wait before issuing a forceful shutdown.
   */
  private static final long DEFAULT_SHUTDOWN_TIMEOUT = 5; // Was 60;
  private static final TimeUnit DEFAULT_SHUTDOWN_TIMEUNIT = TimeUnit.SECONDS;

  private final ExecutorService executorService;
  private final long shutdownTimeout;
  private final TimeUnit shutdownTimeoutUnit;

  /**
   * Creates a new shutdown hook.
   */
  public ExecutorServiceShutdownHook(ExecutorService executorService) {
    this(executorService, DEFAULT_THREAD_NAME, DEFAULT_SHUTDOWN_TIMEOUT, DEFAULT_SHUTDOWN_TIMEUNIT);
  }

  /**
   * Creates a new shutdown hook.
   */
  public ExecutorServiceShutdownHook(ExecutorService executorService, long shutdownTimeout, TimeUnit shutdownTimeoutUnit) {
    this(executorService, DEFAULT_THREAD_NAME, shutdownTimeout, shutdownTimeoutUnit);
  }

  /**
   * Creates a new shutdown hook.
   */
  public ExecutorServiceShutdownHook(ExecutorService executorService, String threadName) {
    this(executorService, threadName, DEFAULT_SHUTDOWN_TIMEOUT, DEFAULT_SHUTDOWN_TIMEUNIT);
  }

  /**
   * Creates a new shutdown hook.
   */
  public ExecutorServiceShutdownHook(ExecutorService executorService, String threadName, long shutdownTimeout, TimeUnit shutdownTimeoutUnit) {
    super(threadName);
    this.executorService = executorService;
    this.shutdownTimeout = shutdownTimeout;
    this.shutdownTimeoutUnit = shutdownTimeoutUnit;
  }

  @Override
  public void run() {
    try {
      executorService.shutdown();
    } catch (SecurityException e) {
      logger.log(Level.WARNING, null, e);
    }
    try {
      if (!executorService.awaitTermination(shutdownTimeout, shutdownTimeoutUnit)) {
        try {
          executorService.shutdownNow();
        } catch (SecurityException e) {
          logger.log(Level.WARNING, null, e);
        }
      }
    } catch (InterruptedException e) {
      try {
        // Force shutdown
        logger.log(Level.SEVERE, null, e);
        try {
          executorService.shutdownNow();
        } catch (SecurityException e2) {
          logger.log(Level.WARNING, null, e2);
        }
      } finally {
        // Restore the interrupted status
        Thread.currentThread().interrupt();
      }
    }
  }
}
