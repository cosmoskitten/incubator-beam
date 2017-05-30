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
import java.util.Map;
import org.apache.beam.runners.core.construction.PTransformTranslation.TransformPayloadTranslator;
import org.apache.beam.sdk.common.runner.v1.RunnerApi;
import org.apache.beam.sdk.common.runner.v1.RunnerApi.FunctionSpec;
import org.apache.beam.sdk.io.WriteFiles;
import org.apache.beam.sdk.runners.AppliedPTransform;
import org.apache.beam.sdk.transforms.PTransform;

/**
 * Utility methods for translating a {@link WriteFiles} to and from {@link RunnerApi}
 * representations.
 */
public class WriteFilesTranslation {

  static class WriteFilesTranslator implements TransformPayloadTranslator<WriteFiles<?>> {
    @Override
    public String getUrn(WriteFiles<?> transform) {
      return PTransformTranslation.WRITE_FILES_TRANSFORM_URN;
    }

    @Override
    public FunctionSpec translate(
        AppliedPTransform<?, ?, WriteFiles<?>> transform, SdkComponents components) {
      return FunctionSpec.newBuilder()
          .setUrn(getUrn(transform.getTransform()))
          .build();
    }
  }


  /**
   * Registers {@link WriteFilesTranslator}.
   */
  @AutoService(TransformPayloadTranslatorRegistrar.class)
  public static class Registrar implements TransformPayloadTranslatorRegistrar {
    @Override
    public Map<? extends Class<? extends PTransform>, ? extends TransformPayloadTranslator>
    getTransformPayloadTranslators() {
      return Collections.singletonMap(WriteFiles.class, new WriteFilesTranslator());
    }
  }
}
