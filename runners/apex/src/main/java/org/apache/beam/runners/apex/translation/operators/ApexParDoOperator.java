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
package org.apache.beam.runners.apex.translation.operators;

import com.datatorrent.api.Context.OperatorContext;
import com.datatorrent.api.DefaultInputPort;
import com.datatorrent.api.DefaultOutputPort;
import com.datatorrent.api.annotation.InputPortFieldAnnotation;
import com.datatorrent.api.annotation.OutputPortFieldAnnotation;
import com.datatorrent.common.util.BaseOperator;
import com.esotericsoftware.kryo.serializers.FieldSerializer.Bind;
import com.esotericsoftware.kryo.serializers.JavaSerializer;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.apache.beam.runners.apex.ApexPipelineOptions;
import org.apache.beam.runners.apex.ApexRunner;
import org.apache.beam.runners.apex.translation.utils.ApexStateInternals.ApexStateBackend;
import org.apache.beam.runners.apex.translation.utils.ApexStateInternals.StateInternalsProxy;
import org.apache.beam.runners.apex.translation.utils.ApexStreamTuple;
import org.apache.beam.runners.apex.translation.utils.NoOpStepContext;
import org.apache.beam.runners.apex.translation.utils.SerializablePipelineOptions;
import org.apache.beam.runners.apex.translation.utils.ValueAndCoderKryoSerializable;
import org.apache.beam.runners.core.AggregatorFactory;
import org.apache.beam.runners.core.DoFnRunner;
import org.apache.beam.runners.core.DoFnRunners;
import org.apache.beam.runners.core.DoFnRunners.OutputManager;
import org.apache.beam.runners.core.ExecutionContext;
import org.apache.beam.runners.core.InMemoryTimerInternals;
import org.apache.beam.runners.core.KeyedWorkItem;
import org.apache.beam.runners.core.OutputAndTimeBoundedSplittableProcessElementInvoker;
import org.apache.beam.runners.core.OutputWindowedValue;
import org.apache.beam.runners.core.PushbackSideInputDoFnRunner;
import org.apache.beam.runners.core.SideInputHandler;
import org.apache.beam.runners.core.SplittableParDo;
import org.apache.beam.runners.core.StateInternals;
import org.apache.beam.runners.core.StateInternalsFactory;
import org.apache.beam.runners.core.StateNamespace;
import org.apache.beam.runners.core.StatefulDoFnRunner;
import org.apache.beam.runners.core.TimerInternals;
import org.apache.beam.runners.core.TimerInternalsFactory;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.ListCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VoidCoder;
import org.apache.beam.sdk.transforms.Aggregator;
import org.apache.beam.sdk.transforms.Combine.CombineFn;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.reflect.DoFnInvoker;
import org.apache.beam.sdk.transforms.reflect.DoFnInvokers;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.util.NullSideInputReader;
import org.apache.beam.sdk.util.SideInputReader;
import org.apache.beam.sdk.util.TimeDomain;
import org.apache.beam.sdk.util.UserCodeException;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Apex operator for Beam {@link DoFn}.
 */
public class ApexParDoOperator<InputT, OutputT> extends BaseOperator implements OutputManager {
  private static final Logger LOG = LoggerFactory.getLogger(ApexParDoOperator.class);
  private boolean traceTuples = true;

  @Bind(JavaSerializer.class)
  private final SerializablePipelineOptions pipelineOptions;
  @Bind(JavaSerializer.class)
  private final DoFn<InputT, OutputT> doFn;
  @Bind(JavaSerializer.class)
  private final TupleTag<OutputT> mainOutputTag;
  @Bind(JavaSerializer.class)
  private final List<TupleTag<?>> sideOutputTags;
  @Bind(JavaSerializer.class)
  private final WindowingStrategy<?, ?> windowingStrategy;
  @Bind(JavaSerializer.class)
  private final List<PCollectionView<?>> sideInputs;

  private StateInternalsProxy<?> currentKeyStateInternals;
  // TODO: fault tolerant timer internals
  private final transient CurrentKeyTimerInternals<Object> currentKeyTimerInternals =
      new CurrentKeyTimerInternals<>();

  private final StateInternals<Void> sideInputStateInternals;
  private final ValueAndCoderKryoSerializable<List<WindowedValue<InputT>>> pushedBack;
  private LongMin pushedBackWatermark = new LongMin();
  private long currentInputWatermark = Long.MIN_VALUE;
  private long currentOutputWatermark = currentInputWatermark;

  private transient PushbackSideInputDoFnRunner<InputT, OutputT> pushbackDoFnRunner;
  private transient SideInputHandler sideInputHandler;
  private transient Map<TupleTag<?>, DefaultOutputPort<ApexStreamTuple<?>>> sideOutputPortMapping =
      Maps.newHashMapWithExpectedSize(5);
  private transient DoFnInvoker<InputT, OutputT> doFnInvoker;

  public ApexParDoOperator(
      ApexPipelineOptions pipelineOptions,
      DoFn<InputT, OutputT> doFn,
      TupleTag<OutputT> mainOutputTag,
      List<TupleTag<?>> sideOutputTags,
      WindowingStrategy<?, ?> windowingStrategy,
      List<PCollectionView<?>> sideInputs,
      Coder<WindowedValue<InputT>> inputCoder,
      ApexStateBackend stateBackend
      ) {
    this.pipelineOptions = new SerializablePipelineOptions(pipelineOptions);
    this.doFn = doFn;
    this.mainOutputTag = mainOutputTag;
    this.sideOutputTags = sideOutputTags;
    this.windowingStrategy = windowingStrategy;
    this.sideInputs = sideInputs;
    this.sideInputStateInternals = new StateInternalsProxy<>(
        stateBackend.newStateInternalsFactory(VoidCoder.of()));

    if (sideOutputTags.size() > sideOutputPorts.length) {
      String msg = String.format("Too many side outputs (currently only supporting %s).",
          sideOutputPorts.length);
      throw new UnsupportedOperationException(msg);
    }

    Coder<List<WindowedValue<InputT>>> coder = ListCoder.of(inputCoder);
    this.pushedBack = new ValueAndCoderKryoSerializable<>(new ArrayList<WindowedValue<InputT>>(),
        coder);

    Coder<?> keyCoder = null;
    if (doFn instanceof SplittableParDo.ProcessFn) {
      // we know that it is keyed on String
      keyCoder = StringUtf8Coder.of();
      this.currentKeyStateInternals = new StateInternalsProxy<>(
          stateBackend.newStateInternalsFactory(keyCoder));
    }


  }

  @SuppressWarnings("unused") // for Kryo
  private ApexParDoOperator() {
    this.pipelineOptions = null;
    this.doFn = null;
    this.mainOutputTag = null;
    this.sideOutputTags = null;
    this.windowingStrategy = null;
    this.sideInputs = null;
    this.pushedBack = null;
    this.sideInputStateInternals = null;
  }

  public final transient DefaultInputPort<ApexStreamTuple<WindowedValue<InputT>>> input =
      new DefaultInputPort<ApexStreamTuple<WindowedValue<InputT>>>() {
    @Override
    public void process(ApexStreamTuple<WindowedValue<InputT>> t) {
      if (t instanceof ApexStreamTuple.WatermarkTuple) {
        processWatermark((ApexStreamTuple.WatermarkTuple<?>) t);
      } else {
        if (traceTuples) {
          LOG.debug("\ninput {}\n", t.getValue());
        }
        Iterable<WindowedValue<InputT>> justPushedBack = processElementInReadyWindows(t.getValue());
        for (WindowedValue<InputT> pushedBackValue : justPushedBack) {
          pushedBackWatermark.add(pushedBackValue.getTimestamp().getMillis());
          pushedBack.get().add(pushedBackValue);
        }
      }
    }
  };

  @InputPortFieldAnnotation(optional = true)
  public final transient DefaultInputPort<ApexStreamTuple<WindowedValue<Iterable<?>>>> sideInput1 =
      new DefaultInputPort<ApexStreamTuple<WindowedValue<Iterable<?>>>>() {
    @Override
    public void process(ApexStreamTuple<WindowedValue<Iterable<?>>> t) {
      if (t instanceof ApexStreamTuple.WatermarkTuple) {
        // ignore side input watermarks
        return;
      }

      int sideInputIndex = 0;
      if (t instanceof ApexStreamTuple.DataTuple) {
        sideInputIndex = ((ApexStreamTuple.DataTuple<?>) t).getUnionTag();
      }

      if (traceTuples) {
        LOG.debug("\nsideInput {} {}\n", sideInputIndex, t.getValue());
      }

      PCollectionView<?> sideInput = sideInputs.get(sideInputIndex);
      sideInputHandler.addSideInputValue(sideInput, t.getValue());

      List<WindowedValue<InputT>> newPushedBack = new ArrayList<>();
      for (WindowedValue<InputT> elem : pushedBack.get()) {
        Iterable<WindowedValue<InputT>> justPushedBack = processElementInReadyWindows(elem);
        Iterables.addAll(newPushedBack, justPushedBack);
      }

      pushedBack.get().clear();
      pushedBackWatermark.clear();
      for (WindowedValue<InputT> pushedBackValue : newPushedBack) {
        pushedBackWatermark.add(pushedBackValue.getTimestamp().getMillis());
        pushedBack.get().add(pushedBackValue);
      }

      // potentially emit watermark
      processWatermark(ApexStreamTuple.WatermarkTuple.of(currentInputWatermark));
    }
  };

  @OutputPortFieldAnnotation(optional = true)
  public final transient DefaultOutputPort<ApexStreamTuple<?>> output = new DefaultOutputPort<>();

  @OutputPortFieldAnnotation(optional = true)
  public final transient DefaultOutputPort<ApexStreamTuple<?>> sideOutput1 =
      new DefaultOutputPort<>();
  @OutputPortFieldAnnotation(optional = true)
  public final transient DefaultOutputPort<ApexStreamTuple<?>> sideOutput2 =
      new DefaultOutputPort<>();
  @OutputPortFieldAnnotation(optional = true)
  public final transient DefaultOutputPort<ApexStreamTuple<?>> sideOutput3 =
      new DefaultOutputPort<>();
  @OutputPortFieldAnnotation(optional = true)
  public final transient DefaultOutputPort<ApexStreamTuple<?>> sideOutput4 =
      new DefaultOutputPort<>();
  @OutputPortFieldAnnotation(optional = true)
  public final transient DefaultOutputPort<ApexStreamTuple<?>> sideOutput5 =
      new DefaultOutputPort<>();

  public final transient DefaultOutputPort<?>[] sideOutputPorts = {sideOutput1, sideOutput2,
      sideOutput3, sideOutput4, sideOutput5};

  @Override
  public <T> void output(TupleTag<T> tag, WindowedValue<T> tuple) {
    DefaultOutputPort<ApexStreamTuple<?>> sideOutputPort = sideOutputPortMapping.get(tag);
    if (sideOutputPort != null) {
      sideOutputPort.emit(ApexStreamTuple.DataTuple.of(tuple));
    } else {
      output.emit(ApexStreamTuple.DataTuple.of(tuple));
    }
    if (traceTuples) {
      LOG.debug("\nemitting {}\n", tuple);
    }
  }

  private Iterable<WindowedValue<InputT>> processElementInReadyWindows(WindowedValue<InputT> elem) {
    try {
      pushbackDoFnRunner.startBundle();
      if (currentKeyStateInternals != null) {
        InputT value = elem.getValue();
        // TODO: generic key extraction
        Object key;
        if (value instanceof KeyedWorkItem) {
          key = ((KeyedWorkItem) value).key();
        } else {
          key = ((KV) value).getKey();
        }
        ((StateInternalsProxy) currentKeyStateInternals).setKey(key);
        currentKeyTimerInternals.currentKey = key;
      }
      Iterable<WindowedValue<InputT>> pushedBack = pushbackDoFnRunner
          .processElementInReadyWindows(elem);
      pushbackDoFnRunner.finishBundle();
      return pushedBack;
    } catch (UserCodeException ue) {
      if (ue.getCause() instanceof AssertionError) {
        ApexRunner.ASSERTION_ERROR.set((AssertionError) ue.getCause());
      }
      throw ue;
    }
  }

  private void processWatermark(ApexStreamTuple.WatermarkTuple<?> mark) {
    this.currentInputWatermark = mark.getTimestamp();

    if (sideInputs.isEmpty()) {
      if (traceTuples) {
        LOG.debug("\nemitting watermark {}\n", mark);
      }
      output.emit(mark);
      return;
    }

    long potentialOutputWatermark =
        Math.min(pushedBackWatermark.get(), currentInputWatermark);
    if (potentialOutputWatermark > currentOutputWatermark) {
      currentOutputWatermark = potentialOutputWatermark;
      if (traceTuples) {
        LOG.debug("\nemitting watermark {}\n", currentOutputWatermark);
      }
      output.emit(ApexStreamTuple.WatermarkTuple.of(currentOutputWatermark));
    }
  }

  @Override
  public void setup(OperatorContext context) {
    this.traceTuples = ApexStreamTuple.Logging.isDebugEnabled(pipelineOptions.get(), this);
    SideInputReader sideInputReader = NullSideInputReader.of(sideInputs);
    if (!sideInputs.isEmpty()) {
      sideInputHandler = new SideInputHandler(sideInputs, sideInputStateInternals);
      sideInputReader = sideInputHandler;
    }

    for (int i = 0; i < sideOutputTags.size(); i++) {
      @SuppressWarnings("unchecked")
      DefaultOutputPort<ApexStreamTuple<?>> port = (DefaultOutputPort<ApexStreamTuple<?>>)
          sideOutputPorts[i];
      sideOutputPortMapping.put(sideOutputTags.get(i), port);
    }

    NoOpStepContext stepContext = new NoOpStepContext() {

      @Override
      public StateInternals<?> stateInternals() {
        return currentKeyStateInternals;
      }

      @Override
      public TimerInternals timerInternals() {
        return currentKeyTimerInternals;
      }

    };
    DoFnRunner<InputT, OutputT> doFnRunner = DoFnRunners.simpleRunner(
        pipelineOptions.get(),
        doFn,
        sideInputReader,
        this,
        mainOutputTag,
        sideOutputTags,
        stepContext,
        new NoOpAggregatorFactory(),
        windowingStrategy
        );

    doFnInvoker = DoFnInvokers.invokerFor(doFn);
    doFnInvoker.invokeSetup();

    if (this.currentKeyStateInternals != null) {

      StatefulDoFnRunner.CleanupTimer cleanupTimer =
          new StatefulDoFnRunner.TimeInternalsCleanupTimer(
              stepContext.timerInternals(), windowingStrategy);

      @SuppressWarnings({"rawtypes"})
      Coder windowCoder = windowingStrategy.getWindowFn().windowCoder();

      @SuppressWarnings({"unchecked"})
      StatefulDoFnRunner.StateCleaner<?> stateCleaner =
          new StatefulDoFnRunner.StateInternalsStateCleaner<>(
              doFn, stepContext.stateInternals(), windowCoder);

      doFnRunner = DoFnRunners.defaultStatefulDoFnRunner(
          doFn,
          doFnRunner,
          stepContext,
          new NoOpAggregatorFactory(),
          windowingStrategy,
          cleanupTimer,
          stateCleaner);
    }

    pushbackDoFnRunner =
        PushbackSideInputDoFnRunner.create(doFnRunner, sideInputs, sideInputHandler);

    if (doFn instanceof SplittableParDo.ProcessFn) {

      @SuppressWarnings("unchecked")
      StateInternalsFactory<String> stateInternalsFactory =
          (StateInternalsFactory<String>) this.currentKeyStateInternals.getFactory();

      @SuppressWarnings({ "rawtypes", "unchecked" })
      SplittableParDo.ProcessFn<InputT, OutputT, Object, RestrictionTracker<Object>>
        splittableDoFn = (SplittableParDo.ProcessFn) doFn;
      splittableDoFn.setStateInternalsFactory(stateInternalsFactory);
      @SuppressWarnings({ "rawtypes", "unchecked" })
      TimerInternalsFactory<String> timerInternalsFactory = (TimerInternalsFactory)
        currentKeyTimerInternals.factory;
      splittableDoFn.setTimerInternalsFactory(timerInternalsFactory);
      splittableDoFn.setProcessElementInvoker(
          new OutputAndTimeBoundedSplittableProcessElementInvoker<>(
              doFn,
              pipelineOptions.get(),
              new OutputWindowedValue<OutputT>() {
                @Override
                public void outputWindowedValue(
                    OutputT output,
                    Instant timestamp,
                    Collection<? extends BoundedWindow> windows,
                    PaneInfo pane) {
                  output(
                      mainOutputTag,
                      WindowedValue.of(output, timestamp, windows, pane));
                }

                @Override
                public <SideOutputT> void sideOutputWindowedValue(
                    TupleTag<SideOutputT> tag,
                    SideOutputT output,
                    Instant timestamp,
                    Collection<? extends BoundedWindow> windows,
                    PaneInfo pane) {
                  output(tag, WindowedValue.of(output, timestamp, windows, pane));
                }
              },
              sideInputReader,
              Executors.newSingleThreadScheduledExecutor(Executors.defaultThreadFactory()),
              10000,
              Duration.standardSeconds(10)));
    }

  }

  @Override
  public void teardown() {
    doFnInvoker.invokeTeardown();
    super.teardown();
  }

  @Override
  public void beginWindow(long windowId) {
  }

  @Override
  public void endWindow() {
  }

  /**
   * TODO: Placeholder for aggregation, to be implemented for embedded and cluster mode.
   * It is called from {@link org.apache.beam.runners.core.SimpleDoFnRunner}.
   */
  public static class NoOpAggregatorFactory implements AggregatorFactory {

    private NoOpAggregatorFactory() {
    }

    @Override
    public <InputT, AccumT, OutputT> Aggregator<InputT, OutputT> createAggregatorForDoFn(
        Class<?> fnClass, ExecutionContext.StepContext step,
        String name, CombineFn<InputT, AccumT, OutputT> combine) {
      return new NoOpAggregator<>();
    }

    private static class NoOpAggregator<InputT, OutputT> implements Aggregator<InputT, OutputT>,
        java.io.Serializable {
      private static final long serialVersionUID = 1L;

      @Override
      public void addValue(InputT value) {
      }

      @Override
      public String getName() {
        // TODO Auto-generated method stub
        return null;
      }

      @Override
      public CombineFn<InputT, ?, OutputT> getCombineFn() {
        // TODO Auto-generated method stub
        return null;
      }

    };
  }

  private static class LongMin {
    long state = Long.MAX_VALUE;

    public void add(long l) {
      state = Math.min(state, l);
    }

    public long get() {
      return state;
    }

    public void clear() {
      state = Long.MAX_VALUE;
    }

  }

  private class CurrentKeyTimerInternals<K> implements TimerInternals {

    private TimerInternalsFactory<K> factory = new TimerInternalsFactory<K>() {
      @Override
      public TimerInternals timerInternalsForKey(K key) {
        InMemoryTimerInternals timerInternals = perKeyTimerInternals.get(key);
        if (timerInternals == null) {
          System.out.println("new timer internals for " + key);
          perKeyTimerInternals.put(key, timerInternals = new InMemoryTimerInternals());
        }
        return timerInternals;
      }
    };

    // TODO: fault tolerant timers
    final Map<K, InMemoryTimerInternals> perKeyTimerInternals = new HashMap<>();
    private K currentKey;

    @Override
    public void setTimer(StateNamespace namespace, String timerId, Instant target,
        TimeDomain timeDomain) {
      factory.timerInternalsForKey(currentKey).setTimer(
          namespace, timerId, target, timeDomain);
    }

    @Override
    public void setTimer(TimerData timerData) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteTimer(StateNamespace namespace, String timerId, TimeDomain timeDomain) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteTimer(StateNamespace namespace, String timerId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteTimer(TimerData timerKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Instant currentProcessingTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Instant currentSynchronizedProcessingTime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Instant currentInputWatermarkTime() {
      return new Instant(currentInputWatermark);
    }

    @Override
    public Instant currentOutputWatermarkTime() {
      throw new UnsupportedOperationException();
    }

  }

}
