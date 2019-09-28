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
package org.apache.beam.runners.dataflow.worker.counters;

import com.google.api.services.dataflow.model.CounterUpdate;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.beam.runners.dataflow.worker.MetricsToCounterUpdateConverter.Kind;

public class CounterUpdateAggregators {

  private static final Map<String, CounterUpdateAggregator> aggregators =
      ImmutableMap.of(
          Kind.SUM.toString(), new SumCounterUpdateAggregator(),
          Kind.MEAN.toString(), new MeanCounterUpdateAggregator(),
          Kind.DISTRIBUTION.toString(), new DistributionCounterUpdateAggregator());

  private static String getCounterUpdateKind(CounterUpdate counterUpdate) {
    if (counterUpdate.getStructuredNameAndMetadata() != null
        && counterUpdate.getStructuredNameAndMetadata().getMetadata() != null) {
      return counterUpdate.getStructuredNameAndMetadata().getMetadata().getKind();
    }
    if (counterUpdate.getNameAndKind() != null) {
      return counterUpdate.getNameAndKind().getKind();
    }
    throw new IllegalArgumentException(
        "CounterUpdate must have either StructuredNameAndMetadata or NameAndKind.");
  }

  public static List<CounterUpdate> aggregate(List<CounterUpdate> counterUpdates) {
    if (counterUpdates == null || counterUpdates.isEmpty()) {
      return counterUpdates;
    }
    CounterUpdate first = counterUpdates.get(0);
    String kind = getCounterUpdateKind(first);
    if (aggregators.containsKey(kind)) {
      // Return list containing combined CounterUpdate
      return Collections.singletonList(aggregators.get(kind).aggregate(counterUpdates));
    }
    // not able to aggregate the counter updates.
    return counterUpdates;
  }
}
