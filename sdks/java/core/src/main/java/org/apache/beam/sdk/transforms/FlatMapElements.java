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
package org.apache.beam.sdk.transforms;

import static org.apache.beam.vendor.guava.v20_0.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v20_0.com.google.common.base.Preconditions.checkState;

import javax.annotation.Nullable;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.transforms.Contextful.Fn;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.display.HasDisplayData;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;

/**
 * {@code PTransform}s for mapping a simple function that returns iterables over the elements of a
 * {@link PCollection} and merging the results.
 */
public class FlatMapElements<InputT, OutputT>
    extends PTransform<PCollection<? extends InputT>, PCollection<OutputT>> {
  @Nullable private final transient TypeDescriptor<InputT> inputType;
  @Nullable private final transient TypeDescriptor<OutputT> outputType;
  @Nullable private final transient Object originalFnForDisplayData;
  @Nullable private final Contextful<Fn<InputT, Iterable<OutputT>>> fn;

  private FlatMapElements(
      @Nullable Contextful<Fn<InputT, Iterable<OutputT>>> fn,
      @Nullable Object originalFnForDisplayData,
      @Nullable TypeDescriptor<InputT> inputType,
      TypeDescriptor<OutputT> outputType) {
    this.fn = fn;
    this.originalFnForDisplayData = originalFnForDisplayData;
    this.inputType = inputType;
    this.outputType = outputType;
  }

  /**
   * For a {@code InferableFunction<InputT, ? extends Iterable<OutputT>>} {@code fn}, return a
   * {@link PTransform} that applies {@code fn} to every element of the input {@code
   * PCollection<InputT>} and outputs all of the elements to the output {@code
   * PCollection<OutputT>}.
   *
   * <p>{@link InferableFunction} has the advantage of providing type descriptor information, but it
   * is generally more convenient to specify output type via {@link #into(TypeDescriptor)}, and
   * provide the mapping as a lambda expression to {@link #via(ProcessFunction)}.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * PCollection<String> lines = ...;
   * PCollection<String> words = lines.apply(FlatMapElements.via(
   *     new InferableFunction<String, List<String>>() {
   *       public Integer apply(String line) throws Exception {
   *         return Arrays.asList(line.split(" "));
   *       }
   *     });
   * }</pre>
   */
  public static <InputT, OutputT> FlatMapElements<InputT, OutputT> via(
      InferableFunction<? super InputT, ? extends Iterable<OutputT>> fn) {
    Contextful<Fn<InputT, Iterable<OutputT>>> wrapped = (Contextful) Contextful.fn(fn);
    TypeDescriptor<OutputT> outputType =
        TypeDescriptors.extractFromTypeParameters(
            (TypeDescriptor<Iterable<OutputT>>) fn.getOutputTypeDescriptor(),
            Iterable.class,
            new TypeDescriptors.TypeVariableExtractor<Iterable<OutputT>, OutputT>() {});
    TypeDescriptor<InputT> inputType = (TypeDescriptor<InputT>) fn.getInputTypeDescriptor();
    return new FlatMapElements<>(wrapped, fn, inputType, outputType);
  }

  /** @deprecated binary compatibility adapter for {@link #via(ProcessFunction)} */
  @Deprecated
  public static <InputT, OutputT> FlatMapElements<InputT, OutputT> via(
      SimpleFunction<InputT, ? extends Iterable<OutputT>> fn) {
    return via((InferableFunction<? super InputT, ? extends Iterable<OutputT>>) fn);
  }

  /**
   * Returns a new {@link FlatMapElements} transform with the given type descriptor for the output
   * type, but the mapping function yet to be specified using {@link #via(ProcessFunction)}.
   */
  public static <OutputT> FlatMapElements<?, OutputT> into(
      final TypeDescriptor<OutputT> outputType) {
    return new FlatMapElements<>(null, null, null, outputType);
  }

  /**
   * For a {@code ProcessFunction<InputT, ? extends Iterable<OutputT>>} {@code fn}, returns a {@link
   * PTransform} that applies {@code fn} to every element of the input {@code PCollection<InputT>}
   * and outputs all of the elements to the output {@code PCollection<OutputT>}.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * PCollection<String> words = lines.apply(
   *     FlatMapElements.into(TypeDescriptors.strings())
   *                    .via((String line) -> Arrays.asList(line.split(" ")))
   * }</pre>
   */
  public <NewInputT> FlatMapElements<NewInputT, OutputT> via(
      ProcessFunction<NewInputT, ? extends Iterable<OutputT>> fn) {
    return new FlatMapElements<>(
        (Contextful) Contextful.fn(fn), fn, TypeDescriptors.inputOf(fn), outputType);
  }

  /** @deprecated binary compatibility adapter for {@link #via(ProcessFunction)} */
  @Deprecated
  public <NewInputT> FlatMapElements<NewInputT, OutputT> via(
      SerializableFunction<NewInputT, ? extends Iterable<OutputT>> fn) {
    return via((ProcessFunction) fn);
  }

  /** Like {@link #via(ProcessFunction)}, but allows access to additional context. */
  @Experimental(Experimental.Kind.CONTEXTFUL)
  public <NewInputT> FlatMapElements<NewInputT, OutputT> via(
      Contextful<Fn<NewInputT, Iterable<OutputT>>> fn) {
    return new FlatMapElements<>(
        fn, fn.getClosure(), TypeDescriptors.inputOf(fn.getClosure()), outputType);
  }

  @Override
  public PCollection<OutputT> expand(PCollection<? extends InputT> input) {
    checkArgument(fn != null, ".via() is required");
    return input.apply(
        "FlatMap",
        ParDo.of(
                new DoFn<InputT, OutputT>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) throws Exception {
                    Iterable<OutputT> res =
                        fn.getClosure().apply(c.element(), Fn.Context.wrapProcessContext(c));
                    for (OutputT output : res) {
                      c.output(output);
                    }
                  }

                  @Override
                  public TypeDescriptor<InputT> getInputTypeDescriptor() {
                    return inputType;
                  }

                  @Override
                  public TypeDescriptor<OutputT> getOutputTypeDescriptor() {
                    checkState(
                        outputType != null,
                        "%s output type descriptor was null; "
                            + "this probably means that getOutputTypeDescriptor() was called after "
                            + "serialization/deserialization, but it is only available prior to "
                            + "serialization, for constructing a pipeline and inferring coders",
                        FlatMapElements.class.getSimpleName());
                    return outputType;
                  }

                  @Override
                  public void populateDisplayData(DisplayData.Builder builder) {
                    builder.delegate(FlatMapElements.this);
                  }
                })
            .withSideInputs(fn.getRequirements().getSideInputs()));
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    super.populateDisplayData(builder);
    builder.add(DisplayData.item("class", originalFnForDisplayData.getClass()));
    if (originalFnForDisplayData instanceof HasDisplayData) {
      builder.include("fn", (HasDisplayData) originalFnForDisplayData);
    }
  }
}
