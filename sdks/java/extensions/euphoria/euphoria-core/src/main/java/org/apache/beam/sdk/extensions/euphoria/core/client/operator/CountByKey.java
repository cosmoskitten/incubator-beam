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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.Iterables;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.beam.sdk.extensions.euphoria.core.annotation.audience.Audience;
import org.apache.beam.sdk.extensions.euphoria.core.annotation.operator.Derived;
import org.apache.beam.sdk.extensions.euphoria.core.annotation.operator.StateComplexity;
import org.apache.beam.sdk.extensions.euphoria.core.client.dataset.Dataset;
import org.apache.beam.sdk.extensions.euphoria.core.client.functional.UnaryFunction;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.base.Builders;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.base.OptionalMethodBuilder;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.base.ShuffleOperator;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.hint.OutputHint;
import org.apache.beam.sdk.extensions.euphoria.core.client.type.TypeAwares;
import org.apache.beam.sdk.extensions.euphoria.core.client.type.TypeUtils;
import org.apache.beam.sdk.extensions.euphoria.core.client.util.Sums;
import org.apache.beam.sdk.extensions.euphoria.core.translate.OperatorTransform;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.TimestampCombiner;
import org.apache.beam.sdk.transforms.windowing.Trigger;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.joda.time.Duration;

/**
 * Operator counting elements with same key.
 *
 * <h3>Builders:</h3>
 *
 * <ol>
 *   <li>{@code [named] ..................} give name to the operator [optional]
 *   <li>{@code of .......................} input dataset
 *   <li>{@code keyBy ....................} key extractor function
 *   <li>{@code [windowBy] ...............} windowing (see {@link WindowFn}), default is no
 *       windowing
 *   <li>{@code [triggeredBy] ............} defines windowing trigger, follows [windowBy] if called
 *   <li>{@code [accumulationMode] .......} windowing accumulation mode, follows [triggeredBy]
 *   <li>{@code output ...................} build output dataset
 * </ol>
 */
@Audience(Audience.Type.CLIENT)
@Derived(state = StateComplexity.CONSTANT, repartitions = 1)
public class CountByKey<InputT, KeyT> extends ShuffleOperator<InputT, KeyT, KV<KeyT, Long>>
    implements CompositeOperator<InputT, KV<KeyT, Long>> {

  /**
   * Starts building a nameless {@link CountByKey} operator to process the given input dataset.
   *
   * @param <InputT> the type of elements of the input dataset
   * @param input the input data set to be processed
   * @return a builder to complete the setup of the new operator
   * @see #named(String)
   * @see OfBuilder#of(Dataset)
   */
  public static <InputT> KeyByBuilder<InputT> of(Dataset<InputT> input) {
    return new Builder<>(null).of(input);
  }

  /**
   * Starts building a named {@link CountByKey} operator.
   *
   * @param name a user provided name of the new operator to build
   * @return a builder to complete the setup of the new operator
   */
  public static OfBuilder named(@Nullable String name) {
    return new Builder(name);
  }

  /** Builder for 'of' step */
  public interface OfBuilder extends Builders.Of {

    @Override
    <InputT> KeyByBuilder<InputT> of(Dataset<InputT> input);
  }

  /** Builder for 'keyBy' step */
  public interface KeyByBuilder<InputT> extends Builders.KeyBy<InputT> {

    @Override
    <T> WindowByBuilder<T> keyBy(UnaryFunction<InputT, T> keyExtractor, TypeDescriptor<T> keyType);

    @Override
    default <T> WindowByBuilder<T> keyBy(UnaryFunction<InputT, T> keyExtractor) {
      return keyBy(keyExtractor, null);
    }
  }

  /** Builder for 'windowBy' step */
  public interface WindowByBuilder<KeyT>
      extends Builders.WindowBy<TriggeredByBuilder<KeyT>>,
          OptionalMethodBuilder<WindowByBuilder<KeyT>, OutputBuilder<KeyT>>,
          OutputBuilder<KeyT> {

    @Override
    <W extends BoundedWindow> TriggeredByBuilder<KeyT> windowBy(WindowFn<Object, W> windowing);

    @Override
    default OutputBuilder<KeyT> applyIf(
        boolean cond, UnaryFunction<WindowByBuilder<KeyT>, OutputBuilder<KeyT>> fn) {
      return cond ? requireNonNull(fn).apply(this) : this;
    }
  }

  /** Builder for 'triggeredBy' step */
  public interface TriggeredByBuilder<KeyT>
      extends Builders.TriggeredBy<AccumulationModeBuilder<KeyT>> {

    @Override
    AccumulationModeBuilder<KeyT> triggeredBy(Trigger trigger);
  }

  /** Builder for 'accumulationMode' step */
  public interface AccumulationModeBuilder<KeyT>
      extends Builders.AccumulationMode<WindowedOutputBuilder<KeyT>> {

    @Override
    WindowedOutputBuilder<KeyT> accumulationMode(
        WindowingStrategy.AccumulationMode accumulationMode);
  }

  /** Builder for 'windowed output' step */
  public interface WindowedOutputBuilder<KeyT>
      extends Builders.WindowedOutput<WindowedOutputBuilder<KeyT>>, OutputBuilder<KeyT> {}

  /** Builder for 'output' step */
  public interface OutputBuilder<KeyT> extends Builders.Output<KV<KeyT, Long>> {}

  /**
   * Builder for CountByKey operator.
   *
   * @param <InputT> type of input
   * @param <KeyT> type of key
   */
  private static class Builder<InputT, KeyT>
      implements OfBuilder,
          KeyByBuilder<InputT>,
          WindowByBuilder<KeyT>,
          TriggeredByBuilder<KeyT>,
          AccumulationModeBuilder<KeyT>,
          WindowedOutputBuilder<KeyT>,
          OutputBuilder<KeyT> {

    private final WindowBuilder<InputT> windowBuilder = new WindowBuilder<>();

    @Nullable private final String name;
    private Dataset<InputT> input;
    private UnaryFunction<InputT, KeyT> keyExtractor;
    @Nullable private TypeDescriptor<KeyT> keyType;

    Builder(@Nullable String name) {
      this.name = name;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> KeyByBuilder<T> of(Dataset<T> input) {
      this.input = (Dataset<InputT>) requireNonNull(input);
      return (KeyByBuilder) this;
    }

    @Override
    public <T> WindowByBuilder<T> keyBy(
        UnaryFunction<InputT, T> keyExtractor, @Nullable TypeDescriptor<T> keyType) {
      @SuppressWarnings("unchecked")
      final Builder<InputT, T> casted = (Builder<InputT, T>) this;
      casted.keyExtractor = requireNonNull(keyExtractor);
      casted.keyType = keyType;
      return casted;
    }

    @Override
    public <W extends BoundedWindow> TriggeredByBuilder<KeyT> windowBy(
        WindowFn<Object, W> windowFn) {
      windowBuilder.windowBy(windowFn);
      return this;
    }

    @Override
    public AccumulationModeBuilder<KeyT> triggeredBy(Trigger trigger) {
      windowBuilder.triggeredBy(trigger);
      return this;
    }

    @Override
    public WindowedOutputBuilder<KeyT> accumulationMode(
        WindowingStrategy.AccumulationMode accumulationMode) {
      windowBuilder.accumulationMode(accumulationMode);
      return this;
    }

    @Override
    public WindowedOutputBuilder<KeyT> withAllowedLateness(Duration allowedLateness) {
      windowBuilder.withAllowedLateness(allowedLateness);
      return this;
    }

    @Override
    public WindowedOutputBuilder<KeyT> withAllowedLateness(
        Duration allowedLateness, Window.ClosingBehavior closingBehavior) {
      windowBuilder.withAllowedLateness(allowedLateness, closingBehavior);
      return this;
    }

    @Override
    public WindowedOutputBuilder<KeyT> withTimestampCombiner(TimestampCombiner timestampCombiner) {
      windowBuilder.withTimestampCombiner(timestampCombiner);
      return this;
    }

    @Override
    public WindowedOutputBuilder<KeyT> withOnTimeBehavior(Window.OnTimeBehavior behavior) {
      windowBuilder.withOnTimeBehavior(behavior);
      return this;
    }

    @Override
    public Dataset<KV<KeyT, Long>> output(OutputHint... outputHints) {
      final CountByKey<InputT, KeyT> rbk =
          new CountByKey<>(
              name,
              keyExtractor,
              keyType,
              windowBuilder.getWindow().orElse(null),
              TypeUtils.keyValues(
                  TypeAwares.orObjects(Optional.ofNullable(keyType)), TypeDescriptors.longs()));
      return OperatorTransform.apply(rbk, Collections.singletonList(input));
    }
  }

  private CountByKey(
      @Nullable String name,
      UnaryFunction<InputT, KeyT> keyExtractor,
      @Nullable TypeDescriptor<KeyT> keyType,
      @Nullable Window<InputT> window,
      TypeDescriptor<KV<KeyT, Long>> outputType) {
    super(name, outputType, keyExtractor, keyType, window);
  }

  @Override
  public Dataset<KV<KeyT, Long>> expand(List<Dataset<InputT>> inputs) {
    return ReduceByKey.named(getName().orElse(null))
        .of(Iterables.getOnlyElement(inputs))
        .keyBy(getKeyExtractor())
        .valueBy(v -> 1L, TypeDescriptors.longs())
        .combineBy(Sums.ofLongs())
        .applyIf(
            getWindow().isPresent(),
            builder -> {
              @SuppressWarnings("unchecked")
              final ReduceByKey.WindowByInternalBuilder<InputT, KeyT, Long> casted =
                  (ReduceByKey.WindowByInternalBuilder) builder;
              return casted.windowBy(
                  getWindow()
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Unable to resolve windowing for CountByKey expansion.")));
            })
        .output();
  }
}
