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
package org.apache.beam.sdk.io.text;

import static org.apache.beam.sdk.io.Compression.AUTO;
import static org.apache.beam.sdk.io.Compression.BZIP2;
import static org.apache.beam.sdk.io.Compression.DEFLATE;
import static org.apache.beam.sdk.io.Compression.GZIP;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.text.ParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.GenerateSequence;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.common.HashingFn;
import org.apache.beam.sdk.io.common.IOTestPipelineOptions;
import org.apache.beam.sdk.io.fs.MatchResult;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.values.PCollection;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Parameterized;


/**
 * Integration tests for {@link org.apache.beam.sdk.io.TextIO}.
 *
 * <p>Run this test using the command below. Pass in connection information via PipelineOptions:
 * <pre>
 *  mvn -e -Pio-it verify -pl sdks/java/io/text -DintegrationTestPipelineOptions='[
 *  "--numberOfRecords=100000",
 *  "--filenamePrefix=TEXTIOIT"
 *  ]'
 * </pre>
 * */
@RunWith(Enclosed.class)
public class TextIOIT {

  private static String filenamePrefix;
  private static Long numberOfTextLines;

  @BeforeClass
  public static void setup() throws ParseException {
    PipelineOptionsFactory.register(IOTestPipelineOptions.class);
    IOTestPipelineOptions options = TestPipeline.testingPipelineOptions()
        .as(IOTestPipelineOptions.class);

    numberOfTextLines = options.getNumberOfRecords();
    filenamePrefix = appendTimestamp(options.getFilenamePrefix());
  }

  private static String appendTimestamp(String filenamePrefix) {
    return String.format("%s_%s", filenamePrefix, new Date().getTime());
  }

  /** IO IT with no compression. */
  @RunWith(JUnit4.class)
  public static class UncompressedTextIOIT {

    @Rule
    public TestPipeline pipeline = TestPipeline.create();

    @Test
    public void writeThenReadAll() {
      PCollection<String> testFilenames = pipeline
          .apply("Generate sequence", GenerateSequence.from(0).to(numberOfTextLines))
          .apply("Produce text lines", ParDo.of(new DeterministicallyConstructTestTextLineFn()))
          .apply("Write content to files", TextIO.write().to(filenamePrefix).withOutputFilenames())
          .getPerDestinationOutputFilenames().apply(Values.<String>create());

      PCollection<String> consolidatedHashcode = testFilenames
          .apply("Read all files", TextIO.readAll())
          .apply("Calculate hashcode", Combine.globally(new HashingFn()));

      String expectedHash = getExpectedHashForLineCount(numberOfTextLines);
      PAssert.thatSingleton(consolidatedHashcode).isEqualTo(expectedHash);

      testFilenames.apply("Delete test files", ParDo.of(new DeleteFileFn())
          .withSideInputs(consolidatedHashcode.apply(View.<String>asSingleton())));

      pipeline.run().waitUntilFinish();
    }
  }

  /** IO IT with various compression types. */
  @RunWith(Parameterized.class)
  public static class CompressedTextIOIT {

    @Rule
    public TestPipeline pipeline = TestPipeline.create();

    @Parameterized.Parameters()
    public static Iterable<Compression> data() {
      return ImmutableList.<Compression>builder()
          .add(GZIP)
          .add(DEFLATE)
          .add(BZIP2)
          .build();
    }

    @Parameterized.Parameter()
    public Compression compression;

    @Test
    public void writeThenReadAllWithCompression() {
      TextIO.TypedWrite<String, Object> write = TextIO
          .write()
          .to(filenamePrefix)
          .withOutputFilenames()
          .withCompression(compression);

      TextIO.ReadAll read = TextIO.readAll().withCompression(AUTO);

      PCollection<String> testFilenames = pipeline
          .apply("Generate sequence", GenerateSequence.from(0).to(numberOfTextLines))
          .apply("Produce text lines", ParDo.of(new DeterministicallyConstructTestTextLineFn()))
          .apply("Write content to files", write)
          .getPerDestinationOutputFilenames().apply(Values.<String>create());

      PCollection<String> consolidatedHashcode = testFilenames
          .apply("Read all files", read)
          .apply("Calculate hashcode", Combine.globally(new HashingFn()));

      String expectedHash = getExpectedHashForLineCount(numberOfTextLines);
      PAssert.thatSingleton(consolidatedHashcode).isEqualTo(expectedHash);

      testFilenames.apply("Delete test files", ParDo.of(new DeleteFileFn())
          .withSideInputs(consolidatedHashcode.apply(View.<String>asSingleton())));

      pipeline.run().waitUntilFinish();
    }
  }

  private static String getExpectedHashForLineCount(Long lineCount) {
    Map<Long, String> expectedHashes = ImmutableMap.of(
        100_000L, "4c8bb3b99dcc59459b20fefba400d446",
        1_000_000L, "9796db06e7a7960f974d5a91164afff1"
    );

    String hash = expectedHashes.get(lineCount);
    if (hash == null) {
      throw new UnsupportedOperationException(
          String.format("No hash for that line count: %s", lineCount));
    }
    return hash;
  }

  private static class DeterministicallyConstructTestTextLineFn extends DoFn<Long, String> {
    @ProcessElement
    public void processElement(ProcessContext c) {
      c.output(String.format("IO IT Test line of text. Line seed: %s", c.element()));
    }
  }

  private static class DeleteFileFn extends DoFn<String, Void> {
    @ProcessElement
    public void processElement(ProcessContext c) throws IOException {
      MatchResult match = Iterables
          .getOnlyElement(FileSystems.match(Collections.singletonList(c.element())));
      FileSystems.delete(toResourceIds(match));
    }

    private Collection<ResourceId> toResourceIds(MatchResult match) throws IOException {
      return FluentIterable.from(match.metadata())
          .transform(new Function<MatchResult.Metadata, ResourceId>() {
            @Override
            public ResourceId apply(MatchResult.Metadata metadata) {
              return metadata.resourceId();
            }
          }).toList();
    }
  }
}
