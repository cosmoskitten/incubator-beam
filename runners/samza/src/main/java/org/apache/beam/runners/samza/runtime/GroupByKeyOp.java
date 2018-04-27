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

package org.apache.beam.runners.samza.runtime;

import java.util.Collections;
import org.apache.beam.runners.core.DoFnRunner;
import org.apache.beam.runners.core.DoFnRunners;
import org.apache.beam.runners.core.GroupAlsoByWindowViaWindowSetNewDoFn;
import org.apache.beam.runners.core.KeyedWorkItem;
import org.apache.beam.runners.core.KeyedWorkItemCoder;
import org.apache.beam.runners.core.KeyedWorkItems;
import org.apache.beam.runners.core.NullSideInputReader;
import org.apache.beam.runners.core.StateInternalsFactory;
import org.apache.beam.runners.core.SystemReduceFn;
import org.apache.beam.runners.core.TimerInternals.TimerData;
import org.apache.beam.runners.samza.SamzaExecutionContext;
import org.apache.beam.runners.samza.metrics.DoFnRunnerWithMetrics;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.VoidCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.samza.config.Config;
import org.apache.samza.operators.TimerRegistry;
import org.apache.samza.storage.kv.KeyValueStore;
import org.apache.samza.task.TaskContext;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samza operator for {@link org.apache.beam.sdk.transforms.GroupByKey}.
 */
public class GroupByKeyOp<K, InputT, OutputT>
    implements Op<KeyedWorkItem<K, InputT>, KV<K, OutputT>, K> {
  private static final Logger LOG = LoggerFactory.getLogger(GroupByKeyOp.class);

  private final TupleTag<KV<K, OutputT>> mainOutputTag;
  private final KeyedWorkItemCoder<K, InputT> inputCoder;
  private final WindowingStrategy<?, BoundedWindow> windowingStrategy;
  private final OutputManagerFactory<KV<K, OutputT>> outputManagerFactory;
  private final Coder<K> keyCoder;
  private final SystemReduceFn<K, InputT, ?, OutputT, BoundedWindow> reduceFn;
  private final String stepName;

  private transient StateInternalsFactory<Void> stateInternalsFactory;
  private transient SamzaTimerInternalsFactory<K> timerInternalsFactory;
  private transient DoFnRunner<KeyedWorkItem<K, InputT>, KV<K, OutputT>> fnRunner;

  public GroupByKeyOp(TupleTag<KV<K, OutputT>> mainOutputTag,
                      Coder<KeyedWorkItem<K, InputT>> inputCoder,
                      SystemReduceFn<K, InputT, ?, OutputT, BoundedWindow> reduceFn,
                      WindowingStrategy<?, BoundedWindow> windowingStrategy,
                      OutputManagerFactory<KV<K, OutputT>> outputManagerFactory,
                      String stepName) {
    this.mainOutputTag = mainOutputTag;
    this.windowingStrategy = windowingStrategy;
    this.outputManagerFactory = outputManagerFactory;
    this.stepName = stepName;

    if (!(inputCoder instanceof KeyedWorkItemCoder)) {
      throw new IllegalArgumentException(
          String.format(
              "GroupByKeyOp requires input to use KeyedWorkItemCoder. Got: %s",
              inputCoder.getClass()));
    }
    this.inputCoder = (KeyedWorkItemCoder<K, InputT>) inputCoder;
    this.keyCoder = this.inputCoder.getKeyCoder();
    this.reduceFn = reduceFn;
  }

  @Override
  public void open(Config config,
                   TaskContext context,
                   TimerRegistry<KeyedTimerData<K>> timerRegistry,
                   OpEmitter<KV<K, OutputT>> emitter) {
    final DoFnRunners.OutputManager outputManager = outputManagerFactory.create(emitter);

    @SuppressWarnings("unchecked")
    final KeyValueStore<byte[], byte[]> store =
        (KeyValueStore<byte[], byte[]>) context.getStore("beamStore");

    this.stateInternalsFactory =
        new SamzaStoreStateInternals.Factory<>(
            mainOutputTag.getId(),
            store,
            VoidCoder.of());

    this.timerInternalsFactory = new SamzaTimerInternalsFactory<>(
        inputCoder.getKeyCoder(), timerRegistry);

    final DoFn<KeyedWorkItem<K, InputT>, KV<K, OutputT>> doFn =
        GroupAlsoByWindowViaWindowSetNewDoFn.create(
            windowingStrategy,
            new SamzaStoreStateInternals.Factory<>(
                mainOutputTag.getId(),
                store,
                keyCoder),
            timerInternalsFactory,
            NullSideInputReader.of(Collections.emptyList()),
            reduceFn,
            outputManager,
            mainOutputTag);

    final DoFnRunner<KeyedWorkItem<K, InputT>, KV<K, OutputT>> doFnRunner =
        DoFnRunners.simpleRunner(
            PipelineOptionsFactory.create(),
            doFn,
            NullSideInputReader.of(Collections.emptyList()),
            outputManager,
            mainOutputTag,
            Collections.emptyList(),
            DoFnOp.createStepContext(stateInternalsFactory, timerInternalsFactory),
            windowingStrategy);

    final SamzaExecutionContext executionContext =
        (SamzaExecutionContext) context.getUserContext();
    this.fnRunner = DoFnRunnerWithMetrics.wrap(
        doFnRunner, executionContext.getMetricsContainer(), stepName);
  }

  @Override
  public void processElement(WindowedValue<KeyedWorkItem<K, InputT>> inputElement,
                             OpEmitter<KV<K, OutputT>> emitter) {
    fnRunner.startBundle();
    fnRunner.processElement(inputElement);
    fnRunner.finishBundle();
  }

  @Override
  public void processWatermark(Instant watermark, OpEmitter<KV<K, OutputT>> ctx) {
    timerInternalsFactory.setInputWatermark(watermark);

    fnRunner.startBundle();
    for (KeyedTimerData<K> keyedTimerData : timerInternalsFactory.removeReadyTimers()) {
      fireTimer(keyedTimerData.getKey(), keyedTimerData.getTimerData());
    }
    fnRunner.finishBundle();

    if (timerInternalsFactory.getOutputWatermark() == null
        || timerInternalsFactory.getOutputWatermark().isBefore(watermark)) {
      timerInternalsFactory.setOutputWatermark(watermark);
      ctx.emitWatermark(timerInternalsFactory.getOutputWatermark());
    }
  }

  @Override
  public void processTimer(KeyedTimerData<K> keyedTimerData) {
    fnRunner.startBundle();
    fireTimer(keyedTimerData.getKey(), keyedTimerData.getTimerData());
    fnRunner.finishBundle();
  }

  private void fireTimer(K key, TimerData timer) {
    LOG.debug("Firing timer {} for key {}", timer, key);
    fnRunner.processElement(
        WindowedValue.valueInGlobalWindow(
            KeyedWorkItems.timersWorkItem(key, Collections.singletonList(timer))));
  }
}
