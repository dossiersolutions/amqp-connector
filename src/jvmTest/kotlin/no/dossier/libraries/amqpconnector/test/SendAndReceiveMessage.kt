package no.dossier.libraries.amqpconnector.test

import kotlinx.coroutines.*
import no.dossier.libraries.amqpconnector.dsl.AmqpConnectorRole.PublisherAndConsumer
import no.dossier.libraries.amqpconnector.dsl.connector
import no.dossier.libraries.amqpconnector.dsl.consumer
import no.dossier.libraries.amqpconnector.dsl.publisher
import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.amqpconnector.error.AmqpError
import no.dossier.libraries.amqpconnector.error.AmqpPublishingError
import no.dossier.libraries.amqpconnector.primitives.AmqpBindingKey.Custom
import no.dossier.libraries.amqpconnector.primitives.AmqpInboundMessage
import no.dossier.libraries.amqpconnector.primitives.AmqpOutboundMessage
import no.dossier.libraries.amqpconnector.primitives.AmqpRoutingKey
import no.dossier.libraries.amqpconnector.test.utils.SuspendableSignalAwaiterWithTimeout
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.testcontainers.containers.Network
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SendAndReceiveMessageTest {

    class SampleAmqpService(
        private val brokerConnectionString: String,
        private val onSomeDataMessage: (AmqpInboundMessage<String>) -> Outcome<AmqpConsumingError, Unit>,
        private val onOtherMessage: (AmqpInboundMessage<String>) -> Outcome<AmqpConsumingError, Unit>
    ) {
        private val connector = connector(role = PublisherAndConsumer) {
            connectionString = brokerConnectionString

            consumer(onSomeDataMessage) {
                messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
                exchange { name = "somedata-exchange" }
                bindingKey = Custom("somedata.#")
            }

            consumer(onOtherMessage) {
                messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
                exchange { name = "somedata-exchange" }
                bindingKey = Custom("other.#")
            }
        }

        val publisher = connector.publisher {
            exchange { name = "somedata-exchange" }
            routingKey = "somedata.cool.special"
        }

        suspend fun sendSamplePublication(request: String): Outcome<AmqpPublishingError, Unit> =
            publisher(AmqpOutboundMessage(request))

        suspend fun sendSamplePublication(request: String, routingKey: String): Outcome<AmqpPublishingError, Unit> =
            publisher(AmqpOutboundMessage(request, routingKey = AmqpRoutingKey.Custom(routingKey)))

        fun shutdown() {
            connector.shutdown()
        }
    }

    @Container
    val rabbitMQContainer: RabbitMQContainer = DossierRabbitMqContainer(Network.newNetwork(), "rabbitmq")

    lateinit var sampleAmqpService: SampleAmqpService
    lateinit var signalAwaiter: SuspendableSignalAwaiterWithTimeout<AmqpError, String>

    @BeforeAll
    fun initialize() {
        rabbitMQContainer.start()

        signalAwaiter = SuspendableSignalAwaiterWithTimeout(
            timeoutError = AmqpConsumingError("Timeout")
        )

        val connectionString = "amqp://${rabbitMQContainer.adminUsername}:${rabbitMQContainer.adminPassword}" +
                "@${rabbitMQContainer.containerIpAddress}:${rabbitMQContainer.amqpPort}/"

        sampleAmqpService = SampleAmqpService(
            brokerConnectionString = connectionString,
            onSomeDataMessage = { message ->
                val expectedRoutingKey = "somedata.cool.special"
                if (message.routingKey == expectedRoutingKey) {
                    signalAwaiter.resume(Success(message.payload))
                }
                else {
                    signalAwaiter.resume(Failure(AmqpConsumingError(
                        "Expected routing key: $expectedRoutingKey, but was: ${message.routingKey}"
                    )))
                }
                Success(Unit)
            },
            onOtherMessage = { message ->
                val expectedRoutingKey = "other.data"
                if (message.routingKey == expectedRoutingKey) {
                    signalAwaiter.resume(Success("other: ${message.payload}"))
                }
                else {
                    signalAwaiter.resume(Failure(AmqpConsumingError(
                        "Expected routing key: $expectedRoutingKey, but was: ${message.routingKey}"
                    )))
                }
                Success(Unit)
            }
        )
    }

    @AfterAll
    fun teardown() {
        sampleAmqpService.shutdown()
        rabbitMQContainer.stop()
    }

    @Test
    fun testSendAndReceive() {
        val receivingOutcome = runBlocking {
            signalAwaiter.runAndAwaitSignal {
                runBlocking {
                    sampleAmqpService.sendSamplePublication("Test")
                }
            }
        }

        when (receivingOutcome) {
            is Failure -> fail(receivingOutcome.error.message)
            is Success -> assertEquals("Test", receivingOutcome.value)
        }
    }

    @Test
    fun `should be possible to override routing in publisher invocations`() {
        val receivingOutcome = runBlocking {
            signalAwaiter.runAndAwaitSignal {
                runBlocking {
                    sampleAmqpService.sendSamplePublication("Test", "other.data")
                }
            }
        }

        when (receivingOutcome) {
            is Failure -> fail(receivingOutcome.error.message)
            is Success -> assertEquals("other: Test", receivingOutcome.value)
        }
    }
}
