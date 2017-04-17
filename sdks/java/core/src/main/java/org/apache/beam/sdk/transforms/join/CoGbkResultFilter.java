package org.apache.beam.sdk.transforms.join;

import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TupleTag;

/**
 * A collection of {@link PTransform} that can be applied to filter the result of
 * {@link CoGroupByKey}.
 *
 * <h2>InnerJoinFilter</h2>
 *
 * {@link InnerJoinFilter} check the values of each {@code TupleTag} in
 * {@code CoGbkResult}, if any TupleTag has no value, the record is filtered out.
 *
 */
public class CoGbkResultFilter {

  /**
   * Return a {@code PTransform} to perform InnerJoin filter.
   *
   * @param <K> the type of the keys in the input and output
   * {@code PCollection}s
   */
  public static <K> InnerJoinFilter<K> createInnerJoinFilter(){
    return new InnerJoinFilter<K>();
  }

  /**
   * A filter act as INNER_JOIN in SQL.
   *
   *<p>This filter verifies values of each input {@code TupleTag} in {@code CoGbkResult}. Only
   *when none is empty, this element is emitted. For example, given the results as below
   *<pre>{@code
   * user1, [[], [order1, order2]]
   * user2, [[address2], [order3]]
   * user3, [[address3], []]
   *}
   *</pre>
   *
   *<p>The output after {@code withInnerFilter} is
   *<pre>{@code
   * user2, [[address2], [order3]]
   *}
   *</pre>
   *
   */
  private static class InnerJoinFilter<K>
      extends PTransform<PCollection<KV<K, CoGbkResult>>, PCollection<KV<K, CoGbkResult>>> {

    @Override
    public PCollection<KV<K, CoGbkResult>> expand(PCollection<KV<K, CoGbkResult>> input) {
      return input.apply("InnerJoinFilter",
          ParDo.of(new DoFn<KV<K, CoGbkResult>, KV<K, CoGbkResult>>() {

            @ProcessElement
            public void processElement(ProcessContext c) {
              CoGbkResult result = c.element().getValue();
              for (TupleTag<?> tag : result.getSchema().getTupleTagList().getAll()) {
                if (!result.getAll(tag).iterator().hasNext()) {
                  return;
                }
              }
              c.output(c.element());
            }
          }));
    }
  }

}
