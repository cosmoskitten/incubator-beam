package com.dataartisans.flink.dataflow.translation.functions;

import com.dataartisans.flink.dataflow.translation.wrappers.CombineFnAggregatorWrapper;
import com.dataartisans.flink.dataflow.translation.wrappers.SerializableFnAggregatorWrapper;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.Combine;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.SerializableFunction;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindow;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.common.collect.ImmutableList;
import org.apache.flink.api.common.functions.RichMapPartitionFunction;
import org.apache.flink.util.Collector;
import org.joda.time.Instant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates a {@link com.google.cloud.dataflow.sdk.transforms.DoFn} that uses side outputs
 * inside a Flink {@link org.apache.flink.api.common.functions.RichMapPartitionFunction}.
 *
 * We get a mapping from {@link com.google.cloud.dataflow.sdk.values.TupleTag} to output index
 * and must tag all outputs with the output number. Afterwards a filter will filter out
 * those elements that are not to be in a specific output.
 */
public class FlinkMultiOutputDoFnFunction<IN, OUT> extends RichMapPartitionFunction<IN, RawUnionValue> {

	private final DoFn<IN, OUT> doFn;
	private final PipelineOptions options;
	private final Map<TupleTag<?>, Integer> outputMap;

	public FlinkMultiOutputDoFnFunction(DoFn<IN, OUT> doFn, PipelineOptions options, Map<TupleTag<?>, Integer> outputMap) {
		this.doFn = doFn;
		this.options = options;
		this.outputMap = outputMap;
	}

	@Override
	public void mapPartition(Iterable<IN> values, Collector<RawUnionValue> out) throws Exception {
		ProcessContext context = new ProcessContext(doFn, out);
		this.doFn.startBundle(context);
		for (IN value : values) {
			context.inValue = value;
			doFn.processElement(context);
		}
		this.doFn.finishBundle(context);
	}

	private class ProcessContext extends DoFn<IN, OUT>.ProcessContext {

		IN inValue;
		Collector<RawUnionValue> outCollector;

		public ProcessContext(DoFn<IN, OUT> fn, Collector<RawUnionValue> outCollector) {
			fn.super();
			this.outCollector = outCollector;
		}

		@Override
		public IN element() {
			return this.inValue;
		}

		@Override
		public DoFn.KeyedState keyedState() {
			throw new UnsupportedOperationException("Getting the keyed state is not supported!");
		}

		@Override
		public Instant timestamp() {
			return Instant.now();
		}

		@Override
		public Collection<? extends BoundedWindow> windows() {
			return ImmutableList.of();
		}

		@Override
		public PipelineOptions getPipelineOptions() {
			return options;
		}

		@Override
		public <T> T sideInput(PCollectionView<T, ?> view) {
			List<T> sideInput = getRuntimeContext().getBroadcastVariable(view.getTagInternal()
					.getId());
			List<WindowedValue<?>> windowedValueList = new ArrayList<>(sideInput.size());
			for (T input : sideInput) {
				windowedValueList.add(WindowedValue.of(input, Instant.now(), ImmutableList.of(GlobalWindow.INSTANCE)));
			}
			return view.fromIterableInternal(windowedValueList);
		}

		@Override
		public void output(OUT value) {
			// assume that index 0 is the default output
			outCollector.collect(new RawUnionValue(0, value));
		}

		@Override
		public void outputWithTimestamp(OUT output, Instant timestamp) {
			// not FLink's way, just output normally
			output(output);
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> void sideOutput(TupleTag<T> tag, T value) {
			Integer index = outputMap.get(tag);
			if (index != null) {
				outCollector.collect(new RawUnionValue(index, value));
			}
		}

		@Override
		public <T> void sideOutputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
			sideOutput(tag, output);
		}

		@Override
		public <AI, AA, AO> Aggregator<AI> createAggregator(String name, Combine.CombineFn<? super AI, AA, AO> combiner) {
			CombineFnAggregatorWrapper<AI, AA, AO> wrapper = new CombineFnAggregatorWrapper<>(combiner);
			getRuntimeContext().addAccumulator(name, wrapper);
			return wrapper;
		}

		@Override
		public <AI, AO> Aggregator<AI> createAggregator(String name, SerializableFunction<Iterable<AI>, AO> serializableFunction) {
			SerializableFnAggregatorWrapper<AI, AO> wrapper = new SerializableFnAggregatorWrapper<>(serializableFunction);
			getRuntimeContext().addAccumulator(name, wrapper);
			return wrapper;
		}
	}
}
