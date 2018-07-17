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

import static org.apache.beam.sdk.extensions.sql.impl.JdbcDriver.CONNECT_STRING_PREFIX;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import sqlline.SqlLine;
import sqlline.SqlLine.Status;

/** {@link BeamSqlLine} provides default arguments to SqlLine. */
public class BeamSqlLine {

  private static final String NICKNAME = "BeamSQL";

  public static void main(String[] args) throws IOException {
    runSqlLine(checkConnectionArgs(args), null, null, null);
  }

  private static String[] checkConnectionArgs(String[] args) {
    List<String> argsList = new ArrayList<String>(Arrays.asList(args));

    if (!argsList.contains("-nn")) {
      argsList.add("-nn");
      argsList.add(NICKNAME);
    }

    if (!argsList.contains("-u")) {
      argsList.add("-u");
      argsList.add(CONNECT_STRING_PREFIX);
    }

    return argsList.toArray(new String[argsList.size()]);
  }

  static Status runSqlLine(
      String[] args,
      InputStream inputStream,
      @Nullable PrintStream outputStream,
      @Nullable PrintStream errorStream)
      throws IOException {
    SqlLine sqlLine = new SqlLine();

    if (outputStream != null) {
      sqlLine.setOutputStream(outputStream);
    }

    if (errorStream != null) {
      sqlLine.setErrorStream(errorStream);
    }

    return sqlLine.begin(args, inputStream, true);
  }

  // static void testMain(String[] args, PrintStream outputStream) throws IOException {
  //   List<String> wrappedArgList = checkConnectionArgs(args);
  //   SqlLine sqlLine = new SqlLine();
  //   sqlLine.setOutputStream(outputStream);
  //   sqlLine.begin(wrappedArgList.toArray(new String[wrappedArgList.size()]), null, true);
  // }
}
