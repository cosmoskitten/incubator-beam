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
package org.apache.beam.sdk.metrics;

import com.fasterxml.jackson.annotation.JsonFilter;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;

/**
 * The results of a single current metric. TODO(BEAM-6265): Decouple wire formats from internal
 * formats, remove usage of MetricName.
 */
@Experimental(Kind.METRICS)
@JsonFilter("committedMetrics")
public abstract class MetricResult<T> {
  /** Return the name of the metric. */
  public MetricName getName() {
    return getKey().metricName();
  };

  public abstract MetricKey getKey();

  /**
   * Return the value of this metric across all successfully completed parts of the pipeline.
   *
   * <p>Not all runners will support committed metrics. If they are not supported, the runner will
   * throw an {@link UnsupportedOperationException}.
   */
  @Nullable
  public abstract T getCommitted();

  /** Return the value of this metric across all attempts of executing all parts of the pipeline. */
  @Nullable
  public abstract T getAttempted();

  public <V> MetricResult<V> transform(Function<T, V> fn) {
    return MetricResult.create(
        getKey(),
        getCommitted() == null ? null : fn.apply(getCommitted()),
        getAttempted() == null ? null : fn.apply(getAttempted()));
  }

  public static <T> MetricResult<T> create(MetricKey key, T committed, T attempted) {
    return DefaultMetricResult.create(key, committed, attempted);
  }
}
