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
package org.apache.beam.runners.flink.translation.wrappers;

import org.apache.beam.runners.flink.translation.utils.SerializedPipelineOptions;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.io.Source;
import org.apache.beam.sdk.options.PipelineOptions;

import org.apache.flink.api.common.io.DefaultInputSplitAssigner;
import org.apache.flink.api.common.io.InputFormat;
import org.apache.flink.api.common.io.statistics.BaseStatistics;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.InputSplitAssigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;


/**
 * A Flink {@link org.apache.flink.api.common.io.InputFormat} that wraps a
 * Dataflow {@link org.apache.beam.sdk.io.Source}.
 */
public class SourceInputFormat<T> implements InputFormat<T, SourceInputSplit<T>> {
  private static final Logger LOG = LoggerFactory.getLogger(SourceInputFormat.class);

  private final BoundedSource<T> initialSource;

  private transient PipelineOptions options;
  private final SerializedPipelineOptions serializedOptions;

  private transient BoundedSource.BoundedReader<T> reader;
  private boolean inputAvailable = false;

  public SourceInputFormat(BoundedSource<T> initialSource, PipelineOptions options) {
    this.initialSource = initialSource;
    this.serializedOptions = new SerializedPipelineOptions(options);
  }

  @Override
  public void configure(Configuration configuration) {
    options = serializedOptions.getPipelineOptions();
  }

  @Override
  public void open(SourceInputSplit<T> sourceInputSplit) throws IOException {
    reader = ((BoundedSource<T>) sourceInputSplit.getSource()).createReader(options);
    inputAvailable = reader.start();
  }

  @Override
  public BaseStatistics getStatistics(BaseStatistics baseStatistics) throws IOException {
    try {
      final long estimatedSize = initialSource.getEstimatedSizeBytes(options);

      return new BaseStatistics() {
        @Override
        public long getTotalInputSize() {
          return estimatedSize;
        }

        @Override
        public long getNumberOfRecords() {
          return BaseStatistics.NUM_RECORDS_UNKNOWN;
        }

        @Override
        public float getAverageRecordWidth() {
          return BaseStatistics.AVG_RECORD_BYTES_UNKNOWN;
        }
      };
    } catch (Exception e) {
      LOG.warn("Could not read Source statistics: {}", e);
    }

    return null;
  }

  @Override
  @SuppressWarnings("unchecked")
  public SourceInputSplit<T>[] createInputSplits(int numSplits) throws IOException {
    try {
      long desiredSizeBytes = initialSource.getEstimatedSizeBytes(options) / numSplits;
      List<? extends Source<T>> shards = initialSource.splitIntoBundles(desiredSizeBytes, options);
      int numShards = shards.size();
      SourceInputSplit<T>[] sourceInputSplits = new SourceInputSplit[numShards];
      for (int i = 0; i < numShards; i++) {
        sourceInputSplits[i] = new SourceInputSplit<>(shards.get(i), i);
      }
      return sourceInputSplits;
    } catch (Exception e) {
      throw new IOException("Could not create input splits from Source.", e);
    }
  }

  @Override
  public InputSplitAssigner getInputSplitAssigner(final SourceInputSplit[] sourceInputSplits) {
    return new DefaultInputSplitAssigner(sourceInputSplits);
  }


  @Override
  public boolean reachedEnd() throws IOException {
    return !inputAvailable;
  }

  @Override
  public T nextRecord(T t) throws IOException {
    if (inputAvailable) {
      final T current = reader.getCurrent();
      // advance reader to have a record ready next time
      inputAvailable = reader.advance();
      return current;
    }

    return null;
  }

  @Override
  public void close() throws IOException {
    if (reader != null) {
      reader.close();
    }
  }
}
