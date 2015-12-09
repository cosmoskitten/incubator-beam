/*
 * Copyright 2015 Data Artisans GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dataartisans.flink.dataflow.translation;

import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.runners.TransformTreeNode;
import com.google.cloud.dataflow.sdk.transforms.AppliedPTransform;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.join.CoGroupByKey;
import com.google.cloud.dataflow.sdk.values.PValue;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

public class FlinkStreamingPipelineTranslator extends FlinkPipelineTranslator {

	/** The necessary context in the case of a straming job. */
	private final FlinkStreamingTranslationContext streamingContext;

	private int depth = 0;

	/** Composite transform that we want to translate before proceeding with other transforms. */
	private PTransform<?, ?> currentCompositeTransform;

	public FlinkStreamingPipelineTranslator(StreamExecutionEnvironment env, PipelineOptions options) {
		this.streamingContext = new FlinkStreamingTranslationContext(env, options);
	}

	// --------------------------------------------------------------------------------------------
	//  Pipeline Visitor Methods
	// --------------------------------------------------------------------------------------------

	@Override
	public void enterCompositeTransform(TransformTreeNode node) {
		System.out.println(genSpaces(this.depth) + "enterCompositeTransform- " + formatNodeName(node));

		PTransform<?, ?> transform = node.getTransform();
		if (transform != null && currentCompositeTransform == null) {

			StreamTransformTranslator<?> translator = FlinkStreamingTransformTranslators.getTranslator(transform);
			if (translator != null) {
				currentCompositeTransform = transform;
			}
		}
		this.depth++;
	}

	@Override
	public void leaveCompositeTransform(TransformTreeNode node) {
		PTransform<?, ?> transform = node.getTransform();
		if (transform != null && currentCompositeTransform == transform) {

			StreamTransformTranslator<?> translator = FlinkStreamingTransformTranslators.getTranslator(transform);
			if (translator != null) {
				System.out.println(genSpaces(this.depth) + "doingCompositeTransform- " + formatNodeName(node));
				applyStreamingTransform(transform, node, translator);
				currentCompositeTransform = null;
			} else {
				throw new IllegalStateException("Attempted to translate composite transform " +
						"but no translator was found: " + currentCompositeTransform);
			}
		}
		this.depth--;
		System.out.println(genSpaces(this.depth) + "leaveCompositeTransform- " + formatNodeName(node));
	}

	@Override
	public void visitTransform(TransformTreeNode node) {
		System.out.println(genSpaces(this.depth) + "visitTransform- " + formatNodeName(node));
		if (currentCompositeTransform != null) {
			// ignore it
			return;
		}

		// get the transformation corresponding to hte node we are
		// currently visiting and translate it into its Flink alternative.

		PTransform<?, ?> transform = node.getTransform();
		StreamTransformTranslator<?> translator = FlinkStreamingTransformTranslators.getTranslator(transform);
		if (translator == null) {
			System.out.println(node.getTransform().getClass());
			throw new UnsupportedOperationException("The transform " + transform + " is currently not supported.");
		}
		applyStreamingTransform(transform, node, translator);
	}

	@Override
	public void visitValue(PValue value, TransformTreeNode producer) {
		// do nothing here
	}

	private <T extends PTransform<?, ?>> void applyStreamingTransform(PTransform<?, ?> transform, TransformTreeNode node, StreamTransformTranslator<?> translator) {
		if (this.streamingContext == null) {
			throw new IllegalStateException("The FlinkPipelineTranslator is not yet initialized.");
		}

		@SuppressWarnings("unchecked")
		T typedTransform = (T) transform;

		@SuppressWarnings("unchecked")
		StreamTransformTranslator<T> typedTranslator = (StreamTransformTranslator<T>) translator;

		// create the applied PTransform on the batchContext
		streamingContext.setCurrentTransform(AppliedPTransform.of(
				node.getFullName(), node.getInput(), node.getOutput(), (PTransform) transform));
		typedTranslator.translateNode(typedTransform, streamingContext);
	}

	/**
	 * A translator of a {@link PTransform}.
	 */
	public interface StreamTransformTranslator<Type extends PTransform> {
		void translateNode(Type transform, FlinkStreamingTranslationContext context);
	}

	private static String genSpaces(int n) {
		String s = "";
		for (int i = 0; i < n; i++) {
			s += "|   ";
		}
		return s;
	}

	private static String formatNodeName(TransformTreeNode node) {
		return node.toString().split("@")[1] + node.getTransform();
	}
}
