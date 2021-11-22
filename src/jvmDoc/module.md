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

3. Create instance of the connector using DSL and start publishing and/or consuming messages

```
       class ExampleResource {
           val connector = connector(role = PublisherAndConsumer) {
               connectionString = "amqp://guest:guest@localhost:5672/"
               clientName = "sample-app"
        
               consumer(::sampleProcessingFunction) {
                   workersCoroutineScope = CoroutineScope(Dispatchers.Default)
                   exchange { name = "some-ref-data" }
                   bindingKey = "refdata.*.user.#"
               }
           }
        
           val publisher = connector.publisher {
               exchange { name = "somedata-exchange" }
               routingKey = "somedata.cool.special"
           }
    
           suspend fun sampleProcessingFunction(message: AmqpMessage<String>): Outcome<AmqpConsumingError, Unit> {
               println(message.payload)
               return Success(Unit)
           }
        
           suspend fun sendSamplePublication(request: String): Outcome<AmqpPublishingError, Unit> =
               publisher(AmqpMessage(request)) 
       }
```

   See [connector][no.dossier.libraries.amqpconnector.dsl.connector],
   [consumer][no.dossier.libraries.amqpconnector.dsl.consumer],
   [publisher][no.dossier.libraries.amqpconnector.dsl.publisher],
   and [rpcClient][no.dossier.libraries.amqpconnector.dsl.rpcClient] 
   DSL functions for more details and examples

## Debugging

To enable debugging messages just set the logging level for amqp-connector classes to DEBUG:
    
    logging.level.no.dossier.libraries.amqpconnector=DEBUG

Optionally, if you want to debug the used coroutines, you can run the application with:

    -Dkotlinx.coroutines.debug

VM option and possibly modify the logging pattern to be able to see 
the whole "thread-name@coroutineId" expression, e.g.:

    logging.pattern.console= %d{yyyy-MM-dd HH:mm:ss} [%40.40t] --- %msg%n