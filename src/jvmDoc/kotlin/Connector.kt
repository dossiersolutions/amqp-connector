@file:Suppress("MemberVisibilityCanBePrivate", "unused", "UNUSED_PARAMETER")

package no.dossier.libraries.amqpconnector.samples

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import no.dossier.libraries.amqpconnector.dsl.*
import no.dossier.libraries.amqpconnector.dsl.AmqpConnectorRole.*
import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.amqpconnector.error.AmqpRpcError
import no.dossier.libraries.amqpconnector.primitives.AmqpInboundMessage
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success

fun basicConsumingConnectorInitialization() {
    /*
     * Sample initialization of consuming connector
     * Note: We don't need to refer to the connector since it has its own lifecycle,
     * so it can be just simply initialized in the init { } block
     */
    class SomeExampleAmqpResource {
        init {
            connector(role = Consumer)
            {
                connectionString = "amqp://guest:guest@localhost:5672/"
                clientName = "sample-app"

                consumer(::sampleProcessingFunction) {
                    /* consumer setup here (exchange and queue definitions etc.) */
                }
            }
        }

        /* A sample message processing function */
        fun sampleProcessingFunction(message: AmqpInboundMessage<String>): Outcome<AmqpConsumingError, Unit> {
            /*
             * The Outcome indicates whether the processing was successful and the message should be ACKed
             * or not and the message should be rejected. Also, when implementing RPC server, the Success value
             * will be used as a reply to the message
             */
            return Success(Unit)
        }
    }
}

fun basicPublishingConnectorInitialization() {
    /* Sample initialization of publishing connector */
    class SomeExamplePublishingService {
        val connector = connector(role = Publisher) {
            connectionString = "amqp://guest:guest@localhost:567s2/"
            clientName = "sample-app"
        }

        val samplePublisher = connector.publisher {
            /* Publisher setup comes here */
        }

        /* Note that publisher invocation must be done from coroutine scope therefore this is suspending function */
        suspend fun sendSampleMessage(msg: String) {
            samplePublisher(AmqpInboundMessage(msg))
        }
    }
}

fun basicPublishingAndConsumingConnectorInitialization() {
    /* Sample initialization of publishing and consuming connector */
    class SomeExampleRpcClient {
        val connector = connector(role = PublisherAndConsumer) {
            connectionString = "amqp://guest:guest@localhost:567s2/"
            clientName = "sample-app"
        }

        val sampleRpcClient = connector.rpcClient<String> {
            /* Rpc client setup here */
        }

        /* Note that publisher invocation must be done from coroutine scope therefore this is suspending function */
        suspend fun sendSampleMessage(msg: String): Outcome<AmqpRpcError, String> = sampleRpcClient(AmqpInboundMessage(msg))
    }
}

fun basicSpringIntegration() {

    /*
    * Sample Spring integration
    *
    * Create a custom application-level coroutine scope using delegated implementation of CoroutineScope.
    * This makes it easy to replace the default coroutine context with something else
    * for the whole application, if needed.
    */
    class ApplicationCoroutineScope : CoroutineScope by CoroutineScope(Dispatchers.Default)

    /*
     * Create a configuration which registers a bean representing that newly created coroutine scope
     */
    @Configuration
    class CoroutineScopeConfiguration {
        @Bean
        fun scope(): ApplicationCoroutineScope = ApplicationCoroutineScope()
    }

    /*
     * Create a plain singleton-scoped spring component which will
     */
    @Component
    @Scope("singleton")
    class ExampleResource(
        /* The application-level coroutine scope is autowired */
        private val applicationCoroutineScope: ApplicationCoroutineScope
    ) {
        init {
            connector(role = Consumer) {
                connectionString = "amqp://guest:guest@localhost:5672/"
                clientName = "sample-app"

                consumer(::sampleProcessingFunction) {
                    /* Tell the consumer to run workers in our application-level coroutine scope */
                    messageProcessingCoroutineScope = applicationCoroutineScope

                    /* Reminder of consumer setup here */
                }
            }
        }

        /* Message processing function */
        fun sampleProcessingFunction(message: AmqpInboundMessage<String>): Outcome<AmqpConsumingError, Unit> {
            return Success(Unit)
        }
    }

    @Component
    @Scope("singleton")
    class ExampleRpcClient(
        private val applicationCoroutineScope: ApplicationCoroutineScope
    ) {
        val connector = connector(role = PublisherAndConsumer) {
            connectionString = "amqp://guest:guest@localhost:567s2/"
            clientName = "sample-app"
        }

        val sampleRpcClient = connector.rpcClient<String> {
            /* Tell the RPC client to execute the reply-consumer in our application-level coroutine scope */
            messageProcessingCoroutineScope = applicationCoroutineScope
            /* Reminder of RPC client setup here */
        }

        suspend fun sendSampleMessage(msg: String): Outcome<AmqpRpcError, String> = sampleRpcClient(AmqpInboundMessage(msg))
    }

    /*
     * Note that the provided coroutine scope is needed just for Consuming or PublishingAndConsuming connectors.
     * It is not required for connectors which are just publishing
     */
}
