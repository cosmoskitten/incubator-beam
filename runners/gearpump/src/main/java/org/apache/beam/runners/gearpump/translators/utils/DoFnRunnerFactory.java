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

package org.apache.beam.runners.gearpump.translators.utils;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.apache.beam.runners.core.AggregatorFactory;
import org.apache.beam.runners.core.DoFnRunner;
import org.apache.beam.runners.core.DoFnRunners;
import org.apache.beam.runners.core.PushbackSideInputDoFnRunner;
import org.apache.beam.runners.core.SimpleDoFnRunner;
import org.apache.beam.runners.gearpump.GearpumpPipelineOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.util.ExecutionContext;
import org.apache.beam.sdk.util.ReadyCheckingSideInputReader;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;

/**
 * a serializable {@link SimpleDoFnRunner}.
 */
public class DoFnRunnerFactory<InputT, OutputT> implements Serializable {

  private static final long serialVersionUID = -4109539010014189725L;
  private final DoFn<InputT, OutputT> fn;
  private final transient PipelineOptions options;
  private final Collection<PCollectionView<?>> sideInputs;
  private final DoFnRunners.OutputManager outputManager;
  private final TupleTag<OutputT> mainOutputTag;
  private final List<TupleTag<?>> sideOutputTags;
  private final ExecutionContext.StepContext stepContext;
  private final AggregatorFactory aggregatorFactory;
  private final WindowingStrategy<?, ?> windowingStrategy;

  public DoFnRunnerFactory(
      GearpumpPipelineOptions pipelineOptions,
      DoFn<InputT, OutputT> doFn,
      Collection<PCollectionView<?>> sideInputs,
      DoFnRunners.OutputManager outputManager,
      TupleTag<OutputT> mainOutputTag,
      List<TupleTag<?>> sideOutputTags,
      ExecutionContext.StepContext stepContext,
      AggregatorFactory aggregatorFactory,
      WindowingStrategy<?, ?> windowingStrategy) {
    this.fn = doFn;
    this.options = pipelineOptions;
    this.sideInputs = sideInputs;
    this.outputManager = outputManager;
    this.mainOutputTag = mainOutputTag;
    this.sideOutputTags = sideOutputTags;
    this.stepContext = stepContext;
    this.aggregatorFactory = aggregatorFactory;
    this.windowingStrategy = windowingStrategy;
  }

  public PushbackSideInputDoFnRunner<InputT, OutputT> createRunner(
      ReadyCheckingSideInputReader sideInputReader) {
    DoFnRunner<InputT, OutputT> underlying = DoFnRunners.createDefault(
        options, fn, sideInputReader, outputManager, mainOutputTag,
        sideOutputTags, stepContext, aggregatorFactory, windowingStrategy);
    return PushbackSideInputDoFnRunner.create(underlying, sideInputs, sideInputReader);
  }

}
