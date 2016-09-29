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
package org.apache.beam.sdk.io;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import org.apache.beam.sdk.io.DecoratedFileSink.WriterOutputDecorator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link WriterOutputGzipDecoratorFactory} and associated {@link WriterOutputDecorator}.
 */
@RunWith(JUnit4.class)
public class WriterOutputGzipDecoratorFactoryTest {
  @Rule
  public TemporaryFolder tmpFolder = new TemporaryFolder();

  @Test
  public void testCreateAndWrite() throws FileNotFoundException, IOException {
    final WriterOutputGzipDecoratorFactory factory = WriterOutputGzipDecoratorFactory.getInstance();
    final File file = tmpFolder.newFile("test.gz");
    final OutputStream fos = new FileOutputStream(file);
    final WriterOutputDecorator decorator = factory.create(fos);
    decorator.write("abc\n".getBytes(StandardCharsets.UTF_8));
    decorator.out.write("123\n".getBytes(StandardCharsets.UTF_8));
    decorator.finish();
    decorator.close();
    // Read Gzipped data back in using standard API.
    final BufferedReader br =
        new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(file)),
            StandardCharsets.UTF_8.name()));
    assertEquals("First line should read 'abc'", "abc", br.readLine());
    assertEquals("Second line should read '123'", "123", br.readLine());
  }

}
