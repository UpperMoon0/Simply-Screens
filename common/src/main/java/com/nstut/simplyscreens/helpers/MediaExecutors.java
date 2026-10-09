package com.nstut.simplyscreens.helpers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class MediaExecutors {
    private MediaExecutors() {
    }

    public static ExecutorService newDaemonFixedThreadPool(int threads, String threadName) {
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    public static ExecutorService newDaemonBoundedThreadPool(int threads, int queueCapacity, String threadName) {
        if (threads <= 0 || queueCapacity <= 0) throw new IllegalArgumentException("threads and queueCapacity must be positive");
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

}
