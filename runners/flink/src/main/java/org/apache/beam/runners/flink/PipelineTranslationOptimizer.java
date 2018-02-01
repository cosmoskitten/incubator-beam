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
package org.apache.beam.runners.flink;

import org.apache.beam.model.pipeline.v1.RunnerApi.IsBounded.Enum;
import org.apache.beam.runners.core.construction.ReadTranslation;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.runners.AppliedPTransform;
import org.apache.beam.sdk.runners.TransformHierarchy;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.PValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Traverses the Pipeline to determine the {@link TranslationMode} for this pipeline.
 */
class PipelineTranslationOptimizer extends FlinkPipelineTranslator {

  private static final Logger LOG = LoggerFactory.getLogger(PipelineTranslationOptimizer.class);

  private TranslationMode translationMode;

  private final FlinkPipelineOptions options;
  private final Pipeline pipeline;

  public PipelineTranslationOptimizer(TranslationMode defaultMode, FlinkPipelineOptions options,
      Pipeline pipeline) {
    this.translationMode = defaultMode;
    this.options = options;
    this.pipeline = pipeline;
  }

  public TranslationMode getTranslationMode() {

    // override user-specified translation mode
    if (options.isStreaming()) {
      return TranslationMode.STREAMING;
    }

    return translationMode;
  }

  @Override
  public CompositeBehavior enterCompositeTransform(TransformHierarchy.Node node) {
    return CompositeBehavior.ENTER_TRANSFORM;
  }

  @Override
  public void leaveCompositeTransform(TransformHierarchy.Node node) {}

  @Override
  public void visitPrimitiveTransform(TransformHierarchy.Node node) {
    AppliedPTransform<?, ?, ?> appliedPTransform = node.toAppliedPTransform(pipeline);
    Class<? extends PTransform> transformClass = node.getTransform().getClass();

    if (ReadTranslation.isBounded(appliedPTransform) == Enum.UNBOUNDED) {
      LOG.info("Found {}. Switching to streaming execution.", transformClass);
      translationMode = TranslationMode.STREAMING;
    }
  }

  @Override
  public void visitValue(PValue value, TransformHierarchy.Node producer) {}
}
