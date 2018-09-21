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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.beam.sdk.extensions.euphoria.core.annotation.audience.Audience;
import org.apache.beam.sdk.extensions.euphoria.core.annotation.operator.Recommended;
import org.apache.beam.sdk.extensions.euphoria.core.annotation.operator.StateComplexity;
import org.apache.beam.sdk.extensions.euphoria.core.client.dataset.Dataset;
import org.apache.beam.sdk.extensions.euphoria.core.client.functional.BinaryFunctor;
import org.apache.beam.sdk.extensions.euphoria.core.client.functional.UnaryFunction;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.base.Builders;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.base.OptionalMethodBuilder;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.base.ShuffleOperator;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.hint.OutputHint;
import org.apache.beam.sdk.extensions.euphoria.core.client.type.TypeAwares;
import org.apache.beam.sdk.extensions.euphoria.core.translate.OperatorTransform;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.Trigger;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.apache.beam.sdk.values.WindowingStrategy;

/**
 * Inner join of two datasets by given key producing single new dataset.
 *
 * <p>When joining two streams, the join has to specify windowing which groups elements from streams
 * into {@link Window}s. The join operation is performed within same windows produced on left and
 * right side of input {@link Dataset}s.
 *
 * <h3>Builders:</h3>
 *
 * <ol>
 *   <li>{@code [named] ..................} give name to the operator [optional]
 *   <li>{@code of .......................} left and right input dataset
 *   <li>{@code by .......................} {@link UnaryFunction}s transforming left and right
 *       elements into keys
 *   <li>{@code using ....................} {@link BinaryFunctor} receiving left and right element
 *       from joined window
 *   <li>{@code [windowBy] ...............} windowing (see {@link WindowFn}), default is no
 *       windowing
 *   <li>{@code [triggeredBy] ............} defines windowing trigger, follows [windowBy] if called
 *   <li>{@code [accumulationMode] .......} windowing accumulation mode, follows [triggeredBy]
 *   <li>{@code (output | outputValues) ..} build output dataset
 * </ol>
 */
@Audience(Audience.Type.CLIENT)
@Recommended(
  reason =
      "Might be useful to override because of performance reasons in a "
          + "specific join types (e.g. sort join), which might reduce the space "
          + "complexity",
  state = StateComplexity.LINEAR,
  repartitions = 1
)
public class Join<LeftT, RightT, KeyT, OutputT>
    extends ShuffleOperator<Object, KeyT, KV<KeyT, OutputT>> {

  public static <LeftT, RightT> ByBuilder<LeftT, RightT> of(
      Dataset<LeftT> left, Dataset<RightT> right) {
    return named(null).of(left, right);
  }

  /**
   * Name of join operator.
   *
   * @param name of operator
   * @return OfBuilder
   */
  public static OfBuilder named(@Nullable String name) {
    return new Builder<>(name, Type.INNER);
  }

  /** Type of join. */
  public enum Type {
    INNER,
    LEFT,
    RIGHT,
    FULL
  }

  /** Builder for the 'of' step */
  public interface OfBuilder {

    <LeftT, RightT> ByBuilder<LeftT, RightT> of(Dataset<LeftT> left, Dataset<RightT> right);
  }

  /** Builder for the 'by' step */
  public interface ByBuilder<LeftT, RightT> {

    <K> UsingBuilder<LeftT, RightT, K> by(
        UnaryFunction<LeftT, K> leftKeyExtractor,
        UnaryFunction<RightT, K> rightKeyExtractor,
        @Nullable TypeDescriptor<K> keyType);

    default <T> UsingBuilder<LeftT, RightT, T> by(
        UnaryFunction<LeftT, T> leftKeyExtractor, UnaryFunction<RightT, T> rightKeyExtractor) {
      return by(leftKeyExtractor, rightKeyExtractor, null);
    }
  }

  /** Builder for the 'using' step */
  public interface UsingBuilder<LeftT, RightT, KeyT> {

    <OutputT> WindowByBuilder<KeyT, OutputT> using(
        BinaryFunctor<LeftT, RightT, OutputT> joinFunc,
        @Nullable TypeDescriptor<OutputT> outputTypeDescriptor);

    default <OutputT> WindowByBuilder<KeyT, OutputT> using(
        BinaryFunctor<LeftT, RightT, OutputT> joinFunc) {
      return using(joinFunc, null);
    }
  }

  /** Builder for the 'windowBy' step */
  public interface WindowByBuilder<KeyT, OutputT>
      extends OptionalMethodBuilder<WindowByBuilder<KeyT, OutputT>, OutputBuilder<KeyT, OutputT>>,
          Builders.WindowBy<TriggeredByBuilder<KeyT, OutputT>>,
          OutputBuilder<KeyT, OutputT> {

    @Override
    default OutputBuilder<KeyT, OutputT> applyIf(
        boolean cond,
        UnaryFunction<WindowByBuilder<KeyT, OutputT>, OutputBuilder<KeyT, OutputT>> fn) {
      return cond ? requireNonNull(fn).apply(this) : this;
    }
  }

  /** Builder for the 'triggeredBy' step */
  public interface TriggeredByBuilder<KeyT, OutputT>
      extends Builders.TriggeredBy<AccumulatorModeBuilder<KeyT, OutputT>> {}

  /** Builder for the 'accumulatorMode' step */
  public interface AccumulatorModeBuilder<KeyT, OutputT>
      extends Builders.AccumulatorMode<OutputBuilder<KeyT, OutputT>> {}

  /**
   * Last builder in a chain. It concludes this operators creation by calling {@link
   * #output(OutputHint...)}.
   */
  public interface OutputBuilder<KeyT, OutputT>
      extends Builders.Output<KV<KeyT, OutputT>>, Builders.OutputValues<KeyT, OutputT> {}

  /** Parameters of this operator used in builders. */
  static class Builder<LeftT, RightT, KeyT, OutputT>
      implements OfBuilder,
          ByBuilder<LeftT, RightT>,
          UsingBuilder<LeftT, RightT, KeyT>,
          WindowByBuilder<KeyT, OutputT>,
          TriggeredByBuilder<KeyT, OutputT>,
          AccumulatorModeBuilder<KeyT, OutputT>,
          OutputBuilder<KeyT, OutputT> {

    @Nullable private final String name;
    private final Type type;
    private Dataset<LeftT> left;
    private Dataset<RightT> right;
    private UnaryFunction<LeftT, KeyT> leftKeyExtractor;
    private UnaryFunction<RightT, KeyT> rightKeyExtractor;
    @Nullable private TypeDescriptor<KeyT> keyType;
    private BinaryFunctor<LeftT, RightT, OutputT> joinFunc;
    @Nullable private TypeDescriptor<OutputT> outputType;
    @Nullable private Window<Object> window;

    Builder(@Nullable String name, Type type) {
      this.name = name;
      this.type = type;
    }

    @Override
    public <T, S> ByBuilder<T, S> of(Dataset<T> left, Dataset<S> right) {
      @SuppressWarnings("unchecked")
      final Builder<T, S, ?, ?> casted = (Builder) this;
      casted.left = requireNonNull(left);
      casted.right = requireNonNull(right);
      return casted;
    }

    @Override
    public <T> UsingBuilder<LeftT, RightT, T> by(
        UnaryFunction<LeftT, T> leftKeyExtractor,
        UnaryFunction<RightT, T> rightKeyExtractor,
        @Nullable TypeDescriptor<T> keyType) {
      @SuppressWarnings("unchecked")
      final Builder<LeftT, RightT, T, ?> casted = (Builder) this;
      casted.leftKeyExtractor = leftKeyExtractor;
      casted.rightKeyExtractor = rightKeyExtractor;
      casted.keyType = keyType;
      return casted;
    }

    @Override
    public <T> WindowByBuilder<KeyT, T> using(
        BinaryFunctor<LeftT, RightT, T> joinFunc, @Nullable TypeDescriptor<T> outputType) {
      @SuppressWarnings("unchecked")
      final Builder<LeftT, RightT, KeyT, T> casted = (Builder) this;
      casted.joinFunc = requireNonNull(joinFunc);
      casted.outputType = outputType;
      return casted;
    }

    @Override
    public <W extends BoundedWindow> TriggeredByBuilder<KeyT, OutputT> windowBy(
        WindowFn<Object, W> windowFn) {
      this.window = Window.into(requireNonNull(windowFn));
      return this;
    }

    @Override
    public AccumulatorModeBuilder<KeyT, OutputT> triggeredBy(Trigger trigger) {
      this.window = requireNonNull(window).triggering(trigger);
      return this;
    }

    @Override
    public OutputBuilder<KeyT, OutputT> accumulationMode(
        WindowingStrategy.AccumulationMode accumulationMode) {
      switch (requireNonNull(accumulationMode)) {
        case DISCARDING_FIRED_PANES:
          window = requireNonNull(window).discardingFiredPanes();
          break;
        case ACCUMULATING_FIRED_PANES:
          window = requireNonNull(window).accumulatingFiredPanes();
          break;
        default:
          throw new IllegalArgumentException(
              "Unknown accumulation mode [" + accumulationMode + "]");
      }
      return this;
    }

    @Override
    public Dataset<KV<KeyT, OutputT>> output(OutputHint... outputHints) {
      final Join<LeftT, RightT, KeyT, OutputT> join =
          new Join<>(
              name,
              type,
              leftKeyExtractor,
              rightKeyExtractor,
              keyType,
              joinFunc,
              TypeDescriptors.kvs(
                  TypeAwares.orObjects(Optional.ofNullable(keyType)),
                  TypeAwares.orObjects(Optional.ofNullable(outputType))),
              window);
      @SuppressWarnings("unchecked")
      final List<Dataset<Object>> inputs = Arrays.asList((Dataset) left, (Dataset) right);
      return OperatorTransform.apply(join, inputs);
    }

    @Override
    public Dataset<OutputT> outputValues(OutputHint... outputHints) {
      return MapElements.named(name != null ? name + "::extract-values" : null)
          .of(output(outputHints))
          .using(KV::getValue, outputType)
          .output(outputHints);
    }
  }

  private final Type type;
  private final UnaryFunction<LeftT, KeyT> leftKeyExtractor;
  private final UnaryFunction<RightT, KeyT> rightKeyExtractor;
  private final BinaryFunctor<LeftT, RightT, OutputT> functor;

  private Join(
      @Nullable String name,
      Type type,
      UnaryFunction<LeftT, KeyT> leftKeyExtractor,
      UnaryFunction<RightT, KeyT> rightKeyExtractor,
      @Nullable TypeDescriptor<KeyT> keyType,
      BinaryFunctor<LeftT, RightT, OutputT> functor,
      @Nullable TypeDescriptor<KV<KeyT, OutputT>> outputType,
      @Nullable Window<Object> window) {
    super(name, outputType, null, keyType, window);
    this.type = type;
    this.leftKeyExtractor = leftKeyExtractor;
    this.rightKeyExtractor = rightKeyExtractor;
    this.functor = functor;
  }

  public Type getType() {
    return type;
  }

  public UnaryFunction<LeftT, KeyT> getLeftKeyExtractor() {
    return leftKeyExtractor;
  }

  public UnaryFunction<RightT, KeyT> getRightKeyExtractor() {
    return rightKeyExtractor;
  }

  public BinaryFunctor<LeftT, RightT, OutputT> getJoiner() {
    return functor;
  }
}
