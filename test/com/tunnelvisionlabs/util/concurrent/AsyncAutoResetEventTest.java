// Licensed under the MIT license. See LICENSE file in the project root for full license information.
package com.tunnelvisionlabs.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Test;

/**
 * Copied from Microsoft/vs-threading@14f77875.
 */
public class AsyncAutoResetEventTest extends TestBase {
	private AsyncAutoResetEvent event = new AsyncAutoResetEvent();

	@Test
	public void testSingleThreadedPulse() {
		CompletableFuture<Void> future = Async.forAsync(
			() -> 0,
			i -> i < 5,
			i -> i + 1,
			i -> {
				CompletableFuture<Void> t = event.waitAsync();
				Assert.assertFalse(t.isDone());
				event.set();
				return Async.awaitAsync(t);
			});

		future.join();
	}

	@Test
	public void testMultipleSetOnlySignalsOnce() {
		this.event.set();
		this.event.set();
		CompletableFuture<Void> future = Async.awaitAsync(
			event.waitAsync(),
			() -> {
				CompletableFuture<Void> t = event.waitAsync();
				Assert.assertFalse(t.isDone());
				return Async.awaitAsync(
					Async.delayAsync(ASYNC_DELAY),
					() -> {
						Assert.assertFalse(t.isDone());
						return Futures.completedNull();
					});
			});

		future.join();
	}

	@Test
	public void testOrderPreservingQueue() {
		List<CompletableFuture<Void>> waiters = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			waiters.add(event.waitAsync());
		}

		CompletableFuture<Void> future = Async.forAsync(
			() -> 0,
			i -> i < waiters.size(),
			i -> i + 1,
			i -> {
				event.set();
				return Async.awaitAsync(waiters.get(i));
			});

		future.join();
	}

	/**
	 * Verifies that inlining continuations do not have to complete execution before {@link AsyncAutoResetEvent#set()}
	 * returns.
	 */
	@Test
	public void testSetReturnsBeforeInlinedContinuations() throws Exception {
		CompletableFuture<Void> setReturned = new CompletableFuture<>();
		CompletableFuture<Void> inlinedContinuation = event.waitAsync().whenComplete((result, exception) -> {
			try {
				// Arrange to synchronously block the continuation until set() has returned,
				// which would deadlock if set() does not return until inlined continuations complete.
				setReturned.get(ASYNC_DELAY.toMillis(), TimeUnit.MILLISECONDS);
			} catch (InterruptedException | ExecutionException | TimeoutException ex) {
				throw new CompletionException(ex);
			}
		});

		event.set();
		setReturned.complete(null);
		inlinedContinuation.get(ASYNC_DELAY.toMillis(), TimeUnit.MILLISECONDS);
	}

	/**
	 * Verifies that inlining continuations works when the option is set.
	 */
	@Test
	public void testSetInlinesContinuationsUnderSwitch() throws Exception {
		event = new AsyncAutoResetEvent(/*allowInliningAwaiters:*/true);
		Thread settingThread = Thread.currentThread();
		final AtomicBoolean setReturned = new AtomicBoolean(false);
		CompletableFuture<Void> inlinedContinuation = event.waitAsync().whenComplete((result, exception) -> {
			// Arrange to synchronously block the continuation until set() has returned,
			// which would deadlock if set() does not return until inlined continuations complete.
			Assert.assertFalse(setReturned.get());
			Assert.assertSame(settingThread, Thread.currentThread());
		});

		event.set();
		setReturned.set(true);
		Assert.assertTrue(inlinedContinuation.isDone());
		// rethrow any exceptions in the continuation
		inlinedContinuation.get(ASYNC_DELAY.toMillis(), TimeUnit.MILLISECONDS);
	}

	@Test
	public void testWaitAsync_WithCancellation_DoesNotClaimSignal() {
		CompletableFuture<Void> waitFuture = event.waitAsync();
		Assert.assertFalse(waitFuture.isDone());

		// Cancel the request and ensure that it propagates to the task.
		waitFuture.cancel(true);
		try {
			waitFuture.join();
			Assert.fail("Future was expected to be cancelled.");
		} catch (CancellationException ex) {
		}

		// Now set the event and verify that a future waiter gets the signal immediately.
		event.set();
		waitFuture = event.waitAsync();
		Assert.assertTrue(waitFuture.isDone());
		Assert.assertFalse(waitFuture.isCompletedExceptionally());
	}

	@Test
	public void testWaitAsync_WithCancellationToken_DoesNotClaimSignal() {
		CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();

		CompletableFuture<Void> waitFuture = event.waitAsync(cancellationTokenSource.getToken());
		Assert.assertFalse(waitFuture.isDone());

		// Cancel the request and ensure that it propagates to the task.
		cancellationTokenSource.cancel();

		try {
			waitFuture.join();
			Assert.fail("Future was expected to be cancelled.");
		} catch (CancellationException ex) {
		}

		// Now set the event and verify that a future waiter gets the signal immediately.
		event.set();
		waitFuture = event.waitAsync();
		Assert.assertTrue(waitFuture.isDone());
		Assert.assertFalse(waitFuture.isCompletedExceptionally());
	}

	@Test
	public void testWaitAsync_WithCancellationToken_PrecanceledDoesNotClaimExistingSignal() {
		CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
		cancellationTokenSource.cancel();

		// Verify that a pre-set signal is not reset by a canceled wait request.
		this.event.set();
		try {
			event.waitAsync(cancellationTokenSource.getToken()).join();
			Assert.fail("Future was expected to transition to a canceled state.");
		} catch (CancellationException ex) {
		}

		// Verify that the signal was not acquired.
		CompletableFuture<Void> waitTask = this.event.waitAsync();
		Assert.assertTrue(waitTask.isDone());
		Assert.assertFalse(waitTask.isCompletedExceptionally());
	}

	@Test
	public void testWaitAsync_Canceled_DoesNotInlineContinuations() {
		CompletableFuture<Void> future = event.waitAsync();
		verifyDoesNotInlineContinuations(future, () -> future.cancel(true));
	}

	@Test
	public void testWaitAsync_Canceled_DoesInlineContinuations() {
		event = new AsyncAutoResetEvent(/*allowInliningAwaiters:*/true);
		CompletableFuture<Void> future = event.waitAsync();
		verifyCanInlineContinuations(future, () -> future.cancel(true));
	}

//        /// <summary>
//        /// Verifies that long-lived, uncanceled CancellationTokens do not result in leaking memory.
//        /// </summary>
//        [Fact, Trait("TestCategory", "FailsInCloudTest")]
//        public void WaitAsync_WithCancellationToken_DoesNotLeakWhenNotCanceled()
//        {
//            var cts = new CancellationTokenSource();
//
//            this.CheckGCPressure(
//                () =>
//                {
//                    this.evt.WaitAsync(cts.Token);
//                    this.evt.Set();
//                },
//                500);
//        }
//
//        /// <summary>
//        /// Verifies that long-lived, uncanceled CancellationTokens do not result in leaking memory.
//        /// </summary>
//        [Fact, Trait("TestCategory", "FailsInCloudTest")]
//        public void WaitAsync_WithCancellationToken_DoesNotLeakWhenCanceled()
//        {
//            this.CheckGCPressure(
//                () =>
//                {
//                    var cts = new CancellationTokenSource();
//                    this.evt.WaitAsync(cts.Token);
//                    cts.Cancel();
//                },
//                1000);
//        }
}
