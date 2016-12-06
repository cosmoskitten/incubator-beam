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
package org.apache.beam.sdk.values;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.CountingInput;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.RunnableOnService;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.values.PCollection.IsBounded;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link PCollectionTuple}. */
@RunWith(JUnit4.class)
public final class PCollectionTupleTest implements Serializable {
  @Test
  public void testOfThenHas() {
    Pipeline pipeline = TestPipeline.create();
    PCollection<Object> pCollection = PCollection.createPrimitiveOutputInternal(
        pipeline, WindowingStrategy.globalDefault(), IsBounded.BOUNDED);
    TupleTag<Object> tag = new TupleTag<>();

    assertTrue(PCollectionTuple.of(tag, pCollection).has(tag));
  }

  @Test
  public void testEmpty() {
    Pipeline pipeline = TestPipeline.create();
    TupleTag<Object> tag = new TupleTag<>();
    assertFalse(PCollectionTuple.empty(pipeline).has(tag));
  }

  @Test
  @Category(RunnableOnService.class)
  public void testComposePCollectionTuple() {
    Pipeline pipeline = TestPipeline.create();

    List<Integer> inputs = Arrays.asList(3, -42, 666);

    TupleTag<Integer> mainOutputTag = new TupleTag<Integer>("main") {};
    TupleTag<Integer> emptyOutputTag = new TupleTag<Integer>("empty") {};
    final TupleTag<Integer> sideOutputTag = new TupleTag<Integer>("side") {};

    PCollection<Integer> mainInput = pipeline
        .apply(Create.of(inputs));

    PCollectionTuple outputs = mainInput.apply(ParDo
        .of(new DoFn<Integer, Integer>() {
          @ProcessElement
          public void processElement(ProcessContext c) {
            c.sideOutput(sideOutputTag, c.element());
          }})
        .withOutputTags(emptyOutputTag, TupleTagList.of(sideOutputTag)));
    assertNotNull("outputs.getPipeline()", outputs.getPipeline());
    outputs = outputs.and(mainOutputTag, mainInput);

    PAssert.that(outputs.get(mainOutputTag)).containsInAnyOrder(inputs);
    PAssert.that(outputs.get(sideOutputTag)).containsInAnyOrder(inputs);
    PAssert.that(outputs.get(emptyOutputTag)).empty();

    pipeline.run();
  }

  @Test
  public void testExpandHasMatchingTags() {
    TupleTag<Integer> intTag = new TupleTag<>();
    TupleTag<String> strTag = new TupleTag<>();
    TupleTag<Long> longTag = new TupleTag<>();

    Pipeline p = TestPipeline.create();
    PCollection<Long> longs = p.apply(CountingInput.upTo(100L));
    PCollection<String> strs = p.apply(Create.of("foo", "bar", "baz"));
    PCollection<Integer> ints = longs.apply(MapElements.via(new SimpleFunction<Long, Integer>() {
      @Override
      public Integer apply(Long input) {
        return input.intValue();
      }
    }));

    Map<TupleTag<?>, PCollection<?>> pcsByTag =
        ImmutableMap.<TupleTag<?>, PCollection<?>>builder()
            .put(strTag, strs)
            .put(intTag, ints)
            .put(longTag, longs)
            .build();
    PCollectionTuple tuple =
        PCollectionTuple.of(intTag, ints).and(longTag, longs).and(strTag, strs);
    assertThat(tuple.getAll(), equalTo(pcsByTag));
    for (TaggedPValue taggedValue : tuple.expand()) {
      TupleTag<?> tag = taggedValue.getTag();
      PValue value = taggedValue.getValue();
      assertThat("The tag should map back to the value", tuple.get(tag), equalTo(value));
      assertThat(value, Matchers.<PValue>equalTo(pcsByTag.get(tag)));
    }
  }
}
