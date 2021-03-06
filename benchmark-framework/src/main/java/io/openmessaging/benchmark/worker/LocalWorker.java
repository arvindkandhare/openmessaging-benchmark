/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.openmessaging.benchmark.worker;

import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.HdrHistogram.Recorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.RateLimiter;

import io.netty.util.concurrent.DefaultThreadFactory;
import io.openmessaging.benchmark.DriverConfiguration;
import io.openmessaging.benchmark.driver.BenchmarkConsumer;
import io.openmessaging.benchmark.driver.BenchmarkDriver;
import io.openmessaging.benchmark.driver.BenchmarkProducer;
import io.openmessaging.benchmark.driver.ConsumerCallback;
import io.openmessaging.benchmark.utils.Timer;
import io.openmessaging.benchmark.utils.distributor.KeyDistributor;
import io.openmessaging.benchmark.worker.commands.ConsumerAssignment;
import io.openmessaging.benchmark.worker.commands.CountersStats;
import io.openmessaging.benchmark.worker.commands.CumulativeLatencies;
import io.openmessaging.benchmark.worker.commands.PeriodStats;
import io.openmessaging.benchmark.worker.commands.ProducerWorkAssignment;
import io.openmessaging.benchmark.worker.commands.TopicsInfo;

public class LocalWorker implements Worker, ConsumerCallback {

    private BenchmarkDriver benchmarkDriver = null;

    private List<BenchmarkProducer> producers = new ArrayList<>();
    private List<BenchmarkConsumer> consumers = new ArrayList<>();

    private final RateLimiter rateLimiter = RateLimiter.create(1.0);

    private final ExecutorService executor = Executors.newCachedThreadPool(new DefaultThreadFactory("local-worker"));

    private final LongAdder messagesSent = new LongAdder();
    private final LongAdder bytesSent = new LongAdder();

    private final LongAdder messagesReceived = new LongAdder();
    private final LongAdder bytesReceived = new LongAdder();

    private final LongAdder totalMessagesSent = new LongAdder();
    private final LongAdder totalMessagesReceived = new LongAdder();

    private final Recorder publishLatencyRecorder = new Recorder(TimeUnit.SECONDS.toMicros(60), 5);
    private final Recorder cumulativePublishLatencyRecorder = new Recorder(TimeUnit.SECONDS.toMicros(60), 5);

    private final Recorder endToEndLatencyRecorder = new Recorder(TimeUnit.HOURS.toMicros(12), 5);
    private final Recorder endToEndCumulativeLatencyRecorder = new Recorder(TimeUnit.HOURS.toMicros(12), 5);

    private boolean testCompleted = false;

    private boolean consumersArePaused = false;

    @Override
    public void initializeDriver(File driverConfigFile) throws IOException {
        Preconditions.checkArgument(benchmarkDriver == null);
        testCompleted = false;

        DriverConfiguration driverConfiguration = mapper.readValue(driverConfigFile, DriverConfiguration.class);

        log.info("Driver: {}", writer.writeValueAsString(driverConfiguration));

        try {
            benchmarkDriver = (BenchmarkDriver) Class.forName(driverConfiguration.driverClass).newInstance();
            benchmarkDriver.initialize(driverConfigFile);
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> createTopics(TopicsInfo topicsInfo) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        Timer timer = new Timer();

        String topicPrefix = benchmarkDriver.getTopicNamePrefix();

        List<String> topics = new ArrayList<>();
        for (int i = 0; i < topicsInfo.numberOfTopics; i++) {
            String topic = String.format("%s-%s-%04d", topicPrefix, getRandomString(), i);
            topics.add(topic);
            futures.add(benchmarkDriver.createTopic(topic, topicsInfo.numberOfPartitionsPerTopic));
        }

        futures.forEach(CompletableFuture::join);

        log.info("Created {} topics in {} ms", topics.size(), timer.elapsedMillis());
        return topics;
    }

    @Override
    public void createProducers(List<String> topics) {
        Timer timer = new Timer();

        List<CompletableFuture<BenchmarkProducer>> futures = topics.stream()
                .map(topic -> benchmarkDriver.createProducer(topic)).collect(toList());

        futures.forEach(f -> producers.add(f.join()));
        log.info("Created {} producers in {} ms", producers.size(), timer.elapsedMillis());
    }

    @Override
    public void createConsumers(ConsumerAssignment consumerAssignment) {
        Timer timer = new Timer();

        List<CompletableFuture<BenchmarkConsumer>> futures = consumerAssignment.topicsSubscriptions.stream()
                .map(ts -> benchmarkDriver.createConsumer(ts.topic, ts.subscription, this)).collect(toList());

        futures.forEach(f -> consumers.add(f.join()));
        log.info("Created {} consumers in {} ms", consumers.size(), timer.elapsedMillis());
    }

    @Override
    public void startLoad(ProducerWorkAssignment producerWorkAssignment) {
        int processors = Runtime.getRuntime().availableProcessors();

        final Function<BenchmarkProducer, KeyDistributor> assignKeyDistributor = (any) -> KeyDistributor
                .build(producerWorkAssignment.keyDistributorType);

        rateLimiter.setRate(producerWorkAssignment.publishRate);

        Lists.partition(producers, processors).stream()
                .map(producersPerThread -> producersPerThread.stream()
                        .collect(Collectors.toMap(Function.identity(), assignKeyDistributor)))
                .forEach(producersWithKeyDistributor -> submitProducersToExecutor(producersWithKeyDistributor,
                        producerWorkAssignment.payloadData));
    }

    @Override
    public void probeProducers() throws IOException {
        producers.forEach(
                producer -> producer.sendAsync("key", new byte[10]).thenRun(() -> totalMessagesSent.increment()));
    }

    private void submitProducersToExecutor(Map<BenchmarkProducer, KeyDistributor> producersWithKeyDistributor,
            byte[] payloadData) {
        executor.submit(() -> {
            try {
                while (!testCompleted) {
                    producersWithKeyDistributor.forEach((producer, producersKeyDistributor) -> {
                        rateLimiter.acquire();
                        final long sendTime = System.nanoTime();
                        producer.sendAsync(producersKeyDistributor.next(), payloadData).thenRun(() -> {
                            messagesSent.increment();
                            totalMessagesSent.increment();
                            bytesSent.add(payloadData.length);

                            long latencyMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - sendTime);
                            publishLatencyRecorder.recordValue(latencyMicros);
                            cumulativePublishLatencyRecorder.recordValue(latencyMicros);
                        }).exceptionally(ex -> {
                            log.warn("Write error on message", ex);
                            return null;
                        });
                    });
                }
            } catch (Throwable t) {
                log.error("Got error", t);
            }
        });
    }

    @Override
    public void adjustPublishRate(double publishRate) {
        rateLimiter.setRate(publishRate);
    }

    @Override
    public PeriodStats getPeriodStats() {
        PeriodStats stats = new PeriodStats();
        stats.messagesSent = messagesSent.sumThenReset();
        stats.bytesSent = bytesSent.sumThenReset();
        stats.messagesReceived = messagesReceived.sumThenReset();
        stats.bytesReceived = bytesReceived.sumThenReset();
        stats.totalMessagesSent = totalMessagesSent.sum();
        stats.totalMessagesReceived = totalMessagesReceived.sum();

        stats.publishLatency = publishLatencyRecorder.getIntervalHistogram();
        stats.endToEndLatency = endToEndLatencyRecorder.getIntervalHistogram();
        return stats;
    }

    @Override
    public CumulativeLatencies getCumulativeLatencies() {
        CumulativeLatencies latencies = new CumulativeLatencies();
        latencies.publishLatency = cumulativePublishLatencyRecorder.getIntervalHistogram();
        latencies.endToEndLatency = endToEndCumulativeLatencyRecorder.getIntervalHistogram();
        return latencies;
    }

    @Override
    public CountersStats getCountersStats() throws IOException {
        CountersStats stats = new CountersStats();
        stats.messagesSent = totalMessagesSent.sum();
        stats.messagesReceived = totalMessagesReceived.sum();
        return stats;
    }

    @Override
    public void messageReceived(byte[] data, long publishTimestamp) {
        messagesReceived.increment();
        totalMessagesReceived.increment();
        bytesReceived.add(data.length);

        long now = System.currentTimeMillis();
        long endToEndLatencyMicros = TimeUnit.MILLISECONDS.toMicros(now - publishTimestamp);
        endToEndCumulativeLatencyRecorder.recordValue(endToEndLatencyMicros);
        endToEndLatencyRecorder.recordValue(endToEndLatencyMicros);

        while (consumersArePaused) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void pauseConsumers() throws IOException {
        consumersArePaused = true;
        log.info("Pausing consumers");
    }

    @Override
    public void resumeConsumers() throws IOException {
        consumersArePaused = false;
        log.info("Resuming consumers");
    }

    @Override
    public void resetStats() throws IOException {
        publishLatencyRecorder.reset();
        cumulativePublishLatencyRecorder.reset();
        endToEndLatencyRecorder.reset();
        endToEndCumulativeLatencyRecorder.reset();
    }

    @Override
    public void stopAll() throws IOException {
        testCompleted = true;
        consumersArePaused = false;

        publishLatencyRecorder.reset();
        cumulativePublishLatencyRecorder.reset();
        endToEndLatencyRecorder.reset();
        endToEndCumulativeLatencyRecorder.reset();

        messagesSent.reset();
        bytesSent.reset();
        messagesReceived.reset();
        bytesReceived.reset();
        totalMessagesSent.reset();
        totalMessagesReceived.reset();

        try {
            Thread.sleep(100);

            for (BenchmarkProducer producer : producers) {
                producer.close();
            }
            producers.clear();

            for (BenchmarkConsumer consumer : consumers) {
                consumer.close();
            }
            consumers.clear();

            if (benchmarkDriver != null) {
                benchmarkDriver.close();
                benchmarkDriver = null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {
        executor.shutdown();
    }

    private static final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static {
        mapper.enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
    }

    private static final Random random = new Random();

    private static final String getRandomString() {
        byte[] buffer = new byte[5];
        random.nextBytes(buffer);
        return BaseEncoding.base64Url().omitPadding().encode(buffer);
    }

    private static final Logger log = LoggerFactory.getLogger(LocalWorker.class);
}
