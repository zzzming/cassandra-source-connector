/**
 * Copyright DataStax, Inc 2021.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.kafka.source;

import com.datastax.cassandra.cdc.producer.KafkaMutationSender;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.testcontainers.cassandra.CassandraContainer;
import com.datastax.testcontainers.kafka.KafkaConnectContainer;
import com.datastax.testcontainers.kafka.SchemaRegistryContainer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.confluent.connect.avro.AvroConverter;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.storage.Converter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
public class KafkaCassandraSourceTests {

    public static final DockerImageName CASSANDRA_IMAGE = DockerImageName.parse(
            Optional.ofNullable(System.getenv("CASSANDRA_IMAGE")).orElse("cassandra:4.0-beta4"))
            .asCompatibleSubstituteFor("cassandra");

    public static final String CONFLUENT_PLATFORM_VERSION = "5.5.1";

    public static final DockerImageName KAFKA_IMAGE = DockerImageName.parse(
            Optional.ofNullable(System.getenv("KAFKA_IMAGE"))
                    .orElse("confluentinc/cp-kafka:" + CONFLUENT_PLATFORM_VERSION))
            .asCompatibleSubstituteFor("kafka");
    public static final DockerImageName KAFKA_SCHEMA_REGISTRY_IMAGE = DockerImageName.parse(
            Optional.ofNullable(System.getenv("KAFKA_SCHEMA_REGISTRY_IMAGE"))
                    .orElse("confluentinc/cp-schema-registry:" + CONFLUENT_PLATFORM_VERSION));
    public static final DockerImageName KAFKA_CONNECT_IMAGE = DockerImageName.parse(
            Optional.ofNullable(System.getenv("KAFKA_CONNECT_IMAGE"))
                    .orElse("confluentinc/cp-kafka-connect-base:" + CONFLUENT_PLATFORM_VERSION));

    private static String seed = "1";   // must match the source connector config
    private static Network testNetwork;
    private static CassandraContainer<?> cassandraContainer1;
    private static CassandraContainer<?> cassandraContainer2;
    private static KafkaContainer kafkaContainer;
    private static SchemaRegistryContainer schemaRegistryContainer;
    private static KafkaConnectContainer kafkaConnectContainer;

    static final int GIVE_UP = 100;

    @BeforeAll
    public static final void initBeforeClass() throws Exception {
        testNetwork = Network.newNetwork();

        kafkaContainer = new KafkaContainer(KAFKA_IMAGE)
                .withNetwork(testNetwork)
                .withEmbeddedZookeeper()
                .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withName("kafka-" + seed))
                .withEnv("KAFKA_NUM_PARTITIONS", "1")
                .withStartupTimeout(Duration.ofSeconds(30));
        kafkaContainer.start();

        String internalKafkaBootstrapServers = String.format("PLAINTEXT:/%s:%s", kafkaContainer.getContainerName(), 9092);
        schemaRegistryContainer = SchemaRegistryContainer
                .create(KAFKA_SCHEMA_REGISTRY_IMAGE, seed, internalKafkaBootstrapServers)
                .withNetwork(testNetwork)
                .withStartupTimeout(Duration.ofSeconds(30));
        schemaRegistryContainer.start();

        String sourceBuildDir = System.getProperty("sourceBuildDir");
        String projectVersion = System.getProperty("projectVersion");
        String sourceJarFile = String.format(Locale.ROOT, "source-kafka-%s-all.jar", projectVersion);
        kafkaConnectContainer = KafkaConnectContainer
                .create(KAFKA_CONNECT_IMAGE, seed, internalKafkaBootstrapServers, schemaRegistryContainer.getRegistryUrlInDockerNetwork())
                .withNetwork(testNetwork)
                .withFileSystemBind(
                        String.format(Locale.ROOT, "%s/libs/%s", sourceBuildDir, sourceJarFile),
                        String.format(Locale.ROOT, "/connect-plugins/%s", sourceJarFile))
                .withStartupTimeout(Duration.ofSeconds(180));
        kafkaConnectContainer.start();

        String producerBuildDir = System.getProperty("producerBuildDir");
        cassandraContainer1 = CassandraContainer.createCassandraContainerWithKafkaProducer(
                CASSANDRA_IMAGE, testNetwork, 1, producerBuildDir, "v4",
                internalKafkaBootstrapServers, schemaRegistryContainer.getRegistryUrlInDockerNetwork());
        cassandraContainer2 = CassandraContainer.createCassandraContainerWithKafkaProducer(
                CASSANDRA_IMAGE, testNetwork, 2, producerBuildDir, "v4",
                internalKafkaBootstrapServers, schemaRegistryContainer.getRegistryUrlInDockerNetwork());
        cassandraContainer1.start();
        cassandraContainer2.start();
    }

    @AfterAll
    public static void closeAfterAll() {
        cassandraContainer1.close();
        cassandraContainer2.close();
        kafkaConnectContainer.close();
        schemaRegistryContainer.close();
        kafkaContainer.close();
    }

    KafkaConsumer<byte[], byte[]> createConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryContainer.getRegistryUrl());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.ByteArrayDeserializer.class.getName());
        props.put("max.poll.interval.ms", "60000");  // because we need to wait synced commitlogs on disk
        return new KafkaConsumer<>(props);
    }

    @Test
    public void testWithAvroConverters() throws InterruptedException, IOException {
        Converter keyConverter = new AvroConverter();
        keyConverter.configure(
                ImmutableMap.of(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryContainer.getRegistryUrl()),
                true);
        Converter valueConverter = new AvroConverter();
        valueConverter.configure(
                ImmutableMap.of(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryContainer.getRegistryUrl()),
                false);
        testSourceConnector("ks1", keyConverter, valueConverter, false);
    }

    @Test
    public void testWithJsonConverters() throws InterruptedException, IOException {
        Converter keyConverter = new JsonConverter();
        keyConverter.configure(
                ImmutableMap.of(
                        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryContainer.getRegistryUrl(),
                        "key.converter.schemas.enable", "true"),
                true);
        Converter valueConverter = new JsonConverter();
        valueConverter.configure(
                ImmutableMap.of(
                        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryContainer.getRegistryUrl(),
                        "value.converter.schemas.enable", "true"),
                false);
        testSourceConnector("ks2", keyConverter, valueConverter, true);
    }

    public void testSourceConnector(String ksName, Converter keyConverter, Converter valueConverter, boolean schemaWithNullValue) throws InterruptedException, IOException {
        try (CqlSession cqlSession = cassandraContainer1.getCqlSession()) {
            cqlSession.execute("CREATE KEYSPACE IF NOT EXISTS " + ksName +
                    " WITH replication = {'class':'SimpleStrategy','replication_factor':'2'};");
            cqlSession.execute("CREATE TABLE IF NOT EXISTS " + ksName + ".table1 (id text PRIMARY KEY, a int) WITH cdc=true");
            cqlSession.execute("INSERT INTO " + ksName + ".table1 (id, a) VALUES('1',1)");
            cqlSession.execute("INSERT INTO " + ksName + ".table1 (id, a) VALUES('2',1)");
            cqlSession.execute("INSERT INTO " + ksName + ".table1 (id, a) VALUES('3',1)");

            cqlSession.execute("CREATE TABLE IF NOT EXISTS " + ksName + ".table2 (a text, b int, c int, PRIMARY KEY(a,b)) WITH cdc=true");
            cqlSession.execute("INSERT INTO " + ksName + ".table2 (a,b,c) VALUES('1',1,1)");
            cqlSession.execute("INSERT INTO " + ksName + ".table2 (a,b,c) VALUES('2',1,1)");
            cqlSession.execute("INSERT INTO " + ksName + ".table2 (a,b,c) VALUES('3',1,1)");
        }

        kafkaConnectContainer.setLogging("com.datastax", "DEBUG");

        // source connector must be deployed after the creation of the cassandra table.
        assertEquals(201, kafkaConnectContainer.deployConnector("source-cassandra-" + ksName + "-table1.yaml"));
        assertEquals(201, kafkaConnectContainer.deployConnector("source-cassandra-" + ksName + "-table2.yaml"));

        // wait commitlogs sync on disk
        Thread.sleep(11000);
        KafkaConsumer<byte[], byte[]> consumer = createConsumer("test-consumer-data-group-" + ksName);
        consumer.subscribe(ImmutableList.of("data-" + ksName + ".table1", "data-" + ksName + ".table2"));
        int noRecordsCount = 0;

        Schema expectedKeySchema1 = SchemaBuilder.string().optional().build();
        Schema expectedKeySchema2 = SchemaBuilder.struct()
                .name(ksName + ".table2")
                .version(1)
                .doc(KafkaMutationSender.SCHEMA_DOC_PREFIX + ksName + ".table2")
                .field("a", SchemaBuilder.string().optional().build())
                .field("b", SchemaBuilder.int32().optional().build())
                .build();

        Schema expectedValueSchema1 = SchemaBuilder.struct()
                .name(ksName + ".table1")
                .doc(CassandraConverter.TABLE_SCHEMA_DOC_PREFIX + ksName + ".table1")
                .optional()
                .field("a", SchemaBuilder.int32().optional().build())
                .build();
        Schema expectedValueSchema2 = SchemaBuilder.struct()
                .name(ksName + ".table2")
                .doc(CassandraConverter.TABLE_SCHEMA_DOC_PREFIX + ksName + ".table2")
                .optional()
                .field("c", SchemaBuilder.int32().optional().build())
                .build();

        Map<String, Integer> mutationTable1 = new HashMap<>();
        Map<String, Integer> mutationTable2 = new HashMap<>();
        while (true) {
            final ConsumerRecords<byte[], byte[]> consumerRecords = consumer.poll(Duration.ofMillis(100));
            if (consumerRecords.count() == 0) {
                noRecordsCount++;
                if (noRecordsCount > GIVE_UP) break;
            }
            for (ConsumerRecord<byte[], byte[]> record : consumerRecords) {
                String topicName = record.topic();
                SchemaAndValue keySchemaAndValue = keyConverter.toConnectData(topicName, record.key());
                SchemaAndValue valueSchemaAndValue = valueConverter.toConnectData(topicName, record.value());
                System.out.println("Consumer Record: topicName=" + topicName +
                        " partition=" + record.partition() +
                        " offset=" + record.offset() +
                        " key=" + keySchemaAndValue.value() +
                        " value=" + valueSchemaAndValue.value());
                System.out.println("key schema: " + CassandraConverter.schemaToString(keySchemaAndValue.schema()));
                System.out.println("value schema: " + CassandraConverter.schemaToString(valueSchemaAndValue.schema()));
                if (topicName.endsWith("table1")) {
                    assertEquals((Integer) 0, mutationTable1.computeIfAbsent((String) keySchemaAndValue.value(), k -> 0));
                    assertEquals(expectedValueSchema1, valueSchemaAndValue.schema());
                    assertEquals(expectedKeySchema1, keySchemaAndValue.schema());
                    assertEquals((Integer) 1, ((Struct) valueSchemaAndValue.value()).getInt32("a"));
                    mutationTable1.compute((String) keySchemaAndValue.value(), (k, v) -> v + 1);
                } else if (topicName.endsWith("table2")) {
                    assertEquals((Integer) 0, mutationTable2.computeIfAbsent(((Struct) keySchemaAndValue.value()).getString("a"), k -> 0));
                    assertEquals((Integer) 1, ((Struct) keySchemaAndValue.value()).getInt32("b"));
                    assertEquals((Integer) 1, ((Struct) valueSchemaAndValue.value()).getInt32("c"));
                    assertEquals(expectedKeySchema2, keySchemaAndValue.schema());
                    assertEquals(expectedValueSchema2, valueSchemaAndValue.schema());
                    mutationTable2.compute(((Struct) keySchemaAndValue.value()).getString("a"), (k, v) -> v + 1);
                }
            }
            consumer.commitSync();
        }
        // expect exactly one update per PK
        for (int i = 1; i < 4; i++) {
            assertEquals((Integer) 1, mutationTable1.get(Integer.toString(i)));
            assertEquals((Integer) 1, mutationTable1.get(Integer.toString(i)));
        }

        // trigger a schema update
        try (CqlSession cqlSession = cassandraContainer1.getCqlSession()) {
            cqlSession.execute("ALTER TABLE " + ksName + ".table1 ADD b double");
            cqlSession.execute("INSERT INTO " + ksName + ".table1 (id,a,b) VALUES('1',1,1.0)");

            cqlSession.execute("CREATE TYPE " + ksName + ".type2 (a bigint, b smallint);");
            cqlSession.execute("ALTER TABLE " + ksName + ".table2 ADD d type2");
            cqlSession.execute("INSERT INTO " + ksName + ".table2 (a,b,c,d) VALUES('1',1,1,{a:1,b:1})");
        }
        // wait commitlogs sync on disk
        Thread.sleep(15000);

        Schema expectedValueSchema1v2 = SchemaBuilder.struct()
                .name(ksName + ".table1")
                .doc(CassandraConverter.TABLE_SCHEMA_DOC_PREFIX + ksName + ".table1")
                .optional()
                .field("a", SchemaBuilder.int32().optional().build())
                .field("b", SchemaBuilder.float64().optional().build())
                .build();
        Schema type2Schema = SchemaBuilder.struct()
                .name(ksName + ".type2")
                .doc(CassandraConverter.TYPE_SCHEMA_DOC_PREFIX + ksName + ".type2")
                .optional()
                .field("a", SchemaBuilder.int64().optional().build())
                .field("b", SchemaBuilder.int16().optional().build())
                .build();
        Schema expectedValueSchema2v2 = SchemaBuilder.struct()
                .name(ksName + ".table2")
                .doc(CassandraConverter.TABLE_SCHEMA_DOC_PREFIX + ksName + ".table2")
                .optional()
                .field("c", SchemaBuilder.int32().optional().build())
                .field("d", type2Schema)
                .build();
        noRecordsCount = 0;
        while (true) {
            final ConsumerRecords<byte[], byte[]> consumerRecords = consumer.poll(Duration.ofMillis(100));
            if (consumerRecords.count() == 0) {
                noRecordsCount++;
                if (noRecordsCount > GIVE_UP) break;
            }
            for (ConsumerRecord<byte[], byte[]> record : consumerRecords) {
                String topicName = record.topic();
                SchemaAndValue keySchemaAndValue = keyConverter.toConnectData(topicName, record.key());
                SchemaAndValue valueSchemaAndValue = valueConverter.toConnectData(topicName, record.value());
                System.out.println("Consumer Record: topicName=" + topicName +
                        " partition=" + record.partition() +
                        " offset=" + record.offset() +
                        " key=" + keySchemaAndValue.value() +
                        " value=" + valueSchemaAndValue.value());
                System.out.println("key schema: " + CassandraConverter.schemaToString(keySchemaAndValue.schema()));
                System.out.println("value schema: " + CassandraConverter.schemaToString(valueSchemaAndValue.schema()));
                if (topicName.endsWith("table1")) {
                    assertEquals("1", keySchemaAndValue.value());
                    assertEquals(expectedKeySchema1, keySchemaAndValue.schema());
                    Struct expectedValue = new Struct(valueSchemaAndValue.schema())
                            .put("a", 1)
                            .put("b", 1.0d);
                    assertEquals(expectedValue, valueSchemaAndValue.value());
                    assertEquals(expectedValueSchema1v2, valueSchemaAndValue.schema());
                    mutationTable1.compute((String) keySchemaAndValue.value(), (k, v) -> v + 1);
                } else if (topicName.endsWith("table2")) {
                    Struct expectedKey = new Struct(keySchemaAndValue.schema())
                            .put("a", "1")
                            .put("b", 1);
                    Struct expectedValue = new Struct(valueSchemaAndValue.schema())
                            .put("c", 1)
                            .put("d", new Struct(type2Schema)
                                    .put("a", 1L)
                                    .put("b", (short) 1));
                    assertEquals(expectedKey, keySchemaAndValue.value());
                    assertEquals(expectedKeySchema2, keySchemaAndValue.schema());
                    assertEquals(expectedValue, valueSchemaAndValue.value());
                    assertEquals(expectedValueSchema2v2, valueSchemaAndValue.schema());
                    mutationTable2.compute(((Struct) keySchemaAndValue.value()).getString("a"), (k, v) -> v + 1);
                }
            }
            consumer.commitSync();
        }
        // expect 2 updates for PK = 1, and 1 for PK=2 and 3
        assertEquals((Integer) 2, mutationTable1.get("1"));
        assertEquals((Integer) 2, mutationTable2.get("1"));
        assertEquals((Integer) 1, mutationTable1.get("2"));
        assertEquals((Integer) 1, mutationTable2.get("2"));
        assertEquals((Integer) 1, mutationTable1.get("3"));
        assertEquals((Integer) 1, mutationTable2.get("3"));

        // delete rows
        try (CqlSession cqlSession = cassandraContainer1.getCqlSession()) {
            cqlSession.execute("DELETE FROM " + ksName + ".table1 WHERE id = '1'");
            cqlSession.execute("DELETE FROM " + ksName + ".table2 WHERE a = '1' AND b = 1");
        }
        // wait commitlogs sync on disk
        Thread.sleep(11000);

        noRecordsCount = 0;
        while (true) {
            final ConsumerRecords<byte[], byte[]> consumerRecords = consumer.poll(Duration.ofMillis(100));
            if (consumerRecords.count() == 0) {
                noRecordsCount++;
                if (noRecordsCount > GIVE_UP) break;
            }
            for (ConsumerRecord<byte[], byte[]> record : consumerRecords) {
                String topicName = record.topic();
                SchemaAndValue keySchemaAndValue = keyConverter.toConnectData(topicName, record.key());
                SchemaAndValue valueSchemaAndValue = valueConverter.toConnectData(topicName, record.value());

                System.out.println("Consumer Record: topicName=" + topicName +
                        " partition=" + record.partition() +
                        " offset=" + record.offset() +
                        " key=" + keySchemaAndValue.value() +
                        " value=" + valueSchemaAndValue.value());
                System.out.println("key schema: " + CassandraConverter.schemaToString(keySchemaAndValue.schema()));
                if (valueSchemaAndValue.schema() != null)
                    System.out.println("value schema: " + CassandraConverter.schemaToString(valueSchemaAndValue.schema()));

                if (topicName.endsWith("table1")) {
                    assertEquals("1", keySchemaAndValue.value());
                    assertEquals(expectedKeySchema1, keySchemaAndValue.schema());
                    assertEquals(null, valueSchemaAndValue.value());
                    if (schemaWithNullValue) {
                        assertEquals(expectedValueSchema1v2, valueSchemaAndValue.schema());
                    } else {
                        assertEquals(null, valueSchemaAndValue.schema());
                    }
                    mutationTable1.compute((String) keySchemaAndValue.value(), (k, v) -> v + 1);
                } else if (topicName.endsWith("table2")) {
                    Struct expectedKey = new Struct(keySchemaAndValue.schema())
                            .put("a", "1")
                            .put("b", 1);
                    assertEquals(expectedKey, keySchemaAndValue.value());
                    assertEquals(expectedKeySchema2, keySchemaAndValue.schema());
                    assertEquals(null, valueSchemaAndValue.value());
                    if (schemaWithNullValue) {
                        assertEquals(expectedValueSchema2v2, valueSchemaAndValue.schema());
                    } else {
                        assertEquals(null, valueSchemaAndValue.schema());
                    }
                    mutationTable2.compute(((Struct) keySchemaAndValue.value()).getString("a"), (k, v) -> v + 1);
                }
            }
            consumer.commitSync();
        }
        // expect 3 updates for PK=1, and 1 for PK=2 and 3
        assertEquals((Integer) 3, mutationTable1.get("1"));
        assertEquals((Integer) 3, mutationTable2.get("1"));
        assertEquals((Integer) 1, mutationTable1.get("2"));
        assertEquals((Integer) 1, mutationTable2.get("2"));
        assertEquals((Integer) 1, mutationTable1.get("3"));
        assertEquals((Integer) 1, mutationTable2.get("3"));

        consumer.close();
        assertEquals(204, kafkaConnectContainer.undeployConnector("cassandra-source-" + ksName + "-table1"));
        assertEquals(204, kafkaConnectContainer.undeployConnector("cassandra-source-" + ksName + "-table2"));
    }
}
