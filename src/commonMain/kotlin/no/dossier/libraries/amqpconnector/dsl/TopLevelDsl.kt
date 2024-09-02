@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package no.dossier.libraries.amqpconnector.dsl

import kotlinx.serialization.serializer
import no.dossier.libraries.amqpconnector.connector.*
import no.dossier.libraries.amqpconnector.consumer.AmqpConsumer
import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.amqpconnector.primitives.*
import no.dossier.libraries.amqpconnector.publisher.AmqpPublisher
import no.dossier.libraries.amqpconnector.rpc.AmqpRpcClient
import no.dossier.libraries.functional.*

/**
 * [AmqpConnector] maintains separate inbound and outbound connections to the broker. For applications
 * which don't need bi-directional message flow, it is beneficial to use a specific subtype
 * of the connector which creates either only inbound or only outbound connection.
 *
 * The [AmqpConnectorRole] implies which specific [AmqpConnectorFactory] and which specific
 * [AmqpConnectorConfigPrototype] will be used for construction of the connector
 *
 * @see AmqpConnector
 * @see AmqpConnectorFactory
 */
sealed class AmqpConnectorRole<
        P: AmqpConnectorConfigPrototype<out AmqpConnectorConfig>,
        F: AmqpConnectorFactory<out AmqpConnector, out AmqpConnectorConfig>> {

    /**
     * [Publisher] role implies construction of [PublishingAmqpConnectorImpl].
     * Only publishers ([AmqpPublisher]) can be used with this connector.
     */
    object Publisher
        : AmqpConnectorRole<GenericAmqpConnectorConfigPrototype, PublishingAmqpConnectorFactory>() {

        override val connectorFactory get() = PublishingAmqpConnectorFactory
        override val connectorConfigPrototypeCtor get() = ::GenericAmqpConnectorConfigPrototype
    }

    /**
     * [Consumer] role implies construction of [ConsumingAmqpConnector]
     * Only consumers ([AmqpConsumer]) can be used with this connector.
     */
    object Consumer
        : AmqpConnectorRole<ConsumingAmqpConnectorConfigPrototype, ConsumingAmqpConnectorFactory>() {

        override val connectorFactory get() = ConsumingAmqpConnectorFactory
        override val connectorConfigPrototypeCtor get() = ::ConsumingAmqpConnectorConfigPrototype
    }

    /**
     * [PublisherAndConsumer] role implies construction of [PublishingConsumingAmqpConnectorImpl]
     * Consumers ([AmqpConsumer]), publishers ([AmqpPublisher]) and RPC clients ([AmqpRpcClient])
     * can be used with this connector.
     */
    object PublisherAndConsumer
        : AmqpConnectorRole<ConsumingAmqpConnectorConfigPrototype, PublishingConsumingAmqpConnectorFactory>() {

        override val connectorFactory get() = PublishingConsumingAmqpConnectorFactory
        override val connectorConfigPrototypeCtor get() = ::ConsumingAmqpConnectorConfigPrototype
    }

    abstract val connectorFactory: F
    abstract val connectorConfigPrototypeCtor: () -> P
}

/**
 * Calling the [connector] DSL function is a preferred way of instantiating [AmqpConnector].
 * The [AmqpConnector] has few different implementations, providing different numbers of underlying AMQP connections
 * for publishing and consuming messages. A specific implementation is chosen based on the [role] parameter.
 *
 * The role parameter also determines which implementation of [AmqpConnectorConfigPrototype] will be used
 * as a receiver to the [builderBlock]. Each configuration prototype implementation offers different set of
 * attributes and functions which can be used for configuration of the connector.
 *
 * Note that connections are automatically established upon creation of a connector instance. If a connection is lost,
 * the connector tries to recover it automatically. However, if a connection cannot be established yet at the beginning,
 * the [connector] will throw a RuntimeException.
 *
 * @param C Specific type of [AmqpConnector]
 * @param S Specific type of [AmqpConnectorConfig]
 * @param F Specific type of [AmqpConnectorFactory]
 * @param P Specific type of [AmqpConnectorConfigPrototype]
 * @param R Specific type of [AmqpConnectorRole]
 * @param role Implies which [AmqpConnectorFactory] and [AmqpConnectorConfigPrototype] will be used,
 * and thus which [AmqpConnector] implementation will be constructed
 * @param builderBlock A contextual lambda applied to [AmqpConnectorConfigPrototype]
 * in order to configure the connector.
 * @return An instance of a specific subtype of [AmqpConnector]
 * @throws RuntimeException if the connector cannot be instantiated
 *
 * @see consumer Consumer initialization and configuration
 * @see publisher Publisher initialization and configuration
 * @see rpcClient RPC Client initialization and configuration
 * @see AmqpConnector Connector types, its lifecycle, description of underlying connections
 * @see AmqpConnectorRole Connector types and use-cases
 * @see AmqpConnectorConfigPrototype Configuration attributes and functions
 * @sample no.dossier.libraries.amqpconnector.samples.basicConsumingConnectorInitialization
 * @sample no.dossier.libraries.amqpconnector.samples.basicPublishingConnectorInitialization
 * @sample no.dossier.libraries.amqpconnector.samples.basicPublishingAndConsumingConnectorInitialization
 * @sample no.dossier.libraries.amqpconnector.samples.basicSpringIntegration
 */
fun <C, S, F: AmqpConnectorFactory<C, S>, P: AmqpConnectorConfigPrototype<S>, R: AmqpConnectorRole<P, F>> connector(
    role: R,
    builderBlock: P.() -> Unit
): C = role.connectorConfigPrototypeCtor()
    .apply(builderBlock).build()
    .andThen { configuration -> role.connectorFactory.create(configuration) }
    .unwrap()

/**
 * Defines new [AmqpConsumer] within the scope of [AmqpConnector]. Consumers defined this way are
 * non-cancellable, which means that their lifecycle is the same as the lifecycle of the whole connector.
 *
 * The consumer is configured using a set of attributes and functions on [AmqpConsumerPrototype]
 * (the receiver of the [builderBlock] contextual lambda).
 * Check [AmqpConsumerPrototype] for details regarding each configuration option.
 *
 * @param T Type of payload of the messages this consumer is supposed to consume
 * @param U Type of payload of the reply messages (Usually [Unit] when replying is not configured)
 * @param messageHandler Typically reference to a function or a lambda
 * which is supposed to process the incoming messages
 * @param builderBlock A contextual lambda applied to [AmqpConsumerPrototype]
 * in order to configure the consumer.
 * @return An instance of [AmqpConsumer]
 * @throws RuntimeException if the consumer cannot be instantiated
 *
 * @see AmqpConsumer
 * @see AmqpConsumerPrototype
 * @Sample no.dossier.libraries.amqpconnector.samples.sampleConsumerWithTemporaryQueue
 * @Sample no.dossier.libraries.amqpconnector.samples.sampleConsumerWithPersistentQueue
 * @Sample no.dossier.libraries.amqpconnector.samples.consumerWithExhaustiveConfiguration
 */
inline fun <reified T: Any, reified U: Any> ConsumingAmqpConnectorConfigPrototype.consumer(
    noinline messageHandler: suspend (AmqpInboundMessage<T>) ->  Outcome<AmqpConsumingError, U>,
    builderBlock: AmqpConsumerPrototype<T>.() -> Unit,
): AmqpConsumer<T, U> {
    val consumer = AmqpConsumerPrototype<T>()
        .apply(builderBlock).build(messageHandler, serializer(), serializer())

    consumerBuilderOutcomes += consumer
    return consumer.unwrap()
}

/**
 * Defines new [AmqpPublisher] within the scope of [AmqpConnector].
 *
 * The publisher is configured using a set of attributes and functions on [AmqpPublisherPrototype]
 * (the receiver of the [builderBlock] contextual lambda).
 * Check [AmqpPublisherPrototype] for details regarding each configuration option.
 *
 * @param builderBlock A contextual lambda applied to [AmqpPublisherPrototype]
 * in order to configure the consumer.
 * @return An instance of [AmqpPublisher]
 * @throws RuntimeException if the publisher cannot be instantiated
 *
 * @see AmqpPublisher
 * @see AmqpPublisherPrototype
 * @sample no.dossier.libraries.amqpconnector.samples.samplePublisherPublishingToSpecificQueueWithoutConfirmations
 * @sample no.dossier.libraries.amqpconnector.samples.samplePublisherPublishingToTopicExchange
 * @sample no.dossier.libraries.amqpconnector.samples.samplePublisherWithExhaustiveConfiguration
 * @sample no.dossier.libraries.amqpconnector.samples.sampleSendMethod
 * @sample no.dossier.libraries.amqpconnector.samples.sampleSendLotOfMessages
 */
fun PublishingAmqpConnector.publisher(
    builderBlock: AmqpPublisherPrototype.() -> Unit
): AmqpPublisher =
    AmqpPublisherPrototype()
        .apply(builderBlock).build(publishingConnection)
        .unwrap()

/**
 * Defines new [AmqpRpcClient] within the scope of [AmqpConnector].
 *
 * The RPC client is configured using a set of attributes and functions on [AmqpRpcClientPrototype]
 * (the receiver of the [builderBlock] contextual lambda).
 * Check [AmqpRpcClientPrototype] for details regarding each configuration option.
 *
 * @param U Type of response payload (note that the request payload is bound only on invocation of the RPC client)
 * @param builderBlock A contextual lambda applied to [AmqpRpcClientPrototype]
 * in order to configure the consumer.
 * @return An instance of [AmqpRpcClient]
 * @throws RuntimeException if the RPC client cannot be instantiated
 *
 * @see AmqpRpcClient
 * @see AmqpRpcClientPrototype
 * @sample no.dossier.libraries.amqpconnector.samples.sampleRpcClient
 */
inline fun <reified U: Any> PublishingConsumingAmqpConnectorImpl.rpcClient(
    builderBlock: AmqpRpcClientPrototype<U>.() -> Unit
): AmqpRpcClient<U> =
    AmqpRpcClientPrototype<U>()
        .apply(builderBlock)
        .build(consumingConnection, publishingConnection, serializer())
        .unwrap()
