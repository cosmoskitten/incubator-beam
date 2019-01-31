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
package org.apache.beam.fn.harness.data;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.apache.beam.runners.core.metrics.ExecutionStateTracker;
import org.apache.beam.runners.core.metrics.MetricsContainerStepMap;
import org.apache.beam.sdk.fn.function.ThrowingRunnable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PTransformFunctionRegistry}. */
@RunWith(JUnit4.class)
public class PTransformFunctionRegistryTest {

  @Test
  public void functionsAreInvokedIndirectlyAfterRegisteringAndInvoking() throws Exception {
    PTransformFunctionRegistry testObject =
        new PTransformFunctionRegistry(
            mock(MetricsContainerStepMap.class), mock(ExecutionStateTracker.class), "start");

    ThrowingRunnable runnableA = mock(ThrowingRunnable.class);
    ThrowingRunnable runnableB = mock(ThrowingRunnable.class);
    testObject.register("pTransformA", runnableA);
    testObject.register("pTransformB", runnableB);

    for (ThrowingRunnable func : testObject.getFunctions()) {
      func.run();
    }

    verify(runnableA, times(1)).run();
    verify(runnableB, times(1)).run();
  }
}
