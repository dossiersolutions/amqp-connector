# Module amqp-connector

AMQP-Connector is a library for integration with AMQP broker (RabbitMQ).
It relies on the official [RabbitMQ client for Java](https://www.rabbitmq.com/api-guide.html).

## Features
 * Publish/Subscribe, Direct notifications, Broadcasts, Request / Reply schemes (RPC)
 * Fast async publishing using Kotlin coroutines
 * Publisher confirms
 * Explicit consumer acknowledgements / rejections
 * Fast parallel message processing using Kotlin coroutines
 * Enforced or on-demand replying
 * Configurable queues (durability, explicitness, auto-deletion)
 * Configurable forwarding of dead-letter messages
 * Automatic connection and channel recovery

### Currently unsupported
 * Publishing and processing messages in batches
 * Implicit message acknowledgements
 * Dynamic subscription and un-subscription 

## Getting started
 1. Add the amqp-connector dependency to your Gradle build script
    
            implementation("no.dossier.libraries:amqp-connector:0.15")
 2. Create instance of the connector using DSL and start publishing and/or consuming messages
    (SpringBoot integration example)
        
            class ApplicationCoroutineScope : CoroutineScope by CoroutineScope(Dispatchers.Default)
        
        @Configuration
        class CoroutineScopeConfiguration {
            @Bean
            fun scope(): ApplicationCoroutineScope = ApplicationCoroutineScope()
        }
 
        @Component
        @Scope("singleton")
        class ExampleResource(
            private val applicationCoroutineScope: ApplicationCoroutineScope
        ) {
            init {
                connector(role = Consumer) {
                    connectionString = "amqp://guest:guest@localhost:5672/"
                    clientName = "sample-app"

                    consumer(::sampleProcessingFunction) {
                        workersCoroutineScope = applicationCoroutineScope
                        exchange {
                            name = "some-ref-data"
                        }
                        bindingKey = "refdata.*.user.#"
                    }
                }
            }
 
            fun sampleProcessingFunction(message: AmqpMessage<String>): Outcome<AmqpConsumingError, Unit> {
                println(message.payload)
                return Success(Unit)
            }
        }

## Debugging

To enable debugging messages just set the logging level for amqp-connector classes to DEBUG:
    
    logging.level.no.dossier.libraries.amqpconnector=DEBUG

Optionally, if you want to debug the used coroutines, you can run the application with:

    -Dkotlinx.coroutines.debug

VM option and possibly modify the logging pattern to be able to see 
the whole "thread-name@coroutineId" expression, e.g.:

    logging.pattern.console= %d{yyyy-MM-dd HH:mm:ss} [%40.40t] --- %msg%n