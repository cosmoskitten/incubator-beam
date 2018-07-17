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
package org.apache.beam.sdk.extensions.sql.jdbc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.beam.sdk.extensions.sql.jdbc.BeamSqlLineTestingUtils.buildArgs;
import static org.apache.beam.sdk.extensions.sql.jdbc.BeamSqlLineTestingUtils.toLines;
import static org.hamcrest.CoreMatchers.everyItem;
import static org.junit.Assert.assertThat;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.gcp.pubsub.TestPubsub;
import org.hamcrest.collection.IsIn;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/** BeamSqlLine integration tests. */
public class BeamSqlLineIT implements Serializable {

  @Rule public transient TestPubsub eventsTopic = TestPubsub.create();

  private static final Integer numberOfThreads = 2;
  private static String createPubsubTableStatement;
  private static String readFromPubsub;
  private static String filterForSouthManhattan;
  private static PubsubMessageJSONStringConstructor constructor;
  private static ExecutorService pool;

  @BeforeClass
  public static void setUp() {
    pool = Executors.newFixedThreadPool(numberOfThreads);

    createPubsubTableStatement =
        "CREATE TABLE taxi_rides (\n"
            + "         event_timestamp TIMESTAMP,\n"
            + "         attributes MAP<VARCHAR, VARCHAR>,\n"
            + "         payload ROW<\n"
            + "           ride_id VARCHAR,\n"
            + "           point_idx INT,\n"
            + "           latitude DOUBLE,\n"
            + "           longitude DOUBLE,\n"
            + "           meter_reading DOUBLE,\n"
            + "           meter_increment DOUBLE,\n"
            + "           ride_status VARCHAR,\n"
            + "           passenger_count TINYINT>)\n"
            + "       TYPE pubsub \n"
            + "       LOCATION '%s'\n"
            + "       TBLPROPERTIES '{\"timestampAttributeKey\": \"ts\"}';";

    readFromPubsub =
        "SELECT event_timestamp, taxi_rides.payload.ride_status, taxi_rides.payload.latitude, "
            + "taxi_rides.payload.longitude from taxi_rides LIMIT 3;";

    filterForSouthManhattan =
        "SELECT event_timestamp, taxi_rides.payload.ride_status, \n"
            + "taxi_rides.payload.latitude, taxi_rides.payload.longitude from taxi_rides\n"
            + "       WHERE taxi_rides.payload.longitude > -74.747\n"
            + "         AND taxi_rides.payload.longitude < -73.969\n"
            + "         AND taxi_rides.payload.latitude > 40.699\n"
            + "         AND taxi_rides.payload.latitude < 40.720 LIMIT 2;";

    constructor =
        new PubsubMessageJSONStringConstructor(
            "ride_id",
            "point_idx",
            "latitude",
            "longitude",
            "meter_reading",
            "meter_increment",
            "ride_status",
            "passenger_count");
  }

  @AfterClass
  public static void tearDown() {
    pool.shutdown();
  }

  @Test
  public void testSelectFromPubsub() throws Exception {
    Future<List<List<String>>> expectedResult =
        pool.submit(
            (Callable)
                () -> {
                  String[] args =
                      buildArgs(
                          String.format(createPubsubTableStatement, eventsTopic.topicPath()),
                          readFromPubsub);

                  ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                  BeamSqlLine.runSqlLine(args, null, outputStream, null);
                  return toLines(outputStream);
                });

    // Wait 10 sec to allow creating a subscription.
    Thread.sleep(10 * 1000);
    List<PubsubMessage> messages =
        ImmutableList.of(
            message(
                convertTimestampToMillis("2018-07-01 21:25:20"),
                constructor.construct("id1", 1, 40.702, -74.001, 1000, 10, "enroute", 2)),
            message(
                convertTimestampToMillis("2018-07-01 21:26:06"),
                constructor.construct("id2", 2, 40.703, -74.002, 1000, 10, "enroute", 4)),
            message(
                convertTimestampToMillis("2018-07-02 13:26:06"),
                constructor.construct("id3", 3, 30.0, -72.32324, 2000, 20, "enroute", 7)));

    eventsTopic.publish(messages);

    assertThat(
        Arrays.asList(
            Arrays.asList("2018-07-01 21:25:20", "enroute", "40.702", "-74.001"),
            Arrays.asList("2018-07-01 21:26:06", "enroute", "40.703", "-74.002"),
            Arrays.asList("2018-07-02 13:26:06", "enroute", "30.0", "-72.32324")),
        everyItem(IsIn.isOneOf(expectedResult.get(30, TimeUnit.SECONDS).toArray())));
  }

  @Test
  public void testFilterForSouthManhattan() throws Exception {
    Future<List<List<String>>> expectedResult =
        pool.submit(
            (Callable)
                () -> {
                  String[] args =
                      buildArgs(
                          String.format(createPubsubTableStatement, eventsTopic.topicPath()),
                          filterForSouthManhattan);

                  ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                  BeamSqlLine.runSqlLine(args, null, outputStream, null);
                  return toLines(outputStream);
                });

    // Wait 10 sec to allow creating a subscription.
    Thread.sleep(10 * 1000);
    List<PubsubMessage> messages =
        ImmutableList.of(
            message(
                convertTimestampToMillis("2018-07-01 21:25:20"),
                constructor.construct("id1", 1, 40.701, -74.001, 1000, 10, "enroute", 2)),
            message(
                convertTimestampToMillis("2018-07-01 21:26:06"),
                constructor.construct("id2", 2, 40.702, -74.002, 1000, 10, "enroute", 4)),
            message(
                convertTimestampToMillis("2018-07-02 13:26:06"),
                constructor.construct("id3", 3, 30, -72.32324, 2000, 20, "enroute", 7)),
            message(
                convertTimestampToMillis("2018-07-02 14:28:22"),
                constructor.construct("id4", 4, 34, -73.32324, 2000, 20, "enroute", 8)));

    eventsTopic.publish(messages);

    assertThat(
        Arrays.asList(
            Arrays.asList("2018-07-01 21:25:20", "enroute", "40.701", "-74.001"),
            Arrays.asList("2018-07-01 21:26:06", "enroute", "40.702", "-74.002")),
        everyItem(IsIn.isOneOf(expectedResult.get(30, TimeUnit.SECONDS).toArray())));
  }

  private long convertTimestampToMillis(String timestamp) throws ParseException {
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    return dateFormat.parse(timestamp).getTime();
  }

  private PubsubMessage message(long timestampInMillis, String jsonPayload) {
    return new PubsubMessage(
        jsonPayload.getBytes(UTF_8), ImmutableMap.of("ts", String.valueOf(timestampInMillis)));
  }

  private static class PubsubMessageJSONStringConstructor {
    private List<String> messageSchema;

    public PubsubMessageJSONStringConstructor(String... schemas) {
      ImmutableList.Builder<String> builder = ImmutableList.<String>builder();
      for (String schema : schemas) {
        builder.add(schema);
      }

      messageSchema = builder.build();
    }

    public String construct(Object... values) throws IllegalArgumentException {
      if (values.length != messageSchema.size()) {
        throw new IllegalArgumentException(
            String.format(
                "length of values %d does not match " + "with size of schema %d",
                values.length, messageSchema.size()));
      }

      JSONObject jsonObject = new JSONObject();
      for (int i = 0; i < values.length; i++) {
        jsonObject.put(messageSchema.get(i), values[i]);
      }

      return jsonObject.toString();
    }
  }
}
