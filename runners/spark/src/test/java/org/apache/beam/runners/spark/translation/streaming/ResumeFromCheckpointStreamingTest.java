/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.spark.translation.streaming;

import static org.apache.beam.sdk.metrics.MetricMatchers.attemptedMetricsResult;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.beam.runners.spark.ReuseSparkContextRule;
import org.apache.beam.runners.spark.SparkPipelineOptions;
import org.apache.beam.runners.spark.SparkPipelineResult;
import org.apache.beam.runners.spark.SparkRunner;
import org.apache.beam.runners.spark.aggregators.AggregatorsAccumulator;
import org.apache.beam.runners.spark.coders.CoderHelpers;
import org.apache.beam.runners.spark.metrics.SparkMetricsContainer;
import org.apache.beam.runners.spark.translation.streaming.utils.EmbeddedKafkaCluster;
import org.apache.beam.runners.spark.util.GlobalWatermarkHolder;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.InstantCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.kafka.KafkaIO;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.MetricNameFilter;
import org.apache.beam.sdk.metrics.MetricResult;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.metrics.MetricsFilter;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.Keys;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.transforms.windowing.AfterWatermark;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PDone;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.hamcrest.collection.IsEmptyIterable;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;


/**
 * Tests DStream recovery from checkpoint.
 *
 * <p>Runs the pipeline reading from a Kafka backlog with a WM function that will move to infinity
 * on a EOF signal.
 * After resuming from checkpoint, a single output (guaranteed by the WM) is asserted, along with
 * {@link Counter}s values that are expected to resume from previous count as well.
 */
public class ResumeFromCheckpointStreamingTest {
  private static final EmbeddedKafkaCluster.EmbeddedZookeeper EMBEDDED_ZOOKEEPER =
      new EmbeddedKafkaCluster.EmbeddedZookeeper();
  private static final EmbeddedKafkaCluster EMBEDDED_KAFKA_CLUSTER =
      new EmbeddedKafkaCluster(EMBEDDED_ZOOKEEPER.getConnection(), new Properties());
  private static final String TOPIC = "kafka_beam_test_topic";

  @Rule
  public TemporaryFolder tmpFolder = new TemporaryFolder();
  @Rule
  public ReuseSparkContextRule noContextResue = ReuseSparkContextRule.no();
  @Rule
  public transient TestName testName = new TestName();

  @BeforeClass
  public static void init() throws IOException {
    EMBEDDED_ZOOKEEPER.startup();
    EMBEDDED_KAFKA_CLUSTER.startup();
  }

  private static void produce(Map<String, Instant> messages) {
    Properties producerProps = new Properties();
    producerProps.putAll(EMBEDDED_KAFKA_CLUSTER.getProps());
    producerProps.put("request.required.acks", 1);
    producerProps.put("bootstrap.servers", EMBEDDED_KAFKA_CLUSTER.getBrokerList());
    Serializer<String> stringSerializer = new StringSerializer();
    Serializer<Instant> instantSerializer = new Serializer<Instant>() {
      @Override
      public void configure(Map<String, ?> configs, boolean isKey) { }

      @Override
      public byte[] serialize(String topic, Instant data) {
        return CoderHelpers.toByteArray(data, InstantCoder.of());
      }

      @Override
      public void close() { }
    };

    try (@SuppressWarnings("unchecked") KafkaProducer<String, Instant> kafkaProducer =
        new KafkaProducer(producerProps, stringSerializer, instantSerializer)) {
          for (Map.Entry<String, Instant> en : messages.entrySet()) {
            kafkaProducer.send(new ProducerRecord<>(TOPIC, en.getKey(), en.getValue()));
          }
          kafkaProducer.close();
        }
  }

  @Test
  public void testWithResume() throws Exception {
    SparkPipelineOptions options = PipelineOptionsFactory.create().as(SparkPipelineOptions.class);
    options.setRunner(SparkRunner.class);
    options.setCheckpointDir(tmpFolder.newFolder().toString());
    options.setCheckpointDurationMillis(500L);
    options.setJobName(testName.getMethodName());
    options.setSparkMaster("local[*]");

    // write to Kafka
    produce(ImmutableMap.of(
        "k1", new Instant(100),
        "k2", new Instant(200),
        "k3", new Instant(300),
        "k4", new Instant(400)
    ));

    MetricsFilter metricsFilter =
        MetricsFilter.builder()
            .addNameFilter(MetricNameFilter.inNamespace(ResumeFromCheckpointStreamingTest.class))
            .build();

    MetricsFilter processedMessagesFilter =
        MetricsFilter.builder()
            .addNameFilter(MetricNameFilter.named("main", "processedMessages"))
            .build();

    // first run will read from Kafka backlog - "auto.offset.reset=smallest"
    SparkPipelineResult res = run(options);
    res.waitUntilFinish(Duration.standardSeconds(5));
    Assert.assertTrue(res != null);
    // assertions 1:
    assertThat(res.metrics().queryMetrics(processedMessagesFilter).counters(),
        hasItem(attemptedMetricsResult("main",
            "processedMessages", "EOFShallNotPassFn", 4L)));
    assertThat(res.metrics().queryMetrics(metricsFilter).counters(),
        hasItem(attemptedMetricsResult(ResumeFromCheckpointStreamingTest.class.getName(),
            "allMessages", "EOFShallNotPassFn", 4L)));

    //--- between executions:

    //- clear state.
    AggregatorsAccumulator.clear();
    SparkMetricsContainer.clear();
    GlobalWatermarkHolder.clear();

    //- write a bit more.
    produce(ImmutableMap.of(
        "k5", new Instant(499),
        "EOF", new Instant(500) // to be dropped from [0, 500).
    ));

    // recovery should resume from last read offset, and read the second batch of input.
    res = runAgain(options);
    res.waitUntilFinish(Duration.standardSeconds(5));
    // assertions 2:
    assertThat(res.metrics().queryMetrics(processedMessagesFilter).counters(),
        hasItem(attemptedMetricsResult("main",
            "processedMessages", "EOFShallNotPassFn", 5L)));
    assertThat(res.metrics().queryMetrics(metricsFilter).counters(),
        hasItem(attemptedMetricsResult(ResumeFromCheckpointStreamingTest.class.getName(),
            "allMessages", "EOFShallNotPassFn", 6L)));
    MetricsFilter emptyFilter =
        MetricsFilter.builder().build();
    assertThat(res.metrics().queryMetrics(emptyFilter).counters(),
        hasItem(attemptedMetricsResult("main",
            PAssert.SUCCESS_COUNTER,
            "ResumeFromCheckpointStreamingTest.PAssertWithoutFlatten/ParDo(Assert)",
            1L)));
    // validate assertion didn't fail.
    MetricsFilter failureCounterFilter =
        MetricsFilter.builder()
            .addNameFilter(MetricNameFilter.named("main", PAssert.FAILURE_COUNTER))
            .build();
    assertThat(res.metrics().queryMetrics(failureCounterFilter).counters(),
        IsEmptyIterable.<MetricResult<Long>>emptyIterable());
  }

  private SparkPipelineResult runAgain(SparkPipelineOptions options) {
    // sleep before next run.
    Uninterruptibles.sleepUninterruptibly(10, TimeUnit.MILLISECONDS);
    return run(options);
  }

  private static SparkPipelineResult run(SparkPipelineOptions options) {
    KafkaIO.Read<String, Instant> read = KafkaIO.<String, Instant>read()
        .withBootstrapServers(EMBEDDED_KAFKA_CLUSTER.getBrokerList())
        .withTopics(Collections.singletonList(TOPIC))
        .withKeyCoder(StringUtf8Coder.of())
        .withValueCoder(InstantCoder.of())
        .updateConsumerProperties(ImmutableMap.<String, Object>of("auto.offset.reset", "earliest"))
        .withTimestampFn(new SerializableFunction<KV<String, Instant>, Instant>() {
          @Override
          public Instant apply(KV<String, Instant> kv) {
            return kv.getValue();
          }
        }).withWatermarkFn(new SerializableFunction<KV<String, Instant>, Instant>() {
          @Override
          public Instant apply(KV<String, Instant> kv) {
            // at EOF move WM to infinity.
            String key = kv.getKey();
            Instant instant = kv.getValue();
            return key.equals("EOF") ? BoundedWindow.TIMESTAMP_MAX_VALUE : instant;
          }
        });

    Pipeline p = Pipeline.create(options);

    PCollection<String> expectedCol =
        p.apply(Create.of(ImmutableList.of("side1", "side2")).withCoder(StringUtf8Coder.of()));
    PCollectionView<List<String>> view = expectedCol.apply(View.<String>asList());

    PCollection<Iterable<String>> grouped = p
        .apply(read.withoutMetadata())
        .apply(Keys.<String>create())
        .apply("EOFShallNotPassFn", ParDo.of(new EOFShallNotPassFn(view)).withSideInputs(view))
        .apply(Window.<String>into(FixedWindows.of(Duration.millis(500)))
            .triggering(AfterWatermark.pastEndOfWindow())
                .accumulatingFiredPanes()
                .withAllowedLateness(Duration.ZERO))
        .apply(WithKeys.<Integer, String>of(1))
        .apply(GroupByKey.<Integer, String>create())
        .apply(Values.<Iterable<String>>create());

    grouped.apply(new PAssertWithoutFlatten<>("k1", "k2", "k3", "k4", "k5"));

    return (SparkPipelineResult) p.run();
  }

  @AfterClass
  public static void tearDown() {
    EMBEDDED_KAFKA_CLUSTER.shutdown();
    EMBEDDED_ZOOKEEPER.shutdown();
  }

  /** A pass-through fn that prevents EOF event from passing. */
  private static class EOFShallNotPassFn extends DoFn<String, String> {
    final PCollectionView<List<String>> view;
    private final Counter processedMessages =
        Metrics.counter("main", "processedMessages");
    Counter counter =
        Metrics.counter(ResumeFromCheckpointStreamingTest.class, "allMessages");

    private EOFShallNotPassFn(PCollectionView<List<String>> view) {
      this.view = view;
    }

    @ProcessElement
    public void process(ProcessContext c) {
      String element = c.element();
      // assert that side input is passed correctly before/after resuming from checkpoint.
      assertThat(c.sideInput(view), containsInAnyOrder("side1", "side2"));
      counter.inc();
      if (!element.equals("EOF")) {
        processedMessages.inc();
        c.output(c.element());
      }
    }
  }

  /**
   * A custom PAssert that avoids using {@link org.apache.beam.sdk.transforms.Flatten}
   * until BEAM-1444 is resolved.
   */
  private static class PAssertWithoutFlatten<T>
      extends PTransform<PCollection<Iterable<T>>, PDone> {
    private final T[] expected;

    private PAssertWithoutFlatten(T... expected) {
      this.expected = expected;
    }

    @Override
    public PDone expand(PCollection<Iterable<T>> input) {
      input.apply(ParDo.of(new AssertDoFn<>(expected)));
      return PDone.in(input.getPipeline());
    }

    private static class AssertDoFn<T> extends DoFn<Iterable<T>, Void> {
      private final Counter successCounter =
          Metrics.counter("main", PAssert.SUCCESS_COUNTER);
      private final Counter failureCounter =
          Metrics.counter("main", PAssert.FAILURE_COUNTER);
      private final T[] expected;

      AssertDoFn(T[] expected) {
        this.expected = expected;
      }

      @ProcessElement
      public void processElement(ProcessContext c) throws Exception {
        try {
          assertThat(c.element(), containsInAnyOrder(expected));
          successCounter.inc();
        } catch (Throwable t) {
          failureCounter.inc();
          throw t;
        }
      }
    }
  }

}
