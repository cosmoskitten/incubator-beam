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
package org.apache.beam.sdk.extensions.euphoria.core.client.operator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.apache.beam.sdk.extensions.euphoria.core.client.dataset.Dataset;
import org.apache.beam.sdk.transforms.windowing.DefaultTrigger;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.WindowDesc;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.apache.beam.sdk.values.WindowingStrategy.AccumulationMode;
import org.junit.Test;

/** Test behavior of operator {@code SumByKey}. */
public class SumByKeyTest {

  @Test
  public void testBuild() {
    final Dataset<String> dataset = OperatorTests.createMockDataset(TypeDescriptors.strings());
    final Dataset<KV<String, Long>> counted =
        SumByKey.named("SumByKey1").of(dataset).keyBy(s -> s).output();
    assertTrue(counted.getProducer().isPresent());
    final SumByKey sum = (SumByKey) counted.getProducer().get();
    assertTrue(sum.getName().isPresent());
    assertEquals("SumByKey1", sum.getName().get());
    assertNotNull(sum.getKeyExtractor());
    assertFalse(sum.getWindow().isPresent());
  }

  @Test
  public void testBuild_ImplicitName() {
    final Dataset<String> dataset = OperatorTests.createMockDataset(TypeDescriptors.strings());
    final Dataset<KV<String, Long>> counted = SumByKey.of(dataset).keyBy(s -> s).output();
    assertTrue(counted.getProducer().isPresent());
    final SumByKey sum = (SumByKey) counted.getProducer().get();
    assertFalse(sum.getName().isPresent());
  }

  @Test
  public void testBuild_Windowing() {
    final Dataset<String> dataset = OperatorTests.createMockDataset(TypeDescriptors.strings());
    final Dataset<KV<String, Long>> counted =
        SumByKey.of(dataset)
            .keyBy(s -> s)
            .valueBy(s -> 1L)
            .windowBy(FixedWindows.of(org.joda.time.Duration.standardHours(1)))
            .triggeredBy(DefaultTrigger.of())
            .accumulationMode(AccumulationMode.DISCARDING_FIRED_PANES)
            .output();
    assertTrue(counted.getProducer().isPresent());
    final SumByKey sum = (SumByKey) counted.getProducer().get();
    assertTrue(sum.getWindow().isPresent());
    final Window<?> window = (Window) sum.getWindow().get();
    assertEquals(FixedWindows.of(org.joda.time.Duration.standardHours(1)), window.getWindowFn());
    assertEquals(DefaultTrigger.of(), WindowDesc.of(window).getTrigger());
    assertEquals(
        AccumulationMode.DISCARDING_FIRED_PANES, WindowDesc.of(window).getAccumulationMode());
  }

  @Test
  public void testWindow_applyIf() {
    final Dataset<String> dataset = OperatorTests.createMockDataset(TypeDescriptors.strings());
    final Dataset<KV<String, Long>> counted =
        SumByKey.of(dataset)
            .keyBy(s -> s)
            .valueBy(s -> 1L)
            .applyIf(
                true,
                b ->
                    b.windowBy(FixedWindows.of(org.joda.time.Duration.standardHours(1)))
                        .triggeredBy(DefaultTrigger.of())
                        .accumulationMode(AccumulationMode.DISCARDING_FIRED_PANES))
            .output();
    assertTrue(counted.getProducer().isPresent());
    final SumByKey sum = (SumByKey) counted.getProducer().get();
    assertTrue(sum.getWindow().isPresent());
    final Window<?> window = (Window) sum.getWindow().get();
    assertEquals(FixedWindows.of(org.joda.time.Duration.standardHours(1)), window.getWindowFn());
    assertEquals(DefaultTrigger.of(), WindowDesc.of(window).getTrigger());
    assertEquals(
        AccumulationMode.DISCARDING_FIRED_PANES, WindowDesc.of(window).getAccumulationMode());
  }
}
