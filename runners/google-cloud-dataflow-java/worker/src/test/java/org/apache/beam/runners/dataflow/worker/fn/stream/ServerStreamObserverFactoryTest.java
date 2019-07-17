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
package org.apache.beam.runners.dataflow.worker.fn.stream;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.apache.beam.sdk.fn.stream.BufferingStreamObserver;
import org.apache.beam.sdk.fn.stream.DirectStreamObserver;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.vendor.grpc.v1p21p0.io.grpc.stub.CallStreamObserver;
import org.apache.beam.vendor.grpc.v1p21p0.io.grpc.stub.StreamObserver;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link ServerStreamObserverFactory}. */
@RunWith(JUnit4.class)
public class ServerStreamObserverFactoryTest {
  @Mock private CallStreamObserver<String> mockResponseObserver;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void testDefaultInstantiation() {
    StreamObserver<String> observer =
        ServerStreamObserverFactory.fromOptions(PipelineOptionsFactory.create())
            .from(mockResponseObserver);
    assertThat(observer, instanceOf(DirectStreamObserver.class));
  }

  @Test
  public void testBufferedStreamInstantiation() {
    StreamObserver<String> observer =
        ServerStreamObserverFactory.fromOptions(
                PipelineOptionsFactory.fromArgs(
                        new String[] {"--experiments=beam_fn_api_buffered_stream"})
                    .create())
            .from(mockResponseObserver);
    assertThat(observer, instanceOf(BufferingStreamObserver.class));
  }

  @Test
  public void testBufferedStreamWithLimitInstantiation() {
    StreamObserver<String> observer =
        ServerStreamObserverFactory.fromOptions(
                PipelineOptionsFactory.fromArgs(
                        new String[] {
                          "--experiments=beam_fn_api_buffered_stream,"
                              + "beam_fn_api_buffered_stream_buffer_size=1"
                        })
                    .create())
            .from(mockResponseObserver);
    assertThat(observer, instanceOf(BufferingStreamObserver.class));
    assertEquals(1, ((BufferingStreamObserver<String>) observer).getBufferSize());
  }
}
