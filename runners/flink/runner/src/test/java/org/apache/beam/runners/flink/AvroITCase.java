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

package org.apache.beam.runners.flink;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;

import com.google.common.base.Joiner;

import org.apache.flink.test.util.JavaProgramTestBase;

import java.io.File;
import java.net.URI;


public class AvroITCase extends JavaProgramTestBase {

  protected String resultPath;
  protected String tmpPath;

  public AvroITCase(){
  }

  static final String[] EXPECTED_RESULT = new String[] {
      "Joe red 3",
      "Mary blue 4",
      "Mark green 1",
      "Julia purple 5"
  };

  @Override
  protected void preSubmit() throws Exception {
    resultPath = getTempDirPath("result");
    tmpPath = getTempDirPath("tmp");

    // need to create the dirs, otherwise Beam sinks don't
    // work for these tests
    if (!new File(new URI(tmpPath)).mkdirs()) {
      throw new RuntimeException("Could not create temp output dir.");
    }

    if (!new File(new URI(resultPath)).mkdirs()) {
      throw new RuntimeException("Could not create output dir.");
    }
  }

  @Override
  protected void postSubmit() throws Exception {
    compareResultsByLinesInMemory(Joiner.on('\n').join(EXPECTED_RESULT), resultPath);
  }

  @Override
  protected void testProgram() throws Exception {
    runProgram(tmpPath, resultPath);
  }

  private static void runProgram(String tmpPath, String resultPath) throws Exception {
    Pipeline p = FlinkTestPipeline.createForBatch();

    p
        .apply(Create.of(
            new User("Joe", 3, "red"),
            new User("Mary", 4, "blue"),
            new User("Mark", 1, "green"),
            new User("Julia", 5, "purple"))
            .withCoder(AvroCoder.of(User.class)))
        .apply(AvroIO.Write.to(new URI(tmpPath).getPath() + "/part")
            .withSchema(User.class));

    p.run();

    p = FlinkTestPipeline.createForBatch();

    p
        .apply(AvroIO.Read.from(tmpPath + "/*").withSchema(User.class))
        .apply(ParDo.of(new DoFn<User, String>() {
          @Override
          public void processElement(ProcessContext c) throws Exception {
            User u = c.element();
            String result = u.getName() + " " + u.getFavoriteColor() + " " + u.getFavoriteNumber();
            c.output(result);
          }
        }))
        .apply(TextIO.Write.to(new URI(resultPath).getPath() + "/part"));


    p.run();
  }

  private static class User {

    private String name;
    private int favoriteNumber;
    private String favoriteColor;

    public User() {}

    public User(String name, int favoriteNumber, String favoriteColor) {
      this.name = name;
      this.favoriteNumber = favoriteNumber;
      this.favoriteColor = favoriteColor;
    }

    public String getName() {
      return name;
    }

    public String getFavoriteColor() {
      return favoriteColor;
    }

    public int getFavoriteNumber() {
      return favoriteNumber;
    }
  }

}

