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
package org.apache.beam.sdk.runners.inprocess;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import org.apache.beam.sdk.runners.inprocess.InProcessPipelineRunner.BundleSplit;
import org.apache.beam.sdk.runners.inprocess.InProcessPipelineRunner.CommittedBundle;
import org.apache.beam.sdk.runners.inprocess.InProcessPipelineRunner.UncommittedBundle;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;

import com.google.common.collect.ImmutableList;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Tests for {@link InProcessBundleFactory}.
 */
@RunWith(JUnit4.class)
public class InProcessBundleFactoryTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  private InProcessBundleFactory bundleFactory = InProcessBundleFactory.create();

  private PCollection<Integer> created;
  private PCollection<KV<String, Integer>> downstream;

  @Before
  public void setup() {
    TestPipeline p = TestPipeline.create();
    created = p.apply(Create.of(1, 2, 3));
    downstream = created.apply(WithKeys.<String, Integer>of("foo"));
  }

  @Test
  public void createRootBundleShouldCreateWithNullKey() {
    PCollection<Integer> pcollection = TestPipeline.create().apply(Create.of(1));

    UncommittedBundle<Integer> inFlightBundle = bundleFactory.createRootBundle(pcollection);

    CommittedBundle<Integer> bundle = inFlightBundle.commit(Instant.now());

    assertThat(bundle.isKeyed(), is(false));
    assertThat(bundle.getKey(), nullValue());
  }

  private void createKeyedBundle(Object key) {
    PCollection<Integer> pcollection = TestPipeline.create().apply(Create.of(1));

    UncommittedBundle<Integer> inFlightBundle =
        bundleFactory.createKeyedBundle(null, key, pcollection);

    CommittedBundle<Integer> bundle = inFlightBundle.commit(Instant.now());
    assertThat(bundle.isKeyed(), is(true));
    assertThat(bundle.getKey(), equalTo(key));
  }

  @Test
  public void keyedWithNullKeyShouldCreateKeyedBundle() {
    createKeyedBundle(null);
  }

  @Test
  public void keyedWithKeyShouldCreateKeyedBundle() {
    createKeyedBundle(new Object());
  }

  private <T> void afterCommitGetElementsShouldHaveAddedElements(Iterable<WindowedValue<T>> elems) {
    PCollection<T> pcollection = TestPipeline.create().apply(Create.<T>of());

    UncommittedBundle<T> bundle = bundleFactory.createRootBundle(pcollection);
    Collection<Matcher<? super WindowedValue<T>>> expectations = new ArrayList<>();
    for (WindowedValue<T> elem : elems) {
      bundle.add(elem);
      expectations.add(equalTo(elem));
    }
    Matcher<Iterable<? extends WindowedValue<T>>> containsMatcher =
        Matchers.<WindowedValue<T>>containsInAnyOrder(expectations);
    assertThat(bundle.commit(Instant.now()).getElements(), containsMatcher);
  }

  @Test
  public void getElementsBeforeAddShouldReturnEmptyIterable() {
    afterCommitGetElementsShouldHaveAddedElements(Collections.<WindowedValue<Integer>>emptyList());
  }

  @Test
  public void getElementsAfterAddShouldReturnAddedElements() {
    WindowedValue<Integer> firstValue = WindowedValue.valueInGlobalWindow(1);
    WindowedValue<Integer> secondValue =
        WindowedValue.timestampedValueInGlobalWindow(2, new Instant(1000L));

    afterCommitGetElementsShouldHaveAddedElements(ImmutableList.of(firstValue, secondValue));
  }

  @Test
  public void addAfterCommitShouldThrowException() {
    PCollection<Integer> pcollection = TestPipeline.create().apply(Create.<Integer>of());

    UncommittedBundle<Integer> bundle = bundleFactory.createRootBundle(pcollection);
    bundle.add(WindowedValue.valueInGlobalWindow(1));
    CommittedBundle<Integer> firstCommit = bundle.commit(Instant.now());
    assertThat(firstCommit.getElements(), containsInAnyOrder(WindowedValue.valueInGlobalWindow(1)));

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("3");
    thrown.expectMessage("committed");

    bundle.add(WindowedValue.valueInGlobalWindow(3));
  }

  @Test
  public void commitAfterCommitShouldThrowException() {
    PCollection<Integer> pcollection = TestPipeline.create().apply(Create.<Integer>of());

    UncommittedBundle<Integer> bundle = bundleFactory.createRootBundle(pcollection);
    bundle.add(WindowedValue.valueInGlobalWindow(1));
    CommittedBundle<Integer> firstCommit = bundle.commit(Instant.now());
    assertThat(firstCommit.getElements(), containsInAnyOrder(WindowedValue.valueInGlobalWindow(1)));

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("committed");

    bundle.commit(Instant.now());
  }

  @Test
  public void createBundleUnkeyedResultUnkeyed() {
    CommittedBundle<KV<String, Integer>> newBundle =
        bundleFactory
            .createBundle(bundleFactory.createRootBundle(created).commit(Instant.now()), downstream)
            .commit(Instant.now());
    assertThat(newBundle.isKeyed(), is(false));
  }

  @Test
  public void createBundleKeyedResultPropagatesKey() {
    CommittedBundle<KV<String, Integer>> newBundle =
        bundleFactory
            .createBundle(
                bundleFactory.createKeyedBundle(null, "foo", created).commit(Instant.now()),
                downstream)
            .commit(Instant.now());
    assertThat(newBundle.isKeyed(), is(true));
    assertThat(newBundle.getKey(), Matchers.<Object>equalTo("foo"));
  }

  @Test
  public void createRootBundleUnkeyed() {
    assertThat(bundleFactory.createRootBundle(created).commit(Instant.now()).isKeyed(), is(false));
  }

  @Test
  public void createKeyedBundleKeyed() {
    CommittedBundle<KV<String, Integer>> keyedBundle =
        bundleFactory
            .createKeyedBundle(
                bundleFactory.createRootBundle(created).commit(Instant.now()), "foo", downstream)
            .commit(Instant.now());
    assertThat(keyedBundle.isKeyed(), is(true));
    assertThat(keyedBundle.getKey(), Matchers.<Object>equalTo("foo"));
  }

  @Test
  public void splitOffElementsNoElementsSplit() {
    runBundleSplitAssert(
        ImmutableList.of(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 1, -1, 2),
        ImmutableList.<Integer>of());
  }

  @Test
  public void splitOffElementsAllElementsSplit() {
    runBundleSplitAssert(ImmutableList.<Integer>of(), ImmutableList.of(1, 2, 3, 4, 5));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void splitOffElementsPartialSplit() {
    runBundleSplitAssert(ImmutableList.of(1, 2, 4, 8), ImmutableList.of(0, 3, 5, 6, 7));
  }

  public void runBundleSplitAssert(List<Integer> primaryElems, List<Integer> residualElems) {
    Instant processingTime = Instant.now();
    UncommittedBundle<Integer> builder =
        bundleFactory
            .createKeyedBundle(null, "foo", created);
    List<WindowedValue<Integer>> toRetain = new ArrayList<>();
    for (int i : primaryElems) {
      WindowedValue<Integer> retainElem = WindowedValue.valueInGlobalWindow(i);
      builder.add(retainElem);
      toRetain.add(retainElem);
    }
    List<WindowedValue<Integer>> toSplit = new ArrayList<>();
    for (int i : residualElems) {
      WindowedValue<Integer> splitElem = WindowedValue.valueInGlobalWindow(i);
      builder.add(splitElem);
      toSplit.add(splitElem);
    }
    CommittedBundle<Integer> bundle = builder.commit(processingTime);

    BundleSplit<Integer> split = bundle.splitOffElements(toSplit);

    assertThat(split.getPrimary().getElements(), containsInAnyOrder(toRetain.toArray()));
    assertThat(split.getResidual().getElements(), containsInAnyOrder(toSplit.toArray()));

    assertThat(split.getPrimary().getKey(), Matchers.<Object>equalTo("foo"));
    assertThat(split.getResidual().getKey(), Matchers.<Object>equalTo("foo"));

    assertThat(split.getPrimary().getPCollection(), equalTo(created));
    assertThat(split.getResidual().getPCollection(), equalTo(created));

    assertThat(
        split.getPrimary().getSynchronizedProcessingOutputWatermark(), equalTo(processingTime));
    assertThat(
        split.getResidual().getSynchronizedProcessingOutputWatermark(), equalTo(processingTime));
  }

  @Test
  public void splitOffElementsToSplitNotInOriginal() {
    WindowedValue<Integer> missingElement = WindowedValue.valueInGlobalWindow(Integer.MIN_VALUE);
    CommittedBundle<Integer> bundle =
        bundleFactory
            .createRootBundle(created)
            .add(WindowedValue.valueInGlobalWindow(2))
            .commit(Instant.now());

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage(missingElement.toString());
    thrown.expectMessage("not contained in this bundle");
    bundle.splitOffElements(ImmutableList.of(missingElement));
  }
}
