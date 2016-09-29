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
package org.apache.beam.sdk.io.jdbc;

import static org.junit.Assert.assertEquals;

import java.io.Serializable;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;

import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.testing.NeedsRunner;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.derby.drda.NetworkServerControl;
import org.apache.derby.jdbc.ClientDataSource;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test on the JdbcIO.
 */
public class JdbcIOTest implements Serializable {

  private static final Logger LOGGER = LoggerFactory.getLogger(JdbcIOTest.class);

  private ClientDataSource dataSource;

  @Before
  public void setup() throws Exception {
    System.setProperty("derby.stream.error.file", "target/derby.log");

    NetworkServerControl derbyServer = new NetworkServerControl(InetAddress.getByName
        ("localhost"), 1527);
    derbyServer.start(null);

    dataSource = new ClientDataSource();
    dataSource.setCreateDatabase("create");
    dataSource.setDatabaseName("target/beam");
    dataSource.setServerName("localhost");
    dataSource.setPortNumber(1527);

    try (Connection connection = dataSource.getConnection()) {

      try (Statement statement = connection.createStatement()) {
        try {
          statement.executeUpdate("create table BEAM(id INT, name VARCHAR(500))");
        } catch (Exception e) {
          LOGGER.warn("Can't create table BEAM, it probably already exists", e);
        }
      }

      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("delete from BEAM");
      }

      String[] scientists = {"Einstein", "Darwin", "Copernicus", "Pasteur", "Curie", "Faraday",
          "Newton", "Bohr", "Galilei", "Maxwell"};
      for (int i = 0; i < 1000; i++) {
        int index = i % scientists.length;
        try (PreparedStatement preparedStatement = connection.prepareStatement("insert into BEAM "
            + "values (?,?)")) {
          preparedStatement.setInt(1, i);
          preparedStatement.setString(2, scientists[index]);
          preparedStatement.executeUpdate();
        }
      }

      connection.commit();
    }
  }

  @Test
  @Category(NeedsRunner.class)
  public void testReadWithDataSource() throws Exception {
    TestPipeline pipeline = TestPipeline.create();

    PCollection<KV<String, Integer>> output = pipeline.apply(
        JdbcIO.read()
            .withDataSource(dataSource)
            .withQuery("select name,id from BEAM")
            .withRowMapper(new JdbcIO.RowMapper<KV<String, Integer>>() {
              @Override
              public KV<String, Integer> mapRow(ResultSet resultSet) throws Exception {
                  KV<String, Integer> kv =
                      KV.of(resultSet.getString("name"), resultSet.getInt("id"));
                  return kv;
              }
            }))
        .setCoder(KvCoder.of(
            StringUtf8Coder.of(), SerializableCoder.of(Integer.class)));

    PAssert.thatSingleton(
        output.apply("Count All", Count.<KV<String, Integer>>globally()))
        .isEqualTo(1000L);

    PAssert.that(output
        .apply("Count Scientist", Count.<String, Integer>perKey())
    ).satisfies(new SerializableFunction<Iterable<KV<String, Long>>, Void>() {
      @Override
      public Void apply(Iterable<KV<String, Long>> input) {
        for (KV<String, Long> element : input) {
          assertEquals(100L, element.getValue().longValue());
        }
        return null;
      }
    });

    pipeline.run();
  }

  @Test
  @Category(NeedsRunner.class)
  public void testReadWithConfiguration() throws Exception {
    TestPipeline pipeline = TestPipeline.create();

    PCollection<KV<String, Integer>> output = pipeline.apply(
        JdbcIO.read()
            .withJdbcDriverClassName("org.apache.derby.jdbc.ClientDriver")
            .withJdbcUrl("jdbc:derby://localhost:1527/target/beam")
            .withQuery("select name,id from BEAM")
            .withRowMapper(new JdbcIO.RowMapper<KV<String, Integer>>() {
              @Override
              public KV<String, Integer> mapRow(ResultSet resultSet) throws Exception {
                KV<String, Integer> kv = KV.of(resultSet.getString("name"), resultSet.getInt("id"));
                return kv;
              }
            }))
        .setCoder(KvCoder.of(StringUtf8Coder.of(), SerializableCoder.of(Integer.class)));

    PAssert.thatSingleton(
        output.apply("Count All", Count.<KV<String, Integer>>globally()))
        .isEqualTo(1000L);

    PAssert.that(output
        .apply("Count Scientist", Count.<String, Integer>perKey())
    ).satisfies(new SerializableFunction<Iterable<KV<String, Long>>, Void>() {
      @Override
      public Void apply(Iterable<KV<String, Long>> input) {
        for (KV<String, Long> element : input) {
          assertEquals(100L, element.getValue().longValue());
        }
        return null;
      }
    });

    pipeline.run();
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteWithConfiguration() throws Exception {
    TestPipeline pipeline = TestPipeline.create();

    ArrayList<KV<Integer, String>> data = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      KV<Integer, String> kv = KV.of(i, "Test");
      data.add(kv);
    }
    pipeline.apply(Create.of(data))
        .apply(JdbcIO.write()
            .withJdbcDriverClassName("org.apache.derby.jdbc.ClientDriver")
            .withJdbcUrl("jdbc:derby://localhost:1527/target/beam")
            .withStatement("insert into BEAM values(?, ?)")
            .withPreparedStatementSetter(new JdbcIO.PreparedStatementSetter<KV<Integer, String>>() {
              public void setParameters(KV<Integer, String> element, PreparedStatement statement)
                  throws Exception {
                statement.setInt(1, element.getKey());
                statement.setString(2, element.getValue());
              }
            }));

    pipeline.run();

    try (Connection connection = dataSource.getConnection()) {
      try (Statement statement = connection.createStatement()) {
        try (ResultSet resultSet = statement.executeQuery("select count(*) from BEAM")) {
          resultSet.next();
          int count = resultSet.getInt(1);

          Assert.assertEquals(2000, count);
        }
      }
    }
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteWithEmptyPCollection() throws Exception {
    TestPipeline pipeline = TestPipeline.create();

    pipeline.apply(Create.of(new ArrayList<KV<Integer, String>>()))
        .apply(JdbcIO.write()
            .withJdbcDriverClassName("org.apache.derby.jdbc.ClientDriver")
            .withJdbcUrl("jdbc:derby://localhost:1527/target/beam")
            .withStatement("insert into BEAM values(?, ?)")
            .withPreparedStatementSetter(new JdbcIO.PreparedStatementSetter<KV<Integer, String>>() {
              public void setParameters(KV<Integer, String> element, PreparedStatement statement)
                  throws Exception {
                statement.setInt(1, element.getKey());
                statement.setString(2, element.getValue());
              }
            }));

    pipeline.run();
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteWithDataSource() throws Exception {
    TestPipeline pipeline = TestPipeline.create();

    ArrayList<KV<Integer, String>> data = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      KV<Integer, String> kv = KV.of(i, "Test");
      data.add(kv);
    }
    pipeline.apply(Create.of(data))
        .apply(JdbcIO.write().withDataSource(dataSource)
            .withStatement("insert into BEAM values(?, ?)")
            .withPreparedStatementSetter(new JdbcIO.PreparedStatementSetter<KV<Integer, String>>() {
              public void setParameters(KV<Integer, String> element, PreparedStatement statement)
                  throws Exception {
                statement.setInt(1, element.getKey());
                statement.setString(2, element.getValue());
              }
            }));

    pipeline.run();

    try (Connection connection = dataSource.getConnection()) {
      try (Statement statement = connection.createStatement()) {
        try (ResultSet resultSet = statement.executeQuery("select count(*) from BEAM")) {
          resultSet.next();
          int count = resultSet.getInt(1);

          Assert.assertEquals(2000, count);
        }
      }
    }
  }

  @After
  public void cleanup() throws Exception {
    try {
      try (Connection connection = dataSource.getConnection()) {
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("drop table BEAM");
        }
      }
    } catch (Exception e) {
      // nothing to do
    }
  }

}
