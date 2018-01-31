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

package org.apache.beam.runners.core.construction.graph;

import static com.google.common.collect.Iterables.getOnlyElement;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.model.pipeline.v1.RunnerApi.Components;
import org.apache.beam.model.pipeline.v1.RunnerApi.FunctionSpec;
import org.apache.beam.model.pipeline.v1.RunnerApi.PTransform;
import org.apache.beam.model.pipeline.v1.RunnerApi.ParDoPayload;
import org.apache.beam.model.pipeline.v1.RunnerApi.SideInput;
import org.apache.beam.runners.core.construction.Environments;
import org.apache.beam.runners.core.construction.PTransformTranslation;
import org.apache.beam.runners.core.construction.PipelineTranslation;
import org.apache.beam.runners.core.construction.graph.PipelineNode.PCollectionNode;
import org.apache.beam.runners.core.construction.graph.PipelineNode.PTransformNode;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.CountingSource;
import org.apache.beam.sdk.io.GenerateSequence;
import org.apache.beam.sdk.io.Read;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.joda.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link QueryablePipeline}. */
@RunWith(JUnit4.class)
public class QueryablePipelineTest {
  @Rule public ExpectedException thrown = ExpectedException.none();
  /**
   * Constructing a {@link QueryablePipeline} with components that reference absent {@link
   * RunnerApi.PCollection PCollections} should fail.
   */
  @Test
  public void fromComponentsWithMalformedComponents() {
    Components components =
        Components.newBuilder()
            .putTransforms(
                "read",
                PTransform.newBuilder()
                    .setSpec(
                        FunctionSpec.newBuilder()
                            .setUrn(PTransformTranslation.READ_TRANSFORM_URN)
                            .build())
                    .putOutputs("output", "read.out")
                    .build())
            .putPcollections(
                "read.out", RunnerApi.PCollection.newBuilder().setUniqueName("read.out").build())
            .putTransforms(
                "malformed",
                PTransform.newBuilder()
                    .setSpec(
                        FunctionSpec.newBuilder()
                            .setUrn(PTransformTranslation.PAR_DO_TRANSFORM_URN)
                            .build())
                    .putInputs("in", "read.out")
                    .putOutputs("out", "missing_pc")
                    .build())
            .build();

    thrown.expect(IllegalArgumentException.class);
    QueryablePipeline.fromComponents(components);
  }

  @Test
  public void rootTransforms() {
    Pipeline p = Pipeline.create();
    p.apply("UnboundedRead", Read.from(CountingSource.unbounded()))
        .apply(Window.into(FixedWindows.of(Duration.millis(5L))))
        .apply(Count.perElement());
    p.apply("BoundedRead", Read.from(CountingSource.upTo(100L)));

    Components components = PipelineTranslation.toProto(p).getComponents();
    QueryablePipeline qp = QueryablePipeline.fromComponents(components);

    assertThat(qp.getRootTransforms(), hasSize(2));
    for (PTransformNode rootTransform : qp.getRootTransforms()) {
      assertThat(
          "Root transforms should have no inputs",
          rootTransform.getTransform().getInputsCount(),
          equalTo(0));
      assertThat(
          "Only added source reads to the pipeline",
          rootTransform.getTransform().getSpec().getUrn(),
          equalTo(PTransformTranslation.READ_TRANSFORM_URN));
    }
  }

  /**
   * Tests that inputs that are only side inputs are not returned from {@link
   * QueryablePipeline#getPerElementConsumers(PCollectionNode)} and are returned from {@link
   * QueryablePipeline#getSideInputs(PTransformNode)}.
   */
  @Test
  public void transformWithSideAndMainInputs() {
    Pipeline p = Pipeline.create();
    PCollection<Long> longs = p.apply("BoundedRead", Read.from(CountingSource.upTo(100L)));
    PCollectionView<String> view =
        p.apply("Create", Create.of("foo")).apply("View", View.asSingleton());
    longs.apply(
        "par_do",
        ParDo.of(new TestFn())
            .withSideInputs(view)
            .withOutputTags(new TupleTag<>(), TupleTagList.empty()));

    Components components = PipelineTranslation.toProto(p).getComponents();
    QueryablePipeline qp = QueryablePipeline.fromComponents(components);

    String mainInputName =
        getOnlyElement(qp.transformNode("BoundedRead").getTransform().getOutputsMap().values());
    PCollectionNode mainInput = qp.pCollectionNode(mainInputName);
    String sideInputName =
        getOnlyElement(
            components
                .getTransformsOrThrow("par_do")
                .getInputsMap()
                .values()
                .stream()
                .filter(pcollectionName -> !pcollectionName.equals(mainInputName))
                .collect(Collectors.toSet()));
    PCollectionNode sideInput = qp.pCollectionNode(sideInputName);
    PTransformNode parDoNode = qp.transformNode("par_do");

    assertThat(qp.getSideInputs(parDoNode), contains(sideInput));
    assertThat(qp.getPerElementConsumers(mainInput), contains(parDoNode));
    assertThat(qp.getPerElementConsumers(sideInput), not(contains(parDoNode)));
  }

  /**
   * Tests that inputs that are both side inputs and main inputs are returned from {@link
   * QueryablePipeline#getPerElementConsumers(PCollectionNode)} and {@link
   * QueryablePipeline#getSideInputs(PTransformNode)}.
   */
  @Test
  public void transformWithSameSideAndMainInput() {
    Components components =
        Components.newBuilder()
            .putPcollections("read_pc", RunnerApi.PCollection.getDefaultInstance())
            .putPcollections("pardo_out", RunnerApi.PCollection.getDefaultInstance())
            .putTransforms("root", PTransform.newBuilder().putOutputs("out", "read_pc").build())
            .putTransforms(
                "multiConsumer",
                PTransform.newBuilder()
                    .putInputs("main_in", "read_pc")
                    .putInputs("side_in", "read_pc")
                    .putOutputs("out", "pardo_out")
                    .setSpec(
                        FunctionSpec.newBuilder()
                            .setUrn(PTransformTranslation.PAR_DO_TRANSFORM_URN)
                            .setPayload(
                                ParDoPayload.newBuilder()
                                    .putSideInputs("side_in", SideInput.getDefaultInstance())
                                    .build()
                                    .toByteString())
                            .build())
                    .build())
            .build();

    QueryablePipeline qp = QueryablePipeline.fromComponents(components);
    PCollectionNode multiInputPc = qp.pCollectionNode("read_pc");
    PTransformNode multiConsumerPT = qp.transformNode("multiConsumer");
    assertThat(qp.getPerElementConsumers(multiInputPc), contains(multiConsumerPT));
    assertThat(qp.getSideInputs(multiConsumerPT), contains(multiInputPc));
  }

  /**
   * Tests that {@link QueryablePipeline#getPerElementConsumers(PCollectionNode)} returns a
   * transform that consumes the node more than once.
   */
  @Test
  public void perElementConsumersWithConsumingMultipleTimes() {
    Pipeline p = Pipeline.create();
    PCollection<Long> longs = p.apply("BoundedRead", Read.from(CountingSource.upTo(100L)));
    PCollectionList.of(longs).and(longs).and(longs).apply("flatten", Flatten.pCollections());

    Components components = PipelineTranslation.toProto(p).getComponents();
    // This breaks if the way that IDs are assigned to PTransforms changes in PipelineTranslation
    String readOutput =
        getOnlyElement(components.getTransformsOrThrow("BoundedRead").getOutputsMap().values());
    QueryablePipeline qp = QueryablePipeline.fromComponents(components);
    Set<PTransformNode> consumers = qp.getPerElementConsumers(qp.pCollectionNode(readOutput));

    assertThat(consumers.size(), equalTo(1));
    assertThat(
        getOnlyElement(consumers).getTransform().getSpec().getUrn(),
        equalTo(PTransformTranslation.FLATTEN_TRANSFORM_URN));
  }

  @Test
  public void getProducer() {
    Pipeline p = Pipeline.create();
    PCollection<Long> longs = p.apply("BoundedRead", Read.from(CountingSource.upTo(100L)));
    PCollectionList.of(longs).and(longs).and(longs).apply("flatten", Flatten.pCollections());

    Components components = PipelineTranslation.toProto(p).getComponents();
    QueryablePipeline qp = QueryablePipeline.fromComponents(components);

    String longsOutputName =
        getOnlyElement(qp.transformNode("BoundedRead").getTransform().getOutputsMap().values());
    PTransformNode longsProducer = qp.transformNode("BoundedRead");
    PCollectionNode longsOutput = qp.pCollectionNode(longsOutputName);
    String flattenOutputName =
        getOnlyElement(qp.transformNode("flatten").getTransform().getOutputsMap().values());
    PTransformNode flattenProducer = qp.transformNode("flatten");
    PCollectionNode flattenOutput = qp.pCollectionNode(flattenOutputName);

    assertThat(qp.getProducer(longsOutput), equalTo(longsProducer));
    assertThat(qp.getProducer(flattenOutput), equalTo(flattenProducer));
  }

  @Test
  public void getEnvironmentWithEnvironment() {
    Pipeline p = Pipeline.create();
    PCollection<Long> longs = p.apply("BoundedRead", Read.from(CountingSource.upTo(100L)));
    PCollectionList.of(longs).and(longs).and(longs).apply("flatten", Flatten.pCollections());

    Components components = PipelineTranslation.toProto(p).getComponents();
    QueryablePipeline qp = QueryablePipeline.fromComponents(components);

    PTransformNode environmentalRead = qp.transformNode("BoundedRead");
    PTransformNode nonEnvironmentalTransform = qp.transformNode("flatten");

    assertThat(qp.getEnvironment(environmentalRead).isPresent(), is(true));
    assertThat(
        qp.getEnvironment(environmentalRead).get(),
        equalTo(Environments.JAVA_SDK_HARNESS_ENVIRONMENT));
    assertThat(qp.getEnvironment(nonEnvironmentalTransform).isPresent(), is(false));
  }

  private static class TestFn extends DoFn<Long, Long> {
    @ProcessElement
    public void process(ProcessContext ctxt) {}
  }

  @Test
  public void retainOnlyPrimitivesWithOnlyPrimitivesUnchanged() {
    Pipeline p = Pipeline.create();
    p.apply("Read", Read.from(CountingSource.unbounded()))
        .apply(
            "multi-do",
            ParDo.of(new TestFn()).withOutputTags(new TupleTag<>(), TupleTagList.empty()));

    Components originalComponents = PipelineTranslation.toProto(p).getComponents();
    Components primitiveComponents = QueryablePipeline.retainOnlyPrimitives(originalComponents);

    assertThat(primitiveComponents, equalTo(originalComponents));
  }

  @Test
  public void retainOnlyPrimitivesComposites() {
    Pipeline p = Pipeline.create();
    p.apply(
        new org.apache.beam.sdk.transforms.PTransform<PBegin, PCollection<Long>>() {
          @Override
          public PCollection<Long> expand(PBegin input) {
            return input
                .apply(GenerateSequence.from(2L))
                .apply(Window.into(FixedWindows.of(Duration.standardMinutes(5L))))
                .apply(MapElements.into(TypeDescriptors.longs()).via(l -> l + 1));
          }
        });

    Components originalComponents = PipelineTranslation.toProto(p).getComponents();
    Components primitiveComponents = QueryablePipeline.retainOnlyPrimitives(originalComponents);

    // Read, Window.Assign, ParDo. This will need to be updated if the expansions change.
    assertThat(primitiveComponents.getTransformsCount(), equalTo(3));
    for (Map.Entry<String, RunnerApi.PTransform> transformEntry :
        primitiveComponents.getTransformsMap().entrySet()) {
      assertThat(
          originalComponents.getTransformsMap(),
          hasEntry(transformEntry.getKey(), transformEntry.getValue()));
    }

    // Other components should be unchanged
    assertThat(
        primitiveComponents.getPcollectionsCount(),
        equalTo(originalComponents.getPcollectionsCount()));
    assertThat(
        primitiveComponents.getWindowingStrategiesCount(),
        equalTo(originalComponents.getWindowingStrategiesCount()));
    assertThat(primitiveComponents.getCodersCount(), equalTo(originalComponents.getCodersCount()));
    assertThat(
        primitiveComponents.getEnvironmentsCount(),
        equalTo(originalComponents.getEnvironmentsCount()));
  }

  /** This method doesn't do any pruning for reachability, but this may not require a test. */
  @Test
  public void retainOnlyPrimitivesIgnoresUnreachableNodes() {
    Pipeline p = Pipeline.create();
    p.apply(
        new org.apache.beam.sdk.transforms.PTransform<PBegin, PCollection<Long>>() {
          @Override
          public PCollection<Long> expand(PBegin input) {
            return input
                .apply(GenerateSequence.from(2L))
                .apply(Window.into(FixedWindows.of(Duration.standardMinutes(5L))))
                .apply(MapElements.into(TypeDescriptors.longs()).via(l -> l + 1));
          }
        });

    Components augmentedComponents =
        PipelineTranslation.toProto(p)
            .getComponents()
            .toBuilder()
            .putCoders("extra-coder", RunnerApi.Coder.getDefaultInstance())
            .putWindowingStrategies(
                "extra-windowing-strategy", RunnerApi.WindowingStrategy.getDefaultInstance())
            .putEnvironments("extra-env", RunnerApi.Environment.getDefaultInstance())
            .putPcollections("extra-pc", RunnerApi.PCollection.getDefaultInstance())
            .build();
    Components primitiveComponents = QueryablePipeline.retainOnlyPrimitives(augmentedComponents);

    // Other components should be unchanged
    assertThat(
        primitiveComponents.getPcollectionsCount(),
        equalTo(augmentedComponents.getPcollectionsCount()));
    assertThat(
        primitiveComponents.getWindowingStrategiesCount(),
        equalTo(augmentedComponents.getWindowingStrategiesCount()));
    assertThat(primitiveComponents.getCodersCount(), equalTo(augmentedComponents.getCodersCount()));
    assertThat(
        primitiveComponents.getEnvironmentsCount(),
        equalTo(augmentedComponents.getEnvironmentsCount()));
  }
}
