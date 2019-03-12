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
package org.apache.beam.runners.spark.translation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests of {@link StreamingHashPartitioner}. */
public class StreamingHashPartitionerTest {

  @Test
  public void testPartitioning() {
    final StreamingHashPartitioner partitioner = StreamingHashPartitioner.of(2);
    byte[] key1 = "Test1".getBytes();
    byte[] key2 = "Test1".getBytes();

    final int partitionKey1 = partitioner.getPartition(key1);
    final int partitionKey2 = partitioner.getPartition(key2);

    assertEquals(partitionKey1, partitionKey2);
  }
}
