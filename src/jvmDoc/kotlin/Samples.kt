/*
import kotlinx.serialization.Serializable
import no.dossier.coursedirectory.config.ApplicationCoroutineScope
import no.dossier.libraries.amqpconnector.rabbitmq.*
import no.dossier.libraries.amqpconnector.rabbitmq.AmqpConnectorRole.*
import no.dossier.libraries.functional.*
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Serializable
data class SampleRequest(val value: String)
@Serializable
data class SampleReply(val value: String)
@Serializable
data class SamplePayload(val value: String)

@Component
@Scope("singleton")
class ExamplePublishingResource {
    private val connector = connector(role = Publisher) {
        connectionString = "amqp://guest:guest@localhost:5672/"
        clientName = "sample-dossier-app"
    }

    private val samplePublisher = connector.publisher {
        exchange {
            type = AmqpExchangeType.TOPIC
            name = "sample-dossier-app-ref-data-outbound"
        }
        routingKey = "samplepayloads.cool.small"
    }

    /*
     * Can be called anywhere from the application where ExamplePublishingResource is injected
     */
    fun publishSamplePayload(samplePayload: SamplePayload) = samplePublisher(samplePayload)
}

@Component
@Scope("singleton")
class ExampleConsumingResource(
    private val applicationCoroutineScope: ApplicationCoroutineScope
) {
    init {
        connector(role = Consumer) {
            connectionString = "amqp://guest:guest@localhost:5672/"
            clientName = "sample-dossier-app"

            consumer(::processSamplePayloads) {
                numberOfWorkers = 8
                workersCoroutineScope = applicationCoroutineScope
                exchange {
                    name = "sample-dossier-app-ref-data-outbound"
                }
                queue {
                    name = "sample-dossier-app-ref-data-inbound"
                    durable = true
                    exclusive = false
                    autoDelete = false
                }
                deadLetterForwarding {
                    enabled = true
                }
                bindingKey = "samplepayloads.#"
            }
        }
    }

    private fun processSamplePayloads(message: AmqpMessage<SamplePayload>): Result<Unit, AmqpConsumingError> {
        // process message.payload of type SamplePayload

        /*
         * return value of processing function is used for sending ACK or REJECT to the broker (Success or Failure)
         * if dead letter forwarding is enabled and the result is Failure then the message is automatically
         * forwarded to dead-letter exchange (called error) by default
         */
        return Success(Unit)
    }
}

@Component
@Scope("singleton")
class ExampleRpcServerResource(
    private val applicationCoroutineScope: ApplicationCoroutineScope
) {
    init {
        connector(role = Consumer) {
            connectionString = "amqp://guest:guest@localhost:5672/"
            clientName = "sample-dossier-app"

            consumer(::processSampleRequests) {
                workersCoroutineScope = applicationCoroutineScope
                numberOfWorkers = 1
                replyingMode = AmqpReplyingMode.Always
                exchange {
                    type = AmqpExchangeType.DEFAULT
                }
                queue {
                    name = "profile-sample-rpc"
                    durable = true
                    exclusive = false
                    autoDelete = false
                }
            }
        }
    }

    /*
     * Similar processing function as in previous example...
     * A non-Unit result is used as a reply payload (only if replyingMode is set to Always or IfRequired)
     */
    private fun processSampleRequests(message: AmqpMessage<SampleRequest>) =
        Success(SampleReply("test: ${message.payload.value}"))
}

@Component
@Scope("singleton")
class ExampleRpcClientResource(
    private val applicationCoroutineScope: ApplicationCoroutineScope
) {
    private val connector = connector(role = PublisherAndConsumer) {
        connectionString = "amqp://guest:guest@localhost:5672/"
        clientName = "sample-dossier-app"
    }

    val sampleRpcClient = connector.rpcClient<SampleReply> {
        routingKey = "profile-sample-rpc"
        workersCoroutineScope = applicationCoroutineScope
    }

    /*
     * Can be called anywhere from the application where ExampleRpcClientResource is injected
     * But as this is suspending function, must be called from coroutine context..
     */
    suspend fun getSample(requestPayload: SampleRequest): Result<SampleReply, AmqpError> {
        return sampleRpcClient(requestPayload)
    }
}*/