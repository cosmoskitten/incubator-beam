package org.apache.beam.sdk.transforms;

import static com.google.common.base.Preconditions.checkState;

import javax.annotation.Nullable;
import org.apache.beam.sdk.transforms.Contextful.Fn;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.display.HasDisplayData;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;

/**
 * Abstract class providing a base for {@link PTransform}s that map a simple function over elements.
 *
 * <p>The passed {@code fn} returns an {@link Iterable}, each element of which will be sent to
 * output. That interface naturally expresses a FlatMap, but both Map (always one output element)
 * and Filter (either the input element is passed through or an empty collection) can both be
 * expressed as special cases.
 */
abstract class MapperBase<InputT, OutputT>
    extends PTransform<PCollection<? extends InputT>, PCollection<OutputT>> {
  @Nullable private final transient TypeDescriptor<InputT> inputType;
  @Nullable final transient TypeDescriptor<OutputT> outputType;
  @Nullable private final transient Object originalFnForDisplayData;
  @Nullable private final Contextful<Fn<InputT, Iterable<OutputT>>> fn;

  MapperBase(
      String name,
      @Nullable Contextful<Fn<InputT, Iterable<OutputT>>> fn,
      @Nullable Object originalFnForDisplayData,
      @Nullable TypeDescriptor<InputT> inputType,
      TypeDescriptor<OutputT> outputType) {
    super(name);
    this.fn = fn;
    this.originalFnForDisplayData = originalFnForDisplayData;
    this.inputType = inputType;
    this.outputType = outputType;
  }

  @Override
  public PCollection<OutputT> expand(PCollection<? extends InputT> input) {
    return input.apply(
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
                        MapperBase.this.getClass().getSimpleName());
                    return outputType;
                  }

                  @Override
                  public void populateDisplayData(DisplayData.Builder builder) {
                    builder.delegate(MapperBase.this);
                  }
                })
            .withSideInputs(fn.getRequirements().getSideInputs()));
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    super.populateDisplayData(builder);

    // Subclasses can opt to set this null and implement their own display data logic.
    if (originalFnForDisplayData != null) {
      builder.add(DisplayData.item("class", originalFnForDisplayData.getClass()));
    }
    if (originalFnForDisplayData instanceof HasDisplayData) {
      builder.include("fn", (HasDisplayData) originalFnForDisplayData);
    }
  }
}
