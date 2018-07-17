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

import static java.util.stream.Collectors.toList;
import static org.hamcrest.CoreMatchers.everyItem;
import static org.junit.Assert.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hamcrest.collection.IsIn;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Test for {@link org.apache.beam.sdk.extensions.sql.jdbc.BeamSqlLine}. Note that this test only
 * tests for crashes (due to ClassNotFoundException for example). It does not test output.
 */
public class BeamSqlLineTest {
  private static final String NAME_ARG = "-nn";
  private static final String NICKNAME = "BeamSQL";
  private static final String CONNECT_ARG = "-u";
  private static final String CONNECT_STRING_PREFIX = "jdbc:beam:";
  private static final String QUERY_ARG = "-e";

  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void testSqlLine_emptyArgs() throws Exception {
    BeamSqlLine.main(new String[] {});
  }

  @Test
  public void testSqlLine_nullCommand() throws Exception {
    BeamSqlLine.main(new String[] {"-e", ""});
  }

  @Test
  public void testSqlLine_simple() throws Exception {
    BeamSqlLine.main(new String[] {"-e", "SELECT 1;"});
  }

  @Test
  public void testSqlLine_parse() throws Exception {
    BeamSqlLine.main(new String[] {"-e", "SELECT 'beam';"});
  }

  @Test
  public void testSqlLine_ddl() throws Exception {
    BeamSqlLine.main(
        new String[] {
          "-e", "CREATE TABLE test (id INTEGER) TYPE 'text';", "-e", "DROP TABLE test;"
        });
  }

  @Test
  public void classLoader_readFile() throws Exception {
    File simpleTable = folder.newFile();

    BeamSqlLine.main(
        new String[] {
          "-e",
          "CREATE TABLE test (id INTEGER) TYPE 'text' LOCATION '"
              + simpleTable.getAbsolutePath()
              + "';",
          "-e",
          "SELECT * FROM test;",
          "-e",
          "DROP TABLE test;"
        });
  }

  @Test
  public void testSqlLine_select() throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    String[] args = buildArgs("SELECT 3, 'hello', DATE '2018-05-28';");

    BeamSqlLine.runSqlLine(args, null, new PrintStream(byteArrayOutputStream), null);

    List<List<String>> lines = toLines(byteArrayOutputStream);
    assertThat(
        Arrays.asList(Arrays.asList("", "3", "hello", "2018-05-28")),
        everyItem(IsIn.isOneOf(lines.toArray())));
  }

  @Test
  public void testSqlLine_selectFromTable() throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    String[] args =
        buildArgs(
            "CREATE TABLE table_test (col_a VARCHAR, col_b VARCHAR, "
                + "col_c VARCHAR, col_x TINYINT, col_y INT, col_z BIGINT) TYPE 'test';",
            "INSERT INTO table_test VALUES ('a', 'b', 'c', 1, 2, 3);",
            "SELECT * FROM table_test;");

    BeamSqlLine.runSqlLine(args, null, new PrintStream(byteArrayOutputStream), null);

    List<List<String>> lines = toLines(byteArrayOutputStream);
    assertThat(
        Arrays.asList(
            Arrays.asList("", "col_a", "col_b", "col_c", "col_x", "col_y", "col_z"),
            Arrays.asList("", "a", "b", "c", "1", "2", "3")),
        everyItem(IsIn.isOneOf(lines.toArray())));
  }

  @Test
  public void testSqlLine_insertSelect() throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    String[] args =
        buildArgs(
            "CREATE TABLE table_test (col_a VARCHAR, col_b VARCHAR) TYPE 'test';",
            "INSERT INTO table_test SELECT '3', 'hello';",
            "SELECT * FROM table_test;");

    BeamSqlLine.runSqlLine(args, null, new PrintStream(byteArrayOutputStream), null);

    List<List<String>> lines = toLines(byteArrayOutputStream);
    assertThat(
        Arrays.asList(Arrays.asList("", "3", "hello")), everyItem(IsIn.isOneOf(lines.toArray())));
  }

  @Test
  public void testSqlLine_GroupBy() throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    String[] args =
        buildArgs(
            "CREATE TABLE table_test (col_a VARCHAR, col_b VARCHAR) TYPE 'test';",
            "INSERT INTO table_test SELECT '3', 'foo';",
            "INSERT INTO table_test SELECT '3', 'bar';",
            "INSERT INTO table_test SELECT '4', 'foo';",
            "SELECT col_a, count(*) FROM table_test GROUP BY col_a;");

    BeamSqlLine.runSqlLine(args, null, new PrintStream(byteArrayOutputStream), null);

    List<List<String>> lines = toLines(byteArrayOutputStream);
    assertThat(
        Arrays.asList(Arrays.asList("", "3", "2"), Arrays.asList("", "4", "1")),
        everyItem(IsIn.isOneOf(lines.toArray())));
  }

  @Test
  public void testSqlLine_fixedWindow() throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    String[] args =
        buildArgs(
            "CREATE TABLE table_test (col_a VARCHAR, col_b TIMESTAMP) TYPE 'test';",
            "INSERT INTO table_test SELECT '3', TIMESTAMP '2018-07-01 21:26:06';",
            "INSERT INTO table_test SELECT '3', TIMESTAMP '2018-07-01 21:26:07';",
            "SELECT TUMBLE_START(col_b, INTERVAL '1' SECOND), count(*) FROM table_test "
                + "GROUP BY TUMBLE(col_b, INTERVAL '1' SECOND);");

    BeamSqlLine.runSqlLine(args, null, new PrintStream(byteArrayOutputStream), null);

    List<List<String>> lines = toLines(byteArrayOutputStream);
    assertThat(
        Arrays.asList(
            Arrays.asList("", "2018-07-01 21:26:06", "1"),
            Arrays.asList("", "2018-07-01 21:26:07", "1")),
        everyItem(IsIn.isOneOf(lines.toArray())));
  }

  @Test
  public void testSqlLine_slidingWindow() throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    String[] args =
        buildArgs(
            "CREATE TABLE table_test (col_a VARCHAR, col_b TIMESTAMP) TYPE 'test';",
            "INSERT INTO table_test SELECT '3', TIMESTAMP '2018-07-01 21:26:06';",
            "INSERT INTO table_test SELECT '4', TIMESTAMP '2018-07-01 21:26:07';",
            "INSERT INTO table_test SELECT '6', TIMESTAMP '2018-07-01 21:26:08';",
            "INSERT INTO table_test SELECT '7', TIMESTAMP '2018-07-01 21:26:09';",
            "SELECT HOP_END(col_b, INTERVAL '1' SECOND, INTERVAL '2' SECOND), count(*) FROM "
                + "table_test GROUP BY HOP(col_b, INTERVAL '1' SECOND, INTERVAL '2' SECOND);");

    BeamSqlLine.runSqlLine(args, null, new PrintStream(byteArrayOutputStream), null);

    List<List<String>> lines = toLines(byteArrayOutputStream);
    assertThat(
        Arrays.asList(
            Arrays.asList("", "2018-07-01 21:26:07", "1"),
            Arrays.asList("", "2018-07-01 21:26:08", "2"),
            Arrays.asList("", "2018-07-01 21:26:09", "2"),
            Arrays.asList("", "2018-07-01 21:26:10", "2"),
            Arrays.asList("", "2018-07-01 21:26:11", "1")),
        everyItem(IsIn.isOneOf(lines.toArray())));
  }

  private String[] buildArgs(String... strs) {
    List<String> argsList = new ArrayList();
    argsList.add(NAME_ARG);
    argsList.add(NICKNAME);
    argsList.add(CONNECT_ARG);
    argsList.add(CONNECT_STRING_PREFIX);

    for (String str : strs) {
      argsList.add(QUERY_ARG);
      argsList.add(str);
    }

    return argsList.toArray(new String[argsList.size()]);
  }

  private List<List<String>> toLines(ByteArrayOutputStream outputStream) {
    List<String> outputLines = Arrays.asList(outputStream.toString().split("\n"));
    return outputLines.stream().map(BeamSqlLineTest::splitFields).collect(toList());
  }

  private static List<String> splitFields(String outputLine) {
    return Arrays.stream(outputLine.split("\\|")).map(field -> field.trim()).collect(toList());
  }
}
