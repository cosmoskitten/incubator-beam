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
package org.apache.beam.sdk.extensions.tpc;

//import org.apache.beam.runners.dataflow.options.DataflowPipelineWorkerPoolOptions;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.Validation;

/** PipelineOptions for Tpc benchmark launcher. */
public interface TpcOptions extends PipelineOptions {
  @Description("Root path of the csv files to read from")
  @Validation.Required
  String getInputFile();

  void setInputFile(String value);

  @Description("DS or H")
  @Validation.Required
  String getTable();

  void setTable(String value);

  @Description("Query no.")
  @Validation.Required
  Integer getQuery();

  void setQuery(Integer value);

  /** Set this required option to specify where to write the output. */
  @Description("Path of the file to write to")
  //    @Validation.Required
  String getOutput();

  void setOutput(String value);
}
