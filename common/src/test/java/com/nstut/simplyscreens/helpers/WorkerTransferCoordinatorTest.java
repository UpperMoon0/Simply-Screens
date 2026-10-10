package com.nstut.simplyscreens.helpers;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;

class WorkerTransferCoordinatorTest {
    @Test void queuedWorkersHoldTheirKeyAndCapacityUntilCompletion() {
        WorkerTransferCoordinator<String> workers = new WorkerTransferCoordinator<>(1);
        Queue<Runnable> queue = new ArrayDeque<>();
        assertTrue(workers.tryStart("one", queue::add, () -> {}));
        assertFalse(workers.tryStart("one", queue::add, () -> fail("duplicate")));
        assertFalse(workers.tryStart("two", queue::add, () -> fail("over capacity")));
        queue.remove().run();
        assertTrue(workers.tryStart("two", queue::add, () -> {}));
        queue.remove().run();
        assertTrue(workers.tryStart("one", Runnable::run, () -> {}));
    }

    @Test void rejectedSubmissionAndFailedWorkerReleaseTheirLease() {
        WorkerTransferCoordinator<String> workers = new WorkerTransferCoordinator<>(1);
        assertThrows(RejectedExecutionException.class, () -> workers.tryStart("one", task -> {
            throw new RejectedExecutionException();
        }, () -> {}));
        assertThrows(IllegalStateException.class, () -> workers.tryStart("one", Runnable::run, () -> {
            throw new IllegalStateException();
        }));
        assertTrue(workers.tryStart("one", Runnable::run, () -> {}));
    }

    @Test void lateSubmissionFailureCannotReleaseANewerWorkersLease() {
        WorkerTransferCoordinator<String> workers = new WorkerTransferCoordinator<>(1);
        Queue<Runnable> queue = new ArrayDeque<>();
        assertThrows(RejectedExecutionException.class, () -> workers.tryStart("one", task -> {
            task.run();
            assertTrue(workers.tryStart("one", queue::add, () -> {}));
            throw new RejectedExecutionException();
        }, () -> {}));
        assertFalse(workers.tryStart("one", queue::add, () -> fail("new lease lost")));
        queue.remove().run();
        assertTrue(workers.tryStart("one", Runnable::run, () -> {}));
    }
}
