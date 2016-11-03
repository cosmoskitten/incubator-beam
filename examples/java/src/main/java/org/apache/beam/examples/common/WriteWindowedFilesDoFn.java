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
package org.apache.beam.examples.common;

import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.util.IOChannelFactory;
import org.apache.beam.sdk.util.IOChannelUtils;
import org.apache.beam.sdk.values.KV;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

/** Created by klk on 11/4/16. */
public class WriteWindowedFilesDoFn
    extends DoFn<KV<IntervalWindow, Iterable<KV<String, Long>>>, Void> {

  static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);
  static final Coder<String> STRING_CODER = StringUtf8Coder.of();

  private final String output;

  public WriteWindowedFilesDoFn(String output) {
    this.output = output;
  }

  @ProcessElement
  public void processElement(ProcessContext context) throws Exception {
    // Build a file name from the window
    DateTimeFormatter formatter = ISODateTimeFormat.hourMinute();
    IntervalWindow window = context.element().getKey();
    String outputShard =
        String.format(
            "%s-%s-%s", output, formatter.print(window.start()), formatter.print(window.end()));

    // Open the file and write all the values
    IOChannelFactory factory = IOChannelUtils.getFactory(outputShard);
    OutputStream out = Channels.newOutputStream(factory.create(outputShard, "text/plain"));
    for (KV<String, Long> wordCount : context.element().getValue()) {
      STRING_CODER.encode(
          wordCount.getKey() + ": " + wordCount.getValue(), out, Coder.Context.OUTER);
      out.write(NEWLINE);
    }
    out.close();
  }
}
