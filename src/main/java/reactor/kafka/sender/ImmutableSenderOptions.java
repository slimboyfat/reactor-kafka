/*
 * Copyright (c) 2018-2023 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.kafka.sender;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serializer;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.sender.observation.KafkaSenderObservationConvention;
import reactor.util.annotation.NonNull;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;

class ImmutableSenderOptions<K, V> implements SenderOptions<K, V> {

    private static final AtomicInteger PRODUCER_CLIENT_ID_SEQUENCE = new AtomicInteger(1);

    private final Map<String, Object> properties;

    private final Serializer<K> keySerializer;

    private final Serializer<V> valueSerializer;

    private final Duration closeTimeout;

    private final Scheduler scheduler;

    private final int maxInFlight;

    private final boolean stopOnError;

    private final ObservationRegistry observationRegistry;

    private final KafkaSenderObservationConvention observationConvention;

    ImmutableSenderOptions() {
        this(new HashMap<>());
    }

    ImmutableSenderOptions(Properties properties) {
        this(
                properties
                        .entrySet()
                        .stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().toString(),
                                Map.Entry::getValue
                        ))
        );
    }

    ImmutableSenderOptions(Map<String, Object> properties) {
        this.properties = new HashMap<>(properties);
        maybeOverrideClientId();
        keySerializer = null;
        valueSerializer = null;

        closeTimeout = Duration.ofMillis(Long.MAX_VALUE);
        scheduler = Schedulers.immediate();
        maxInFlight = Queues.SMALL_BUFFER_SIZE;
        stopOnError = true;
        this.observationRegistry = ObservationRegistry.NOOP;
        this.observationConvention = null;
    }

    /**
     * Simulate {@link ProducerConfig#CLIENT_ID_CONFIG} generation.
     */
    private void maybeOverrideClientId() {
        String refinedClientId;
        boolean userConfiguredClientId = this.properties.containsKey(ProducerConfig.CLIENT_ID_CONFIG);
        if (userConfiguredClientId) {
            refinedClientId = (String) this.properties.get(ProducerConfig.CLIENT_ID_CONFIG);
        } else {
            String transactionalId = (String) this.properties.get(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
            refinedClientId =
                    "producer-" +
                            (transactionalId != null ? transactionalId : PRODUCER_CLIENT_ID_SEQUENCE.getAndIncrement());
        }
        this.properties.put(ProducerConfig.CLIENT_ID_CONFIG, refinedClientId);
    }

    ImmutableSenderOptions(
            Map<String, Object> properties,
            Serializer<K> serializer,
            Serializer<V> valueSerializer,
            Duration timeout,
            Scheduler scheduler,
            int flight,
            boolean error,
            ObservationRegistry observationRegistry,
            KafkaSenderObservationConvention observationConvention
    ) {
        this.properties = properties;
        keySerializer = serializer;
        this.valueSerializer = valueSerializer;
        closeTimeout = timeout;
        this.scheduler = scheduler;
        maxInFlight = flight;
        stopOnError = error;
        this.observationRegistry = observationRegistry;
        this.observationConvention = observationConvention;
    }

    /**
     * Returns the configuration properties for the underlying Kafka {@link Producer}.
     * @return configuration options for Kafka producer
     */
    @Override
    public Map<String, Object> producerProperties() {
        return new HashMap<>(properties);
    }

    /**
     * Returns the Kafka {@link Producer} configuration property value for the specified option name.
     * @return Kafka producer configuration option value
     */
    @Override
    public Object producerProperty(String name) {
        Objects.requireNonNull(name);

        return properties.get(name);
    }

    /**
     * Sets Kafka {@link Producer} configuration property to the specified value.
     * @return sender options with updated Kafka producer option
     */
    @Override
    public SenderOptions<K, V> producerProperty(String name, Object value) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(value);

        HashMap<String, Object> properties = new HashMap<>(this.properties);
        properties.put(name, value);

        return new ImmutableSenderOptions<>(
                properties,
                keySerializer,
                valueSerializer,
                closeTimeout,
                scheduler,
                maxInFlight,
                stopOnError,
                observationRegistry,
                observationConvention
        );
    }

    /**
     *
     * Returns optionally a serializer witch is used by {@link KafkaProducer} for key serialization.
     * @return configured key serializer instant
     */
    @Override
    public Serializer<K> keySerializer() {
        return keySerializer;
    }

    /**
     * Set a concrete serializer instant to be used by the {@link KafkaProducer} for keys. Overrides any setting of the
     * {@link ProducerConfig#KEY_SERIALIZER_CLASS_CONFIG} property.
     * @param keySerializer key serializer to use in the consumer
     * @return options instance with new key serializer
     */
    @Override
    public SenderOptions<K, V> withKeySerializer(Serializer<K> keySerializer) {
        return new ImmutableSenderOptions<>(
                properties,
                Objects.requireNonNull(keySerializer),
                valueSerializer,
                closeTimeout,
                scheduler,
                maxInFlight,
                stopOnError,
                observationRegistry,
                observationConvention
        );
    }

    /**
     *
     * Returns optionally a serializer witch is used by {@link KafkaProducer} for value serialization.
     * @return configured value serializer instant
     */
    @Override
    public Serializer<V> valueSerializer() {
        return valueSerializer;
    }

    /**
     * Set a concrete serializer instant to be used by the {@link KafkaProducer} for values. Overrides any setting of the
     * {@link ProducerConfig#VALUE_SERIALIZER_CLASS_CONFIG} property.
     * @param valueSerializer value serializer to use in the consumer
     * @return options instance with new value serializer
     */
    @Override
    public SenderOptions<K, V> withValueSerializer(Serializer<V> valueSerializer) {
        return new ImmutableSenderOptions<>(
                properties,
                keySerializer,
                Objects.requireNonNull(valueSerializer),
                closeTimeout,
                scheduler,
                maxInFlight,
                stopOnError,
                observationRegistry,
                observationConvention
        );
    }

    /**
     * Returns the scheduler used for publishing send results.
     * @return response scheduler
     */
    @Override
    public Scheduler scheduler() {
        return scheduler;
    }

    /**
     * Sets the scheduler used for publishing send results.
     * @return sender options with updated response scheduler
     */
    @Override
    public SenderOptions<K, V> scheduler(Scheduler scheduler) {
        return new ImmutableSenderOptions<>(
                properties,
                keySerializer,
                valueSerializer,
                closeTimeout,
                Objects.requireNonNull(scheduler),
                maxInFlight,
                stopOnError,
                observationRegistry,
                observationConvention
        );
    }

    /**
     * Returns the maximum number of in-flight records that are fetched
     * from the outbound record publisher while acknowledgements are pending.
     * @return maximum number of in-flight records
     */
    @Override
    public int maxInFlight() {
        return maxInFlight;
    }

    /**
     * Configures the maximum number of in-flight records that are fetched
     * from the outbound record publisher while acknowledgements are pending.
     * This limit must be configured along with {@link ProducerConfig#BUFFER_MEMORY_CONFIG}
     * to control memory usage and to avoid blocking the reactive pipeline.
     * @return sender options with new in-flight limit
     */
    @Override
    public SenderOptions<K, V> maxInFlight(int maxInFlight) {
        return new ImmutableSenderOptions<>(
                properties,
                keySerializer,
                valueSerializer,
                closeTimeout,
                scheduler,
                maxInFlight,
                stopOnError,
                observationRegistry,
                observationConvention
        );
    }

    /**
     * Returns stopOnError configuration which indicates if a send operation
     * should be terminated when an error is encountered. If set to false, send
     * is attempted for all records in a sequence even if send of one of the records fails
     * with a non-fatal exception.
     * @return boolean indicating if send sequences should fail on first error
     */
    @Override
    public boolean stopOnError() {
        return stopOnError;
    }

    /**
     * Configures error handling behaviour for {@link KafkaSender#send(org.reactivestreams.Publisher)}.
     * If set to true, send fails when an error is encountered and only records
     * that are already in transit may be delivered after the first error. If set to false,
     * an attempt is made to send each record to Kafka, even if one or more records cannot
     * be delivered after the configured number of retries due to a non-fatal exception.
     * This flag should be set along with {@link ProducerConfig#RETRIES_CONFIG} and
     * {@link ProducerConfig#ACKS_CONFIG} to configure the required quality-of-service.
     * By default, stopOnError is true.
     * @param stopOnError true to stop each send sequence on first failure
     * @return sender options with the new stopOnError flag.
     */
    @Override
    public SenderOptions<K, V> stopOnError(boolean stopOnError) {
        return new ImmutableSenderOptions<>(
                properties,
                keySerializer,
                valueSerializer,
                closeTimeout,
                scheduler,
                maxInFlight,
                stopOnError,
                observationRegistry,
                observationConvention
        );
    }

    /**
     * Returns the timeout for graceful shutdown of this sender.
     * @return close timeout duration
     */
    @Override
    public Duration closeTimeout() {
        return closeTimeout;
    }

    /**
     * Configures the timeout for graceful shutdown of this sender.
     * @return sender options with updated close timeout
     */
    @Override
    public SenderOptions<K, V> closeTimeout(Duration timeout) {
        return new ImmutableSenderOptions<>(
                properties,
                keySerializer,
                valueSerializer,
                timeout,
                scheduler,
                maxInFlight,
                stopOnError,
                observationRegistry,
                observationConvention
        );
    }

    @NonNull
    @Override
    public SenderOptions<K, V> withObservation(@NonNull ObservationRegistry observationRegistry,
            @Nullable KafkaSenderObservationConvention observationConvention) {

        return new ImmutableSenderOptions<>(
                properties,
                keySerializer,
                valueSerializer,
                closeTimeout,
                scheduler,
                maxInFlight,
                stopOnError,
                observationRegistry,
                observationConvention
        );
    }

    @NonNull
    @Override
    public ObservationRegistry observationRegistry() {
        return observationRegistry;
    }

    @Nullable
    @Override
    public KafkaSenderObservationConvention observationConvention() {
        return observationConvention;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                properties,
                keySerializer,
                valueSerializer,
                closeTimeout,
                scheduler,
                maxInFlight,
                stopOnError
        );
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (object != null && getClass().equals(object.getClass())) {
            ImmutableSenderOptions<?, ?> that = (ImmutableSenderOptions<?, ?>) object;
            return Objects.equals(maxInFlight, that.maxInFlight)
                    && Objects.equals(stopOnError, that.stopOnError)
                    && Objects.equals(properties, that.properties)
                    && Objects.equals(keySerializer, that.keySerializer)
                    && Objects.equals(valueSerializer, that.valueSerializer)
                    && Objects.equals(closeTimeout, that.closeTimeout)
                    && Objects.equals(scheduler, that.scheduler);
        }
        return false;
    }
}
