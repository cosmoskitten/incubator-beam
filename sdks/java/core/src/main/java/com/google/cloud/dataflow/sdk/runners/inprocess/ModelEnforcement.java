/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.runners.inprocess;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.CommittedBundle;
import com.google.cloud.dataflow.sdk.transforms.AppliedPTransform;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.values.PCollection;

/**
 * Enforcement tools that verify that executing code conforms to the model.
 *
 * <p>ModelEnforcement is performed on a per-element and per-bundle basis. The
 * {@link ModelEnforcement} is provided with the input bundle as part of
 * {@link ModelEnforcementFactory#forBundle(CommittedBundle, AppliedPTransform)}, each element
 * before and after that element is provided to an underlying {@link TransformEvaluator}, and the
 * output {@link InProcessTransformResult} and committed output bundles after the
 * {@link TransformEvaluator} has completed.
 *
 * <p>Typically, {@link ModelEnforcement} will obtain required metadata (such as the {@link Coder}
 * of the input {@link PCollection} on construction, and then enforce per-element behavior
 * (such as the immutability of input elements). When the element is output or the bundle is
 * completed, the required conditions can be enforced across all elements.
 */
public interface ModelEnforcement<T> {
  /**
   * Called before a call to {@link TransformEvaluator#processElement(WindowedValue)} on the
   * provided {@link WindowedValue}.
   */
  void beforeElement(WindowedValue<T> element);

  /**
   * Called after a call to {@link TransformEvaluator#processElement(WindowedValue)} on the
   * provided {@link WindowedValue}.
   */
  void afterElement(WindowedValue<T> element);

  /**
   * Called after a bundle has been completed and {@link TransformEvaluator#finishBundle()} has been
   * called, producing the provided {@link InProcessTransformResult} and
   * {@link CommittedBundle output bundles}.
   */
  void afterFinish(
      CommittedBundle<T> input,
      InProcessTransformResult result,
      Iterable<? extends CommittedBundle<?>> outputs);
}
