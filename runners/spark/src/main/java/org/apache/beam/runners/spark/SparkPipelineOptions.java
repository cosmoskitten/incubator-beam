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

package org.apache.beam.runners.spark;

import org.apache.beam.sdk.options.ApplicationNameOptions;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.StreamingOptions;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.spark.api.java.JavaSparkContext;

/**
 * Spark runner pipeline options.
 */
public interface SparkPipelineOptions extends PipelineOptions, StreamingOptions,
                                              ApplicationNameOptions {
  @Description("The url of the spark master to connect to, (e.g. spark://host:port, local[4]).")
  @Default.String("local[1]")
  String getSparkMaster();

  void setSparkMaster(String master);

  @Override
  @Default.Boolean(false)
  boolean isStreaming();

  @Override
  @Default.String("spark dataflow pipeline job")
  String getAppName();

  @Description("If the spark runner will be initialized with a provided Spark Context")
  @Default.Boolean(false)
  boolean getUsesProvidedSparkContext();
  void setUsesProvidedSparkContext(boolean value);

  @Description("Provided Java Spark Context")
  @JsonIgnore
  JavaSparkContext getProvidedSparkContext();
  void setProvidedSparkContext(JavaSparkContext jsc);

}
