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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Sets;
import java.net.URI;
import javax.annotation.Nullable;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.util.gcsfs.GcsPath;
import org.apache.commons.lang3.SystemUtils;
import org.apache.http.client.utils.URIUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link FileSystems}.
 */
@RunWith(JUnit4.class)
public class FileSystemsTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testSpecToURIWindowsOS() throws Exception {
    if (SystemUtils.IS_OS_WINDOWS) {
      assertEquals(
          "file:///C:/home",
          FileSystems.specToURI("C:\\home").toString());

      assertEquals(
          "file:///C:/home/*",
          FileSystems.specToURI("C:\\home\\*").toString());
    } else {
      // Skip tests
    }
  }

  @Test
  public void testSpecToURI() throws Exception {
    if (!SystemUtils.IS_OS_WINDOWS) {
      // Test path without scheme.
      assertEquals(
          "/home/output",
          FileSystems.specToURI("/home/output").toString());
      // Test path with scheme.
      assertEquals(
          "file:///home/output",
          FileSystems.specToURI("file:///home/output").toString());
      assertEquals(
          "file:/C:/home%20dir/a%20b.txt",
          FileSystems.specToURI("file:/C:/home dir/a b.txt").toString());
      // Test asterisks.
      assertEquals(
          "/home/output/**/*",
          FileSystems.specToURI("/home/output/**/*").toString());
      // Test escaped chars.
      assertEquals(
          "/home/out%20put/*",
          FileSystems.specToURI("/home/out put/*").toString());
    } else {
      // Skip tests
    }
  }

  @Test
  public void testSpecToURINonLocalFileSystem() throws Exception {
    assertEquals(
        "gs://my%20bucket/output",
        FileSystems.specToURI("gs://my bucket/output").toString());
    assertEquals(
        "s3://my%20bucket/output/*",
        FileSystems.specToURI("s3://my bucket/output/*").toString());
  }

  @Test
  public void testGetLowerCaseScheme() throws Exception {
    assertEquals("gs", FileSystems.getLowerCaseScheme("gs://bucket/output"));
    assertEquals("gs", FileSystems.getLowerCaseScheme("GS://bucket/output"));
    assertEquals("file", FileSystems.getLowerCaseScheme("/home/output"));
    assertEquals("file", FileSystems.getLowerCaseScheme("file:///home/output"));
    assertEquals("file", FileSystems.getLowerCaseScheme("C:\\home\\output"));
    assertEquals("file", FileSystems.getLowerCaseScheme("C:\\home\\output"));
  }

  @Test
  public void testSetDefaultConfig() throws Exception {
    PipelineOptions first = PipelineOptionsFactory.create();
    PipelineOptions second = PipelineOptionsFactory.create();
    FileSystems.setDefaultConfig("file", first);
    assertEquals(first, FileSystems.getDefaultConfig("file"));
    assertEquals(first, FileSystems.getDefaultConfig("FILE"));

    FileSystems.setDefaultConfig("FILE", second);
    assertNotEquals(first, FileSystems.getDefaultConfig("file"));
    assertNotEquals(first, FileSystems.getDefaultConfig("FILE"));
    assertEquals(second, FileSystems.getDefaultConfig("file"));
    assertEquals(second, FileSystems.getDefaultConfig("FILE"));
  }

  @Test
  public void testSetDefaultConfigNotFound() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("No FileSystemRegistrar found for scheme: [gs-s3].");
    FileSystems.setDefaultConfig("gs-s3", PipelineOptionsFactory.create());
  }

  @Test
  public void testSetDefaultConfigInvalidScheme() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Scheme: [gs:] doesn't match URI syntax");
    FileSystems.setDefaultConfig("gs:", PipelineOptionsFactory.create());
  }

  @Test
  public void testGetLocalFileSystem() throws Exception {
    assertTrue(
        FileSystems.getFileSystemInternal(URI.create("~/home/")) instanceof LocalFileSystem);
    assertTrue(
        FileSystems.getFileSystemInternal(URI.create("file://home")) instanceof LocalFileSystem);
    assertTrue(
        FileSystems.getFileSystemInternal(URI.create("FILE://home")) instanceof LocalFileSystem);
    assertTrue(
        FileSystems.getFileSystemInternal(URI.create("File://home")) instanceof LocalFileSystem);
  }

  @Test
  public void testVerifySchemesAreUnique() throws Exception {
    thrown.expect(RuntimeException.class);
    thrown.expectMessage("Scheme: [file] has conflicting registrars");
    FileSystems.verifySchemesAreUnique(
        Sets.<FileSystemRegistrar>newHashSet(
            new LocalFileSystemRegistrar(),
            new FileSystemRegistrar() {
              @Override
              public FileSystem fromOptions(@Nullable PipelineOptions options) {
                return null;
              }

              @Override
              public String getScheme() {
                return "FILE";
              }
            }));
  }
}
