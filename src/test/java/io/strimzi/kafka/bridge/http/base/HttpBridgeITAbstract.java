/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.kafka.bridge.http.base;

import io.strimzi.kafka.bridge.clients.BasicKafkaClient;
import io.strimzi.kafka.bridge.config.BridgeConfig;
import io.strimzi.kafka.bridge.config.KafkaConfig;
import io.strimzi.kafka.bridge.config.KafkaConsumerConfig;
import io.strimzi.kafka.bridge.config.KafkaProducerConfig;
import io.strimzi.kafka.bridge.facades.AdminClientFacade;
import io.strimzi.kafka.bridge.http.HttpBridge;
import io.strimzi.kafka.bridge.http.HttpConfig;
import io.strimzi.kafka.bridge.http.services.BaseService;
import io.strimzi.kafka.bridge.http.services.ConsumerService;
import io.strimzi.kafka.bridge.http.services.ProducerService;
import io.strimzi.kafka.bridge.http.services.SeekService;
import io.strimzi.kafka.bridge.metrics.MetricsType;
import io.strimzi.kafka.bridge.utils.Urls;
import io.strimzi.test.container.StrimziKafkaCluster;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;

import static io.strimzi.kafka.bridge.Constants.HTTP_BRIDGE;

@ExtendWith(VertxExtension.class)
@SuppressWarnings({"checkstyle:JavaNCSS"})
@Tag(HTTP_BRIDGE)
public abstract class HttpBridgeITAbstract {
    private static final Logger LOGGER = LogManager.getLogger(HttpBridgeITAbstract.class);
    protected static Map<String, Object> config = new HashMap<>();

    // for periodic/multiple messages test
    protected static final int PERIODIC_MAX_MESSAGE = 10;
    protected static final int PERIODIC_DELAY = 1000;
    protected static final int MULTIPLE_MAX_MESSAGE = 10;
    protected static final int TEST_TIMEOUT = 60;
    protected int count;
    protected String topic;

    public static StrimziKafkaCluster kafkaCluster = null;
    protected static final String BRIDGE_EXTERNAL_ENV = System.getenv().getOrDefault("EXTERNAL_BRIDGE", "FALSE");
    protected static final String KAFKA_EXTERNAL_ENV = System.getenv().getOrDefault("EXTERNAL_KAFKA", "FALSE");

    protected static String kafkaUri;

    protected static long timeout = 5L;

    static {
        if ("FALSE".equals(KAFKA_EXTERNAL_ENV)) {
            kafkaCluster = new StrimziKafkaCluster.StrimziKafkaClusterBuilder()
                .withNumberOfBrokers(1)
                .withSharedNetwork()
                .build();
            kafkaCluster.start();

            kafkaUri = kafkaCluster.getBootstrapServers();

            adminClientFacade = AdminClientFacade.create(kafkaUri);
        } else {
            // else use external kafka
            kafkaUri = "localhost:9092";
        }

        config.put(KafkaConfig.KAFKA_CONFIG_PREFIX + ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaUri);
        config.put(KafkaConsumerConfig.KAFKA_CONSUMER_CONFIG_PREFIX + ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(KafkaProducerConfig.KAFKA_PRODUCER_CONFIG_PREFIX + ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000");
        config.put(HttpConfig.HTTP_CONSUMER_TIMEOUT, timeout);
        config.put(BridgeConfig.METRICS_TYPE, MetricsType.STRIMZI_REPORTER.toString());
        config.put(BridgeConfig.BRIDGE_ID, "my-bridge");
    }

    protected static Vertx vertx;
    protected static WebClient client;
    protected static BasicKafkaClient basicKafkaClient;
    protected static AdminClientFacade adminClientFacade;
    protected static HttpBridge httpBridge;
    protected static BridgeConfig bridgeConfig;

    protected BaseService baseService() {
        return BaseService.getInstance(client);
    }

    protected ConsumerService consumerService() {
        return ConsumerService.getInstance(client);
    }

    protected SeekService seekService() {
        return SeekService.getInstance(client);
    }

    protected ProducerService producerService() {
        return ProducerService.getInstance(client);
    }

    @BeforeAll
    static void beforeAll(VertxTestContext context) {
        vertx = Vertx.vertx();
        adminClientFacade = AdminClientFacade.create(kafkaUri);

        basicKafkaClient = new BasicKafkaClient(kafkaUri);

        LOGGER.info("Environment variable EXTERNAL_BRIDGE:" + BRIDGE_EXTERNAL_ENV);

        if ("FALSE".equals(BRIDGE_EXTERNAL_ENV)) {
            bridgeConfig = BridgeConfig.fromMap(config);
            
            httpBridge = new HttpBridge(bridgeConfig);

            LOGGER.info("Deploying in-memory bridge");
            vertx.deployVerticle(httpBridge).onComplete(context.succeeding(id -> context.completeNow()));
        } else {
            context.completeNow();
            // else we create an external bridge from the OS invoked by `.jar`
        }

        client = WebClient.create(vertx, new WebClientOptions()
            .setDefaultHost(Urls.BRIDGE_HOST)
            .setDefaultPort(Urls.BRIDGE_PORT)
        );
    }

    @AfterAll
    static void afterAll(VertxTestContext context) {
        if ("FALSE".equals(BRIDGE_EXTERNAL_ENV)) {
            vertx.close().onComplete(context.succeeding(arg -> context.completeNow()));
        } else {
            // if we are running an external bridge
            context.completeNow();
        }
    }

    @BeforeEach
    void setUpEach() {
        topic = "my-topic-" + new Random().nextInt(Integer.MAX_VALUE);
    }

    @AfterEach
    void cleanUp() throws InterruptedException, ExecutionException {
        Collection<String> topics = adminClientFacade.listTopic();
        LOGGER.info("Kafka still contains {}", topics);

        if (!topics.isEmpty()) {
            try {
                adminClientFacade.deleteTopics(topics);
            } catch (ExecutionException executionException) {
                if (executionException.getCause() instanceof org.apache.kafka.common.errors.UnknownTopicOrPartitionException) {
                    LOGGER.warn("Some topics not found (already deleted). Ignoring ...");
                } else {
                    throw executionException;
                }
            }

            Collection<String> remainingTopics = adminClientFacade.listTopic();
            if (!remainingTopics.isEmpty()) {
                LOGGER.error("Topics still present after cleanup: {}", remainingTopics);
            }
        }
    }

    protected String generateRandomConsumerGroupName() {
        int salt = new Random().nextInt(Integer.MAX_VALUE);
        return "my-group-" + salt;
    }
}
