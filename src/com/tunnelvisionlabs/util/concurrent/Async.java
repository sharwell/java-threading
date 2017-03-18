// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

public enum Async {
	;

	private static final ScheduledThreadPoolExecutor DELAY_SCHEDULER = new ScheduledThreadPoolExecutor(1);

	@NotNull
	public static <T> CompletableFuture<T> awaitAsync(@NotNull CompletableFuture<? extends T> awaiter) {
		return awaitAsync(awaiter, AsyncFunctions.identity(), true);
	}

	@NotNull
	public static <T> CompletableFuture<T> awaitAsync(@NotNull CompletableFuture<? extends T> awaiter, boolean continueOnCapturedContext) {
		return awaitAsync(awaiter, AsyncFunctions.identity(), continueOnCapturedContext);
	}

	@NotNull
	public static <T, U> CompletableFuture<U> awaitAsync(@NotNull CompletableFuture<? extends T> awaiter, @NotNull Function<? super T, ? extends CompletableFuture<U>> continuation) {
		return awaitAsync(awaiter, continuation, true);
	}

	@NotNull
	public static <T, U> CompletableFuture<U> awaitAsync(@NotNull CompletableFuture<? extends T> awaiter, @NotNull Function<? super T, ? extends CompletableFuture<U>> continuation, boolean continueOnCapturedContext) {
		if (awaiter.isDone()) {
			return awaiter.thenCompose(continuation);
		}

		SynchronizationContext syncContext = continueOnCapturedContext ? SynchronizationContext.getCurrent() : null;
		if (syncContext != null) {
			throw new UnsupportedOperationException("Not implemented");
		}

		final Function<? super T, ? extends CompletableFuture<U>> flowContinuation = ExecutionContext.wrap(continuation);
		return awaiter.thenComposeAsync(result -> flowContinuation.apply(result));
	}

	@NotNull
	public static <U> CompletableFuture<U> awaitAsync(@NotNull CompletableFuture<?> awaiter, @NotNull Supplier<? extends CompletableFuture<U>> continuation) {
		return awaitAsync(awaiter, continuation, true);
	}

	@NotNull
	public static <U> CompletableFuture<U> awaitAsync(@NotNull CompletableFuture<?> awaiter, @NotNull Supplier<? extends CompletableFuture<U>> continuation, boolean continueOnCapturedContext) {
		if (awaiter.isDone()) {
			return awaiter.thenCompose(result -> continuation.get());
		}

		SynchronizationContext syncContext = continueOnCapturedContext ? SynchronizationContext.getCurrent() : null;
		if (syncContext != null) {
			throw new UnsupportedOperationException("Not implemented");
		}

		final Supplier<? extends CompletableFuture<U>> flowContinuation = ExecutionContext.wrap(continuation);
		return awaiter.thenComposeAsync(result -> flowContinuation.get());
	}

	@NotNull
	public static <T> CompletableFuture<T> awaitAsync(@NotNull Executor executor) {
		return awaitAsync(executor, AsyncFunctions.identity(), true);
	}

	@NotNull
	public static <T> CompletableFuture<T> awaitAsync(@NotNull Executor executor, boolean continueOnCapturedContext) {
		return awaitAsync(executor, AsyncFunctions.identity(), continueOnCapturedContext);
	}

	@NotNull
	public static <T, U> CompletableFuture<U> awaitAsync(@NotNull Executor executor, @NotNull Function<? super T, ? extends CompletableFuture<? extends U>> continuation) {
		return awaitAsync(executor, continuation, true);
	}

	@NotNull
	public static <T, U> CompletableFuture<U> awaitAsync(@NotNull Executor executor, @NotNull Function<? super T, ? extends CompletableFuture<? extends U>> continuation, boolean continueOnCapturedContext) {
		throw new UnsupportedOperationException("Not implemented");
	}

	@NotNull
	public static CompletableFuture<Void> delayAsync(long time, @NotNull TimeUnit unit) {
		CompletableFuture<Void> result = new CompletableFuture<>();
		ScheduledFuture<?> scheduled = DELAY_SCHEDULER.schedule(
			() -> ForkJoinPool.commonPool().execute(() -> result.complete(null)),
			time,
			unit);

		// Unschedule if cancelled
		result.whenComplete((ignored, exception) -> {
			if (result.isCancelled()) {
				scheduled.cancel(true);
			}
		});

		return result;
	}

	@NotNull
	public static <T> CompletableFuture<T> finallyAsync(@NotNull CompletableFuture<T> future, @NotNull Runnable runnable) {
		// When both future and runnable throw an exception, the semantics of a finally block give precedence to the
		// exception thrown by the finally block. However, the implementation of CompletableFuture.whenComplete gives
		// precedence to the future.
		return future
			.handle((result, exception) -> {
				runnable.run();
				return future;
			})
			.thenCompose(Functions.identity());
	}

	@NotNull
	public static CompletableFuture<Void> whileAsync(@NotNull Supplier<? extends Boolean> predicate, @NotNull Supplier<? extends CompletableFuture<?>> body) {
		if (!predicate.get()) {
			return Futures.completedNull();
		}

		final ConcurrentLinkedQueue<Supplier<CompletableFuture<?>>> futures = new ConcurrentLinkedQueue<>();
		final AtomicReference<Supplier<CompletableFuture<?>>> evaluateBody = new AtomicReference<>();
		evaluateBody.set(() -> {
			CompletableFuture<?> bodyResult = body.get();
			return bodyResult.thenRun(() -> {
				if (predicate.get()) {
					futures.add(evaluateBody.get());
				}
			});
		});

		futures.add(evaluateBody.get());
		return whileImplAsync(futures);
	}

	@NotNull
	private static CompletableFuture<Void> whileImplAsync(@NotNull ConcurrentLinkedQueue<Supplier<CompletableFuture<?>>> futures) {
		while (true) {
			Supplier<CompletableFuture<?>> next = futures.poll();
			if (next == null) {
				return Futures.completedNull();
			}

			CompletableFuture<?> future = next.get();
			if (!future.isDone()) {
				return awaitAsync(future, () -> whileImplAsync(futures));
			}
		}
	}
}