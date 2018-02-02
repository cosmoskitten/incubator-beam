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

import java.io.DataOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.xml.ws.http.HTTPException;

/** HTTP Sink to push metrics in a POST HTTP request. */
public class MetricsHttpSink extends MetricsSink<String> {
  private final String urlString;

  /** @param urlString the URL of the endpoint */
  public MetricsHttpSink(String urlString) {
      this.urlString = urlString;
  }

  @Override
  protected MetricsSerializer<String> provideSerializer() {
    return new JsonMetricsSerializer();
  }

  @Override
  protected void writeSerializedMetrics(String metrics) throws Exception {
    URL url = new URL(urlString);
    byte[] postData = metrics.getBytes(StandardCharsets.UTF_8);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setDoOutput(true);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setRequestProperty("charset", "utf-8");
    connection.setRequestProperty("Content-Length", Integer.toString(postData.length));
    connection.setUseCaches(false);
    try (DataOutputStream connectionOuputStream =
        new DataOutputStream(connection.getOutputStream())) {
      connectionOuputStream.write(postData);
    }
    int responseCode = connection.getResponseCode();
    if (responseCode != 200){
      throw new MetricsPusher.MetricsPushException(new HTTPException(responseCode));
    }
  }
}
