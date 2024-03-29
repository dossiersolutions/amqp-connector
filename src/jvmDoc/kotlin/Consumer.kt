@file:Suppress("MemberVisibilityCanBePrivate", "unused", "UNUSED_PARAMETER")

package no.dossier.libraries.amqpconnector.samples

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import no.dossier.libraries.amqpconnector.consumer.AmqpReplyingMode
import no.dossier.libraries.amqpconnector.dsl.ConsumingAmqpConnectorConfigPrototype
import no.dossier.libraries.amqpconnector.dsl.consumer
import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.amqpconnector.primitives.AmqpBindingKey.Custom
import no.dossier.libraries.amqpconnector.primitives.AmqpExchangeType
import no.dossier.libraries.amqpconnector.primitives.AmqpInboundMessage
import no.dossier.libraries.amqpconnector.primitives.DeadLetterRoutingKey
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success

/* A sample message processing function */
fun sampleProcessingFunction(message: AmqpInboundMessage<String>): Outcome<AmqpConsumingError, Unit> {
    /*
     * The Outcome indicates whether the processing was successful and the message should be ACKed
     * or not and the message should be rejected. Also, when implementing RPC server, the Success value
     * will be used as a reply to the message
     */
    return Success(Unit)
}

fun ConsumingAmqpConnectorConfigPrototype.sampleConsumerWithTemporaryQueue() {
    /*
     * Definition of simple consumer, which uses a temporary
     * (non-durable, exclusive and auto-delete) queue, with random name, generated by the broker.
     * The consumer is bound to "some-ref-data" exchange of type "topic" (default option) and it uses a binding key
     * "refdata.*.user.#", which means all messages with routing key in form of:
     * "refdata.[any word here].user.[any.number.of.other.words.here]" passed to the specified exchange will be consumed
     */
    consumer(::sampleProcessingFunction) {
        messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
        exchange {
            name = "some-ref-data"
        }
        bindingKey = Custom("refdata.*.user.#")
    }
}

fun ConsumingAmqpConnectorConfigPrototype.sampleConsumerWithPersistentQueue() {
    /*
     * In this example, as opposed to the previous one, the consumer uses a durable,
     * non-exclusive and non-auto-deletable queue (with explicitly specified name).
     * This means the queue remains existing even when the broker is restarted
     * or the connection to the broker is closed, and it allows multiple clients to attach
     * and consume the messages concurrently
     */
    consumer(::sampleProcessingFunction) {
        messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
        exchange {
            name = "some-ref-data"
        }
        queue {
            name = "dossier-sample-app-ref-data-inbound"
            durable = true
            exclusive = false
            autoDelete = false
        }
        bindingKey = Custom("refdata.*.type.#")
    }
}

fun ConsumingAmqpConnectorConfigPrototype.consumerWithExhaustiveConfiguration() {
    /*
     * An example with exhaustive configuration options
     *
     * Compared to the previous example, it specifies larger processing pipe buffer,
     * it enables enforced replying to every consumed message, and it specifies dead-letter forwarding
     * (further routing of rejected message)
     */
    consumer(::sampleProcessingFunction) {
        messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default) // Mandatory value
        exchange {
            name = "some-ref-data" // Default: "" (default exchange)
            type = AmqpExchangeType.DIRECT // Default: AmqpExchangeType.TOPIC
            durable = true // Default: false
        }
        queue {
            name = "dossier-sample-app-ref-data-inbound" // Default: "" (server generated name)
            durable = true // Default: false
            exclusive = false // Default: true
            autoDelete = false // Default: true
        }
        replyingMode = AmqpReplyingMode.Always // Default: AmqpReplyingMode.Never
        bindingKey = Custom("refdata.*.type.#") // Default: "#" (wildcard for everything)
        deadLetterForwarding {
            enabled = true // Default: false
            routingKey = DeadLetterRoutingKey.SameAsOriginalMessage // Default: DeadLetterRoutingKey.OriginalQueueName
            implicitQueueEnabled = false // Default: true
            exchange {
                type = AmqpExchangeType.DIRECT // Default: "" (default exchange)
                name = "some-ref-data-error-queue" // Default: AmqpExchangeType.TOPIC
            }
        }
        onMessageConsumed = { message ->
            //log message
        } // Default: { _ -> }
        onMessageRejected = { message ->
            //log message
        } // Default: { _ -> }
        onMessageReplyPublished = { message, actualRoutingKey ->
            //log message
        } // Default: { _ -> }
        autoAckEnabled = true // Default: false
        prefetchCount = 2000 // Default: 5000
    }
}
