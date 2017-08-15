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

package org.apache.beam.runners.core.construction;

import com.google.auto.service.AutoService;
import java.util.Collections;
import org.apache.beam.sdk.PipelineRunner;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsRegistrar;
import org.apache.beam.sdk.runners.PipelineRunnerRegistrar;

/**
 * Registrars for the {@link JobApiRunner} and associated options.
 */
public class BeamJobApiRunnerRegistrar {
  /** The {@link PipelineRunnerRegistrar} for the {@link JobApiRunner}. */
  @AutoService(PipelineRunnerRegistrar.class)
  public static class JobApiRunnerRegistrar implements PipelineRunnerRegistrar {
    @Override
    public Iterable<Class<? extends PipelineRunner<?>>> getPipelineRunners() {
      return Collections.<Class<? extends PipelineRunner<?>>>singletonList(JobApiRunner.class);
    }
  }

  /** The {@link PipelineOptionsRegistrar} for {@link BeamJobApiOptions}. */
  @AutoService(PipelineOptionsRegistrar.class)
  public static class JobApiOptionsRegistrar implements PipelineOptionsRegistrar {
    @Override
    public Iterable<Class<? extends PipelineOptions>> getPipelineOptions() {
      return Collections.<Class<? extends PipelineOptions>>singleton(BeamJobApiOptions.class);
    }
  }
}
