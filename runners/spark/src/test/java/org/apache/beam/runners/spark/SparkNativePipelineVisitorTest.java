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

package org.apache.beam.runners.spark;

import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import org.apache.beam.runners.spark.examples.WordCount;
import org.apache.beam.runners.spark.translation.EvaluationContext;
import org.apache.beam.runners.spark.translation.SparkPipelineTranslator;
import org.apache.beam.runners.spark.translation.TransformTranslator;
import org.apache.beam.runners.spark.translation.streaming.StreamingTransformTranslator;
import org.apache.beam.runners.spark.translation.streaming.utils.SparkTestPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.kafka.KafkaIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.Distinct;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.hamcrest.Matchers;
import org.joda.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;


/**
 * Test {@link SparkNativePipelineVisitor} with different pipelines.
 */
public class SparkNativePipelineVisitorTest {

  private File outputDir;

  private JavaSparkContext jsc;

  @Rule
  public final SparkTestPipelineOptions pipelineOptions = new SparkTestPipelineOptions();

  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Before
  public void setUp() throws IOException {
    outputDir = tmpDir.newFolder("out");
    outputDir.delete();
    jsc = new JavaSparkContext("local[*]", "Existing_Context");
  }

  @After
  public void teardown() {
    jsc.stop();
  }

  @Test
  public void debugBoundedPipeline() {
    SparkPipelineOptions options = getDebugOptions(jsc);

    Pipeline pipeline = Pipeline.create(options);

    pipeline
        .apply(Create.of(Collections.<String>emptyList()).withCoder(StringUtf8Coder.of()))
        .apply(new WordCount.CountWords())
        .apply(MapElements.via(new WordCount.FormatAsTextFn()))
        .apply(TextIO.Write.to(outputDir.getAbsolutePath()).withNumShards(3).withSuffix(".txt"));

    TransformTranslator.Translator translator = new TransformTranslator.Translator();
    EvaluationContext context = new EvaluationContext(jsc, pipeline);
    SparkNativePipelineVisitor visitor = new SparkNativePipelineVisitor(translator, context);

    pipeline.traverseTopologically(visitor);

    final String expectedPipeline = "sparkContext.parallelize(Arrays.asList(...))\n"
        + ".mapPartitions(new org.apache.beam.runners.spark.examples.WordCount$ExtractWordsFn())\n"
        + ".mapPartitions(new org.apache.beam.sdk.transforms.Count$PerElement$1())\n"
        + ".<combinePerKey>\n"
        + ".mapPartitions(new org.apache.beam.runners.spark.examples.WordCount$FormatAsTextFn())\n."
        + "<org.apache.beam.sdk.io.TextIO$Write$Bound>";

    String debugString = visitor.getDebugString();

    System.out.println(debugString);

    assertThat("Debug pipeline did not equal expected",
        debugString,
        Matchers.equalTo(expectedPipeline));
  }

  @Test
  public void debugUnboundedPipeline() {
    SparkPipelineOptions options = getDebugOptions(jsc);
    options.setStreaming(true);

    org.apache.spark.streaming.Duration batchDuration =
        new org.apache.spark.streaming.Duration(options.getBatchIntervalMillis());

    JavaStreamingContext jssc = new JavaStreamingContext(jsc, batchDuration);

    Pipeline pipeline = Pipeline.create(options);

    KafkaIO.Read<String, String> read = KafkaIO.<String, String>read()
        .withBootstrapServers("mykafka:9092")
        .withTopics(Collections.singletonList("my_input_topic"))
        .withKeyCoder(StringUtf8Coder.of())
        .withValueCoder(StringUtf8Coder.of());

    KafkaIO.Write<String, String> write = KafkaIO.<String, String>write()
        .withBootstrapServers("myotherkafka:9092")
        .withTopic("my_output_topic")
        .withKeyCoder(StringUtf8Coder.of())
        .withValueCoder(StringUtf8Coder.of());

    KvCoder<String, String> stringKvCoder = KvCoder.of(StringUtf8Coder.of(), StringUtf8Coder.of());

    pipeline
        .apply(read.withoutMetadata()).setCoder(stringKvCoder)
        .apply(Window.<KV<String, String>>into(FixedWindows.of(Duration.standardSeconds(5))))
        .apply(ParDo.of(new FormatKVFn()))
        .apply(Distinct.<String>create())
        .apply(WithKeys.of(new ArbitraryKeyFunction()))
        .apply(write);

    SparkPipelineTranslator translator =
        new StreamingTransformTranslator.Translator(
        new TransformTranslator.Translator());
    EvaluationContext context = new EvaluationContext(jsc, pipeline, jssc);
    SparkNativePipelineVisitor visitor = new SparkNativePipelineVisitor(translator, context);

    pipeline.traverseTopologically(visitor);

    final String expectedPipeline = "KafkaUtils.createDirectStream(...)\n"
        + ".<window>\n"
        + ".mapPartitions(new org.apache.beam.runners.spark."
        + "SparkNativePipelineVisitorTest$FormatKVFn())\n"
        + ".mapPartitions(new org.apache.beam.sdk.transforms.Distinct$2())\n"
        + ".<combinePerKey>\n"
        + ".mapPartitions(new org.apache.beam.sdk.transforms.Keys$1())\n"
        + ".mapPartitions(new org.apache.beam.sdk.transforms.WithKeys$2())\n"
        + ".<org.apache.beam.sdk.io.kafka.AutoValue_KafkaIO_Write>";

    String debugString = visitor.getDebugString();

    System.out.println(debugString);

    assertThat("Debug pipeline did not equal expected",
        debugString,
        Matchers.equalTo(expectedPipeline));
  }

  private static class FormatKVFn extends DoFn<KV<String, String>, String> {
    @SuppressWarnings("unused")
    @ProcessElement
    public void processElement(ProcessContext c) {
      c.output(c.element().getKey() + "," + c.element().getValue());
    }
  }

  private static class ArbitraryKeyFunction implements SerializableFunction<String, String> {
    @Override
    public String apply(String input) {
      return "someKey";
    }
  }

  private SparkPipelineOptions getDebugOptions(JavaSparkContext jsc) {
    SparkContextOptions options = PipelineOptionsFactory.as(SparkContextOptions.class);
    options.setProvidedSparkContext(jsc);
    options.setDebugPipeline(true);
    options.setRunner(TestSparkRunner.class);
    return options;
  }
}
