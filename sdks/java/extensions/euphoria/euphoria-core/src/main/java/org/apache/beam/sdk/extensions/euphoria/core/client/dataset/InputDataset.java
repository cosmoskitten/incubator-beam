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
package org.apache.beam.sdk.extensions.euphoria.core.client.dataset;

import java.util.Collection;
import javax.annotation.Nullable;
import org.apache.beam.sdk.extensions.euphoria.core.annotation.audience.Audience;
import org.apache.beam.sdk.extensions.euphoria.core.client.flow.Flow;
import org.apache.beam.sdk.extensions.euphoria.core.client.io.DataSink;
import org.apache.beam.sdk.extensions.euphoria.core.client.io.DataSource;
import org.apache.beam.sdk.extensions.euphoria.core.client.operator.base.Operator;

/** {@code InputDataset} that is input of a {@code Flow}. */
@Audience(Audience.Type.EXECUTOR)
class InputDataset<T> implements Dataset<T> {

  private final Flow flow;
  private final DataSource<T> source;
  private final boolean bounded;

  public InputDataset(Flow flow, DataSource<T> source, boolean bounded) {
    this.flow = flow;
    this.source = source;
    this.bounded = bounded;
  }

  @Nullable
  @Override
  public DataSource<T> getSource() {
    return source;
  }

  @Nullable
  @Override
  public Operator<?, T> getProducer() {
    return null;
  }

  @Override
  public void persist(DataSink<T> sink) {
    throw new UnsupportedOperationException("The input dataset is already stored.");
  }

  @Override
  public Flow getFlow() {
    return flow;
  }

  @Override
  public boolean isBounded() {
    return bounded;
  }

  @Override
  public Collection<Operator<?, ?>> getConsumers() {
    return flow.getConsumersOf(this);
  }
}
