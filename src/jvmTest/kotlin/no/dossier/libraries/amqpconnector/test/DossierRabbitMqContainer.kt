package no.dossier.libraries.amqpconnector.test

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.testcontainers.containers.Network
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.utility.DockerImageName

data class FederationUpstream(val name: String, val target: DossierRabbitMqContainer, val maxHops: Int = 1)

data class ExchangeShovel(
    val name: String,
    val source: DossierRabbitMqContainer,
    val destination: DossierRabbitMqContainer,
    val sourceExchange: String,
    val destinationExchange: String = sourceExchange,
    val sourceExchangeKey: String = "#"
)

class DossierRabbitMqContainer(network: Network, networkAlias: String) :
    RabbitMQContainer(DockerImageName.parse("rabbitmq:3.7.25-management-alpine")) {

    private val shovels: MutableSet<ExchangeShovel> = mutableSetOf()

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

    suspend fun waitForFederatedConsumer(name: String, timeoutMs: Long = 10000) {
        val pattern = Regex("^federation: $name", RegexOption.MULTILINE)
        withTimeout(timeoutMs) {
            while (!execInContainer("rabbitmqctl", "list_consumers").stdout.contains(pattern)) {
                println("waiting for consumer: $name")
                delay(100)
            }
        }
    }

    fun withExchangeShovel(vararg shovels: ExchangeShovel) = apply {
        withPluginsEnabled("rabbitmq_shovel")
        this.shovels.addAll(shovels)
        shovels.forEach { shovel ->
            withParameter(
                "shovel",
                shovel.name,
                """{
                        "src-uri" : "amqp://${shovel.source.networkAliases.first()}",
                        "src-exchange": "${shovel.sourceExchange}",
                        "src-exchange-key": "${shovel.sourceExchangeKey}",
                        "dest-exchange": "${shovel.destinationExchange}",
                        "dest-uri": "amqp://${shovel.destination.networkAliases.first()}"
                    }"""
            )
        }
    }

    suspend fun waitForShovels(names: List<String> = shovels.map { it.name }, timeoutMs: Long = 10000) {
        val isAllShovelsRunning = {
            execInContainer("rabbitmqctl", "shovel_status").stdout.let { status ->
                names.map { status.contains(Regex("""${Regex.escape(it)}\s+amqp://.*\s+running""")) }.all { it }
            }
        }

        withTimeout(timeoutMs) {
            while (!isAllShovelsRunning()) {
                println("waiting for shovels: ${names.joinToString(", ")}")
                delay(100)
            }
        }
    }
}