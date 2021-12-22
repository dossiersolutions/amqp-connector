package no.dossier.libraries.amqpconnector.test

import kotlinx.coroutines.*
import no.dossier.libraries.amqpconnector.connector.PublishingConsumingAmqpConnectorImpl
import no.dossier.libraries.amqpconnector.consumer.AmqpReplyingMode
import no.dossier.libraries.amqpconnector.dsl.*
import no.dossier.libraries.amqpconnector.primitives.AmqpExchangeType
import no.dossier.libraries.amqpconnector.primitives.AmqpMessage
import no.dossier.libraries.amqpconnector.primitives.AmqpMessageProperty
import no.dossier.libraries.functional.Success
import no.dossier.libraries.functional.getOrElse
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.containers.Network
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class FederationTest {
    private val network = Network.newNetwork()
    private val domain1Container = DossierRabbitMqContainer(network, "domain1")
    private val crossdomainContainer = DossierRabbitMqContainer(network, "crossdomain")

    private lateinit var domain1Connector: PublishingConsumingAmqpConnectorImpl
    private lateinit var crossdomainConnector: PublishingConsumingAmqpConnectorImpl

    private var suspended: Continuation<String>? = null

    @Test
    fun `should be possible to send messages internally in one domain using rpc`() {
        val sendMessage = domain1Connector.rpcClient<String> {
            exchange { name = "domain1.internal" }
            messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
        }

        val response = runBlocking { sendMessage("rpc message from domain1") }

        assertEquals("domain1-rpc-internal: rpc message from domain1", response.getOrElse { throw Exception() })
    }

    @Test
    fun `should be possible to send messages from domain to crossdomain`() {
        val sendMessage = domain1Connector.publisher { exchange { name = "federated.crossdomain" } }

        val result = runBlocking {
            sendMessage(AmqpMessage("hello from domain1"))
            waitForConsumer()
        }

        assertEquals("crossdomain: hello from domain1", result)
    }

    @Test
    fun `should be possible to send messages from crossdomain to domain`() {
        val sendMessage = crossdomainConnector.publisher { exchange { name = "federated.domain1" } }

        val result = runBlocking {
            sendMessage(AmqpMessage("hello from crossdomain"))
            waitForConsumer()
        }

        assertEquals("domain1: hello from crossdomain", result)
    }

    //@Disabled("TODO we need to add support for federated RPC (cannot use default exchange)")
    @Test
    fun `should be possible to send messages from domain to crossdomain using rpc`() {
        val sendRequest = domain1Connector.rpcClient<String> {
            exchange { name = "federated.crossdomain" }
            replyToExchange {
                name = "federated.replying.domain1"
                type = AmqpExchangeType.DIRECT
            }
            messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
        }

        val response = runBlocking {
            launch { waitForConsumer() }
            sendRequest("rpc message from domain1")
        }

        assertEquals("crossdomain-rpc-federated: rpc message from domain1", response.getOrElse { throw Exception() })
    }

    @BeforeAll
    fun beforeAll() {
        startContainers()
        configureConnectors()
    }

    private fun startContainers() {
        runBlocking {
            launch {
                domain1Container.withFederation(FederationUpstream("crossdomain", crossdomainContainer)).start()
            }
            launch {
                crossdomainContainer.withFederation(FederationUpstream("domain1", domain1Container)).start()
            }
        }
    }

    private fun configureConnectors() {
        domain1Connector = connector(role = AmqpConnectorRole.PublisherAndConsumer) {
            connectionString = domain1Container.amqpUrl

            consumer({ message: AmqpMessage<String> -> Success("domain1-rpc-internal: ${message.payload}") }) {
                replyingMode = AmqpReplyingMode.IfRequired
                messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
                exchange { name = "domain1.internal" }
            }

            consumer({ message: AmqpMessage<String> ->
                resumeWith("domain1: ${message.payload}")
                Success("domain1-rpc-federated: ${message.payload}")
            }) {
                replyingMode = AmqpReplyingMode.IfRequired
                messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
                exchange { name = "federated.domain1" }
            }
        }

        crossdomainConnector = connector(role = AmqpConnectorRole.PublisherAndConsumer) {
            connectionString = crossdomainContainer.amqpUrl

            consumer({ message: AmqpMessage<String> ->
                resumeWith("crossdomain: ${message.payload}")
                Success("crossdomain-rpc-federated: ${message.payload}")
            }) {
                replyingMode = AmqpReplyingMode.IfRequired
                messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default)
                exchange { name = "federated.crossdomain" }
            }
        }

        runBlocking {
            launch { domain1Container.waitForFederatedConsumer("federated.crossdomain") }
            launch { crossdomainContainer.waitForFederatedConsumer("federated.replying.domain1") }
            launch { crossdomainContainer.waitForFederatedConsumer("federated.domain1") }
        }
    }

    @AfterAll
    fun stopContainers() {
        crossdomainContainer.stop()
        domain1Container.stop()
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