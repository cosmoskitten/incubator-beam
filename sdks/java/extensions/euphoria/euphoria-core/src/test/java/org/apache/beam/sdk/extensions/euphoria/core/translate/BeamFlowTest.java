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

package org.apache.beam.sdk.extensions.euphoria.core.translate;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.beam.sdk.extensions.euphoria.core.client.dataset.Dataset;
import org.apache.beam.sdk.extensions.euphoria.core.client.flow.Flow;
import org.apache.beam.sdk.extensions.euphoria.core.client.io.Collector;
import org.apache.beam.sdk.extensions.euphoria.core.client.io.ListDataSink;
import org.apache.beam.sdk.extensions.euphoria.core.client.io.ListDataSource;
import org.apache.beam.sdk.extensions.euphoria.core.client.io.VoidSink;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.AssignEventTime;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.CountByKey;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.FlatMap;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.Join;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.MapElements;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.ReduceByKey;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.ReduceWindow;
import org.apache.beam.sdk.extensions.euphoria.core.client.util.Sums;
import org.apache.beam.sdk.extensions.euphoria.core.translate.coder.KryoCoder;
import org.apache.beam.sdk.extensions.euphoria.testing.DatasetAssert;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.DefaultTrigger;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

/** Test for {@link BeamFlow}. */
public class BeamFlowTest implements Serializable {

  @Rule public transient TestPipeline pipeline = TestPipeline.create();

  @Test
  public void testPipelineExec() {
    BeamFlow flow = BeamFlow.of(pipeline);
    ListDataSource<Integer> source = ListDataSource.bounded(Arrays.asList(1, 2, 3, 4, 5));
    ListDataSink<Integer> sink = ListDataSink.get();
    Dataset<Integer> input = flow.createInput(source);
    MapElements.of(input).using(e -> e + 1).output().persist(sink);

    pipeline.run().waitUntilFinish();
    DatasetAssert.unorderedEquals(sink.getOutputs(), 2, 3, 4, 5, 6);
  }

  @Test
  public void testEuphoriaLoadBeamProcess() {
    BeamFlow flow = BeamFlow.of(pipeline);
    ListDataSource<Integer> source = ListDataSource.bounded(Arrays.asList(1, 2, 3, 4, 5));
    Dataset<Integer> input = flow.createInput(source);
    PCollection<Integer> unwrapped = flow.unwrapped(input);
    PCollection<Integer> output =
        unwrapped
            .apply(
                ParDo.of(
                    new DoFn<Integer, Integer>() {
                      @ProcessElement
                      public void process(ProcessContext context) {
                        context.output(context.element() + 1);
                      }
                    }))
            .setTypeDescriptor(TypeDescriptor.of(Integer.class));
    PAssert.that(output).containsInAnyOrder(2, 3, 4, 5, 6);
    pipeline.run();
  }

  @Test
  public void testEuphoriaLoadBeamProcessWithEventTime() {
    BeamFlow flow = BeamFlow.of(pipeline);
    ListDataSource<Integer> source = ListDataSource.bounded(Arrays.asList(1, 2, 3, 4, 5));
    Dataset<Integer> input = flow.createInput(source, e -> System.currentTimeMillis());
    PCollection<Integer> unwrapped = flow.unwrapped(input);
    PCollection<Integer> output =
        unwrapped
            .apply(
                ParDo.of(
                    new DoFn<Integer, Integer>() {
                      @ProcessElement
                      public void process(ProcessContext context) {
                        context.output(context.element() + 1);
                      }
                    }))
            .setTypeDescriptor(TypeDescriptor.of(Integer.class));
    PAssert.that(output).containsInAnyOrder(2, 3, 4, 5, 6);
    pipeline.run();
  }

  @Test
  public void testPipelineFromBeam() {
    List<Integer> inputs = Arrays.asList(1, 2, 3, 4, 5);
    BeamFlow flow = BeamFlow.of(pipeline);
    PCollection<Integer> input =
        pipeline.apply(Create.of(inputs)).setTypeDescriptor(TypeDescriptor.of(Integer.class));
    Dataset<Integer> ds = flow.wrapped(input);
    ListDataSink<Integer> sink = ListDataSink.get();
    MapElements.of(ds).using(e -> e + 1).output().persist(sink);
    pipeline.run().waitUntilFinish();
    DatasetAssert.unorderedEquals(sink.getOutputs(), 2, 3, 4, 5, 6);
  }

  @Test
  public void testPipelineToAndFromBeam() {
    List<Integer> inputs = Arrays.asList(1, 2, 3, 4, 5);
    BeamFlow flow = BeamFlow.of(pipeline);
    PCollection<Integer> input =
        pipeline.apply(Create.of(inputs)).setTypeDescriptor(TypeDescriptor.of(Integer.class));
    Dataset<Integer> ds = flow.wrapped(input);
    Dataset<Integer> output =
        FlatMap.of(ds).using((Integer e, Collector<Integer> c) -> c.collect(e + 1)).output();
    PCollection<Integer> unwrapped = flow.unwrapped(output);
    PAssert.that(unwrapped).containsInAnyOrder(2, 3, 4, 5, 6);
    pipeline.run();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testPipelineWithRBK() {
    String raw = "hi there hi hi sue bob hi sue ZOW bob";
    List<String> words = Arrays.asList(raw.split(" "));
    BeamFlow flow = BeamFlow.of(pipeline);
    PCollection<String> input =
        pipeline.apply(Create.of(words)).setTypeDescriptor(TypeDescriptor.of(String.class));
    Dataset<String> dataset = flow.wrapped(input);
    Dataset<KV<String, Long>> output = CountByKey.of(dataset).keyBy(e -> e).output();
    PCollection<KV<String, Long>> beamOut = flow.unwrapped(output);
    PAssert.that(beamOut)
        .containsInAnyOrder(
            KV.of("hi", 4L),
            KV.of("there", 1L),
            KV.of("sue", 2L),
            KV.of("ZOW", 1L),
            KV.of("bob", 2L));
    pipeline.run();
  }

  @Test
  public void testPipelineWithEventTime() {
    List<KV<Integer, Long>> raw =
        Arrays.asList(
            KV.of(1, 1000L),
            KV.of(2, 1500L),
            KV.of(3, 1800L), // first window
            KV.of(4, 2000L),
            KV.of(5, 2500L)); // second window
    BeamFlow flow = BeamFlow.of(pipeline);
    PCollection<KV<Integer, Long>> input = pipeline.apply(Create.of(raw));
    Dataset<KV<Integer, Long>> dataset = flow.wrapped(input);
    Dataset<KV<Integer, Long>> timeAssigned =
        AssignEventTime.of(dataset).using(KV::getValue).output();
    Dataset<Integer> output =
        ReduceWindow.of(timeAssigned)
            .valueBy(KV::getKey)
            .combineBy(Sums.ofInts())
            .windowBy(FixedWindows.of(org.joda.time.Duration.standardSeconds(1)))
            .triggeredBy(DefaultTrigger.of())
            .discardingFiredPanes()
            .output();
    PCollection<Integer> beamOut = flow.unwrapped(output);
    PAssert.that(beamOut).containsInAnyOrder(6, 9);
    pipeline.run();
  }

  @Test
  @Ignore("This shout be resolved in BEAM-5248")
  public void testMultipleDatasetTransform() {
    final Flow flow = Flow.create(); //TODO try to use BeamFlow -> test passes

    final ListDataSource<Integer> input =
        ListDataSource.unbounded(Arrays.asList(1, 2, 3), Arrays.asList(2, 3, 4));

    final ListDataSink<Integer> output1 = ListDataSink.get();
    Dataset<Integer> inputUsedTwice = flow.createInput(input);
    MapElements.of(inputUsedTwice).using(i -> i + 1).output().persist(output1);

    Dataset<KV<Integer, String>> output =
        MapElements.of(inputUsedTwice).using(e -> KV.of(0, e.toString())).output();

    Join.named("usage-of-input-again")
        .of(output, inputUsedTwice)
        .by(KV::getKey, e -> e)
        .using((KV<Integer, String> a, Integer b, Collector<Integer> c) -> c.collect(a.getKey()))
        .outputValues()
        .persist(new VoidSink<>());

    BeamRunnerWrapper executor = BeamRunnerWrapper.ofDirect();
    executor.executeSync(flow);
  }

  @Test
  public void testDatasetAsInputToMultipleTransforms() {
    final Flow flow = Flow.create();
    //final BeamFlow flow = BeamFlow.of(pipeline);

    final ListDataSource<Integer> input =
        ListDataSource.unbounded(Arrays.asList(1, 2, 3), Arrays.asList(2, 3, 4));

    final ListDataSink<Integer> output1 = ListDataSink.get();
    Dataset<Integer> inputUsedTwice = flow.createInput(input);

    MapElements.of(inputUsedTwice).using(i -> i + 1).output().persist(output1);

    Dataset<KV<Integer, String>> output =
        MapElements.named("firstMap")
            .of(inputUsedTwice)
            .using(e -> KV.of(0, e.toString()))
            .output();

    Dataset<Integer> secondMapping =
        MapElements.named("secondMap").of(inputUsedTwice).using(i -> i).output();

    BeamRunnerWrapper executor = BeamRunnerWrapper.ofDirect();
    executor.executeSync(flow);

    //    PAssert.that(flow.unwrapped(output))
    //        .containsInAnyOrder()

  }

  @Test
  public void testDatasetAsInputToMultipleCoderSettingTransforms() {
    final Flow flow = Flow.create();
    //final BeamFlow   flow = BeamFlow.of(pipeline);

    final ListDataSource<Integer> input =
        ListDataSource.unbounded(Arrays.asList(1, 2, 3), Arrays.asList(2, 3, 4));

    final ListDataSink<Integer> output1 = ListDataSink.get();
    Dataset<Integer> inputUsedTwice = flow.createInput(input);

    MapElements.of(inputUsedTwice).using(i -> i + 1).output().persist(output1);

    Dataset<KV<Integer, Integer>> firstOutput =
        ReduceByKey.named("FirstRBK")
            .of(inputUsedTwice)
            .keyBy(i -> i % 2)
            .reduceBy(Sums.ofInts())
            .windowBy(FixedWindows.of(Duration.standardSeconds(1)))
            .triggeredBy(DefaultTrigger.of())
            .discardingFiredPanes()
            .output();

    Dataset<KV<Integer, Integer>> secondOutput =
        ReduceByKey.named("SecondRBK")
            .of(firstOutput)
            .keyBy(KV::getKey)
            .valueBy(KV::getValue)
            .reduceBy(Sums.ofInts())
            .output();

    BeamRunnerWrapper executor = BeamRunnerWrapper.ofDirect();
    executor.executeSync(flow);

    //    PAssert.that(flow.unwrapped(output))
    //        .containsInAnyOrder()

  }

  @Test
  @Ignore("This shout be deleted in BEAM-5248")
  public void windowingOnceSetTwiceUsed() {

    pipeline
        .getCoderRegistry()
        .registerCoderForType(
            TypeDescriptor.of(TestObject.class), KryoCoder.withoutClassRegistration());

    PCollection<KV<String, TestObject>> inputAC =
        pipeline
            .apply(
                Create.of(
                        // first window
                        KV.of("a", new TestObject(1, "Achird")),
                        KV.of("a", new TestObject(1, "Aldebaran")),
                        KV.of("c", new TestObject(1, "Caph")),
                        //second window
                        KV.of("a", new TestObject(8, "Achird")),
                        KV.of("a", new TestObject(8, "Aldebaran")),
                        KV.of("c", new TestObject(8, "Caph")))
                    .withCoder(KryoCoder.withoutClassRegistration()))
            .setTypeDescriptor(
                TypeDescriptors.kvs(
                    TypeDescriptors.strings(), TypeDescriptor.of(TestObject.class)));

    PCollection<KV<String, TestObject>> timestampedOutput =
        inputAC.apply(
            "with-timespamp",
            ParDo.of(
                new DoFn<KV<String, TestObject>, KV<String, TestObject>>() {
                  @ProcessElement
                  @SuppressWarnings("unused")
                  public void processElement(ProcessContext ctx) {
                    KV<String, TestObject> element = ctx.element();
                    ctx.outputWithTimestamp(
                        element, new Instant(element.getValue().getTimestamp()));
                  }
                }));

    PCollection<KV<String, TestObject>> windowingApplied =
        timestampedOutput.apply(
            Window.<KV<String, TestObject>>into(FixedWindows.of(Duration.millis(5)))
                .triggering(DefaultTrigger.of())
                .discardingFiredPanes());

    PCollection<KV<String, Iterable<TestObject>>> groupped =
        windowingApplied.apply(GroupByKey.create());

    DoFn<KV<String, Iterable<TestObject>>, KV<String, TestObject>> grouppingFn =
        new DoFn<KV<String, Iterable<TestObject>>, KV<String, TestObject>>() {

          @ProcessElement
          @SuppressWarnings("unused")
          public void processElement(ProcessContext ctx) {
            KV<String, Iterable<TestObject>> element = ctx.element();

            String value = "";
            long lastTime = 0;
            for (TestObject to : element.getValue()) {
              lastTime = to.getTimestamp();
              value = value + "+" + to.getValue();
            }

            ctx.output(KV.of("the-same-key", new TestObject(lastTime, value)));
          }
        };

    PCollection<KV<String, TestObject>> firstGrouping =
        groupped.apply("first-condense", ParDo.of(grouppingFn));

    PAssert.that(firstGrouping)
        .containsInAnyOrder(
            KV.of("the-same-key", new TestObject(1, "+Achird+Aldebaran")),
            KV.of("the-same-key", new TestObject(8, "+Achird+Aldebaran")),
            KV.of("the-same-key", new TestObject(1, "+Caph")),
            KV.of("the-same-key", new TestObject(8, "+Caph")));

    PCollection<KV<String, Iterable<TestObject>>> pregrouppedSecondTime =
        firstGrouping.apply("second-condense", GroupByKey.create());

    PCollection<KV<String, TestObject>> secondGrouping =
        pregrouppedSecondTime.apply(ParDo.of(grouppingFn));

    PAssert.that(secondGrouping)
        .containsInAnyOrder(
            KV.of("the-same-key", new TestObject(1, "++Achird+Aldebaran++Caph")),
            KV.of("the-same-key", new TestObject(8, "++Achird+Aldebaran++Caph")));
    ;

    pipeline.run();
  }

  private static class TestObject {
    private long timestamp;
    private String value;

    public TestObject(long timestamp, String value) {
      this.timestamp = timestamp;
      this.value = value;
    }

    public long getTimestamp() {
      return timestamp;
    }

    public String getValue() {
      return value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      TestObject that = (TestObject) o;
      return timestamp == that.timestamp && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(timestamp, value);
    }

    @Override
    public String toString() {
      return timestamp + ":" + value;
    }
  }
}
