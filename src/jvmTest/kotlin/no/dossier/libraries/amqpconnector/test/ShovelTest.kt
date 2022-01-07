package no.dossier.libraries.amqpconnector.test

import kotlinx.coroutines.*
import no.dossier.libraries.amqpconnector.connector.PublishingConsumingAmqpConnectorImpl
import no.dossier.libraries.amqpconnector.consumer.AmqpReplyingMode
import no.dossier.libraries.amqpconnector.dsl.*
import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.amqpconnector.primitives.AmqpBindingKey
import no.dossier.libraries.amqpconnector.primitives.AmqpExchangeType
import no.dossier.libraries.amqpconnector.primitives.AmqpMessage
import no.dossier.libraries.amqpconnector.publisher.AmqpPublisher
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success
import no.dossier.libraries.functional.getOrElse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.containers.Network
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ShovelTest {
    private val network = Network.newNetwork()
    private val domain1Container = DossierRabbitMqContainer(network, "domain1")
    private val domain2Container = DossierRabbitMqContainer(network, "domain2")
    private val crossdomainContainer = DossierRabbitMqContainer(network, "crossdomain")

    private lateinit var domain1Connector: PublishingConsumingAmqpConnectorImpl
    private lateinit var domain2Connector: PublishingConsumingAmqpConnectorImpl
    private lateinit var crossdomainConnector: PublishingConsumingAmqpConnectorImpl

    private var suspended: Continuation<String>? = null

    @BeforeAll
    fun beforeAll() {
        startAmqpBrokers()
        addAmqpConnectors()
        runBlocking {
            domain1Container.waitForShovels()
            domain2Container.waitForShovels()
        }
    }

    @AfterAll
    fun stopContainers() {
        crossdomainContainer.stop()
        domain1Container.stop()
        domain2Container.stop()
    }

    @Test
    fun `should be possible to send messages from domain to crossdomain`() {
        val publisher = domain1Connector.publisher {
            routingKey = "crossdomain.key"
            exchange { name = "crossdomain" }
        }

        assertMessagesReceivedBy(publisher, "Crossdomain")
    }

    @Test
    fun `should be possible to send messages from crossdomain to domain`() {
        val publisher = crossdomainConnector.publisher {
            routingKey = "domain1.key"
            exchange { name = "crossdomain" }
        }

        assertMessagesReceivedBy(publisher, "Domain 1")
    }

    @Test
    fun `should be possible to send messages from domain to domain via crossdomain`() {
        val publisher = domain1Connector.publisher {
            routingKey = "domain2.key"
            exchange { name = "crossdomain" }
        }

        assertMessagesReceivedBy(publisher, "Domain 2")
    }

    @Test
    fun `should be possible to send rpc request from domain to crossdomain`() {
        val rpcClient = domain1Connector.rpcClient<String> {
            messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
            routingKey = "crossdomain.rpc"
            exchange { name = "crossdomain" }
            replyToExchange {
                name = "domain1-rpc-response"
                type = AmqpExchangeType.TOPIC
            }
        }

        runBlocking {
            val response = rpcClient("request").getOrElse { throw RuntimeException(it.error.message) }
            assertEquals("Crossdomain received: request", response)
        }
    }

    @Test
    fun `should be possible to send rpc request from domain to domain via crossdomain`() {
        val rpcClient = domain1Connector.rpcClient<String> {
            messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
            routingKey = "domain2.rpc"
            exchange { name = "crossdomain" }
            replyToExchange {
                name = "domain1-rpc-response"
                type = AmqpExchangeType.TOPIC
            }
        }

        runBlocking {
            val response = rpcClient("request").getOrElse { throw RuntimeException(it.error.message) }
            assertEquals("Domain 2 received: request", response)
        }
    }

    private fun startAmqpBrokers() {
        crossdomainContainer.start()

        domain1Container.withExchangeShovel(
            ExchangeShovel(
                name = "from-domain1-to-crossdomain",
                source = domain1Container,
                destination = crossdomainContainer,
                sourceExchange = "crossdomain",
                sourceExchangeKey = "crossdomain.#"
            ),
            ExchangeShovel(
                name = "from-crossdomain-to-domain1",
                source = crossdomainContainer,
                destination = domain1Container,
                sourceExchange = "crossdomain",
                sourceExchangeKey = "domain1.#"
            ),
            ExchangeShovel(
                name = "from-domain1-to-domain2",
                source = domain1Container,
                destination = crossdomainContainer,
                sourceExchange = "crossdomain",
                sourceExchangeKey = "domain2.#"
            ),
            ExchangeShovel(
                name = "from-crossdomain-to-domain1-rpc-response",
                source = crossdomainContainer,
                destination = domain1Container,
                sourceExchange = "domain1-rpc-response",
                destinationExchange = "domain1-rpc-response"
            )
        ).start()

        domain2Container.withExchangeShovel(
            ExchangeShovel(
                name = "from-domain1-to-domain2",
                source = crossdomainContainer,
                destination = domain2Container,
                sourceExchange = "crossdomain",
                sourceExchangeKey = "domain2.#"
            ),
            ExchangeShovel(
                name = "from-domain2-to-domain1-rpc",
                source = domain2Container,
                destination = crossdomainContainer,
                sourceExchange = "domain1-rpc-response",
                destinationExchange = "domain1-rpc-response"
            )
        ).start()
    }

    private fun addAmqpConnectors() {
        domain1Connector = connector(role = AmqpConnectorRole.PublisherAndConsumer) {
            connectionString = domain1Container.amqpUrl

            declareExchange("crossdomain")
            declareExchange("domain1-rpc-response")
            addSuspendingConsumer("Domain 1", "crossdomain", "domain1.key")
        }

        domain2Connector = connector(role = AmqpConnectorRole.PublisherAndConsumer) {
            connectionString = domain2Container.amqpUrl

            declareExchange("domain1-rpc-response")
            addSuspendingConsumer("Domain 2", "crossdomain", "domain2.key")

            consumer({ message: AmqpMessage<String> ->
                Success("Domain 2 received: ${message.payload}")
            }) {
                messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
                bindingKey = AmqpBindingKey.Custom("domain2.rpc")
                replyingMode = AmqpReplyingMode.Always
                exchange {
                    name = "crossdomain"
                }
            }
        }

        crossdomainConnector = connector(role = AmqpConnectorRole.PublisherAndConsumer) {
            connectionString = crossdomainContainer.amqpUrl

            declareExchange("domain1-rpc-response")
            addSuspendingConsumer("Crossdomain", "crossdomain", "crossdomain.key")

            consumer({ message: AmqpMessage<String> ->
                Success("Crossdomain received: ${message.payload}")
            }) {
                messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
                bindingKey = AmqpBindingKey.Custom("crossdomain.rpc")
                replyingMode = AmqpReplyingMode.Always
                exchange {
                    name = "crossdomain"
                }
            }
        }
    }

    private fun ConsumingAmqpConnectorConfigPrototype.addSuspendingConsumer(
        consumerName: String,
        exchangeName: String,
        bindingKey: String
    ) {
        consumer({ message: AmqpMessage<String> ->
            resumeWith("$consumerName received: ${message.payload}")
            Success("Ok")
        }) {
            messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
            this.bindingKey = AmqpBindingKey.Custom(bindingKey)
            exchange {
                name = exchangeName
            }
        }
    }

    /**
     * Used to create exchange to enable shoveling, does not consume messages
     * TODO Need an API to create exchange without attaching listener
     */
    private fun ConsumingAmqpConnectorConfigPrototype.declareExchange(
        exchangeName: String,
        exchangeType: AmqpExchangeType = AmqpExchangeType.TOPIC
    ) {
        consumer({ _: AmqpMessage<String> -> Failure("Listener not in use") as Outcome<AmqpConsumingError, String> }) {
            bindingKey = AmqpBindingKey.Custom("ignore-me")
            messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
            exchange {
                name = exchangeName
                type = exchangeType
            }
        }
    }

    private fun assertMessagesReceivedBy(sendRequest: AmqpPublisher, consumerName: String) {
        runBlocking {
            sendRequest(AmqpMessage("Some message"))
            val result = waitForConsumer()
            assertEquals("$consumerName received: Some message", result)
        }
    }

    private suspend fun waitForConsumer() = suspendCoroutine<String> { suspended = it }

    private suspend fun resumeWith(value: String) {
        withTimeout(1000) {
            while (suspended == null) {
                delay(100)
            }
        }
        suspended?.resumeWith(Result.success(value))
        suspended = null
    }
}