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
package org.apache.beam.sdk.transforms.windowing;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Tests for {@link OutputTimeFns}. */
@RunWith(JUnit4.class)
public class OutputTimeFnsTest {

  @Parameters(name = "{index}: {0}")
  public static Iterable<OutputTimeFn<BoundedWindow>> data() {
    return ImmutableList.of(
        OutputTimeFns.outputAtEarliestInputTimestamp(),
        OutputTimeFns.outputAtLatestInputTimestamp(),
        OutputTimeFns.outputAtEndOfWindow());
  }

  @Parameter(0)
  public OutputTimeFn<?> outputTimeFn;

  @Test
  public void testToProtoAndBack() throws Exception {
    OutputTimeFn<?> result = OutputTimeFns.fromProto(OutputTimeFns.toProto(outputTimeFn));

    assertThat(result, equalTo((OutputTimeFn) outputTimeFn));
  }
}
