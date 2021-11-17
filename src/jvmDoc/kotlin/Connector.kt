package no.dossier.libraries.amqpconnector.rabbitmq.samples

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import no.dossier.libraries.amqpconnector.rabbitmq.*
import no.dossier.libraries.amqpconnector.rabbitmq.AmqpConnectorRole.*
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
        fun sampleProcessingFunction(message: AmqpMessage<String>): Outcome<AmqpConsumingError, Unit> {
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
            samplePublisher(AmqpMessage(msg))
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
        suspend fun sendSampleMessage(msg: String): Outcome<AmqpRpcError, String> = sampleRpcClient(AmqpMessage(msg))
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
                    workersCoroutineScope = applicationCoroutineScope

                    /* Reminder of consumer setup here */
                }
            }
        }

        /* Message processing function */
        fun sampleProcessingFunction(message: AmqpMessage<String>): Outcome<AmqpConsumingError, Unit> {
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
            workersCoroutineScope = applicationCoroutineScope
            /* Reminder of RPC client setup here */
        }

        suspend fun sendSampleMessage(msg: String): Outcome<AmqpRpcError, String> = sampleRpcClient(AmqpMessage(msg))
    }

    /*
     * Note that the provided coroutine scope is needed just for Consuming or PublishingAndConsuming connectors.
     * It is not required for connectors which are just publishing
     */
}


/*

@Component
@Scope("singleton")
class ExamplePublishingResource {
    private val connector = connector(role = Publisher) {
        connectionString = "amqp://guest:guest@localhost:5672/"
        clientName = "sample-dossier-app"
    }

    private val samplePublisher = connector.publisher {
        exchange {
            type = AmqpExchangeType.TOPIC
            name = "sample-dossier-app-ref-data-outbound"
        }
        routingKey = "samplepayloads.cool.small"
    }

    /*
     * Can be called anywhere from the application where ExamplePublishingResource is injected
     */
    fun publishSamplePayload(samplePayload: SamplePayload) = samplePublisher(samplePayload)
}

@Component
@Scope("singleton")
class ExampleConsumingResource(
    private val applicationCoroutineScope: ApplicationCoroutineScope
) {
    init {
        connector(role = Consumer) {
            connectionString = "amqp://guest:guest@localhost:5672/"
            clientName = "sample-dossier-app"

            consumer(::processSamplePayloads) {
                numberOfWorkers = 8
                workersCoroutineScope = applicationCoroutineScope
                exchange {
                    name = "sample-dossier-app-ref-data-outbound"
                }
                queue {
                    name = "sample-dossier-app-ref-data-inbound"
                    durable = true
                    exclusive = false
                    autoDelete = false
                }
                deadLetterForwarding {
                    enabled = true
                }
                bindingKey = "samplepayloads.#"
            }
        }
    }

    private fun processSamplePayloads(message: AmqpMessage<SamplePayload>): Result<Unit, AmqpConsumingError> {
        // process message.payload of type SamplePayload

        /*
         * return value of processing function is used for sending ACK or REJECT to the broker (Success or Failure)
         * if dead letter forwarding is enabled and the result is Failure then the message is automatically
         * forwarded to dead-letter exchange (called error) by default
         */
        return Success(Unit)
    }
}

@Component
@Scope("singleton")
class ExampleRpcServerResource(
    private val applicationCoroutineScope: ApplicationCoroutineScope
) {
    init {
        connector(role = Consumer) {
            connectionString = "amqp://guest:guest@localhost:5672/"
            clientName = "sample-dossier-app"

            consumer(::processSampleRequests) {
                workersCoroutineScope = applicationCoroutineScope
                numberOfWorkers = 1
                replyingMode = AmqpReplyingMode.Always
                exchange {
                    type = AmqpExchangeType.DEFAULT
                }
                queue {
                    name = "profile-sample-rpc"
                    durable = true
                    exclusive = false
                    autoDelete = false
                }
            }
        }
    }

    /*
     * Similar processing function as in previous example...
     * A non-Unit result is used as a reply payload (only if replyingMode is set to Always or IfRequired)
     */
    private fun processSampleRequests(message: AmqpMessage<SampleRequest>) =
        Success(SampleReply("test: ${message.payload.value}"))
}

@Component
@Scope("singleton")
class ExampleRpcClientResource(
    private val applicationCoroutineScope: ApplicationCoroutineScope
) {
    private val connector = connector(role = PublisherAndConsumer) {
        connectionString = "amqp://guest:guest@localhost:5672/"
        clientName = "sample-dossier-app"
    }

    val sampleRpcClient = connector.rpcClient<SampleReply> {
        routingKey = "profile-sample-rpc"
        workersCoroutineScope = applicationCoroutineScope
    }

    /*
     * Can be called anywhere from the application where ExampleRpcClientResource is injected
     * But as this is suspending function, must be called from coroutine context..
     */
    suspend fun getSample(requestPayload: SampleRequest): Result<SampleReply, AmqpError> {
        return sampleRpcClient(requestPayload)
    }



    @GetMapping("/testrpc")
    fun getHelloReply(): HelloReply = runBlocking {
        courseDirectoryAmqpEndpoints.getHello(HelloRequest("cauky"))
            .getOrElse { throw RuntimeException(it.error.toString()) }
    }

    @GetMapping("/test")
    fun testSend(): Long = measureNanoTime {
        runBlocking {
            repeat(10_000) {
                launch {
                    when (val result = courseDirectoryAmqpEndpoints.sendTestPublication()) {
                        is Success -> result.value.toString()
                        is Failure -> result.error.toString()
                    }
                }
            }
        }
    } / 1000_000

}

    val testPublisher = amqpConnector.publisher {
        exchange {
            type = AmqpExchangeType.DEFAULT
        }
        confirmations = true
        routingKey = "somequeue"
    }

    val helloRpcClient = amqpConnector.rpcClient<HelloReply> {
        routingKey = "profile-hello-rpc"
        workersCoroutineScope = applicationCoroutineScope
    }

    suspend fun getHello(requestPayload: HelloRequest): Outcome<AmqpError, HelloReply> {
        return helloRpcClient(requestPayload)
    }

    suspend fun sendTestPublication(): Outcome<AmqpPublishingError, Unit> =
        testPublisher(AmqpMessage("haf")).also {
            logger.info {
                "← \uD83D\uDCE8 AMQP Publisher - message sent"
            }
        }

    private fun processHelloRequests(message: AmqpMessage<HelloRequest>) =
        Success(HelloReply("test: ${message.payload.value}"))



        consumer(::processHelloRequests) {
            workersCoroutineScope = applicationCoroutineScope
            numberOfWorkers = 1
            replyingMode = AmqpReplyingMode.Always
            exchange {
                type = AmqpExchangeType.DEFAULT
            }
            queue {
                name = "profile-hello-rpc"
                durable = true
                exclusive = false
                autoDelete = false
            }
        }

@Serializable
data class HelloRequest(val value: String)
@Serializable
data class HelloReply(val value: String)


*/
