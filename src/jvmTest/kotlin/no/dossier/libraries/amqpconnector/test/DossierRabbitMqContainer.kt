package no.dossier.libraries.amqpconnector.test

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.testcontainers.containers.Network
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.utility.DockerImageName

data class FederationUpstream(val name: String, val target: DossierRabbitMqContainer)

class DossierRabbitMqContainer(network: Network, networkAlias: String) :
    RabbitMQContainer(DockerImageName.parse("rabbitmq:3.7.25-management-alpine")) {
    init {
        withNetwork(network)
        withNetworkAliases(networkAlias)
    }

    fun start(vararg upstream: FederationUpstream) {
        super.start()
        enableFederation(*upstream)
    }

    private fun enableFederation(vararg upstreams: FederationUpstream) = apply {
        execInContainer("rabbitmq-plugins", "enable", "rabbitmq_federation")
        execInContainer(
            "rabbitmqctl",
            "set_policy",
            "exchange-federation",
            """^federated\.""",
            """{"federation-upstream-set":"all"}""",
            "--priority",
            "10",
            "--apply-to",
            "exchanges"
        )
        upstreams.forEach { (name, target) -> addFederationUpstream(name, target) }
    }

    private fun addFederationUpstream(name: String, target: DossierRabbitMqContainer) = apply {
        execInContainer(
            "rabbitmqctl",
            "set_parameter",
            "federation-upstream",
            name,
            """{"uri":"amqp://${target.networkAliases.first()}:5672"}"""
        )
    }

    suspend fun waitForFederatedConsumer(name: String, timeoutMs: Long = 1000) {
        val pattern = Regex("^federation: $name", RegexOption.MULTILINE)
        withTimeout(timeoutMs) {
            while (execInContainer("rabbitmqctl", "list_consumers").stdout.contains(pattern)) {
                println("waiting for consumer: $name")
                delay(100)
            }
        }
    }
}