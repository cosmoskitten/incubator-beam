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

package org.apache.beam.runners.samza;

import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.PipelineRunner;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsValidator;
import org.apache.commons.io.FileUtils;
import org.apache.samza.config.JobConfig;
import org.apache.samza.config.factories.PropertiesConfigFactory;

/**
 * Test {@link SamzaRunner}.
 */
public class TestSamzaRunner extends PipelineRunner<PipelineResult> {
  private final SamzaRunner delegate;

  public static TestSamzaRunner fromOptions(PipelineOptions options) {
    return new TestSamzaRunner(createSamzaPipelineOptions(options));
  }

  public static SamzaPipelineOptions createSamzaPipelineOptions(PipelineOptions options) {
    try {
      final SamzaPipelineOptions samzaOptions = PipelineOptionsValidator
          .validate(SamzaPipelineOptions.class, options);
      final PropertiesConfigFactory configFactory = new PropertiesConfigFactory();
      final URI configUri = TestSamzaRunner.class.getClassLoader()
          .getResource("samza-conf.properties").toURI();
      final Map<String, String> config = new HashMap<>(configFactory.getConfig(configUri));
      final File storeDir = Paths.get(
          System.getProperty("java.io.tmpdir"), "beam-samza-test").toFile();
      //  Re-create the folder for test stores
      FileUtils.deleteDirectory(storeDir);
      storeDir.mkdir();

      config.put(JobConfig.JOB_LOGGED_STORE_BASE_DIR(), storeDir.getAbsolutePath());
      config.put(JobConfig.JOB_NON_LOGGED_STORE_BASE_DIR(), storeDir.getAbsolutePath());
      samzaOptions.setSamzaConfig(config);
      return samzaOptions;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public TestSamzaRunner(SamzaPipelineOptions options) {
    this.delegate = SamzaRunner.fromOptions(options);
  }

  @Override
  public PipelineResult run(Pipeline pipeline) {
    try {
      final PipelineResult result = delegate.run(pipeline);
      result.waitUntilFinish();
      return result;
    } catch (Throwable t) {
      // Search for AssertionError. If present use it as the cause of the pipeline failure.
      Throwable current = t;

      while (current != null) {
        if (current instanceof AssertionError) {
          throw (AssertionError) current;
        }
        current = current.getCause();
      }

      throw t;
    }
  }
}
