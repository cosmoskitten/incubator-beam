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
package org.apache.beam.runners.dataflow.util;

import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.options.PipelineOptionsFactory;

/* Standalone program to upload files to GCS, for testing in isolation. */
public class GCSUploadMain {
  public static void main(String[] args) {
    DataflowPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args).as(DataflowPipelineOptions.class);
    FileSystems.setDefaultPipelineOptions(options);
    GcsStager stager = GcsStager.fromOptions(options);
    stager.stageFiles(options.getFilesToStage());
  }
}
