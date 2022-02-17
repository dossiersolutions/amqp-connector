@file:Suppress("MemberVisibilityCanBePrivate", "unused", "UNUSED_VARIABLE")

package no.dossier.libraries.amqpconnector.samples

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.dossier.libraries.amqpconnector.dsl.AmqpConnectorRole.Publisher
import no.dossier.libraries.amqpconnector.dsl.connector
import no.dossier.libraries.amqpconnector.dsl.publisher
import no.dossier.libraries.amqpconnector.error.AmqpPublishingError
import no.dossier.libraries.amqpconnector.primitives.AmqpExchangeType
import no.dossier.libraries.amqpconnector.primitives.AmqpInboundMessage
import no.dossier.libraries.amqpconnector.primitives.AmqpOutboundMessage
import no.dossier.libraries.functional.Outcome

fun samplePublisherPublishingToSpecificQueueWithoutConfirmations() {
    /* Creation of sample publisher, publishing to specific queue, with disabled confirmations from broker */

    val connector = connector(role = Publisher) {
        // connector setup here
    }

    val testPublisher = connector.publisher {
        exchange {
            type = AmqpExchangeType.DEFAULT
        }
        routingKey = "some-queue-name"
        confirmations = false
    }
}

fun samplePublisherPublishingToTopicExchange() {
    /* Creation of sample publisher, publishing to topic exchange */

    val connector = connector(role = Publisher) {
        // connector setup here
    }

    val testPublisher = connector.publisher {
        exchange {
            name = "somedata-exchange"
        }
        routingKey = "somedata.cool.special"
    }
}

fun samplePublisherWithExhaustiveConfiguration() {
    /* Creation of sample publisher with exhaustive configuration */

    val connector = connector(role = Publisher) {
        // connector setup here
    }

    val testPublisher = connector.publisher {
        exchange {
            type = AmqpExchangeType.DIRECT // Default: AmqpExchangeType.TOPIC
            name = "somedata-exchange" // Default: ""
        }
        routingKey = "somedata.cool.special" // Default: ""
        confirmations = true // Default: true
    }
}

fun sampleSendMethod() {
    /* Creation of sample send method */

    @Serializable
    data class SampleRequest(val value: String)

    val connector = connector(role = Publisher) {
        // connector setup here
    }

    val testPublisher = connector.publisher {
        // publisher setup here
    }

    /*
     * The request type is bound only on the invocation of the publisher, so it can be any custom
     * class serializable by kotlinx.serialization (SampleRequest in this case)
     */
    suspend fun sendTestPublication(request: SampleRequest): Outcome<AmqpPublishingError, Unit> =
        testPublisher(AmqpOutboundMessage(request))
}

fun sampleSendLotOfMessages() {
    /*
    * When publishing huge amount of message at the same time, always consider doing it in parallel,
    * it improves the performance significantly (especially when confirmations from the broker are enabled)
    */
    runBlocking {
        repeat(10_000) {
            launch {
                // call the send method here
            }
        }
    }
}