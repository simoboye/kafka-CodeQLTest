/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.clients.consumer.internals.events.ApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.ApplicationEventHandler;
import org.apache.kafka.clients.consumer.internals.events.AssignmentChangeApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.ListOffsetsApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.NewTopicsMetadataUpdateRequestEvent;
import org.apache.kafka.clients.consumer.internals.events.OffsetFetchApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.ResetPositionsApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.SubscriptionChangeApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.UnsubscribeApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.ValidatePositionsApplicationEvent;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.InvalidGroupIdException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.ListOffsetsRequest;
import org.apache.kafka.common.utils.Timer;
import org.apache.kafka.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AsyncKafkaConsumerTest {

    private AsyncKafkaConsumer<?, ?> consumer;
    private ConsumerTestBuilder.AsyncKafkaConsumerTestBuilder testBuilder;
    private ApplicationEventHandler applicationEventHandler;

    @BeforeEach
    public void setup() {
        // By default, the consumer is part of a group.
        setup(ConsumerTestBuilder.createDefaultGroupInformation());
    }

    private void setup(Optional<ConsumerTestBuilder.GroupInformation> groupInfo) {
        testBuilder = new ConsumerTestBuilder.AsyncKafkaConsumerTestBuilder(groupInfo);
        applicationEventHandler = testBuilder.applicationEventHandler;
        consumer = testBuilder.consumer;
    }

    @AfterEach
    public void cleanup() {
        if (testBuilder != null) {
            testBuilder.close();
        }
    }

    private void resetWithEmptyGroupId() {
        // Create a consumer that is not configured as part of a group.
        cleanup();
        setup(Optional.empty());
    }

    @Test
    public void testSuccessfulStartupShutdown() {
        assertDoesNotThrow(() -> consumer.close());
    }

    @Test
    public void testInvalidGroupId() {
        // Create consumer without group id
        resetWithEmptyGroupId();
        assertThrows(InvalidGroupIdException.class, () -> consumer.committed(new HashSet<>()));
    }

    @Test
    public void testFailOnClosedConsumer() {
        consumer.close();
        final IllegalStateException res = assertThrows(IllegalStateException.class, consumer::assignment);
        assertEquals("This consumer has already been closed.", res.getMessage());
    }

    @Test
    public void testCommitAsync_NullCallback() throws InterruptedException {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(new TopicPartition("my-topic", 0), new OffsetAndMetadata(100L));
        offsets.put(new TopicPartition("my-topic", 1), new OffsetAndMetadata(200L));

        doReturn(future).when(consumer).commit(offsets, false);
        consumer.commitAsync(offsets, null);
        future.complete(null);
        TestUtils.waitForCondition(future::isDone,
                2000,
                "commit future should complete");

        assertFalse(future.isCompletedExceptionally());
    }

    @ParameterizedTest
    @MethodSource("commitExceptionSupplier")
    public void testCommitAsync_UserSuppliedCallback(Exception exception) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(new TopicPartition("my-topic", 1), new OffsetAndMetadata(200L));

        doReturn(future).when(consumer).commit(offsets, false);
        MockCommitCallback callback = new MockCommitCallback();
        assertDoesNotThrow(() -> consumer.commitAsync(offsets, callback));

        if (exception == null) {
            future.complete(null);
            consumer.maybeInvokeCommitCallbacks();
            assertNull(callback.exception);
        } else {
            future.completeExceptionally(exception);
            consumer.maybeInvokeCommitCallbacks();
            assertSame(exception.getClass(), callback.exception.getClass());
        }
    }

    private static Stream<Exception> commitExceptionSupplier() {
        return Stream.of(
                null,  // For the successful completion scenario
                new KafkaException("Test exception"),
                new GroupAuthorizationException("Group authorization exception"));
    }

    @Test
    public void testFencedInstanceException() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        doReturn(future).when(consumer).commit(new HashMap<>(), false);
        assertDoesNotThrow(() -> consumer.commitAsync());
        future.completeExceptionally(Errors.FENCED_INSTANCE_ID.exception());
    }

    @Test
    public void testCommitted() {
        Map<TopicPartition, OffsetAndMetadata> offsets = mockTopicPartitionOffset();
        CompletableFuture<Map<TopicPartition, OffsetAndMetadata>> committedFuture = new CompletableFuture<>();
        committedFuture.complete(offsets);

        try (MockedConstruction<OffsetFetchApplicationEvent> ignored = offsetFetchEventMocker(committedFuture)) {
            assertDoesNotThrow(() -> consumer.committed(offsets.keySet(), Duration.ofMillis(1000)));
            verify(applicationEventHandler).add(ArgumentMatchers.isA(OffsetFetchApplicationEvent.class));
        }
    }

    @Test
    public void testCommitted_ExceptionThrown() {
        Map<TopicPartition, OffsetAndMetadata> offsets = mockTopicPartitionOffset();
        CompletableFuture<Map<TopicPartition, OffsetAndMetadata>> committedFuture = new CompletableFuture<>();
        committedFuture.completeExceptionally(new KafkaException("Test exception"));

        try (MockedConstruction<OffsetFetchApplicationEvent> ignored = offsetFetchEventMocker(committedFuture)) {
            assertThrows(KafkaException.class, () -> consumer.committed(offsets.keySet(), Duration.ofMillis(1000)));
            verify(applicationEventHandler).add(ArgumentMatchers.isA(OffsetFetchApplicationEvent.class));
        }
    }

    @Test
    public void testEnsureCallbackExecutedByApplicationThread() {
        final String currentThread = Thread.currentThread().getName();
        ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
        MockCommitCallback callback = new MockCommitCallback();
        CountDownLatch latch = new CountDownLatch(1);  // Initialize the latch with a count of 1
        try {
            CompletableFuture<Void> future = new CompletableFuture<>();
            doReturn(future).when(consumer).commit(new HashMap<>(), false);
            assertDoesNotThrow(() -> consumer.commitAsync(new HashMap<>(), callback));
            // Simulating some background work
            backgroundExecutor.submit(() -> {
                future.complete(null);
                latch.countDown();
            });
            latch.await();
            assertEquals(1, consumer.callbacks());
            consumer.maybeInvokeCommitCallbacks();
            assertEquals(currentThread, callback.completionThread);
        } catch (Exception e) {
            fail("Not expecting an exception");
        } finally {
            backgroundExecutor.shutdown();
        }
    }

    @Test
    public void testEnsureCommitSyncExecutedCommitAsyncCallbacks() {
        MockCommitCallback callback = new MockCommitCallback();
        CompletableFuture<Void> future = new CompletableFuture<>();
        doReturn(future).when(consumer).commit(new HashMap<>(), false);
        assertDoesNotThrow(() -> consumer.commitAsync(new HashMap<>(), callback));
        future.completeExceptionally(new NetworkException("Test exception"));
        assertMockCommitCallbackInvoked(() -> consumer.commitSync(),
            callback,
            Errors.NETWORK_EXCEPTION);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testPollLongThrowsException() {
        Exception e = assertThrows(UnsupportedOperationException.class, () -> consumer.poll(0L));
        assertEquals("Consumer.poll(long) is not supported when \"group.protocol\" is \"consumer\". " +
            "This method is deprecated and will be removed in the next major release.", e.getMessage());
    }

    @Test
    public void testEnsurePollExecutedCommitAsyncCallbacks() {
        MockCommitCallback callback = new MockCommitCallback();
        CompletableFuture<Void> future = new CompletableFuture<>();
        consumer.assign(Collections.singleton(new TopicPartition("foo", 0)));
        doReturn(future).when(consumer).commit(new HashMap<>(), false);
        assertDoesNotThrow(() -> consumer.commitAsync(new HashMap<>(), callback));
        future.complete(null);
        assertMockCommitCallbackInvoked(() -> consumer.poll(Duration.ZERO),
            callback,
            null);
    }

    @Test
    public void testEnsureShutdownExecutedCommitAsyncCallbacks() {
        MockCommitCallback callback = new MockCommitCallback();
        CompletableFuture<Void> future = new CompletableFuture<>();
        doReturn(future).when(consumer).commit(new HashMap<>(), false);
        assertDoesNotThrow(() -> consumer.commitAsync(new HashMap<>(), callback));
        future.complete(null);
        assertMockCommitCallbackInvoked(() -> consumer.close(Duration.ZERO),
            callback,
            null);
    }

    private void assertMockCommitCallbackInvoked(final Executable task, final MockCommitCallback callback,
                                                 final Errors exception) {
        assertDoesNotThrow(task);
        assertEquals(1, callback.invoked);
        if (callback.exception instanceof RetriableException)
            assertEquals(callback.exception.getClass(), RetriableCommitFailedException.class);
        else
            assertNull(callback.exception);
    }

    private static class MockCommitCallback implements OffsetCommitCallback {
        public int invoked = 0;
        public Exception exception = null;
        public String completionThread;

        @Override
        public void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) {
            invoked++;
            this.completionThread = Thread.currentThread().getName();
            this.exception = exception;
        }
    }
    /**
     * This is a rather ugly bit of code. Not my choice :(
     *
     * <p/>
     *
     * Inside the {@link org.apache.kafka.clients.consumer.Consumer#committed(Set, Duration)} call we create an
     * instance of {@link OffsetFetchApplicationEvent} that holds the partitions and internally holds a
     * {@link CompletableFuture}. We want to test different behaviours of the {@link Future#get()}, such as
     * returning normally, timing out, throwing an error, etc. By mocking the construction of the event object that
     * is created, we can affect that behavior.
     */
    private static MockedConstruction<OffsetFetchApplicationEvent> offsetFetchEventMocker(CompletableFuture<Map<TopicPartition, OffsetAndMetadata>> future) {
        // This "answer" is where we pass the future to be invoked by the ConsumerUtils.getResult() method
        Answer<Map<TopicPartition, OffsetAndMetadata>> getInvocationAnswer = invocation -> {
            // This argument captures the actual argument value that was passed to the event's get() method, so we
            // just "forward" that value to our mocked call
            Timer timer = invocation.getArgument(0);
            return ConsumerUtils.getResult(future, timer);
        };

        MockedConstruction.MockInitializer<OffsetFetchApplicationEvent> mockInitializer = (mock, ctx) -> {
            // When the event's get() method is invoked, we call the "answer" method just above
            when(mock.get(any())).thenAnswer(getInvocationAnswer);

            // When the event's type() method is invoked, we have to return the type as it will be null in the mock
            when(mock.type()).thenReturn(ApplicationEvent.Type.FETCH_COMMITTED_OFFSET);

            // This is needed for the WakeupTrigger code that keeps track of the active task
            when(mock.future()).thenReturn(future);
        };

        return mockConstruction(OffsetFetchApplicationEvent.class, mockInitializer);
    }

    @Test
    public void testAssign() {
        final TopicPartition tp = new TopicPartition("foo", 3);
        consumer.assign(singleton(tp));
        assertTrue(consumer.subscription().isEmpty());
        assertTrue(consumer.assignment().contains(tp));
        verify(applicationEventHandler).add(any(AssignmentChangeApplicationEvent.class));
        verify(applicationEventHandler).add(any(NewTopicsMetadataUpdateRequestEvent.class));
    }

    @Test
    public void testAssignOnNullTopicPartition() {
        assertThrows(IllegalArgumentException.class, () -> consumer.assign(null));
    }

    @Test
    public void testAssignOnEmptyTopicPartition() {
        consumer.assign(Collections.emptyList());
        assertTrue(consumer.subscription().isEmpty());
        assertTrue(consumer.assignment().isEmpty());
    }

    @Test
    public void testAssignOnNullTopicInPartition() {
        assertThrows(IllegalArgumentException.class, () -> consumer.assign(singleton(new TopicPartition(null, 0))));
    }

    @Test
    public void testAssignOnEmptyTopicInPartition() {
        assertThrows(IllegalArgumentException.class, () -> consumer.assign(singleton(new TopicPartition("  ", 0))));
    }

    @Test
    public void testBeginningOffsetsFailsIfNullPartitions() {
        assertThrows(NullPointerException.class, () -> consumer.beginningOffsets(null,
                Duration.ofMillis(1)));
    }

    @Test
    public void testBeginningOffsets() {
        Map<TopicPartition, OffsetAndTimestamp> expectedOffsetsAndTimestamp =
                mockOffsetAndTimestamp();
        Set<TopicPartition> partitions = expectedOffsetsAndTimestamp.keySet();
        doReturn(expectedOffsetsAndTimestamp).when(applicationEventHandler).addAndGet(any(), any());
        Map<TopicPartition, Long> result =
                assertDoesNotThrow(() -> consumer.beginningOffsets(partitions,
                        Duration.ofMillis(1)));
        Map<TopicPartition, Long> expectedOffsets = expectedOffsetsAndTimestamp.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
        assertEquals(expectedOffsets, result);
        verify(applicationEventHandler).addAndGet(ArgumentMatchers.isA(ListOffsetsApplicationEvent.class),
                ArgumentMatchers.isA(Timer.class));
    }

    @Test
    public void testBeginningOffsetsThrowsKafkaExceptionForUnderlyingExecutionFailure() {
        Set<TopicPartition> partitions = mockTopicPartitionOffset().keySet();
        Throwable eventProcessingFailure = new KafkaException("Unexpected failure " +
                "processing List Offsets event");
        doThrow(eventProcessingFailure).when(applicationEventHandler).addAndGet(any(), any());
        Throwable consumerError = assertThrows(KafkaException.class,
                () -> consumer.beginningOffsets(partitions,
                        Duration.ofMillis(1)));
        assertEquals(eventProcessingFailure, consumerError);
        verify(applicationEventHandler).addAndGet(ArgumentMatchers.isA(ListOffsetsApplicationEvent.class), ArgumentMatchers.isA(Timer.class));
    }

    @Test
    public void testBeginningOffsetsTimeoutOnEventProcessingTimeout() {
        doThrow(new TimeoutException()).when(applicationEventHandler).addAndGet(any(), any());
        assertThrows(TimeoutException.class,
                () -> consumer.beginningOffsets(
                        Collections.singletonList(new TopicPartition("t1", 0)),
                        Duration.ofMillis(1)));
        verify(applicationEventHandler).addAndGet(ArgumentMatchers.isA(ListOffsetsApplicationEvent.class),
                ArgumentMatchers.isA(Timer.class));
    }

    @Test
    public void testOffsetsForTimesOnNullPartitions() {
        assertThrows(NullPointerException.class, () -> consumer.offsetsForTimes(null,
                Duration.ofMillis(1)));
    }

    @Test
    public void testOffsetsForTimesFailsOnNegativeTargetTimes() {
        assertThrows(IllegalArgumentException.class,
                () -> consumer.offsetsForTimes(Collections.singletonMap(new TopicPartition(
                                "topic1", 1), ListOffsetsRequest.EARLIEST_TIMESTAMP),
                        Duration.ofMillis(1)));

        assertThrows(IllegalArgumentException.class,
                () -> consumer.offsetsForTimes(Collections.singletonMap(new TopicPartition(
                                "topic1", 1), ListOffsetsRequest.LATEST_TIMESTAMP),
                        Duration.ofMillis(1)));

        assertThrows(IllegalArgumentException.class,
                () -> consumer.offsetsForTimes(Collections.singletonMap(new TopicPartition(
                                "topic1", 1), ListOffsetsRequest.MAX_TIMESTAMP),
                        Duration.ofMillis(1)));
    }

    @Test
    public void testOffsetsForTimes() {
        Map<TopicPartition, OffsetAndTimestamp> expectedResult = mockOffsetAndTimestamp();
        Map<TopicPartition, Long> timestampToSearch = mockTimestampToSearch();

        doReturn(expectedResult).when(applicationEventHandler).addAndGet(any(), any());
        Map<TopicPartition, OffsetAndTimestamp> result =
                assertDoesNotThrow(() -> consumer.offsetsForTimes(timestampToSearch, Duration.ofMillis(1)));
        assertEquals(expectedResult, result);
        verify(applicationEventHandler).addAndGet(ArgumentMatchers.isA(ListOffsetsApplicationEvent.class),
                ArgumentMatchers.isA(Timer.class));
    }

    // This test ensures same behaviour as the current consumer when offsetsForTimes is called
    // with 0 timeout. It should return map with all requested partitions as keys, with null
    // OffsetAndTimestamp as value.
    @Test
    public void testOffsetsForTimesWithZeroTimeout() {
        TopicPartition tp = new TopicPartition("topic1", 0);
        Map<TopicPartition, OffsetAndTimestamp> expectedResult =
                Collections.singletonMap(tp, null);
        Map<TopicPartition, Long> timestampToSearch = Collections.singletonMap(tp, 5L);

        Map<TopicPartition, OffsetAndTimestamp> result =
                assertDoesNotThrow(() -> consumer.offsetsForTimes(timestampToSearch,
                        Duration.ofMillis(0)));
        assertEquals(expectedResult, result);
        verify(applicationEventHandler, never()).addAndGet(ArgumentMatchers.isA(ListOffsetsApplicationEvent.class),
                ArgumentMatchers.isA(Timer.class));
    }

    @Test
    public void testWakeup_committed() {
        consumer.wakeup();
        assertThrows(WakeupException.class, () -> consumer.committed(mockTopicPartitionOffset().keySet()));
        assertNoPendingWakeup(consumer.wakeupTrigger());
    }

    @Test
    public void testRefreshCommittedOffsetsSuccess() {
        TopicPartition partition = new TopicPartition("t1", 1);
        Set<TopicPartition> partitions = Collections.singleton(partition);
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = Collections.singletonMap(partition, new OffsetAndMetadata(10L));
        testRefreshCommittedOffsetsSuccess(partitions, committedOffsets);
    }

    @Test
    public void testRefreshCommittedOffsetsSuccessButNoCommittedOffsetsFound() {
        TopicPartition partition = new TopicPartition("t1", 1);
        Set<TopicPartition> partitions = Collections.singleton(partition);
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = Collections.emptyMap();
        testRefreshCommittedOffsetsSuccess(partitions, committedOffsets);
    }

    @Test
    public void testRefreshCommittedOffsetsShouldNotResetIfFailedWithTimeout() {
        testUpdateFetchPositionsWithFetchCommittedOffsetsTimeout(true);
    }

    @Test
    public void testRefreshCommittedOffsetsNotCalledIfNoGroupId() {
        // Create consumer without group id so committed offsets are not used for updating positions
        resetWithEmptyGroupId();
        testUpdateFetchPositionsWithFetchCommittedOffsetsTimeout(false);
    }

    @Test
    public void testSubscribeGeneratesEvent() {
        String topic = "topic1";
        consumer.subscribe(singletonList(topic));
        assertEquals(singleton(topic), consumer.subscription());
        assertTrue(consumer.assignment().isEmpty());
        verify(applicationEventHandler).add(ArgumentMatchers.isA(SubscriptionChangeApplicationEvent.class));
    }

    @Test
    public void testUnsubscribeGeneratesUnsubscribeEvent() {
        consumer.unsubscribe();

        // Verify the unsubscribe event was generated and mock its completion.
        final ArgumentCaptor<UnsubscribeApplicationEvent> captor = ArgumentCaptor.forClass(UnsubscribeApplicationEvent.class);
        verify(applicationEventHandler).add(captor.capture());
        UnsubscribeApplicationEvent unsubscribeApplicationEvent = captor.getValue();
        unsubscribeApplicationEvent.future().complete(null);

        assertTrue(consumer.subscription().isEmpty());
        assertTrue(consumer.assignment().isEmpty());
    }

    @Test
    public void testSubscribeToEmptyListActsAsUnsubscribe() {
        consumer.subscribe(Collections.emptyList());
        assertTrue(consumer.subscription().isEmpty());
        assertTrue(consumer.assignment().isEmpty());
        verify(applicationEventHandler).add(ArgumentMatchers.isA(UnsubscribeApplicationEvent.class));
    }

    @Test
    public void testSubscribeToNullTopicCollection() {
        assertThrows(IllegalArgumentException.class, () -> consumer.subscribe((List<String>) null));
    }

    @Test
    public void testSubscriptionOnNullTopic() {
        assertThrows(IllegalArgumentException.class, () -> consumer.subscribe(singletonList(null)));
    }

    @Test
    public void testSubscriptionOnEmptyTopic() {
        String emptyTopic = "  ";
        assertThrows(IllegalArgumentException.class, () -> consumer.subscribe(singletonList(emptyTopic)));
    }

    private void testUpdateFetchPositionsWithFetchCommittedOffsetsTimeout(boolean committedOffsetsEnabled) {
        // Uncompleted future that will time out if used
        CompletableFuture<Map<TopicPartition, OffsetAndMetadata>> committedFuture = new CompletableFuture<>();


        consumer.assign(singleton(new TopicPartition("t1", 1)));

        try (MockedConstruction<OffsetFetchApplicationEvent> ignored = offsetFetchEventMocker(committedFuture)) {
            // Poll with 0 timeout to run a single iteration of the poll loop
            consumer.poll(Duration.ofMillis(0));

            verify(applicationEventHandler).add(ArgumentMatchers.isA(ValidatePositionsApplicationEvent.class));

            if (committedOffsetsEnabled) {
                // Verify there was an OffsetFetch event and no ResetPositions event
                verify(applicationEventHandler).add(ArgumentMatchers.isA(OffsetFetchApplicationEvent.class));
                verify(applicationEventHandler,
                        never()).add(ArgumentMatchers.isA(ResetPositionsApplicationEvent.class));
            } else {
                // Verify there was not any OffsetFetch event but there should be a ResetPositions
                verify(applicationEventHandler,
                        never()).add(ArgumentMatchers.isA(OffsetFetchApplicationEvent.class));
                verify(applicationEventHandler).add(ArgumentMatchers.isA(ResetPositionsApplicationEvent.class));
            }
        }
    }

    private void testRefreshCommittedOffsetsSuccess(Set<TopicPartition> partitions,
                                                    Map<TopicPartition, OffsetAndMetadata> committedOffsets) {
        CompletableFuture<Map<TopicPartition, OffsetAndMetadata>> committedFuture = new CompletableFuture<>();
        committedFuture.complete(committedOffsets);
        consumer.assign(partitions);

        try (MockedConstruction<OffsetFetchApplicationEvent> ignored = offsetFetchEventMocker(committedFuture)) {
            // Poll with 0 timeout to run a single iteration of the poll loop
            consumer.poll(Duration.ofMillis(0));

            verify(applicationEventHandler).add(ArgumentMatchers.isA(ValidatePositionsApplicationEvent.class));
            verify(applicationEventHandler).add(ArgumentMatchers.isA(OffsetFetchApplicationEvent.class));
            verify(applicationEventHandler).add(ArgumentMatchers.isA(ResetPositionsApplicationEvent.class));
        }
    }

    private void assertNoPendingWakeup(final WakeupTrigger wakeupTrigger) {
        assertNull(wakeupTrigger.getPendingTask());
    }

    private HashMap<TopicPartition, OffsetAndMetadata> mockTopicPartitionOffset() {
        final TopicPartition t0 = new TopicPartition("t0", 2);
        final TopicPartition t1 = new TopicPartition("t0", 3);
        HashMap<TopicPartition, OffsetAndMetadata> topicPartitionOffsets = new HashMap<>();
        topicPartitionOffsets.put(t0, new OffsetAndMetadata(10L));
        topicPartitionOffsets.put(t1, new OffsetAndMetadata(20L));
        return topicPartitionOffsets;
    }

    private HashMap<TopicPartition, OffsetAndTimestamp> mockOffsetAndTimestamp() {
        final TopicPartition t0 = new TopicPartition("t0", 2);
        final TopicPartition t1 = new TopicPartition("t0", 3);
        HashMap<TopicPartition, OffsetAndTimestamp> offsetAndTimestamp = new HashMap<>();
        offsetAndTimestamp.put(t0, new OffsetAndTimestamp(5L, 1L));
        offsetAndTimestamp.put(t1, new OffsetAndTimestamp(6L, 3L));
        return offsetAndTimestamp;
    }

    private HashMap<TopicPartition, Long> mockTimestampToSearch() {
        final TopicPartition t0 = new TopicPartition("t0", 2);
        final TopicPartition t1 = new TopicPartition("t0", 3);
        HashMap<TopicPartition, Long> timestampToSearch = new HashMap<>();
        timestampToSearch.put(t0, 1L);
        timestampToSearch.put(t1, 2L);
        return timestampToSearch;
    }
}

