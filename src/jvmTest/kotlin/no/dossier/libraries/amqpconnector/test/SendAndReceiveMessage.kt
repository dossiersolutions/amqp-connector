package no.dossier.libraries.amqpconnector.test

import kotlinx.coroutines.*
import no.dossier.libraries.amqpconnector.dsl.AmqpConnectorRole.PublisherAndConsumer
import no.dossier.libraries.amqpconnector.dsl.connector
import no.dossier.libraries.amqpconnector.dsl.consumer
import no.dossier.libraries.amqpconnector.dsl.publisher
import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.amqpconnector.error.AmqpError
import no.dossier.libraries.amqpconnector.error.AmqpPublishingError
import no.dossier.libraries.amqpconnector.primitives.AmqpMessage
import no.dossier.libraries.amqpconnector.test.utils.SuspendableSignalAwaiterWithTimeout
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success
import org.junit.jupiter.api.*
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SendAndReceiveMessageTest {

    class SampleAmqpService(
        private val brokerConnectionString: String,
        private val onMessage: (AmqpMessage<String>) -> Outcome<AmqpConsumingError, Unit>
    ) {
        private val connector = connector(role = PublisherAndConsumer) {
            connectionString = brokerConnectionString

            consumer(onMessage) {
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

        fun shutdown() { connector.shutdown() }
    }

    @Container
    val rabbitMQContainer: RabbitMQContainer =
        RabbitMQContainer(DockerImageName.parse("rabbitmq:3.7.25-management-alpine"))

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

        sampleAmqpService = SampleAmqpService(connectionString) { message ->
            signalAwaiter.resume(Success(message.payload))
            Success(Unit)
        }
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
            is Success -> Assertions.assertEquals("Test", receivingOutcome.value)
        }
    }
}