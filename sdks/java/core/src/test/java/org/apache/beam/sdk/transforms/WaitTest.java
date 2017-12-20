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
package org.apache.beam.sdk.transforms;

import static org.junit.Assert.assertFalse;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.apache.beam.sdk.testing.NeedsRunner;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.TestStream;
import org.apache.beam.sdk.testing.UsesTestStream;
import org.apache.beam.sdk.transforms.windowing.AfterPane;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.Repeatedly;
import org.apache.beam.sdk.transforms.windowing.SlidingWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TimestampedValue;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link Wait}. */
@RunWith(JUnit4.class)
public class WaitTest implements Serializable {
  @Rule public transient TestPipeline p = TestPipeline.create();

  private static class Event<T> {
    private final Instant processingTime;
    private final TimestampedValue<T> element;
    private final Instant watermarkUpdate;

    private Event(Instant processingTime, TimestampedValue<T> element) {
      this.processingTime = processingTime;
      this.element = element;
      this.watermarkUpdate = null;
    }

    private Event(Instant processingTime, Instant watermarkUpdate) {
      this.processingTime = processingTime;
      this.element = null;
      this.watermarkUpdate = watermarkUpdate;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("processingTime", processingTime)
          .add("element", element)
          .add("watermarkUpdate", watermarkUpdate)
          .toString();
    }
  }

  /**
   * Generates a {@link TestStream} of the given duration containing the values [0, numElements) and
   * the same number of random but monotonic watermark updates, with each element within
   * allowedLateness of the respective watermark update.
   *
   * <p>TODO: Consider moving this into TestStream if it's useful enough.
   */
  private PCollection<Long> generateStreamWithBoundedDisorder(
      String name,
      Instant base,
      Duration totalDuration,
      int numElements,
      Duration allowedLateness) {
    TestStream.Builder<Long> stream = TestStream.create(VarLongCoder.of());

    // Generate numElements random watermark updates. After each one also generate an element within
    // allowedLateness of it.
    List<Instant> watermarks = Lists.newArrayList();
    for (int i = 0; i < numElements; ++i) {
      watermarks.add(base.plus(new Duration((long) (totalDuration.getMillis() * Math.random()))));
    }
    Collections.sort(watermarks);

    List<Event<Long>> events = Lists.newArrayList();
    for (int i = 0; i < numElements; ++i) {
      Instant processingTimestamp =
          base.plus((long) (1.0 * i * totalDuration.getMillis() / (numElements + 1)));
      Instant watermark = watermarks.get(i);
      Instant elementTimestamp =
          watermark.minus((long) (Math.random() * allowedLateness.getMillis()));
      events.add(new Event<Long>(processingTimestamp, watermark));
      events.add(new Event<>(processingTimestamp, TimestampedValue.of((long) i, elementTimestamp)));
    }

    Instant lastProcessingTime = base;
    for (Event<Long> event : events) {
      Duration processingTimeDelta = new Duration(lastProcessingTime, event.processingTime);
      if (processingTimeDelta.getMillis() > 0) {
        stream = stream.advanceProcessingTime(processingTimeDelta);
      }
      lastProcessingTime = event.processingTime;

      if (event.element != null) {
        stream = stream.addElements(event.element);
      } else {
        stream = stream.advanceWatermarkTo(event.watermarkUpdate);
      }
    }
    return p.apply(name, stream.advanceWatermarkToInfinity());
  }

  private static final AtomicReference<Instant> TEST_WAIT_MAX_MAIN_TIMESTAMP =
      new AtomicReference<>();

  @Test
  @Category({NeedsRunner.class, UsesTestStream.class})
  public void testWaitWithSameFixedWindows() {
    testWaitWithParameters(
        Duration.standardMinutes(1) /* duration */,
        Duration.standardSeconds(15) /* lateness */,
        20 /* numMainElements */,
        FixedWindows.of(Duration.standardSeconds(15)),
        20 /* numSignalElements */,
        FixedWindows.of(Duration.standardSeconds(15)));
  }

  @Test
  @Category({NeedsRunner.class, UsesTestStream.class})
  public void testWaitWithDifferentFixedWindows() {
    testWaitWithParameters(
        Duration.standardMinutes(1) /* duration */,
        Duration.standardSeconds(15) /* lateness */,
        20 /* numMainElements */,
        FixedWindows.of(Duration.standardSeconds(15)),
        20 /* numSignalElements */,
        FixedWindows.of(Duration.standardSeconds(7)));
  }

  @Test
  @Category({NeedsRunner.class, UsesTestStream.class})
  public void testWaitWithSignalInSlidingWindows() {
    testWaitWithParameters(
        Duration.standardMinutes(1) /* duration */,
        Duration.standardSeconds(15) /* lateness */,
        20 /* numMainElements */,
        FixedWindows.of(Duration.standardSeconds(15)),
        20 /* numSignalElements */,
        SlidingWindows.of(Duration.standardSeconds(7)).every(Duration.standardSeconds(1)));
  }

  @Test
  @Category({NeedsRunner.class, UsesTestStream.class})
  public void testWaitInGlobalWindow() {
    testWaitWithParameters(
        Duration.standardMinutes(1) /* duration */,
        Duration.standardSeconds(15) /* lateness */,
        20 /* numMainElements */,
        new GlobalWindows(),
        20 /* numSignalElements */,
        new GlobalWindows());
  }

  @Test
  @Category({NeedsRunner.class, UsesTestStream.class})
  public void testWaitWithSomeSignalWindowsEmpty() {
    testWaitWithParameters(
        Duration.standardMinutes(1) /* duration */,
        Duration.standardSeconds(0) /* lateness */,
        20 /* numMainElements */,
        FixedWindows.of(Duration.standardSeconds(1)),
        10 /* numSignalElements */,
        FixedWindows.of(Duration.standardSeconds(1)));
  }

  private void testWaitWithParameters(
      Duration duration,
      Duration lateness,
      int numMainElements,
      WindowFn<? super Long, ?> mainWindowFn,
      int numSignalElements,
      WindowFn<? super Long, ?> signalWindowFn) {
    TEST_WAIT_MAX_MAIN_TIMESTAMP.set(null);

    Instant base = Instant.now();

    PCollection<Long> input =
        generateStreamWithBoundedDisorder("main", base, duration, numMainElements, lateness)
            .apply(
                "Window main",
                Window.into(mainWindowFn)
                    .discardingFiredPanes()
                    // Use an aggressive trigger for main input and signal to get more
                    // frequent / aggressive verification.
                    .triggering(Repeatedly.forever(AfterPane.elementCountAtLeast(1)))
                    .withAllowedLateness(lateness))
            .apply("Fire main", new Fire<Long>());

    PCollection<Long> signal =
        generateStreamWithBoundedDisorder("signal", base, duration, numSignalElements, lateness)
            .apply(
                "Window signal",
                Window.into(signalWindowFn)
                    .discardingFiredPanes()
                    .triggering(Repeatedly.forever(AfterPane.elementCountAtLeast(1)))
                    .withAllowedLateness(lateness))
            .apply("Fire signal", new Fire<Long>())
            .apply(
                "Check sequencing",
                ParDo.of(
                    new DoFn<Long, Long>() {
                      @ProcessElement
                      public void process(ProcessContext c) {
                        Instant maxMainTimestamp = TEST_WAIT_MAX_MAIN_TIMESTAMP.get();
                        if (maxMainTimestamp != null) {
                          assertFalse(
                              "Signal at timestamp "
                                  + c.timestamp()
                                  + " generated after main timestamp progressed to "
                                  + maxMainTimestamp,
                              c.timestamp().isBefore(maxMainTimestamp));
                        }
                        c.output(c.element());
                      }
                    }));

    PCollection<Long> output = input.apply(Wait.<Long>on(signal));

    output.apply(
        "Update main timestamp",
        ParDo.of(
            new DoFn<Long, Long>() {
              @ProcessElement
              public void process(ProcessContext c, BoundedWindow w) {
                while (true) {
                  Instant maxMainTimestamp = TEST_WAIT_MAX_MAIN_TIMESTAMP.get();
                  Instant newMaxTimestamp =
                      (maxMainTimestamp == null || c.timestamp().isAfter(maxMainTimestamp))
                          ? c.timestamp()
                          : maxMainTimestamp;
                  if (TEST_WAIT_MAX_MAIN_TIMESTAMP.compareAndSet(
                      maxMainTimestamp, newMaxTimestamp)) {
                    break;
                  }
                }
                c.output(c.element());
              }
            }));

    List<Long> expectedOutput = Lists.newArrayList();
    for (int i = 0; i < numMainElements; ++i) {
      expectedOutput.add((long) i);
    }
    PAssert.that(output).containsInAnyOrder(expectedOutput);

    p.run();
  }

  private static class Fire<T> extends PTransform<PCollection<T>, PCollection<T>> {
    @Override
    public PCollection<T> expand(PCollection<T> input) {
      return input
          .apply(WithKeys.<String, T>of(""))
          .apply(GroupByKey.<String, T>create())
          .apply(Values.<Iterable<T>>create())
          .apply(Flatten.<T>iterables());
    }
  }
}
