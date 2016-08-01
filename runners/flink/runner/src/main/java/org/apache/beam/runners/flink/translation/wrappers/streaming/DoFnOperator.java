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
package org.apache.beam.runners.flink.translation.wrappers.streaming;

import org.apache.beam.runners.flink.translation.utils.SerializedPipelineOptions;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.join.RawUnionValue;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.CoderUtils;
import org.apache.beam.sdk.util.DoFnRunner;
import org.apache.beam.sdk.util.DoFnRunners;
import org.apache.beam.sdk.util.ExecutionContext;
import org.apache.beam.sdk.util.NullSideInputReader;
import org.apache.beam.sdk.util.PushbackSideInputDoFnRunner;
import org.apache.beam.sdk.util.SideInputHandler;
import org.apache.beam.sdk.util.SideInputReader;
import org.apache.beam.sdk.util.TimerInternals;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.util.common.CounterSet;
import org.apache.beam.sdk.util.state.StateInternals;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;

import com.google.common.collect.Iterables;

import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.base.IntSerializer;
import org.apache.flink.api.common.typeutils.base.VoidSerializer;
import org.apache.flink.runtime.state.AbstractStateBackend;
import org.apache.flink.runtime.state.KvStateSnapshot;
import org.apache.flink.runtime.state.StateHandle;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.operators.TwoInputStreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.StreamTaskState;

import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flink operator for executing {@link DoFn DoFns}.
 *
 * @param <InputT>
 * @param <FnOutputT>
 * @param <OutputT>
 */
public class DoFnOperator<InputT, FnOutputT, OutputT>
    extends AbstractStreamOperator<OutputT>
    implements OneInputStreamOperator<WindowedValue<InputT>, OutputT>,
      TwoInputStreamOperator<WindowedValue<InputT>, RawUnionValue, OutputT> {

  protected final DoFn<InputT, FnOutputT> doFn;
  protected final SerializedPipelineOptions serializedOptions;

  protected final TupleTag<FnOutputT> mainOutputTag;
  protected final List<TupleTag<?>> sideOutputTags;

  protected final Collection<PCollectionView<?>> sideInputs;
  protected final Map<Integer, PCollectionView<?>> sideInputTagMapping;

  protected final boolean hasSideInputs;

  protected final WindowingStrategy<?, ?> windowingStrategy;

  protected final OutputManagerFactory<OutputT> outputManagerFactory;

  protected transient PushbackSideInputDoFnRunner<InputT, FnOutputT> pushbackDoFnRunner;

  protected transient SideInputHandler sideInputHandler;

  protected transient long currentInputWatermark;

  protected transient long currentOutputWatermark;

  private transient long pushedBackElementsWatermarkHold;

  private transient AbstractStateBackend sideInputStateBackend;

  private final ListStateDescriptor<WindowedValue<InputT>> pushedBackDescriptor;

  private transient Map<String, KvStateSnapshot<?, ?, ?, ?, ?>> restoredSideInputState;

  public DoFnOperator(
      DoFn<InputT, FnOutputT> doFn,
      TypeInformation<WindowedValue<InputT>> inputType,
      TupleTag<FnOutputT> mainOutputTag,
      List<TupleTag<?>> sideOutputTags,
      OutputManagerFactory<OutputT> outputManagerFactory,
      WindowingStrategy<?, ?> windowingStrategy,
      Map<Integer, PCollectionView<?>> sideInputTagMapping,
      Collection<PCollectionView<?>> sideInputs,
      PipelineOptions options) {
    this.doFn = doFn;
    this.mainOutputTag = mainOutputTag;
    this.sideOutputTags = sideOutputTags;
    this.sideInputTagMapping = sideInputTagMapping;
    this.sideInputs = sideInputs;
    this.serializedOptions = new SerializedPipelineOptions(options);
    this.windowingStrategy = windowingStrategy;
    this.outputManagerFactory = outputManagerFactory;

    this.hasSideInputs = !sideInputs.isEmpty();

    this.pushedBackDescriptor =
        new ListStateDescriptor<>("pushed-back-values", inputType);

    setChainingStrategy(ChainingStrategy.ALWAYS);
  }

  protected ExecutionContext.StepContext createStepContext() {
    return new StepContext();
  }

  // allow overriding this in WindowDoFnOperator because this one dynamically creates
  // the DoFn
  protected DoFn<InputT, FnOutputT> getDoFn() {
    return doFn;
  }

  @Override
  public void open() throws Exception {
    super.open();

    currentInputWatermark = Long.MIN_VALUE;
    currentOutputWatermark = currentInputWatermark;
    pushedBackElementsWatermarkHold = Long.MAX_VALUE;

    CounterSet counters = new CounterSet();

    SideInputReader sideInputReader = NullSideInputReader.of(sideInputs);
    if (!sideInputs.isEmpty()) {
      String operatorIdentifier =
          this.getClass().getSimpleName() + "_"
              + getRuntimeContext().getIndexOfThisSubtask() + "_sideInput";

      sideInputStateBackend = this
          .getContainingTask()
          .createStateBackend(operatorIdentifier, IntSerializer.INSTANCE);

      if (restoredSideInputState != null) {
        @SuppressWarnings("unchecked,rawtypes")
        HashMap<String, KvStateSnapshot> castRestored = (HashMap) restoredSideInputState;
        sideInputStateBackend.injectKeyValueStateSnapshots(castRestored, 0L);
        restoredSideInputState = null;
      }

      sideInputStateBackend.setCurrentKey(
          ByteBuffer.wrap(CoderUtils.encodeToByteArray(VarIntCoder.of(), 0)));

      StateInternals<Integer> sideInputStateInternals =
          new FlinkStateInternals<>(sideInputStateBackend, VarIntCoder.of());

      sideInputHandler = new SideInputHandler(sideInputs, sideInputStateInternals);
      sideInputReader = sideInputHandler;
    }

    DoFnRunner<InputT, FnOutputT> doFnRunner = DoFnRunners.createDefault(
        serializedOptions.getPipelineOptions(),
        getDoFn(),
        sideInputReader,
        outputManagerFactory.create(output),
        mainOutputTag,
        sideOutputTags,
        createStepContext(),
        counters.getAddCounterMutator(),
        windowingStrategy);

    pushbackDoFnRunner =
        PushbackSideInputDoFnRunner.create(doFnRunner, sideInputs, sideInputHandler);
  }

  @Override
  public void close() throws Exception {
    super.close();
  }

  protected final long getPushbackWatermarkHold() {
    return pushedBackElementsWatermarkHold;
  }

  @Override
  public final void processElement(
      StreamRecord<WindowedValue<InputT>> streamRecord) throws Exception {
    pushbackDoFnRunner.startBundle();
    pushbackDoFnRunner.processElement(streamRecord.getValue());
    pushbackDoFnRunner.finishBundle();
  }

  @Override
  public final void processElement1(
      StreamRecord<WindowedValue<InputT>> streamRecord) throws Exception {
    pushbackDoFnRunner.startBundle();
    Iterable<WindowedValue<InputT>> justPushedBack =
        pushbackDoFnRunner.processElementInReadyWindows(streamRecord.getValue());

    ListState<WindowedValue<InputT>> pushedBack =
        sideInputStateBackend.getPartitionedState(
            null,
            VoidSerializer.INSTANCE,
            pushedBackDescriptor);

    for (WindowedValue<InputT> pushedBackValue : justPushedBack) {
      pushedBackElementsWatermarkHold =
          Math.min(pushedBackValue.getTimestamp().getMillis(), pushedBackElementsWatermarkHold);
      pushedBack.add(pushedBackValue);
    }
    pushbackDoFnRunner.finishBundle();
  }

  @Override
  public final void processElement2(
      StreamRecord<RawUnionValue> streamRecord) throws Exception {
    pushbackDoFnRunner.startBundle();

    @SuppressWarnings("unchecked")
    WindowedValue<Iterable<?>> value =
        (WindowedValue<Iterable<?>>) streamRecord.getValue().getValue();

    PCollectionView<?> sideInput = sideInputTagMapping.get(streamRecord.getValue().getUnionTag());
    sideInputHandler.addSideInputValue(sideInput, value);

    ListState<WindowedValue<InputT>> pushedBack =
        sideInputStateBackend.getPartitionedState(
            null,
            VoidSerializer.INSTANCE,
            pushedBackDescriptor);

    List<WindowedValue<InputT>> newPushedBack = new ArrayList<>();
    for (WindowedValue<InputT> elem: pushedBack.get()) {

      // we need to set the correct key in case the operator is
      // a (keyed) window operator
      setKeyContextElement1(new StreamRecord<>(elem));

      Iterable<WindowedValue<InputT>> justPushedBack =
          pushbackDoFnRunner.processElementInReadyWindows(elem);
      Iterables.addAll(newPushedBack, justPushedBack);
    }

    pushedBack.clear();
    pushedBackElementsWatermarkHold = Long.MAX_VALUE;
    for (WindowedValue<InputT> pushedBackValue : newPushedBack) {
      pushedBackElementsWatermarkHold =
          Math.min(pushedBackValue.getTimestamp().getMillis(), pushedBackElementsWatermarkHold);
      pushedBack.add(pushedBackValue);
    }

    pushbackDoFnRunner.finishBundle();

    // maybe output a new watermark
    processWatermark1(new Watermark(currentInputWatermark));
  }

  @Override
  public void processWatermark(Watermark mark) throws Exception {
    processWatermark1(mark);
  }

  @Override
  public void processWatermark1(Watermark mark) throws Exception {
    this.currentInputWatermark = mark.getTimestamp();
    long potentialOutputWatermark =
        Math.min(getPushbackWatermarkHold(), currentInputWatermark);
    if (potentialOutputWatermark > currentOutputWatermark) {
      currentOutputWatermark = potentialOutputWatermark;
      output.emitWatermark(new Watermark(currentOutputWatermark));
    }
  }

  @Override
  public void processWatermark2(Watermark mark) throws Exception {
    // ignore watermarks from the side-input input
  }

  @Override
  public StreamTaskState snapshotOperatorState(
      long checkpointId,
      long timestamp) throws Exception {

    StreamTaskState streamTaskState = super.snapshotOperatorState(checkpointId, timestamp);

    // we have to manually checkpoint the side-input state backend and store
    // the handle in the "user state" of the task state
    HashMap<String, KvStateSnapshot<?, ?, ?, ?, ?>> sideInputSnapshot =
        sideInputStateBackend.snapshotPartitionedState(checkpointId, timestamp);

    @SuppressWarnings("unchecked,rawtypes")
    StateHandle<Serializable> sideInputStateHandle =
        (StateHandle) sideInputStateBackend.checkpointStateSerializable(
            sideInputSnapshot, checkpointId, timestamp);

    streamTaskState.setFunctionState(sideInputStateHandle);

    return streamTaskState;
  }

  @Override
  public void restoreState(StreamTaskState state, long recoveryTimestamp) throws Exception {
    super.restoreState(state, recoveryTimestamp);

    @SuppressWarnings("unchecked,rawtypes")
    StateHandle<HashMap<String, KvStateSnapshot<?, ?, ?, ?, ?>>> sideInputStateHandle =
        (StateHandle) state.getFunctionState();

    restoredSideInputState = sideInputStateHandle.getState(getUserCodeClassloader());
  }

  /**
   * Factory for creating an {@link DoFnRunners.OutputManager} from
   * a Flink {@link Output}.
   */
  interface OutputManagerFactory<OutputT> extends Serializable {
    DoFnRunners.OutputManager create(Output<StreamRecord<OutputT>> output);
  }

  /**
   * Default implementation of {@link OutputManagerFactory} that creates an
   * {@link DoFnRunners.OutputManager} that only writes to
   * a single logical output.
   */
  public static class DefaultOutputManagerFactory<OutputT>
      implements OutputManagerFactory<OutputT> {
    @Override
    public DoFnRunners.OutputManager create(final Output<StreamRecord<OutputT>> output) {
      return new DoFnRunners.OutputManager() {
        @Override
        public <T> void output(TupleTag<T> tag, WindowedValue<T> value) {
          // with side outputs we can't get around this because we don't
          // know our own output type...
          @SuppressWarnings("unchecked")
          OutputT castValue = (OutputT) value;
          output.collect(new StreamRecord<>(castValue));
        }
      };
    }
  }

  /**
   * Implementation of {@link OutputManagerFactory} that creates an
   * {@link DoFnRunners.OutputManager} that can write to multiple logical
   * outputs by unioning them in a {@link RawUnionValue}.
   */
  public static class MultiOutputOutputManagerFactory
      implements OutputManagerFactory<RawUnionValue> {

    Map<TupleTag<?>, Integer> mapping;

    public MultiOutputOutputManagerFactory(Map<TupleTag<?>, Integer> mapping) {
      this.mapping = mapping;
    }

    @Override
    public DoFnRunners.OutputManager create(final Output<StreamRecord<RawUnionValue>> output) {
      return new DoFnRunners.OutputManager() {
        @Override
        public <T> void output(TupleTag<T> tag, WindowedValue<T> value) {
          int intTag = mapping.get(tag);
          output.collect(new StreamRecord<>(new RawUnionValue(intTag, value)));
        }
      };
    }
  }

  /**
   * {@link StepContext} for running {@link DoFn DoFns} on Flink. This does not allow
   * accessing state or timer internals.
   */
  protected class StepContext implements ExecutionContext.StepContext {

    @Override
    public String getStepName() {
      return null;
    }

    @Override
    public String getTransformName() {
      return null;
    }

    @Override
    public void noteOutput(WindowedValue<?> output) {}

    @Override
    public void noteSideOutput(TupleTag<?> tag, WindowedValue<?> output) {}

    @Override
    public <T, W extends BoundedWindow> void writePCollectionViewData(
        TupleTag<?> tag,
        Iterable<WindowedValue<T>> data,
        Coder<Iterable<WindowedValue<T>>> dataCoder,
        W window,
        Coder<W> windowCoder) throws IOException {
      throw new UnsupportedOperationException("Writing side-input data is not supported.");
    }

    @Override
    public StateInternals<?> stateInternals() {
      throw new UnsupportedOperationException("Not supported for regular DoFns.");
    }

    @Override
    public TimerInternals timerInternals() {
      throw new UnsupportedOperationException("Not supported for regular DoFns.");
    }
  }

}
