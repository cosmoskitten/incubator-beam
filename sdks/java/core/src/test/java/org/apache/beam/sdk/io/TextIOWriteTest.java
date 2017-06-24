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

import static com.google.common.base.MoreObjects.firstNonNull;
import static org.apache.beam.sdk.TestUtils.LINES2_ARRAY;
import static org.apache.beam.sdk.TestUtils.LINES_ARRAY;
import static org.apache.beam.sdk.TestUtils.NO_LINES_ARRAY;
import static org.apache.beam.sdk.io.TextIO.CompressionType.AUTO;
import static org.apache.beam.sdk.io.TextIO.CompressionType.BZIP2;
import static org.apache.beam.sdk.io.TextIO.CompressionType.DEFLATE;
import static org.apache.beam.sdk.io.TextIO.CompressionType.GZIP;
import static org.apache.beam.sdk.io.TextIO.CompressionType.UNCOMPRESSED;
import static org.apache.beam.sdk.io.TextIO.CompressionType.ZIP;
import static org.apache.beam.sdk.transforms.display.DisplayDataMatchers.hasDisplayItem;
import static org.apache.beam.sdk.transforms.display.DisplayDataMatchers.hasValue;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.BoundedSource.BoundedReader;
import org.apache.beam.sdk.io.FileBasedSink.WritableByteChannelFactory;
import org.apache.beam.sdk.io.TextIO.CompressionType;
import org.apache.beam.sdk.io.fs.MatchResult;
import org.apache.beam.sdk.io.fs.MatchResult.Metadata;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.testing.NeedsRunner;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.SourceTestUtils;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.ValidatesRunner;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.display.DisplayDataEvaluator;
import org.apache.beam.sdk.util.CoderUtils;
import org.apache.beam.sdk.values.PCollection;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.deflate.DeflateCompressorOutputStream;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;

/** Tests for {@link TextIO.Write}. */
public class TextIOWriteTest {
  private static final String MY_HEADER = "myHeader";
  private static final String MY_FOOTER = "myFooter";

  private static Path tempFolder;

  @Rule public TestPipeline p = TestPipeline.create();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @BeforeClass
  public static void setupClass() throws IOException {
    tempFolder = Files.createTempDirectory("TextIOTest");
  }

  @AfterClass
  public static void teardownClass() throws IOException {
    Files.walkFileTree(
        tempFolder,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private void runTestWrite(String[] elems) throws Exception {
    runTestWrite(elems, null, null, 1);
  }

  private void runTestWrite(String[] elems, int numShards) throws Exception {
    runTestWrite(elems, null, null, numShards);
  }

  private void runTestWrite(String[] elems, String header, String footer) throws Exception {
    runTestWrite(elems, header, footer, 1);
  }

  private void runTestWrite(String[] elems, String header, String footer, int numShards)
      throws Exception {
    String outputName = "file.txt";
    Path baseDir = Files.createTempDirectory(tempFolder, "testwrite");
    String baseFilename = baseDir.resolve(outputName).toString();

    PCollection<String> input =
        p.apply(Create.of(Arrays.asList(elems)).withCoder(StringUtf8Coder.of()));

    TextIO.Write write = TextIO.write().to(baseFilename).withHeader(header).withFooter(footer);

    if (numShards == 1) {
      write = write.withoutSharding();
    } else if (numShards > 0) {
      write = write.withNumShards(numShards).withShardNameTemplate(ShardNameTemplate.INDEX_OF_MAX);
    }

    input.apply(write);

    p.run();

    assertOutputFiles(
        elems,
        header,
        footer,
        numShards,
        baseDir,
        outputName,
        firstNonNull(
            write.getShardTemplate(), DefaultFilenamePolicy.DEFAULT_UNWINDOWED_SHARD_TEMPLATE));
  }

  private static void assertOutputFiles(
      String[] elems,
      final String header,
      final String footer,
      int numShards,
      Path rootLocation,
      String outputName,
      String shardNameTemplate)
      throws Exception {
    List<File> expectedFiles = new ArrayList<>();
    if (numShards == 0) {
      String pattern = rootLocation.toAbsolutePath().resolve(outputName + "*").toString();
      List<MatchResult> matches = FileSystems.match(Collections.singletonList(pattern));
      for (Metadata expectedFile : Iterables.getOnlyElement(matches).metadata()) {
        expectedFiles.add(new File(expectedFile.resourceId().toString()));
      }
    } else {
      for (int i = 0; i < numShards; i++) {
        expectedFiles.add(
            new File(
                rootLocation.toString(),
                DefaultFilenamePolicy.constructName(
                    outputName, shardNameTemplate, "", i, numShards, null, null)));
      }
    }

    List<List<String>> actual = new ArrayList<>();

    for (File tmpFile : expectedFiles) {
      try (BufferedReader reader = new BufferedReader(new FileReader(tmpFile))) {
        List<String> currentFile = new ArrayList<>();
        for (; ; ) {
          String line = reader.readLine();
          if (line == null) {
            break;
          }
          currentFile.add(line);
        }
        actual.add(currentFile);
      }
    }

    List<String> expectedElements = new ArrayList<>(elems.length);
    for (String elem : elems) {
      byte[] encodedElem = CoderUtils.encodeToByteArray(StringUtf8Coder.of(), elem);
      String line = new String(encodedElem);
      expectedElements.add(line);
    }

    List<String> actualElements =
        Lists.newArrayList(
            Iterables.concat(
                FluentIterable.from(actual)
                    .transform(removeHeaderAndFooter(header, footer))
                    .toList()));

    assertThat(actualElements, containsInAnyOrder(expectedElements.toArray()));

    assertTrue(Iterables.all(actual, haveProperHeaderAndFooter(header, footer)));
  }

  private static Function<List<String>, List<String>> removeHeaderAndFooter(
      final String header, final String footer) {
    return new Function<List<String>, List<String>>() {
      @Nullable
      @Override
      public List<String> apply(List<String> lines) {
        ArrayList<String> newLines = Lists.newArrayList(lines);
        if (header != null) {
          newLines.remove(0);
        }
        if (footer != null) {
          int last = newLines.size() - 1;
          newLines.remove(last);
        }
        return newLines;
      }
    };
  }

  private static Predicate<List<String>> haveProperHeaderAndFooter(
      final String header, final String footer) {
    return new Predicate<List<String>>() {
      @Override
      public boolean apply(List<String> fileLines) {
        int last = fileLines.size() - 1;
        return (header == null || fileLines.get(0).equals(header))
            && (footer == null || fileLines.get(last).equals(footer));
      }
    };
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteStrings() throws Exception {
    runTestWrite(LINES_ARRAY);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteEmptyStringsNoSharding() throws Exception {
    runTestWrite(NO_LINES_ARRAY, 0);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteEmptyStrings() throws Exception {
    runTestWrite(NO_LINES_ARRAY);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testShardedWrite() throws Exception {
    runTestWrite(LINES_ARRAY, 5);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteWithHeader() throws Exception {
    runTestWrite(LINES_ARRAY, MY_HEADER, null);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteWithFooter() throws Exception {
    runTestWrite(LINES_ARRAY, null, MY_FOOTER);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteWithHeaderAndFooter() throws Exception {
    runTestWrite(LINES_ARRAY, MY_HEADER, MY_FOOTER);
  }

  @Test
  @Category(NeedsRunner.class)
  public void testWriteWithWritableByteChannelFactory() throws Exception {
    Coder<String> coder = StringUtf8Coder.of();
    String outputName = "file.txt";
    Path baseDir = Files.createTempDirectory(tempFolder, "testwrite");

    PCollection<String> input = p.apply(Create.of(Arrays.asList(LINES2_ARRAY)).withCoder(coder));

    final WritableByteChannelFactory writableByteChannelFactory =
        new DrunkWritableByteChannelFactory();
    TextIO.Write write =
        TextIO.write()
            .to(baseDir.resolve(outputName).toString())
            .withoutSharding()
            .withWritableByteChannelFactory(writableByteChannelFactory);
    DisplayData displayData = DisplayData.from(write);
    assertThat(displayData, hasDisplayItem("writableByteChannelFactory", "DRUNK"));

    input.apply(write);

    p.run();

    final List<String> drunkElems = new ArrayList<>(LINES2_ARRAY.length * 2 + 2);
    for (String elem : LINES2_ARRAY) {
      drunkElems.add(elem);
      drunkElems.add(elem);
    }
    assertOutputFiles(
        drunkElems.toArray(new String[0]),
        null,
        null,
        1,
        baseDir,
        outputName + writableByteChannelFactory.getFilenameSuffix(),
        write.getShardTemplate());
  }

  @Test
  public void testWriteDisplayData() {
    TextIO.Write write =
        TextIO.write()
            .to("/foo")
            .withSuffix("bar")
            .withShardNameTemplate("-SS-of-NN-")
            .withNumShards(100)
            .withFooter("myFooter")
            .withHeader("myHeader");

    DisplayData displayData = DisplayData.from(write);

    assertThat(displayData, hasDisplayItem("filePrefix", "/foo"));
    assertThat(displayData, hasDisplayItem("fileSuffix", "bar"));
    assertThat(displayData, hasDisplayItem("fileHeader", "myHeader"));
    assertThat(displayData, hasDisplayItem("fileFooter", "myFooter"));
    assertThat(displayData, hasDisplayItem("shardNameTemplate", "-SS-of-NN-"));
    assertThat(displayData, hasDisplayItem("numShards", 100));
    assertThat(displayData, hasDisplayItem("writableByteChannelFactory", "UNCOMPRESSED"));
  }

  @Test
  public void testWriteDisplayDataValidateThenHeader() {
    TextIO.Write write = TextIO.write().to("foo").withHeader("myHeader");

    DisplayData displayData = DisplayData.from(write);

    assertThat(displayData, hasDisplayItem("fileHeader", "myHeader"));
  }

  @Test
  public void testWriteDisplayDataValidateThenFooter() {
    TextIO.Write write = TextIO.write().to("foo").withFooter("myFooter");

    DisplayData displayData = DisplayData.from(write);

    assertThat(displayData, hasDisplayItem("fileFooter", "myFooter"));
  }

  @Test
  public void testGetName() {
    assertEquals("TextIO.Write", TextIO.write().to("somefile").getName());
  }

  /** Options for testing. */
  public interface RuntimeTestOptions extends PipelineOptions {
    ValueProvider<String> getOutput();
    void setOutput(ValueProvider<String> value);
  }

  @Test
  public void testRuntimeOptionsNotCalledInApply() throws Exception {
    p.enableAbandonedNodeEnforcement(false);

    RuntimeTestOptions options = PipelineOptionsFactory.as(RuntimeTestOptions.class);

    p.apply(Create.of("")).apply(TextIO.write().to(options.getOutput()));
  }
}
