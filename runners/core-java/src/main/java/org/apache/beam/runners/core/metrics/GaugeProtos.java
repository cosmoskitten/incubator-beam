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
package org.apache.beam.runners.core.metrics;

import static java.time.Instant.ofEpochMilli;

import org.apache.beam.sdk.metrics.GaugeResult;
import org.apache.beam.vendor.grpc.v1p13p1.com.google.protobuf.Timestamp;
import org.joda.time.Instant;

/** Convert gauges between protobuf and SDK representations. */
public class GaugeProtos {
  public static GaugeResult fromProto(long value, Timestamp timestamp) {
    return GaugeResult.create(
        value, new Instant(timestamp.getSeconds() * 1000 + timestamp.getNanos() / 1000));
  }

  /**
   * In proto form, the {@link GaugeResult#getTimestamp timestamp} goes in the {@link
   * org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfo}'s top level, so we just
   * initialize a {@link SimpleMonitoringInfoBuilder} here.
   */
  public static SimpleMonitoringInfoBuilder toProto(GaugeResult gauge) {
    return new SimpleMonitoringInfoBuilder()
        .setTimestamp(ofEpochMilli(gauge.getTimestamp().getMillis()))
        .setInt64TypeUrn()
        .setInt64Value(gauge.getValue());
  }
}
