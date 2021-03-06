/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.scheduler;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.neo4j.scheduler.Group;
import org.neo4j.scheduler.JobHandle;
import org.neo4j.scheduler.SchedulerThreadFactory;
import org.neo4j.scheduler.SchedulerThreadFactoryFactory;

final class ThreadPool
{
    private final SchedulerThreadFactory threadFactory;
    private final ExecutorService executor;
    private final ConcurrentHashMap<Object,Future<?>> registry;
    private InterruptedException shutdownInterrupted;

    static class ThreadPoolParameters
    {
        volatile int desiredParallelism;
        volatile SchedulerThreadFactoryFactory providedThreadFactory = GroupedDaemonThreadFactory::new;
    }

    ThreadPool( Group group, ThreadGroup parentThreadGroup, ThreadPoolParameters parameters )
    {
        threadFactory = parameters.providedThreadFactory.newSchedulerThreadFactory( group, parentThreadGroup );
        executor = group.buildExecutorService( threadFactory, parameters.desiredParallelism );
        registry = new ConcurrentHashMap<>();
    }

    ThreadFactory getThreadFactory()
    {
        return threadFactory;
    }

    public ExecutorService getExecutorService()
    {
        return executor;
    }

    public <T> JobHandle<T> submit( Callable<T> job )
    {
        Object registryKey = new Object();
        Callable<T> registeredJob = () ->
        {
            try
            {
                return job.call();
            }
            finally
            {
                registry.remove( registryKey );
            }
        };
        Future<T> future = executor.submit( registeredJob );
        registry.put( registryKey, future );
        return new PooledJobHandle<>( future, registryKey, registry );
    }

    public JobHandle<?> submit( Runnable job )
    {
        return submit( asCallable( job ) );
    }

    private static Callable<?> asCallable( Runnable job )
    {
        return () -> {
            job.run();
            return null;
        };
    }

    int activeThreadCount()
    {
        return threadFactory.getThreadGroup().activeCount();
    }

    Stream<Thread> activeThreads()
    {
        ThreadGroup threadGroup = threadFactory.getThreadGroup();
        int activeCountEstimate = threadGroup.activeCount();
        int activeCountFudge = Math.max( (int) Math.sqrt( activeCountEstimate ), 10 );
        Thread[] snapshot = new Thread[activeCountEstimate + activeCountFudge];
        threadGroup.enumerate( snapshot );
        return Arrays.stream( snapshot ).filter( Objects::nonNull );
    }

    void cancelAllJobs()
    {
        registry.values().removeIf( future ->
        {
            future.cancel( true );
            return true;
        } );
    }

    void shutDown()
    {
        executor.shutdown();
        try
        {
            executor.awaitTermination( 30, TimeUnit.SECONDS );
        }
        catch ( InterruptedException e )
        {
            shutdownInterrupted = e;
        }
    }

    InterruptedException getShutdownException()
    {
        return shutdownInterrupted;
    }
}
