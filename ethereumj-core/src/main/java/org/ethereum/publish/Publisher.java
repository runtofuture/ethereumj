package org.ethereum.publish;

import org.ethereum.core.*;
import org.ethereum.listener.EthereumListener;
import org.ethereum.net.eth.message.StatusMessage;
import org.ethereum.net.message.Message;
import org.ethereum.net.p2p.HelloMessage;
import org.ethereum.net.rlpx.Node;
import org.ethereum.net.server.Channel;
import org.ethereum.publish.event.*;
import org.ethereum.publish.event.message.EthStatusUpdated;
import org.ethereum.publish.event.message.PeerHandshaked;
import org.ethereum.publish.event.message.MessageReceived;
import org.ethereum.publish.event.message.MessageSent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Objects.nonNull;
import static java.util.stream.Collectors.toList;
import static org.ethereum.publish.Subscription.to;

/**
 * Event publisher that uses pub/sub model to deliver event messages.<br>
 * Uses {@link EventDispatchThread} as task executor, and subscribers notifying in parallel thread depends on
 * {@link EventDispatchThread} implementation passed via constructor.<br>
 * <p>
 * Usage examples:
 * <pre>
 * {@code
 *
 *     // Publisher creating and subscribing
 *     EventDispatchThread edt = new EventDispatchThread();
 *     Publisher publisher = new Publisher(edt)
 *             .subscribe(to(SingleEvent.class, singleEventPayload -> handleOnce(singleEventPayload)))
 *             .subscribe(to(SomeEvent.class, someEventPayload -> doSmthWith(someEventPayload)))
 *             .subscribe(to(SomeEvent.class, someEventPayload -> doSmthWithElse(someEventPayload);}))
 *             .subscribe(to(AnotherEvent.class, SubscriberClass::handleAnotherEventPayload))
 *             .subscribe(to(OneMoreEvent.class, subscriberInstance::processOneMoreEventPayload)
 *                     .conditionally(oneMoreEventPayload -> shouldHandleOrNot(oneMoreEventPayload)));
 *
 *     // Publishing events
 *     publisher
 *             .publish(new OneMoreEvent())    // will fire processOneMoreEventPayload if shouldHandleOrNot return true
 *             .publish(new SomeEvent())       // will fire doSmthWith and doSmthWithElse with the same payload argument
 *             .publish(new UnknownEvent())    // do nothing, because there is no subscription for this event type
 *             .publish(new SingleEvent())     // will fire handleOnce and unsubscribe all subscribers of this event type
 *             .publish(new SingleEvent());    // do nothing, because there is no subscription for this event type
 * }
 * </p>
 *
 * @see EventDispatchThread
 * @see Subscription
 * @see Event
 * @see SignalEvent
 * @see Single
 *
 * @author Eugene Shevchenko
 */
public class Publisher {

    private static final Logger log = LoggerFactory.getLogger("events");

    private class Command implements Runnable {
        private final List<Subscription> subscriptions;
        private final Event event;

        private Command(List<Subscription> subscriptions, Event event) {
            this.subscriptions = subscriptions;
            this.event = event;
        }

        @Override
        public void run() {
            subscriptions.forEach(subscription -> subscription.handle(event));
        }

        @Override
        public String toString() {
            return format("%s: consumed by %d subscriber(s).", event, subscriptions.size());
        }
    }

    private final Executor executor;
    private final Map<Class<? extends Event>, List<Subscription>> subscriptionsByEvent = new ConcurrentHashMap<>();

    public Publisher(Executor executor) {
        this.executor = executor;
    }

    /**
     * Publishes specified event for all its subscribers.<br>
     * Concurrent execution depends on implementation of nested {@link EventDispatchThread}.
     *
     * @param event event to publish;
     * @return current {@link Publisher} instance to support fluent API.
     */
    public Publisher publish(Event event) {
        List<Subscription> subscriptions = subscriptionsByEvent.getOrDefault(event.getClass(), emptyList());
        if (!subscriptions.isEmpty()) {

            List<Subscription> toHandle = subscriptions.stream()
                    .filter(subscription -> subscription.matches(event))
                    .collect(toList());

            subscriptions.stream()
                    .filter(subscription -> subscription.needUnsubscribeAfter(event))
                    .forEach(this::unsubscribe);

            if (event instanceof Single) {
                subscriptionsByEvent.remove(event.getClass());
            }


            if (!toHandle.isEmpty()) {
                executor.execute(new Command(toHandle, event));
            }
        }

        return this;
    }

    /**
     * Adds specified {@link Subscription} to publisher.<br>
     * Do nothing if specified subscription already added.
     *
     * @param subscription
     * @param <E>          {@link Event} subclass which describes specific event type;
     * @param <P>          payload type of specified event type;
     * @return current {@link Publisher} instance to support fluent API.
     */
    public <E extends Event<P>, P> Publisher subscribe(Subscription<E, P> subscription) {
        List<Subscription> subscriptions = subscriptionsByEvent.computeIfAbsent(subscription.getEventType(), t -> new CopyOnWriteArrayList<>());
        if (subscriptions.contains(subscription)) {
            log.warn("Specified subscription already exists {}.", subscription.getEventType().getSimpleName());
        } else {
            subscriptions.add(subscription);
        }
        return this;
    }

    /**
     * Creates {@link Subscription} from specified parameters and adds it to current publisher.
     *
     * @param eventType even's type to subscribe;
     * @param handler   callback that will be invoked after event will be published;
     * @param <E>       event's type;
     * @param <P>       payload of specified event;
     * @return created {@link Subscription} instance.
     */
    public <E extends Event<P>, P> Subscription<E, P> subscribe(Class<E> eventType, Consumer<P> handler) {
        Subscription<E, P> subscription = new Subscription<>(eventType, handler);
        subscribe(subscription);
        return subscription;
    }

    /**
     * Removes specified {@link Subscription} from publisher.
     *
     * @param subscription subscription to remove;
     * @return current {@link Publisher} instance to support fluent API.
     */
    public Publisher unsubscribe(Subscription subscription) {
        List<Subscription> subscriptions = subscriptionsByEvent.get(subscription.getEventType());
        if (nonNull(subscriptions)) {
            subscriptions.remove(subscription);
            if (subscriptions.isEmpty()) {
                subscriptionsByEvent.remove(subscription.getEventType());
            }
        }

        return this;
    }

    /**
     * Calculates specific event type {@link Subscription}s amount added to current {@link Publisher}.
     *
     * @param eventType event type to filter {@link Subscription}s;
     * @return specified event type {@link Subscription}s count.
     */
    public int subscribersCount(Class<? extends Event> eventType) {
        return subscriptionsByEvent.getOrDefault(eventType, emptyList()).size();
    }

    /**
     * Calculates total amount {@link Subscription}s added to current {@link Publisher}.
     *
     * @return all subscribers total count.
     */
    public int subscribersCount() {
        return subscriptionsByEvent.values().stream()
                .mapToInt(List::size)
                .sum();
    }

    /**
     * Method that subscribes all {@link EthereumListener} callbacks to corresponding events.
     * Avoid using this method directly, because it creates unnecessary stub {@link Subscription}s.
     * Uses for backward compatibility only and will be removed in future releases.
     *
     * @param listener highly likely {@link org.ethereum.listener.EthereumListenerAdapter} subclass.
     */
    public void subscribeListener(EthereumListener listener) {
        this
                .subscribe(to(Trace.class, listener::trace))
                .subscribe(to(NodeDiscovered.class, listener::onNodeDiscovered))
                .subscribe(to(PeerHandshaked.class, data -> listener.onHandShakePeer(data.getChannel(), data.getMessage())))
                .subscribe(to(EthStatusUpdated.class, data -> listener.onEthStatusUpdated(data.getChannel(), data.getMessage())))
                .subscribe(to(MessageReceived.class, data -> listener.onRecvMessage(data.getChannel(), data.getMessage())))
                .subscribe(to(MessageSent.class, data -> listener.onSendMessage(data.getChannel(), data.getMessage())))
                .subscribe(to(BlockAdded.class, listener::onBlock))
                .subscribe(to(BestBlockAdded.class, data -> listener.onBlock(data.getBlockSummary(), data.isBest())))
                .subscribe(to(PeerDisconnected.class, data -> listener.onPeerDisconnect(data.getHost(), data.getPort())))
                .subscribe(to(PendingTransactionsReceived.class, listener::onPendingTransactionsReceived))
                .subscribe(to(PendingStateChanged.class, listener::onPendingStateChanged))
                .subscribe(to(PendingTransactionUpdated.class, data -> listener.onPendingTransactionUpdate(data.getReceipt(), data.getState(), data.getBlock())))
                .subscribe(to(SyncDone.class, listener::onSyncDone))
                .subscribe(to(NoConnections.class, data -> listener.onNoConnections()))
                .subscribe(to(VmTraceCreated.class, data -> listener.onVMTraceCreated(data.getTxHash(), data.getTrace())))
                .subscribe(to(TransactionExecuted.class, listener::onTransactionExecuted))
                .subscribe(to(PeerAddedToSyncPool.class, listener::onPeerAddedToSyncPool));
    }

    /**
     * Creates backward compatibility adaptor for {@link EthereumListener}.
     * Uses for backward compatibility only and will be removed in future releases.
     *
     * @return instance of EthereumListener that proxies all method invokes to publishing corresponding events.
     */
    public EthereumListener asListener() {
        return new EthereumListener() {
            @Override
            public void trace(String output) {
                publish(new Trace(output));
            }

            @Override
            public void onNodeDiscovered(Node node) {
                publish(new NodeDiscovered(node));
            }

            @Override
            public void onHandShakePeer(Channel channel, HelloMessage helloMessage) {
                publish(new PeerHandshaked(channel, helloMessage));
            }

            @Override
            public void onEthStatusUpdated(Channel channel, StatusMessage status) {
                publish(new EthStatusUpdated(channel, status));
            }

            @Override
            public void onRecvMessage(Channel channel, Message message) {
                publish(new MessageReceived(channel, message));
            }

            @Override
            public void onSendMessage(Channel channel, Message message) {
                publish(new MessageSent(channel, message));
            }

            @Override
            public void onBlock(BlockSummary blockSummary) {
                publish(new BlockAdded(blockSummary));
            }

            @Override
            public void onBlock(BlockSummary blockSummary, boolean best) {
                publish(new BestBlockAdded(blockSummary, best));
            }

            @Override
            public void onPeerDisconnect(String host, long port) {
                publish(new PeerDisconnected(host, port));
            }

            @Override
            public void onPendingTransactionsReceived(List<Transaction> transactions) {
                publish(new PendingTransactionsReceived(transactions));
            }

            @Override
            public void onPendingStateChanged(PendingState pendingState) {
                publish(new PendingStateChanged(pendingState));
            }

            @Override
            public void onPendingTransactionUpdate(TransactionReceipt txReceipt, PendingTransactionState state, Block block) {
                publish(new PendingTransactionUpdated(block, txReceipt, state));
            }

            @Override
            public void onSyncDone(SyncState state) {
                publish(new SyncDone(state));
            }

            @Override
            public void onNoConnections() {
                publish(new NoConnections());
            }

            @Override
            public void onVMTraceCreated(String transactionHash, String trace) {
                publish(new VmTraceCreated(transactionHash, trace));
            }

            @Override
            public void onTransactionExecuted(TransactionExecutionSummary summary) {
                publish(new TransactionExecuted(summary));
            }

            @Override
            public void onPeerAddedToSyncPool(Channel peer) {
                publish(new PeerAddedToSyncPool(peer));
            }
        };
    }
}
