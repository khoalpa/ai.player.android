package com.aiplayer.android;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

final class ExecutorTasks {
    private ExecutorTasks() {}

    static <T> Task<T> submit(Executor executor, Callable<T> operation) {
        TaskCompletionSource<T> completion = new TaskCompletionSource<>();
        try {
            executor.execute(() -> {
                T result;
                try {
                    result = operation.call();
                } catch (Exception error) {
                    completion.setException(error);
                    return;
                } catch (Throwable error) {
                    completion.setException(new RuntimeException(error));
                    return;
                }
                completion.setResult(result);
            });
        } catch (RejectedExecutionException error) {
            completion.setException(error);
        }
        return completion.getTask();
    }
}
