/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.quarkus.kafka.oauth.it.container;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.dockerjava.api.command.InspectContainerResponse;
import io.strimzi.StrimziKafkaContainer;
import org.jboss.logging.Logger;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/**
 * Inspired from https://github.com/quarkusio/quarkus/tree/main/integration-tests/kafka-oauth-keycloak/
 */
public class KafkaContainer extends FixedHostPortGenericContainer<KafkaContainer> {

    private static final Logger LOGGER = Logger.getLogger(KafkaContainer.class);

    private static final String STARTER_SCRIPT = "/testcontainers_start.sh";
    private static final int KAFKA_PORT = 9092;
    private static final String LATEST_KAFKA_VERSION;

    private static final List<String> supportedKafkaVersions = new ArrayList<>(3);

    static {
        InputStream inputStream = StrimziKafkaContainer.class.getResourceAsStream("/kafka-versions.txt");
        InputStreamReader streamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);

        try (BufferedReader bufferedReader = new BufferedReader(streamReader)) {
            String kafkaVersion;
            while ((kafkaVersion = bufferedReader.readLine()) != null) {
                supportedKafkaVersions.add(kafkaVersion);
            }
        } catch (IOException e) {
            LOGGER.error("Unable to load the supported Kafka versions", e);
        }

        // sort kafka version from low to high
        Collections.sort(supportedKafkaVersions);

        LATEST_KAFKA_VERSION = supportedKafkaVersions.get(supportedKafkaVersions.size() - 1);
    }

    public KafkaContainer() {
        super("quay.io/strimzi/kafka:" + "latest-kafka-" + LATEST_KAFKA_VERSION);

        withExposedPorts(KAFKA_PORT);
        withFixedExposedPort(KAFKA_PORT, KAFKA_PORT);
        withCopyFileToContainer(MountableFile.forClasspathResource("kafkaServer.properties"),
                "/opt/kafka/config/server.properties");
        waitingFor(Wait.forLogMessage(".*Kafka startTimeMs:.*", 1));
        withNetwork(Network.SHARED);
        withNetworkAliases("kafka");
        withEnv("LOG_DIR", "/tmp");
    }

    @Override
    protected void doStart() {
        // we need it for the startZookeeper(); and startKafka(); to run container before...
        withCommand("sh", "-c", "while [ ! -f " + STARTER_SCRIPT + " ]; do sleep 0.1; done; " + STARTER_SCRIPT);
        super.doStart();
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo, boolean reused) {
        super.containerIsStarting(containerInfo, reused);
        LOGGER.info("Kafka servers :: " + getBootstrapServers());
        String command = "#!/bin/bash \n";
        command += "bin/zookeeper-server-start.sh ./config/zookeeper.properties &\n";
        command += "export CLASSPATH=\"/opt/kafka/libs/strimzi/*:$CLASSPATH\" \n";
        command += "bin/kafka-server-start.sh ./config/server.properties" +
                " --override listeners=JWT://:" + KAFKA_PORT +
                " --override advertised.listeners=" + getBootstrapServers();
        copyFileToContainer(Transferable.of(command.getBytes(StandardCharsets.UTF_8), 700), STARTER_SCRIPT);
    }

    public String getBootstrapServers() {
        return String.format("JWT://%s:%s", getHost(), KAFKA_PORT);
    }

}
