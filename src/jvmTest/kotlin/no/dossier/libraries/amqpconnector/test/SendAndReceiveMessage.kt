package no.dossier.libraries.amqpconnector.test

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import no.dossier.libraries.amqpconnector.dsl.AmqpConnectorRole.PublisherAndConsumer
import no.dossier.libraries.amqpconnector.dsl.connector
import no.dossier.libraries.amqpconnector.dsl.consumer
import no.dossier.libraries.amqpconnector.dsl.publisher
import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.amqpconnector.error.AmqpError
import no.dossier.libraries.amqpconnector.error.AmqpPublishingError
import no.dossier.libraries.amqpconnector.primitives.AmqpMessage
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success
import no.dossier.libraries.stl.suspendCancellableCoroutineWithTimeout
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.coroutines.resume

@Testcontainers
class SendAndReceiveMessageTest {
    @Container
    val rabbitmqContainer: RabbitMQContainer =
        RabbitMQContainer(DockerImageName.parse("rabbitmq:3.7.25-management-alpine"))

    @Test
    fun testSendAndReceive() {
        lateinit var continuation: CancellableContinuation<Outcome<AmqpConsumingError, String>>

        fun sampleProcessingFunction(message: AmqpMessage<String>): Outcome<AmqpConsumingError, Unit> {
            continuation.resume(Success(message.payload))
            return Success(Unit)
        }

        val connector = connector(role = PublisherAndConsumer) {
            connectionString = "amqp://${rabbitmqContainer.adminUsername}:${rabbitmqContainer.adminPassword}" +
                    "@${rabbitmqContainer.containerIpAddress}:${rabbitmqContainer.amqpPort}/"

            consumer(::sampleProcessingFunction) {
                workersCoroutineScope = CoroutineScope(Dispatchers.Default)
                exchange { name = "somedata-exchange" }
                bindingKey = "somedata.#"
            }
        }

        val publisher = connector.publisher {
            exchange { name = "somedata-exchange" }
            routingKey = "somedata.cool.special"
        }

        suspend fun sendSamplePublication(request: String): Outcome<AmqpPublishingError, Unit> =
            publisher(AmqpMessage(request))

        val outcome: Outcome<AmqpError, String> = runBlocking {
            sendSamplePublication("Test")

            suspendCancellableCoroutineWithTimeout(5_000, {
                AmqpConsumingError("Timeout")
            }, {
                continuation = it
            })
        }

        when(outcome) {
            is Failure -> fail(outcome.error.message)
            is Success -> Assertions.assertEquals("Test", outcome.value)
        }

    }
}