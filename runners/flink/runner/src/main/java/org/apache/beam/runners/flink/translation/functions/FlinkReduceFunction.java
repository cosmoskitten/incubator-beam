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
package org.apache.beam.runners.flink.translation.functions;

import java.util.Map;
import org.apache.beam.runners.flink.translation.functions.FlinkCombineRunner.FlinkReduceOutput;
import org.apache.beam.runners.flink.translation.functions.FlinkCombineRunner.ReduceFlinkCombiner;
import org.apache.beam.runners.flink.translation.utils.SerializedPipelineOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.CombineFnBase;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.flink.api.common.functions.RichGroupReduceFunction;
import org.apache.flink.util.Collector;

/**
 * This is the second part for executing a {@link org.apache.beam.sdk.transforms.Combine.PerKey}
 * on Flink, the second part is {@link FlinkReduceFunction}. This function performs the final
 * combination of the pre-combined values after a shuffle.
 *
 * <p>The input to {@link #reduce(Iterable, Collector)} are elements of the same key but
 * for different windows. We have to ensure that we only combine elements of matching
 * windows.
 */
public class FlinkReduceFunction<K, AccumT, OutputT, W extends BoundedWindow>
    extends RichGroupReduceFunction<WindowedValue<KV<K, AccumT>>, WindowedValue<KV<K, OutputT>>> {

  protected final CombineFnBase.PerKeyCombineFn<K, ?, AccumT, OutputT> combineFn;

  protected final WindowingStrategy<?, W> windowingStrategy;

  protected final Map<PCollectionView<?>, WindowingStrategy<?, ?>> sideInputs;

  protected final SerializedPipelineOptions serializedOptions;

  public FlinkReduceFunction(
      CombineFnBase.PerKeyCombineFn<K, ?, AccumT, OutputT> keyedCombineFn,
      WindowingStrategy<?, W> windowingStrategy,
      Map<PCollectionView<?>, WindowingStrategy<?, ?>> sideInputs,
      PipelineOptions pipelineOptions) {

    this.combineFn = keyedCombineFn;

    this.windowingStrategy = windowingStrategy;
    this.sideInputs = sideInputs;

    this.serializedOptions = new SerializedPipelineOptions(pipelineOptions);

  }

  @Override
  public void reduce(
      Iterable<WindowedValue<KV<K, AccumT>>> elements,
      Collector<WindowedValue<KV<K, OutputT>>> out) throws Exception {

    PipelineOptions options = serializedOptions.getPipelineOptions();

    FlinkSideInputReader sideInputReader =
        new FlinkSideInputReader(sideInputs, getRuntimeContext());

    FlinkCombineRunner<K, AccumT, AccumT, OutputT, W> reduceRunner =
        new FlinkCombineRunner<>(new ReduceFlinkCombiner<>(combineFn),
            (WindowingStrategy<Object, W>) windowingStrategy, sideInputReader,
            new FlinkReduceOutput<>(out), options);

    reduceRunner.combine(elements);

  }
}
