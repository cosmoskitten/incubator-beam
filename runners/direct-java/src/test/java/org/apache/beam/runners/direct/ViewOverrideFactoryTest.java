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

package org.apache.beam.runners.direct;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableSet;
import java.io.Serializable;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.beam.runners.direct.ViewOverrideFactory.WriteView;
import org.apache.beam.sdk.Pipeline.PipelineVisitor;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.runners.TransformHierarchy.Node;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View.CreatePCollectionView;
import org.apache.beam.sdk.util.PCollectionViews;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.omg.CORBA.WStringSeqHelper;

/** Tests for {@link ViewOverrideFactory}. */
@RunWith(JUnit4.class)
public class ViewOverrideFactoryTest implements Serializable {
  @Rule
  public transient TestPipeline p = TestPipeline.create().enableAbandonedNodeEnforcement(false);

  private transient ViewOverrideFactory<Integer, List<Integer>> factory =
      new ViewOverrideFactory<>();

  @Test
  public void replacementSucceeds() {
    PCollection<Integer> ints = p.apply("CreateContents", Create.of(1, 2, 3));
    final PCollectionView<List<Integer>> view =
        PCollectionViews.listView(p, WindowingStrategy.globalDefault(), ints.getCoder());
    PCollectionView<List<Integer>> afterReplacement =
        ints.apply(
            factory.getReplacementTransform(
                CreatePCollectionView.<Integer, List<Integer>>of(view)));

    PCollection<Set<Integer>> outputViewContents =
        p.apply("CreateSingleton", Create.of(0))
            .apply(
                "OutputContents",
                ParDo.of(
                        new DoFn<Integer, Set<Integer>>() {
                          @ProcessElement
                          public void outputSideInput(ProcessContext context) {
                            context.output(ImmutableSet.copyOf(context.sideInput(view)));
                          }
                        })
                    .withSideInputs(view));
    PAssert.thatSingleton(outputViewContents).isEqualTo(ImmutableSet.of(1, 2, 3));

    p.run();
  }

  @Test
  public void replacementGetViewReturnsOriginal() {
    PCollection<Integer> ints = p.apply("CreateContents", Create.of(1, 2, 3));
    final PCollectionView<List<Integer>> view =
        PCollectionViews.listView(p, WindowingStrategy.globalDefault(), ints.getCoder());
    PTransform<PCollection<Integer>, PCollectionView<List<Integer>>> replacement =
        factory.getReplacementTransform(CreatePCollectionView.<Integer, List<Integer>>of(view));
    ints.apply(replacement);
    final AtomicBoolean writeViewVisited = new AtomicBoolean();
    p.traverseTopologically(
        new PipelineVisitor.Defaults() {
          @Override
          public void visitPrimitiveTransform(Node node) {
            assertThat(
                "WriteView should be the last primtitive in the graph",
                writeViewVisited.get(),
                is(false));
            if (node.getTransform() instanceof WriteView) {
              writeViewVisited.set(true);
              PCollectionView replacementView = ((WriteView) node.getTransform()).getView();
              assertThat(replacementView, Matchers.<PCollectionView>theInstance(view));
            }
          }
        });

    assertThat(writeViewVisited.get(), is(true));
  }

  @Test
  public void overrideFactoryGetInputSucceeds() {
    ViewOverrideFactory<String, String> factory = new ViewOverrideFactory<>();
    PCollection<String> input = p.apply(Create.of("foo", "bar"));
    assertThat(factory.getInput(input.expand(), p), equalTo(input));
  }
}
