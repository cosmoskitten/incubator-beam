package com.dataartisans.flink.dataflow.translation;

import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.Combine;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.SerializableFunction;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.common.collect.ImmutableList;
import org.apache.flink.api.common.functions.RichMapPartitionFunction;
import org.apache.flink.util.Collector;
import org.joda.time.Instant;

import java.util.Collection;

/**
 * Encapsulates a DoFn inside a Flink MapPartitionFunction
 */
public class FlinkDoFnFunction<IN, OUT> extends RichMapPartitionFunction<IN, OUT> {

	DoFn<IN, OUT> doFn;
	
	public FlinkDoFnFunction(DoFn<IN, OUT> doFn) {
		this.doFn = doFn;
	}

	@Override
	public void mapPartition(Iterable<IN> values, Collector<OUT> out) throws Exception {
		ProcessContext context = new ProcessContext(doFn);
		this.doFn.startBundle(context);
		for (IN value : values) {
			context.inValue = value;
			doFn.processElement(context);
			out.collect(context.outValue);
		}
		this.doFn.finishBundle(context);
	}
	
	private class ProcessContext extends DoFn<IN, OUT>.ProcessContext {

		IN inValue;
		OUT outValue;
		
		public ProcessContext(DoFn<IN, OUT> fn) {
			fn.super();
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
			throw new UnsupportedOperationException("PipelineOptions are not yet supported!");
		}

		@Override
		public <T> T sideInput(PCollectionView<T, ?> view) {
			return null;
		}

		@Override
		public void output(OUT output) {
			this.outValue = output;
		}

		@Override
		public void outputWithTimestamp(OUT output, Instant timestamp) {
			// not FLink's way, just output normally
			output(output);
		}

		@Override
		public <T> void sideOutput(TupleTag<T> tag, T output) {
			throw new UnsupportedOperationException("Side outputs are not supported!");
		}

		@Override
		public <T> void sideOutputWithTimestamp(TupleTag<T> tag, T output, Instant timestamp) {
			sideOutput(tag, output);
		}

		@Override
		public <AI, AA, AO> Aggregator<AI> createAggregator(String name, Combine.CombineFn<? super AI, AA, AO> combiner) {
			//RuntimeContext con = FlinkDoFnFunction.this.getRuntimeContext();
			throw new UnsupportedOperationException("Needs to be implemented!");
		}

		@Override
		public <AI, AO> Aggregator<AI> createAggregator(String name, SerializableFunction<Iterable<AI>, AO> combiner) {
			throw new UnsupportedOperationException("Needs to be implemented!");
		}
	}
}
