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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;

/**
 * Simple POJO representing a filter for querying metrics.
 */
@Experimental(Kind.METRICS)
@AutoValue
public abstract class MetricsFilter {

  public Set<String> steps() {
    return immutableSteps();
  }

  public Set<MetricNameFilter> names() {
    return immutableNames();
  }

  protected abstract ImmutableSet<String> immutableSteps();
  protected abstract ImmutableSet<MetricNameFilter> immutableNames();

  public static Builder builder() {
    return new AutoValue_MetricsFilter.Builder();
  }

  /**
   * Builder for creating a {@link MetricsFilter}.
   */
  @AutoValue.Builder
  public abstract static class Builder {

    protected abstract ImmutableSet.Builder<MetricNameFilter> immutableNamesBuilder();
    protected abstract ImmutableSet.Builder<String> immutableStepsBuilder();

    /**
     * Add a {@link MetricNameFilter}.
     *
     * <p>If no name filters are specified then metrics will be returned regardless of what name
     * they have.
     */
    public Builder addNameFilter(MetricNameFilter nameFilter) {
      immutableNamesBuilder().add(nameFilter);
      return this;
    }

    /**
     * Add a step filter.
     *
     * <p>If no steps are specified then metrics will be included regardless of which step
     * came from.
     */
    public Builder addStep(String step) {
      immutableStepsBuilder().add(step);
      return this;
    }

    public abstract MetricsFilter build();
  }
}
