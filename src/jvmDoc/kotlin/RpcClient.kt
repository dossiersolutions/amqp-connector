@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package no.dossier.libraries.amqpconnector.samples

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import no.dossier.libraries.amqpconnector.dsl.AmqpConnectorRole.PublisherAndConsumer
import no.dossier.libraries.amqpconnector.dsl.connector
import no.dossier.libraries.amqpconnector.dsl.rpcClient
import no.dossier.libraries.amqpconnector.error.AmqpError
import no.dossier.libraries.functional.Outcome

fun sampleRpcClient() {
    /* Creation of sample RpcClient */
    @Serializable
    data class SampleRequest(val value: String)
    @Serializable
    data class SampleResponse(val value: String)

    /* Connector role must be set to PublisherAndConsumer in order to be able to create RPC clients on top of it */
    val connector = connector(role = PublisherAndConsumer) {
        // connector setup here
    }

    /* RPC client requests are routed to default topic, thus the routingKey identifies the target queue */
    val helloRpcClient = connector.rpcClient<SampleResponse> {
        routingKey = "profile-hello-rpc" // Default: ""
        messageProcessingCoroutineScope = CoroutineScope(Dispatchers.Default) // Mandatory value
    }

    suspend fun getSampleResponse(request: SampleRequest): Outcome<AmqpError, SampleResponse> =
        helloRpcClient(request)
}
