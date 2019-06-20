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
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import javax.sql.DataSource;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.io.common.DatabaseTestHelper;
import org.apache.beam.sdk.io.common.NetworkTestHelper;
import org.apache.beam.sdk.io.common.TestRow;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.transforms.Select;
import org.apache.beam.sdk.testing.ExpectedLogs;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.Wait;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList;
import org.apache.commons.dbcp2.PoolingDataSource;
import org.apache.derby.drda.NetworkServerControl;
import org.apache.derby.jdbc.ClientDataSource;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Test on the JdbcIO. */
@RunWith(JUnit4.class)
public class JdbcIOTest implements Serializable {

  private static final Logger LOG = LoggerFactory.getLogger(JdbcIOTest.class);
  private static final int EXPECTED_ROW_COUNT = 1000;
  private static final String BACKOFF_TABLE = "UT_WRITE_BACKOFF";

  private static NetworkServerControl derbyServer;
  private static ClientDataSource dataSource;

  private static int port;
  private static String readTableName;

  @Rule public final transient TestPipeline pipeline = TestPipeline.create();

  @Rule public final transient ExpectedLogs expectedLogs = ExpectedLogs.none(JdbcIO.class);

  @BeforeClass
  public static void beforeClass() throws Exception {
    port = NetworkTestHelper.getAvailableLocalPort();
    LOG.info("Starting Derby database on {}", port);

    // by default, derby uses a lock timeout of 60 seconds. In order to speed up the test
    // and detect the lock faster, we decrease this timeout
    System.setProperty("derby.locks.waitTimeout", "2");
    System.setProperty("derby.stream.error.file", "target/derby.log");

    derbyServer = new NetworkServerControl(InetAddress.getByName("localhost"), port);
    StringWriter out = new StringWriter();
    derbyServer.start(new PrintWriter(out));
    boolean started = false;
    int count = 0;
    // Use two different methods to detect when server is started:
    // 1) Check the server stdout for the "started" string
    // 2) wait up to 15 seconds for the derby server to start based on a ping
    // on faster machines and networks, this may return very quick, but on slower
    // networks where the DNS lookups are slow, this may take a little time
    while (!started && count < 30) {
      if (out.toString().contains("started")) {
        started = true;
      } else {
        count++;
        Thread.sleep(500);
        try {
          derbyServer.ping();
          started = true;
        } catch (Throwable t) {
          // ignore, still trying to start
        }
      }
    }

    dataSource = new ClientDataSource();
    dataSource.setCreateDatabase("create");
    dataSource.setDatabaseName("target/beam");
    dataSource.setServerName("localhost");
    dataSource.setPortNumber(port);

    readTableName = DatabaseTestHelper.getTestTableName("UT_READ");

    DatabaseTestHelper.createTable(dataSource, readTableName);
    addInitialData(dataSource, readTableName);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    try {
      DatabaseTestHelper.deleteTable(dataSource, readTableName);
    } finally {
      if (derbyServer != null) {
        derbyServer.shutdown();
      }
    }
  }

  @Test
  public void testDataSourceConfigurationDataSource() throws Exception {
    JdbcIO.DataSourceConfiguration config = JdbcIO.DataSourceConfiguration.create(dataSource);
    try (Connection conn = config.buildDatasource().getConnection()) {
      assertTrue(conn.isValid(0));
    }
  }

  @Test
  public void testDataSourceConfigurationDataSourceWithoutPool() {
    assertTrue(
        JdbcIO.DataSourceConfiguration.create(dataSource).buildDatasource()
            instanceof ClientDataSource);
  }

  @Test
  public void testDataSourceConfigurationDataSourceWithPool() {
    assertTrue(
        JdbcIO.PoolableDataSourceProvider.of(JdbcIO.DataSourceConfiguration.create(dataSource))
                .apply(null)
            instanceof PoolingDataSource);
  }

  @Test
  public void testDataSourceConfigurationDriverAndUrl() throws Exception {
    JdbcIO.DataSourceConfiguration config =
        JdbcIO.DataSourceConfiguration.create(
            "org.apache.derby.jdbc.ClientDriver",
            "jdbc:derby://localhost:" + port + "/target/beam");
    try (Connection conn = config.buildDatasource().getConnection()) {
      assertTrue(conn.isValid(0));
    }
  }

  @Test
  public void testDataSourceConfigurationUsernameAndPassword() throws Exception {
    String username = "sa";
    String password = "sa";
    JdbcIO.DataSourceConfiguration config =
        JdbcIO.DataSourceConfiguration.create(
                "org.apache.derby.jdbc.ClientDriver",
                "jdbc:derby://localhost:" + port + "/target/beam")
            .withUsername(username)
            .withPassword(password);
    try (Connection conn = config.buildDatasource().getConnection()) {
      assertTrue(conn.isValid(0));
    }
  }

  @Test
  public void testDataSourceConfigurationNullPassword() throws Exception {
    String username = "sa";
    String password = null;
    JdbcIO.DataSourceConfiguration config =
        JdbcIO.DataSourceConfiguration.create(
                "org.apache.derby.jdbc.ClientDriver",
                "jdbc:derby://localhost:" + port + "/target/beam")
            .withUsername(username)
            .withPassword(password);
    try (Connection conn = config.buildDatasource().getConnection()) {
      assertTrue(conn.isValid(0));
    }
  }

  @Test
  public void testDataSourceConfigurationNullUsernameAndPassword() throws Exception {
    String username = null;
    String password = null;
    JdbcIO.DataSourceConfiguration config =
        JdbcIO.DataSourceConfiguration.create(
                "org.apache.derby.jdbc.ClientDriver",
                "jdbc:derby://localhost:" + port + "/target/beam")
            .withUsername(username)
            .withPassword(password);
    try (Connection conn = config.buildDatasource().getConnection()) {
      assertTrue(conn.isValid(0));
    }
  }

  /** Create test data that is consistent with that generated by TestRow. */
  private static void addInitialData(DataSource dataSource, String tableName) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement preparedStatement =
          connection.prepareStatement(String.format("insert into %s values (?,?)", tableName))) {
        for (int i = 0; i < EXPECTED_ROW_COUNT; i++) {
          preparedStatement.clearParameters();
          preparedStatement.setInt(1, i);
          preparedStatement.setString(2, TestRow.getNameForSeed(i));
          preparedStatement.executeUpdate();
        }
      }
      connection.commit();
    }
  }

  @Test
  public void testRead() {
    PCollection<TestRow> rows =
        pipeline.apply(
            JdbcIO.<TestRow>read()
                .withFetchSize(12)
                .withDataSourceConfiguration(JdbcIO.DataSourceConfiguration.create(dataSource))
                .withQuery("select name,id from " + readTableName)
                .withRowMapper(new JdbcTestHelper.CreateTestRowOfNameAndId())
                .withCoder(SerializableCoder.of(TestRow.class)));

    PAssert.thatSingleton(rows.apply("Count All", Count.globally()))
        .isEqualTo((long) EXPECTED_ROW_COUNT);

    Iterable<TestRow> expectedValues = TestRow.getExpectedValues(0, EXPECTED_ROW_COUNT);
    PAssert.that(rows).containsInAnyOrder(expectedValues);

    pipeline.run();
  }

  @Test
  public void testReadWithSingleStringParameter() {
    PCollection<TestRow> rows =
        pipeline.apply(
            JdbcIO.<TestRow>read()
                .withDataSourceConfiguration(JdbcIO.DataSourceConfiguration.create(dataSource))
                .withQuery(String.format("select name,id from %s where name = ?", readTableName))
                .withStatementPreparator(
                    preparedStatement -> preparedStatement.setString(1, TestRow.getNameForSeed(1)))
                .withRowMapper(new JdbcTestHelper.CreateTestRowOfNameAndId())
                .withCoder(SerializableCoder.of(TestRow.class)));

    PAssert.thatSingleton(rows.apply("Count All", Count.globally())).isEqualTo(1L);

    Iterable<TestRow> expectedValues = Collections.singletonList(TestRow.fromSeed(1));
    PAssert.that(rows).containsInAnyOrder(expectedValues);

    pipeline.run();
  }

  @Test
  public void testReadRows() {
    SerializableFunction<Void, DataSource> dataSourceProvider = ignored -> dataSource;
    PCollection<Row> rows =
        pipeline.apply(
            JdbcIO.readRows()
                .withDataSourceProviderFn(dataSourceProvider)
                .withQuery(String.format("select name,id from %s where name = ?", readTableName))
                .withStatementPreparator(
                    preparedStatement ->
                        preparedStatement.setString(1, TestRow.getNameForSeed(1))));

    Schema expectedSchema =
        Schema.of(
            Schema.Field.of("NAME", LogicalTypes.variableLengthString(JDBCType.VARCHAR, 500))
                .withNullable(true),
            Schema.Field.of("ID", Schema.FieldType.INT32).withNullable(true));

    assertEquals(expectedSchema, rows.getSchema());

    PCollection<Row> output = rows.apply(Select.fieldNames("NAME", "ID"));
    PAssert.that(output)
        .containsInAnyOrder(
            ImmutableList.of(Row.withSchema(expectedSchema).addValues("Testval1", 1).build()));

    pipeline.run();
  }

  @Test
  public void testReadWithSchema() {
    SerializableFunction<Void, DataSource> dataSourceProvider = ignored -> dataSource;
    JdbcIO.RowMapper<RowWithSchema> rowMapper =
        rs -> new RowWithSchema(rs.getString("NAME"), rs.getInt("ID"));
    pipeline.getSchemaRegistry().registerJavaBean(RowWithSchema.class);

    PCollection<RowWithSchema> rows =
        pipeline.apply(
            JdbcIO.<RowWithSchema>read()
                .withDataSourceProviderFn(dataSourceProvider)
                .withQuery(String.format("select name,id from %s where name = ?", readTableName))
                .withRowMapper(rowMapper)
                .withCoder(SerializableCoder.of(RowWithSchema.class))
                .withStatementPreparator(
                    preparedStatement ->
                        preparedStatement.setString(1, TestRow.getNameForSeed(1))));

    Schema expectedSchema =
        Schema.of(
            Schema.Field.of("name", Schema.FieldType.STRING),
            Schema.Field.of("id", Schema.FieldType.INT32));

    assertEquals(expectedSchema, rows.getSchema());

    PCollection<Row> output = rows.apply(Select.fieldNames("name", "id"));
    PAssert.that(output)
        .containsInAnyOrder(
            ImmutableList.of(Row.withSchema(expectedSchema).addValues("Testval1", 1).build()));

    pipeline.run();
  }

  @Test
  public void testWrite() throws Exception {
    final long rowsToAdd = 1000L;

    String tableName = DatabaseTestHelper.getTestTableName("UT_WRITE");
    DatabaseTestHelper.createTable(dataSource, tableName);
    try {
      ArrayList<KV<Integer, String>> data = getDataToWrite(rowsToAdd);
      pipeline.apply(Create.of(data)).apply(getJdbcWrite(tableName));

      pipeline.run();

      assertRowCount(tableName, EXPECTED_ROW_COUNT);
    } finally {
      DatabaseTestHelper.deleteTable(dataSource, tableName);
    }
  }

  @Test
  public void testWriteWithResultsAndWaitOn() throws Exception {
    final long rowsToAdd = 1000L;

    String firstTableName = DatabaseTestHelper.getTestTableName("UT_WRITE");
    String secondTableName = DatabaseTestHelper.getTestTableName("UT_WRITE_AFTER_WAIT");
    DatabaseTestHelper.createTable(dataSource, firstTableName);
    DatabaseTestHelper.createTable(dataSource, secondTableName);
    try {
      ArrayList<KV<Integer, String>> data = getDataToWrite(rowsToAdd);

      PCollection<KV<Integer, String>> dataCollection = pipeline.apply(Create.of(data));
      PCollection<Void> rowsWritten =
          dataCollection.apply(getJdbcWrite(firstTableName).withResults());
      dataCollection.apply(Wait.on(rowsWritten)).apply(getJdbcWrite(secondTableName));

      pipeline.run();

      assertRowCount(firstTableName, EXPECTED_ROW_COUNT);
      assertRowCount(secondTableName, EXPECTED_ROW_COUNT);
    } finally {
      DatabaseTestHelper.deleteTable(dataSource, firstTableName);
    }
  }

  private static JdbcIO.Write<KV<Integer, String>> getJdbcWrite(String tableName) {
    return JdbcIO.<KV<Integer, String>>write()
        .withDataSourceConfiguration(
            JdbcIO.DataSourceConfiguration.create(
                "org.apache.derby.jdbc.ClientDriver",
                "jdbc:derby://localhost:" + port + "/target/beam"))
        .withStatement(String.format("insert into %s values(?, ?)", tableName))
        .withBatchSize(10L)
        .withPreparedStatementSetter(
            (element, statement) -> {
              statement.setInt(1, element.getKey());
              statement.setString(2, element.getValue());
            });
  }

  private static ArrayList<KV<Integer, String>> getDataToWrite(long rowsToAdd) {
    ArrayList<KV<Integer, String>> data = new ArrayList<>();
    for (int i = 0; i < rowsToAdd; i++) {
      KV<Integer, String> kv = KV.of(i, "Test");
      data.add(kv);
    }
    return data;
  }

  private static void assertRowCount(String tableName, int expectedRowCount) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (Statement statement = connection.createStatement()) {
        try (ResultSet resultSet = statement.executeQuery("select count(*) from " + tableName)) {
          resultSet.next();
          int count = resultSet.getInt(1);
          Assert.assertEquals(expectedRowCount, count);
        }
      }
    }
  }

  @Test
  public void testWriteWithBackoff() throws Exception {
    String tableName = DatabaseTestHelper.getTestTableName("UT_WRITE_BACKOFF");
    DatabaseTestHelper.createTable(dataSource, tableName);

    // lock table
    Connection connection = dataSource.getConnection();
    Statement lockStatement = connection.createStatement();
    lockStatement.execute("ALTER TABLE " + tableName + " LOCKSIZE TABLE");
    lockStatement.execute("LOCK TABLE " + tableName + " IN EXCLUSIVE MODE");

    // start a first transaction
    connection.setAutoCommit(false);
    PreparedStatement insertStatement =
        connection.prepareStatement("insert into " + tableName + " values(?, ?)");
    insertStatement.setInt(1, 1);
    insertStatement.setString(2, "TEST");
    insertStatement.execute();

    // try to write to this table
    pipeline
        .apply(Create.of(Collections.singletonList(KV.of(1, "TEST"))))
        .apply(
            JdbcIO.<KV<Integer, String>>write()
                .withDataSourceConfiguration(
                    JdbcIO.DataSourceConfiguration.create(
                        "org.apache.derby.jdbc.ClientDriver",
                        "jdbc:derby://localhost:" + port + "/target/beam"))
                .withStatement(String.format("insert into %s values(?, ?)", tableName))
                .withRetryStrategy(
                    (JdbcIO.RetryStrategy)
                        e -> {
                          return "XJ208"
                              .equals(e.getSQLState()); // we fake a deadlock with a lock here
                        })
                .withPreparedStatementSetter(
                    (element, statement) -> {
                      statement.setInt(1, element.getKey());
                      statement.setString(2, element.getValue());
                    }));

    // starting a thread to perform the commit later, while the pipeline is running into the backoff
    Thread commitThread =
        new Thread(
            () -> {
              try {
                Thread.sleep(10000);
                connection.commit();
              } catch (Exception e) {
                // nothing to do
              }
            });
    commitThread.start();
    pipeline.run();
    commitThread.join();

    // we verify the the backoff has been called thanks to the log message
    expectedLogs.verifyWarn("Deadlock detected, retrying");

    assertRowCount(tableName, 2);
  }

  @After
  public void tearDown() {
    try {
      DatabaseTestHelper.deleteTable(dataSource, BACKOFF_TABLE);
    } catch (Exception e) {
      // nothing to do
    }
  }

  @Test
  public void testWriteWithEmptyPCollection() {
    pipeline
        .apply(Create.empty(KvCoder.of(VarIntCoder.of(), StringUtf8Coder.of())))
        .apply(
            JdbcIO.<KV<Integer, String>>write()
                .withDataSourceConfiguration(
                    JdbcIO.DataSourceConfiguration.create(
                        "org.apache.derby.jdbc.ClientDriver",
                        "jdbc:derby://localhost:" + port + "/target/beam"))
                .withStatement("insert into BEAM values(?, ?)")
                .withPreparedStatementSetter(
                    (element, statement) -> {
                      statement.setInt(1, element.getKey());
                      statement.setString(2, element.getValue());
                    }));

    pipeline.run();
  }
}
