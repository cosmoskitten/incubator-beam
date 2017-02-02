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

import java.nio.file.Paths;
import org.apache.commons.lang3.SystemUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link LocalResourceId}.
 */
@RunWith(JUnit4.class)
public class LocalResourceIdTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testResolve() throws Exception {
    if (SystemUtils.IS_OS_WINDOWS) {
      // Skip tests
      return;
    }
    // Tests for local files without the scheme.
    assertEquals(
        toResourceIdentifier("/root/tmp/aa"),
        toResourceIdentifier("/root/tmp/").resolve("aa"));
    assertEquals(
        toResourceIdentifier("/root/tmp/aa/bb/cc/"),
        toResourceIdentifier("/root/tmp/").resolve("aa/").resolve("bb/").resolve("cc/"));

    // Tests absolute path.
    assertEquals(
        toResourceIdentifier("/root/tmp/aa"),
        toResourceIdentifier("/root/tmp/bb/").resolve("/root/tmp/aa"));

    // Tests empty authority and path.
    assertEquals(
        toResourceIdentifier("file:/aa"),
        toResourceIdentifier("file:///").resolve("aa"));

    // Tests path with unicode
    assertEquals(
        toResourceIdentifier("/根目录/输出 文件01.txt"),
        toResourceIdentifier("/根目录/").resolve("输出 文件01.txt"));
    assertEquals(
        toResourceIdentifier("file://根目录/输出 文件01.txt"),
        toResourceIdentifier("file://根目录/").resolve("输出 文件01.txt"));
  }

  @Test
  public void testResolveNormalization() throws Exception {
    if (SystemUtils.IS_OS_WINDOWS) {
      // Skip tests
      return;
    }
    // Tests normalization of "." and ".."
    //
    // Normalization is the implementation choice of LocalResourceId,
    // and it is not required by ResourceId.resolve().
    assertEquals(
        toResourceIdentifier("file://home/bb"),
        toResourceIdentifier("file://root/../home/output/../")
            .resolve("aa/")
            .resolve("../")
            .resolve("bb"));
    assertEquals(
        toResourceIdentifier("file://root/aa/bb"),
        toResourceIdentifier("file://root/./")
            .resolve("aa/")
            .resolve("./")
            .resolve("bb"));
    assertEquals(
        toResourceIdentifier("aa/bb"),
        toResourceIdentifier("a/../")
            .resolve("aa/")
            .resolve("./")
            .resolve("bb"));
    assertEquals(
        toResourceIdentifier("/aa/bb"),
        toResourceIdentifier("/a/../")
            .resolve("aa/")
            .resolve("./")
            .resolve("bb"));

    // Tests "./", "../", "~/".
    assertEquals(
        toResourceIdentifier("aa/bb"),
        toResourceIdentifier("./")
            .resolve("aa/")
            .resolve("./")
            .resolve("bb"));
    assertEquals(
        toResourceIdentifier("../aa/bb"),
        toResourceIdentifier("../")
            .resolve("aa/")
            .resolve("./")
            .resolve("bb"));
    assertEquals(
        toResourceIdentifier("~/aa/bb/"),
        toResourceIdentifier("~/")
            .resolve("aa/")
            .resolve("./")
            .resolve("bb/"));
  }

  @Test
  public void testResolveInvalidNotDirectory() throws Exception {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Expected the path is a directory, but had [/root/tmp].");
    toResourceIdentifier("/root/tmp").resolve("aa");
  }

  @Test
  public void testResolveInWindowsOS() throws Exception {
    if (!SystemUtils.IS_OS_WINDOWS) {
      // Skip tests
      return;
    }
    assertEquals(
        toResourceIdentifier("C:\\my home\\out put"),
        toResourceIdentifier("C:\\my home\\").resolve("out put"));

    assertEquals(
        toResourceIdentifier("C:\\out put"),
        toResourceIdentifier("C:\\my home\\")
            .resolve("..\\")
            .resolve(".\\")
            .resolve("out put"));

    assertEquals(
        toResourceIdentifier("C:\\my home\\**\\*"),
        toResourceIdentifier("C:\\my home\\")
            .resolve("**")
            .resolve("*"));
  }

  @Test
  public void testGetCurrentDirectory() throws Exception {
    // Tests for local files without the scheme.
    assertEquals(
        toResourceIdentifier("/root/tmp/"),
        toResourceIdentifier("/root/tmp/").getCurrentDirectory());
    assertEquals(
        toResourceIdentifier("/"),
        toResourceIdentifier("/").getCurrentDirectory());

    // Tests path with unicode
    assertEquals(
        toResourceIdentifier("/根目录/"),
        toResourceIdentifier("/根目录/输出 文件01.txt").getCurrentDirectory());
    assertEquals(
        toResourceIdentifier("file://根目录/"),
        toResourceIdentifier("file://根目录/输出 文件01.txt").getCurrentDirectory());
  }

  @Test
  public void testEquals() throws Exception {
    assertEquals(
        toResourceIdentifier("/root/tmp/"),
        toResourceIdentifier("/root/tmp/"));

    assertNotEquals(
        toResourceIdentifier("/root/tmp"),
        toResourceIdentifier("/root/tmp/"));
  }

  private LocalResourceId toResourceIdentifier(String str) throws Exception {
    boolean isDirectory;
    if (SystemUtils.IS_OS_WINDOWS) {
      isDirectory = str.endsWith("\\");
    } else {
      isDirectory = str.endsWith("/");
    }
    return LocalResourceId.fromPath(Paths.get(str), isDirectory);
  }
}
