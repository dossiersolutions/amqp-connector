package no.dossier.libraries.amqpconnector.test

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.testcontainers.containers.Network
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.utility.DockerImageName

data class FederationUpstream(val name: String, val target: DossierRabbitMqContainer, val maxHops: Int = 1)

class DossierRabbitMqContainer(network: Network, networkAlias: String) :
    RabbitMQContainer(DockerImageName.parse("rabbitmq:3.7.25-management-alpine")) {
    init {
        withNetwork(network)
        withNetworkAliases(networkAlias)
    }

    fun withFederation(vararg upstreams: FederationUpstream) = apply {
        withPluginsEnabled("rabbitmq_federation")
        withPolicy(
            "exchange-federation",
            "^federated\\.",
            mapOf("federation-upstream-set" to "all"),
            10,
            "exchanges"
        )
        upstreams.forEach { (name, target, maxHops) ->
            withParameter(
                "federation-upstream",
                name,
                """{"uri":"amqp://${target.networkAliases.first()}:5672","max-hops":$maxHops}"""
            )
        }
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