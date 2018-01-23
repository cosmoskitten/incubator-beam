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

package org.apache.beam.sdk.io;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PInput;
import org.apache.beam.sdk.values.POutput;
import org.apache.beam.sdk.values.PValue;
import org.apache.beam.sdk.values.TupleTag;

/** The result of a {@link WriteFiles} transform. */
public class WriteFilesResult<DestinationT> implements POutput {
  private final Pipeline pipeline;
  private final TupleTag<KV<DestinationT, String>> perDestinationOutputFilenamesTag;
  private final PCollection<KV<DestinationT, String>> perDestinationOutputFilenames;

  private WriteFilesResult(
      Pipeline pipeline,
      TupleTag<KV<DestinationT, String>> perDestinationOutputFilenamesTag,
      PCollection<KV<DestinationT, String>> perDestinationOutputFilenames) {
    this.pipeline = pipeline;
    this.perDestinationOutputFilenamesTag = perDestinationOutputFilenamesTag;
    this.perDestinationOutputFilenames = perDestinationOutputFilenames;
  }

  static <DestinationT> WriteFilesResult<DestinationT> in(
      Pipeline pipeline,
      TupleTag<KV<DestinationT, String>> perDestinationOutputFilenamesTag,
      PCollection<KV<DestinationT, String>> perDestinationOutputFilenames) {
    return new WriteFilesResult<>(
        pipeline,
        perDestinationOutputFilenamesTag,
        perDestinationOutputFilenames);
  }

  @Override
  public Map<TupleTag<?>, PValue> expand() {
    return ImmutableMap.of(perDestinationOutputFilenamesTag, perDestinationOutputFilenames);
  }

  @Override
  public Pipeline getPipeline() {
    return pipeline;
  }

  @Override
  public void finishSpecifyingOutput(
      String transformName, PInput input, PTransform<?, ?> transform) {}

  /**
   * Returns a {@link PCollection} of all output filenames generated by this {@link WriteFiles}
   * organized by user destination type.
   */
  public PCollection<KV<DestinationT, String>>  getPerDestinationOutputFilenames() {
    return perDestinationOutputFilenames;
  }
}

