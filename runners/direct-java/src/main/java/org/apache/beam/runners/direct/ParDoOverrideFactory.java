package org.apache.beam.runners.direct;

import org.apache.beam.runners.core.GroupByKeyIntoKeyedWorkItems;
import org.apache.beam.runners.core.SplittableParDo;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFnAdapters;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.reflect.DoFnSignature;
import org.apache.beam.sdk.transforms.reflect.DoFnSignatures;
import org.apache.beam.sdk.util.KeyedWorkItem;
import org.apache.beam.sdk.util.KeyedWorkItemCoder;
import org.apache.beam.sdk.util.ReifyTimestampsAndWindows;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PInput;
import org.apache.beam.sdk.values.POutput;

/**
 * A {@link PTransformOverrideFactory} that provides overrides for applications of a {@link ParDo}
 * in the direct runner. Currently overrides applications of <a
 * href="https://s.apache.org/splittable-do-fn">Splittable DoFn</a>.
 */
class ParDoOverrideFactory implements PTransformOverrideFactory {
  @Override
  @SuppressWarnings("unchecked")
  public <InputT extends PInput, OutputT extends POutput> PTransform<InputT, OutputT> override(
      PTransform<InputT, OutputT> transform) {
    if (!(transform instanceof ParDo.Bound)) {
      return transform;
    }
    ParDo.Bound<InputT, OutputT> that = (ParDo.Bound<InputT, OutputT>) transform;
    DoFn<InputT, OutputT> fn = DoFnAdapters.getDoFn(that.getFn());
    if (fn == null) {
      // This is an OldDoFn, hence not splittable.
      return transform;
    }
    DoFnSignature signature = DoFnSignatures.INSTANCE.getOrParseSignature(fn.getClass());
    if (!signature.processElement().isSplittable()) {
      return transform;
    }
    return new SplittableParDo(that.getName(), fn, new DirectGroupByKeyIntoKeyedWorkItems());
  }

  /**
   * The Direct Runner specific implementation of {@link GroupByKeyIntoKeyedWorkItems}.
   *
   * @param <InputT>
   * @param <RestrictionT>
   */
  private static class DirectGroupByKeyIntoKeyedWorkItems<InputT, RestrictionT>
      implements GroupByKeyIntoKeyedWorkItems<String, KV<InputT, RestrictionT>> {
    @Override
    public PTransform<
            PCollection<KV<String, KV<InputT, RestrictionT>>>,
            PCollection<KeyedWorkItem<String, KV<InputT, RestrictionT>>>>
        forInputCoder(Coder<KV<InputT, RestrictionT>> inputCoder) {
      return new Transform<>(inputCoder);
    }

    static class Transform<InputT, RestrictionT>
        extends PTransform<
            PCollection<KV<String, KV<InputT, RestrictionT>>>,
            PCollection<KeyedWorkItem<String, KV<InputT, RestrictionT>>>> {
      private final Coder<KV<InputT, RestrictionT>> inputCoder;

      private Transform(Coder<KV<InputT, RestrictionT>> inputCoder) {
        this.inputCoder = inputCoder;
      }

      @Override
      public PCollection<KeyedWorkItem<String, KV<InputT, RestrictionT>>> apply(
          PCollection<KV<String, KV<InputT, RestrictionT>>> input) {
        return input
            .apply(new ReifyTimestampsAndWindows<String, KV<InputT, RestrictionT>>())
            .apply(new DirectGroupByKey.DirectGroupByKeyOnly<String, KV<InputT, RestrictionT>>())
            .setCoder(
                KeyedWorkItemCoder.of(
                    StringUtf8Coder.of(),
                    inputCoder,
                    input.getWindowingStrategy().getWindowFn().windowCoder()));
      }
    }
  }
}
