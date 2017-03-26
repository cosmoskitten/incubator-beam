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
import org.apache.beam.runners.flink.translation.functions.FlinkCombineRunner.NoShuffleFlinkCombiner;
import org.apache.beam.runners.flink.translation.utils.SerializedPipelineOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.CombineFnBase;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.flink.api.common.functions.RichGroupReduceFunction;
import org.apache.flink.util.Collector;

/**
 * Special version of {@link FlinkReduceFunction} that supports merging windows.
 *
 * <p>This is different from the pair of function for the non-merging windows case
 * in that we cannot do combining before the shuffle because elements would not
 * yet be in their correct windows for side-input access.
 */
public class FlinkMergingNonShuffleReduceFunction<
    K, InputT, AccumT, OutputT, W extends IntervalWindow>
    extends RichGroupReduceFunction<WindowedValue<KV<K, InputT>>, WindowedValue<KV<K, OutputT>>> {

  private final CombineFnBase.PerKeyCombineFn<K, InputT, AccumT, OutputT> combineFn;

  private final WindowingStrategy<?, W> windowingStrategy;

  private final Map<PCollectionView<?>, WindowingStrategy<?, ?>> sideInputs;

  private final SerializedPipelineOptions serializedOptions;

  public FlinkMergingNonShuffleReduceFunction(
      CombineFnBase.PerKeyCombineFn<K, InputT, AccumT, OutputT> keyedCombineFn,
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
      Iterable<WindowedValue<KV<K, InputT>>> elements,
      Collector<WindowedValue<KV<K, OutputT>>> out) throws Exception {

    PipelineOptions options = serializedOptions.getPipelineOptions();

    FlinkSideInputReader sideInputReader =
        new FlinkSideInputReader(sideInputs, getRuntimeContext());

    FlinkCombineRunner<K, InputT, AccumT, OutputT, W> reduceRunner =
        new FlinkCombineRunner<>(new NoShuffleFlinkCombiner<>(combineFn),
            (WindowingStrategy<Object, W>) windowingStrategy, sideInputReader,
            new FlinkReduceOutput<>(out), options);

    reduceRunner.combine(elements);

  }

}
