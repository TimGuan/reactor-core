/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.core.publisher.Operators.CancelledSubscription;
import reactor.core.publisher.Operators.DeferredSubscription;
import reactor.core.publisher.Operators.EmptySubscription;
import reactor.core.publisher.Operators.MonoSubscriber;
import reactor.core.publisher.Operators.MultiSubscriptionSubscriber;
import reactor.core.publisher.Operators.ScalarSubscription;
import reactor.test.RaceTestUtils;
import reactor.util.context.Context;

import static org.assertj.core.api.Assertions.assertThat;

public class OperatorsTest {

	volatile long testRequest;
	static final AtomicLongFieldUpdater<OperatorsTest> TEST_REQUEST =
			AtomicLongFieldUpdater.newUpdater(OperatorsTest.class, "testRequest");


	@Test
	public void addAndGetAtomicField() {
		TEST_REQUEST.set(this, 0L);

		RaceTestUtils.race(0L,
				s -> Operators.addCap(TEST_REQUEST, this, 1),
				a -> a >= 100_000L,
				(a, b) -> a == b
		);

		TEST_REQUEST.set(this, 0L);

		assertThat(Operators.addCap(TEST_REQUEST, this, -1_000_000L))
				.isEqualTo(0);

		TEST_REQUEST.set(this, 1L);

		assertThat(Operators.addCap(TEST_REQUEST, this, Long.MAX_VALUE))
				.isEqualTo(1L);

		assertThat(Operators.addCap(TEST_REQUEST, this, 0))
				.isEqualTo(Long.MAX_VALUE);
	}


	@Test
	public void addCap() {
		assertThat(Operators.addCap(1, 2)).isEqualTo(3);
		assertThat(Operators.addCap(1, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
		assertThat(Operators.addCap(0, -1)).isEqualTo(Long.MAX_VALUE);
	}

	@Test
	public void constructor(){
		assertThat(new Operators(){}).isNotNull();
	}

	@Test
	public void castAsQueueSubscription() {
		Fuseable.QueueSubscription<String> qs = new Fuseable.SynchronousSubscription<String>() {
			@Override
			public String poll() {
				return null;
			}

			@Override
			public int size() {
				return 0;
			}

			@Override
			public boolean isEmpty() {
				return false;
			}

			@Override
			public void clear() {

			}

			@Override
			public void request(long n) {

			}

			@Override
			public void cancel() {

			}
		};

		Subscription s = Operators.cancelledSubscription();

		assertThat(Operators.as(qs)).isEqualTo(qs);
		assertThat(Operators.as(s)).isNull();
	}

	@Test
	public void cancelledSubscription(){
		Operators.CancelledSubscription es =
				(Operators.CancelledSubscription)Operators.cancelledSubscription();

		assertThat((Object)es).isEqualTo(Operators.CancelledSubscription.INSTANCE);

		//Noop
		es.cancel();
		es.request(-1);
	}

	@Test
	public void noopFluxCancelled(){
		OperatorDisposables.DISPOSED.dispose(); //noop
	}

	@Test
	public void drainSubscriber() {
		AtomicBoolean requested = new AtomicBoolean();
		AtomicBoolean errored = new AtomicBoolean();
		try {
			Hooks.onErrorDropped(e -> {
				assertThat(Exceptions.isErrorCallbackNotImplemented(e)).isTrue();
				assertThat(e.getCause()).hasMessage("test");
				errored.set(true);
			});
			Flux.from(s -> {
				assertThat(s).isEqualTo(Operators.drainSubscriber());
				s.onSubscribe(new Subscription() {
					@Override
					public void request(long n) {
						assertThat(n).isEqualTo(Long.MAX_VALUE);
						requested.set(true);
					}

					@Override
					public void cancel() {

					}
				});
				s.onNext("ignored"); //dropped
				s.onComplete(); //dropped
				s.onError(new Exception("test"));
			})
			    .subscribe(Operators.drainSubscriber());

			assertThat(requested.get()).isTrue();
			assertThat(errored.get()).isTrue();
		}
		finally {
			Hooks.resetOnErrorDropped();
		}
	}

	@Test
	public void scanCancelledSubscription() {
		CancelledSubscription test = CancelledSubscription.INSTANCE;
		assertThat(test.scan(Scannable.Attr.CANCELLED)).isTrue();
	}

	@Test
	public void scanDeferredSubscription() {
		DeferredSubscription test = new DeferredSubscription();
		test.s = Operators.emptySubscription();

		Assertions.assertThat(test.scan(Scannable.Attr.PARENT)).isSameAs(test.s);
		test.requested = 123;
		Assertions.assertThat(test.scan(Scannable.Attr.REQUESTED_FROM_DOWNSTREAM)).isEqualTo(123);

		Assertions.assertThat(test.scan(Scannable.Attr.CANCELLED)).isFalse();
		test.cancel();
		Assertions.assertThat(test.scan(Scannable.Attr.CANCELLED)).isTrue();
	}

	@Test
	public void scanEmptySubscription() {
		EmptySubscription test = EmptySubscription.INSTANCE;
		assertThat(test.scan(Scannable.Attr.TERMINATED)).isTrue();
	}

	@Test
	public void scanMonoSubscriber() {
		CoreSubscriber<Integer> actual = new LambdaSubscriber<>(null, null, null, null);
		MonoSubscriber<Integer, Integer> test = new MonoSubscriber<>(actual);

		Assertions.assertThat(test.scan(Scannable.Attr.ACTUAL)).isSameAs(actual);
		Assertions.assertThat(test.scan(Scannable.Attr.PREFETCH)).isEqualTo(Integer.MAX_VALUE);

		Assertions.assertThat(test.scan(Scannable.Attr.TERMINATED)).isFalse();
		test.complete(4);
		Assertions.assertThat(test.scan(Scannable.Attr.TERMINATED)).isTrue();

		Assertions.assertThat(test.scan(Scannable.Attr.CANCELLED)).isFalse();
		test.cancel();
		Assertions.assertThat(test.scan(Scannable.Attr.CANCELLED)).isTrue();

	}

	@Test
	public void scanMultiSubscriptionSubscriber() {
		CoreSubscriber<Integer> actual = new LambdaSubscriber<>(null, null, null, null);
		MultiSubscriptionSubscriber<Integer, Integer> test = new MultiSubscriptionSubscriber<Integer, Integer>(actual) {
			@Override
			public void onNext(Integer t) {
			}
		};
		Subscription parent = Operators.emptySubscription();
        test.onSubscribe(parent);
		Assertions.assertThat(test.scan(Scannable.Attr.ACTUAL)).isSameAs(actual);
		Assertions.assertThat(test.scan(Scannable.Attr.PARENT)).isSameAs(parent);
		test.request(34);
		Assertions.assertThat(test.scan(Scannable.Attr.REQUESTED_FROM_DOWNSTREAM)).isEqualTo(34);

		Assertions.assertThat(test.scan(Scannable.Attr.CANCELLED)).isFalse();
		test.cancel();
		Assertions.assertThat(test.scan(Scannable.Attr.CANCELLED)).isTrue();
	}

	@Test
	public void scanScalarSubscription() {
		CoreSubscriber<Integer> actual = new LambdaSubscriber<>(null, null, null, null);
		ScalarSubscription<Integer> test = new ScalarSubscription<>(actual, 5);

		Assertions.assertThat(test.scan(Scannable.Attr.ACTUAL)).isSameAs(actual);

		Assertions.assertThat(test.scan(Scannable.Attr.TERMINATED)).isFalse();
		Assertions.assertThat(test.scan(Scannable.Attr.CANCELLED)).isFalse();
		test.poll();
		Assertions.assertThat(test.scan(Scannable.Attr.TERMINATED)).isTrue();
		Assertions.assertThat(test.scan(Scannable.Attr.CANCELLED)).isTrue();
	}

	@Test
	public void onErrorDroppedLocal() {
		AtomicReference<Throwable> hookState = new AtomicReference<>();
		Consumer<Throwable> localHook = hookState::set;
		Context c = Context.of(Hooks.KEY_ON_ERROR_DROPPED, localHook);

		Operators.onErrorDropped(new IllegalArgumentException("boom"), c);

		assertThat(hookState.get()).isInstanceOf(IllegalArgumentException.class)
		                           .hasMessage("boom");
	}

	@Test
	public void onNextDroppedLocal() {
		AtomicReference<Object> hookState = new AtomicReference<>();
		Consumer<Object> localHook = hookState::set;
		Context c = Context.of(Hooks.KEY_ON_NEXT_DROPPED, localHook);

		Operators.onNextDropped("foo", c);

		assertThat(hookState.get()).isEqualTo("foo");
	}

	@Test
	public void onOperatorErrorLocal() {
		BiFunction<Throwable, Object, Throwable> localHook = (e, v) ->
				new IllegalStateException("boom_" + v, e);
		Context c = Context.of(Hooks.KEY_ON_OPERATOR_ERROR, localHook);

		IllegalArgumentException failure = new IllegalArgumentException("foo");

		final Throwable throwable = Operators.onOperatorError(null, failure,
				"foo", c);

		assertThat(throwable).isInstanceOf(IllegalStateException.class)
		                     .hasMessage("boom_foo")
		                     .hasCause(failure);
	}

	@Test
	public void onRejectedExecutionWithoutDataSignalDelegatesToErrorLocal() {
		BiFunction<Throwable, Object, Throwable> localHook = (e, v) ->
				new IllegalStateException("boom_" + v, e);
		Context c = Context.of(Hooks.KEY_ON_OPERATOR_ERROR, localHook);

		IllegalArgumentException failure = new IllegalArgumentException("foo");
		final Throwable throwable = Operators.onRejectedExecution(failure, null, null, null, c);

		assertThat(throwable).isInstanceOf(IllegalStateException.class)
		                     .hasMessage("boom_null")
		                     .hasNoSuppressedExceptions();
		assertThat(throwable.getCause()).isInstanceOf(RejectedExecutionException.class)
		                                .hasMessage("Scheduler unavailable")
		                                .hasCause(failure);
	}

	@Test
	public void onRejectedExecutionWithDataSignalDelegatesToErrorLocal() {
		BiFunction<Throwable, Object, Throwable> localHook = (e, v) ->
				new IllegalStateException("boom_" + v, e);
		Context c = Context.of(Hooks.KEY_ON_OPERATOR_ERROR, localHook);

		IllegalArgumentException failure = new IllegalArgumentException("foo");
		final Throwable throwable = Operators.onRejectedExecution(failure, null,
				null, "bar", c);

		assertThat(throwable).isInstanceOf(IllegalStateException.class)
		                     .hasMessage("boom_bar")
		                     .hasNoSuppressedExceptions();
		assertThat(throwable.getCause()).isInstanceOf(RejectedExecutionException.class)
		                                .hasMessage("Scheduler unavailable")
		                                .hasCause(failure);
	}

	@Test
	public void onRejectedExecutionLocalTakesPrecedenceOverOnOperatorError() {
		BiFunction<Throwable, Object, Throwable> localOperatorErrorHook = (e, v) ->
				new IllegalStateException("boom_" + v, e);

		BiFunction<Throwable, Object, Throwable> localReeHook = (e, v) ->
				new IllegalStateException("rejected_" + v, e);
		Context c = Context.of(
				Hooks.KEY_ON_OPERATOR_ERROR, localOperatorErrorHook,
				Hooks.KEY_ON_REJECTED_EXECUTION, localReeHook);

		IllegalArgumentException failure = new IllegalArgumentException("foo");
		final Throwable throwable = Operators.onRejectedExecution(failure, null,
				null, "bar", c);

		assertThat(throwable).isInstanceOf(IllegalStateException.class)
		                     .hasMessage("rejected_bar")
		                     .hasNoSuppressedExceptions();
		assertThat(throwable.getCause()).isInstanceOf(RejectedExecutionException.class)
		                                .hasMessage("Scheduler unavailable")
		                                .hasCause(failure);
	}

	@Test
	public void testOnRejectedWithReactorRee() {
		Exception originalCause = new Exception("boom");
		RejectedExecutionException original = Exceptions.failWithRejected(originalCause);
		Exception suppressed = new Exception("suppressed");

		RuntimeException test = Operators.onRejectedExecution(original,
				null, suppressed, null, Context.empty());

		assertThat(test)
				.isSameAs(original)
				.hasSuppressedException(suppressed);
	}

	@Test
	public void testOnRejectedWithOutsideRee() {
		RejectedExecutionException original = new RejectedExecutionException("outside");
		Exception suppressed = new Exception("suppressed");

		RuntimeException test = Operators.onRejectedExecution(original,
				null, suppressed, null, Context.empty());

		assertThat(test)
				.isNotSameAs(original)
				.isInstanceOf(RejectedExecutionException.class)
				.hasMessage("Scheduler unavailable")
				.hasCause(original)
				.hasSuppressedException(suppressed);
	}
}
