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

import static org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfo.MonitoringInfoLabels.PCOLLECTION;
import static org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfo.MonitoringInfoLabels.PTRANSFORM;
import static org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfoTypeUrns.Enum.DISTRIBUTION_INT64_TYPE;
import static org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfoTypeUrns.Enum.LATEST_INT64_TYPE;
import static org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfoTypeUrns.Enum.SUM_INT64_TYPE;
import static org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfoUrns.Enum;
import static org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfoUrns.Enum.ELEMENT_COUNT;
import static org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfoUrns.Enum.FINISH_BUNDLE_MSECS;
import static org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfoUrns.Enum.PROCESS_BUNDLE_MSECS;
import static org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfoUrns.Enum.START_BUNDLE_MSECS;
import static org.apache.beam.model.fnexecution.v1.BeamFnApi.labelProps;
import static org.apache.beam.sdk.metrics.BeamUrns.getUrn;
import static org.apache.beam.vendor.guava.v20_0.com.google.common.base.Preconditions.checkArgument;

import org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfo.MonitoringInfoLabels;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.MonitoringInfoLabelProps;
import org.apache.beam.vendor.guava.v20_0.com.google.common.base.Strings;

/** Utility for parsing a URN to a {@link org.apache.beam.sdk.metrics.MetricName}. */
public class MetricUrns {
  public static final String ELEMENT_COUNT_URN = getUrn(ELEMENT_COUNT);
  public static final String START_BUNDLE_MSECS_URN = getUrn(START_BUNDLE_MSECS);
  public static final String PROCESS_BUNDLE_MSECS_URN = getUrn(PROCESS_BUNDLE_MSECS);
  public static final String FINISH_BUNDLE_MSECS_URN = getUrn(FINISH_BUNDLE_MSECS);
  public static final String USER_METRIC_URN_PREFIX = getUrn(Enum.USER_METRIC_URN_PREFIX);
  public static final String SUM_INT64_TYPE_URN = getUrn(SUM_INT64_TYPE);
  public static final String DISTRIBUTION_INT64_TYPE_URN = getUrn(DISTRIBUTION_INT64_TYPE);
  public static final String LATEST_INT64_TYPE_URN = getUrn(LATEST_INT64_TYPE);
  public static final String PCOLLECTION_LABEL = getLabelString(PCOLLECTION);
  public static final String PTRANSFORM_LABEL = getLabelString(PTRANSFORM);

  /** @return The metric URN for a user metric, with a proper URN prefix. */
  public static String urn(String namespace, String name) {
    checkArgument(namespace != null, "Metric namespace must be non-null");
    checkArgument(!Strings.isNullOrEmpty(name), "Metric name must be non-empty");
    String fixedMetricNamespace = namespace.replace(':', '_');
    String fixedMetricName = name.replace(':', '_');
    return String.format("%s%s:%s", USER_METRIC_URN_PREFIX, fixedMetricNamespace, fixedMetricName);
  }

  /** Returns the label string constant defined in the MonitoringInfoLabel enum proto. */
  private static String getLabelString(MonitoringInfoLabels label) {
    MonitoringInfoLabelProps props =
        label.getValueDescriptor().getOptions().getExtension(labelProps);
    return props.getName();
  }
}
