package no.dossier.libraries.amqpconnector.primitives

enum class AmqpExchangeType(val stringRepresentation: String) {
    DEFAULT(""),
    FANOUT("fanout"),
    DIRECT("direct"),
    TOPIC("topic"),
    HEADERS("headers")
}

data class AmqpExchangeSpec(
    val name: String,
    val type: AmqpExchangeType
)