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
package org.apache.beam.examples;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import org.apache.beam.examples.common.ExampleBigQueryTableOptions;
import org.apache.beam.examples.common.ExampleOptions;
import org.apache.beam.runners.direct.DirectRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.util.IOChannelFactory;
import org.apache.beam.sdk.util.IOChannelUtils;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;


/**
 * An example that counts words in text, and can run over either unbounded or bounded input
 * collections.
 *
 * <p>This class, {@link WindowedWordCount}, is the last in a series of four successively more
 * detailed 'word count' examples. First take a look at {@link MinimalWordCount},
 * {@link WordCount}, and {@link DebuggingWordCount}.
 *
 * <p>Basic concepts, also in the MinimalWordCount, WordCount, and DebuggingWordCount examples:
 * Reading text files; counting a PCollection; writing to GCS; executing a Pipeline both locally
 * and using a selected runner; defining DoFns; creating a custom aggregator;
 * user-defined PTransforms; defining PipelineOptions.
 *
 * <p>New Concepts:
 * <pre>
 *   1. Unbounded and bounded pipeline input modes
 *   2. Adding timestamps to data
 *   3. Windowing
 *   4. Re-using PTransforms over windowed PCollections
 *   5. Accessing the window of an element
 *   6. Writing data to per-window text files
 * </pre>
 *
 * <p>By default, the examples will run with the {@code DirectRunner}.
 * To change the runner, specify:
 * <pre>{@code
 *   --runner=YOUR_SELECTED_RUNNER
 * }
 * </pre>
 * See examples/java/README.md for instructions about how to configure different runners.
 *
 * <p>To execute this pipeline locally, specify a local output file (if using the
 * {@link DirectRunner}) or output prefix on a supported distributed file system.
 * <pre>{@code
 *   --output=[YOUR_LOCAL_FILE | YOUR_OUTPUT_PREFIX]
 * }</pre>
 *
 * <p>The input file defaults to a public data set containing the text of of King Lear,
 * by William Shakespeare. You can override it and choose your own input with {@code --inputFile}.
 *
 * <p>By default, the pipeline will do fixed windowing, on 1-minute windows.  You can
 * change this interval by setting the {@code --windowSize} parameter, e.g. {@code --windowSize=10}
 * for 10-minute windows.
 *
 * <p>The example will try to cancel the pipeline on the signal to terminate the process (CTRL-C).
 */
public class WindowedWordCount {
    static final int WINDOW_SIZE = 10;  // Default window duration in minutes
    static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);
    static final Coder<String> STRING_CODER = StringUtf8Coder.of();

  /**
   * Concept #2: A DoFn that sets the data element timestamp. This is a silly method, just for
   * this example, for the bounded data case.
   *
   * <p>Imagine that many ghosts of Shakespeare are all typing madly at the same time to recreate
   * his masterworks. Each line of the corpus will get a random associated timestamp somewhere in a
   * 2-hour period.
   */
  static class AddTimestampFn extends DoFn<String, String> {
    private static final Duration RAND_RANGE = Duration.standardHours(1);
    private final Instant minTimestamp;

    AddTimestampFn() {
      this.minTimestamp = new Instant(System.currentTimeMillis());
    }

    @ProcessElement
    public void processElement(ProcessContext c) {
      // Generate a timestamp that falls somewhere in the past two hours.
      long randMillis = (long) (Math.random() * RAND_RANGE.getMillis());
      Instant randomTimestamp = minTimestamp.plus(randMillis);
      /**
       * Concept #2: Set the data element with that timestamp.
       */
      c.outputWithTimestamp(c.element(), new Instant(randomTimestamp));
    }
  }

  /**
   * Options for {@link WindowedWordCount}.
   *
   * <p>Inherits standard example configuration options, which allow specification of the
   * runner, as well as the {@link WordCount.WordCountOptions} support for
   * specification of the input and output files.
   */
  public interface Options extends WordCount.WordCountOptions,
      ExampleOptions, ExampleBigQueryTableOptions {
    @Description("Fixed window duration, in minutes")
    @Default.Integer(WINDOW_SIZE)
    Integer getWindowSize();
    void setWindowSize(Integer value);
  }

  public static void main(String[] args) throws IOException {
    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
    final String output = options.getOutput();
    final Duration windowSize = Duration.standardMinutes(options.getWindowSize());

    Pipeline pipeline = Pipeline.create(options);

    /**
     * Concept #1: the Beam SDK lets us run the same pipeline with either a bounded or
     * unbounded input source.
     */
    PCollection<String> input = pipeline
      /** Read from the GCS file. */
      .apply(TextIO.Read.from(options.getInputFile()))
      // Concept #2: Add an element timestamp, using an artificial time just to show windowing.
      // See AddTimestampFn for more detail on this.
      .apply(ParDo.of(new AddTimestampFn()));

    /**
     * Concept #3: Window into fixed windows. The fixed window size for this example defaults to 1
     * minute (you can change this with a command-line option). See the documentation for more
     * information on how fixed windows work, and for information on the other types of windowing
     * available (e.g., sliding windows).
     */
    PCollection<String> windowedWords = input
      .apply(Window.<String>into(
        FixedWindows.of(Duration.standardMinutes(options.getWindowSize()))));

    /**
     * Concept #4: Re-use our existing CountWords transform that does not have knowledge of
     * windows over a PCollection containing windowed values.
     */
    PCollection<KV<String, Long>> wordCounts = windowedWords.apply(new WordCount.CountWords());

    /**
     * Concept #5: Customize the output format using windowing information
     *
     * <p>At this point, the data is organized by window. We're writing text files and and have no
     * late data, so for simplicity we can use the window as the key and {@link GroupByKey} to get
     * one output file per window. (if we had late data this key would not be unique)
     *
     * <p>To access the window in a {@link DoFn}, add a {@link BoundedWindow} parameter. This will
     * be automatically detected and populated with the window for the current element.
     */
    PCollection<KV<IntervalWindow, KV<String, Long>>> keyedByWindow =
        wordCounts.apply(
            ParDo.of(
                new DoFn<KV<String, Long>, KV<IntervalWindow, KV<String, Long>>>() {
                  @ProcessElement
                  public void processElement(ProcessContext context, BoundedWindow window) {
                    context.output(KV.of((IntervalWindow) window, context.element()));
                  }
                }));

    /**
     * Concept #6: Format the results and write to a sharded file partitioned by window, using a
     * simple ParDo operation. Because there may be failures followed by retries, the writes must be
     * idempotent, and the files may not appear atomically.
     */
    keyedByWindow
        .apply(GroupByKey.<IntervalWindow, KV<String, Long>>create())
        .apply(
            ParDo.of(
                new DoFn<KV<IntervalWindow, Iterable<KV<String, Long>>>, Void>() {
                  @ProcessElement
                  public void processElement(ProcessContext context) throws Exception {
                    // Build a file name from the window
                    DateTimeFormatter formatter = ISODateTimeFormat.hourMinute();
                    IntervalWindow window = context.element().getKey();
                    String outputShard =
                        String.format(
                            "%s-%s-%s",
                            output, formatter.print(window.start()), formatter.print(window.end()));

                    // Open the file and write all the values
                    IOChannelFactory factory = IOChannelUtils.getFactory(outputShard);
                    OutputStream out =
                        Channels.newOutputStream(factory.create(outputShard, "text/plain"));
                    for (KV<String, Long> wordCount : context.element().getValue()) {
                      STRING_CODER.encode(
                          wordCount.getKey() + ": " + wordCount.getValue(),
                          out,
                          Coder.Context.OUTER);
                      out.write(NEWLINE);
                    }
                    out.close();
                  }
                }));

    PipelineResult result = pipeline.run();
    try {
      result.waitUntilFinish();
    } catch (Exception exc) {
      result.cancel();
    }
  }
}
