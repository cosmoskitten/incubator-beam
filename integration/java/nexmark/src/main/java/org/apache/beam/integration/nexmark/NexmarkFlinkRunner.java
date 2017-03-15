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
package org.apache.beam.integration.nexmark;

import javax.annotation.Nullable;
import org.apache.beam.runners.flink.FlinkRunnerResult;
import org.apache.beam.sdk.PipelineResult;

/**
 * Run a query using the Flink runner.
 */
public class NexmarkFlinkRunner extends NexmarkRunner<NexmarkFlinkDriver.NexmarkFlinkOptions> {
  @Override
  protected boolean isStreaming() {
    return options.isStreaming();
  }

  @Override
  protected int coresPerWorker() {
    return 4;
  }

  @Override
  protected int maxNumWorkers() {
    return 5;
  }

  @Override
  protected boolean canMonitor() {
    return false;
  }

  @Override
  protected void invokeBuilderForPublishOnlyPipeline(
      PipelineBuilder builder) {
    builder.build(options);
  }

  @Override
  protected void waitForPublisherPreload() {
    throw new UnsupportedOperationException();
  }

  @Override
  @Nullable
  protected NexmarkPerf monitor(NexmarkQuery query) {
    return null;
  }

  public NexmarkFlinkRunner(NexmarkFlinkDriver.NexmarkFlinkOptions options) {
    super(options);
  }
}
