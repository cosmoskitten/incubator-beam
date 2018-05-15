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
package org.apache.beam.sdk.extensions.euphoria.core.client.type;

import java.util.stream.Stream;
import org.apache.beam.sdk.extensions.euphoria.core.client.functional.ReduceFunctor;
import org.apache.beam.sdk.extensions.euphoria.core.client.io.Collector;

/** TODO: complete javadoc. */
public class TypeAwareReduceFunctor<InT, OutT>
    extends AbstractTypeAware<ReduceFunctor<InT, OutT>, OutT> implements ReduceFunctor<InT, OutT> {

  private TypeAwareReduceFunctor(ReduceFunctor<InT, OutT> functor, TypeHint<OutT> resultType) {
    super(functor, resultType);
  }

  public static <InT, OutT> TypeAwareReduceFunctor<InT, OutT> of(
      ReduceFunctor<InT, OutT> functor, TypeHint<OutT> typeHint) {
    return new TypeAwareReduceFunctor<>(functor, typeHint);
  }

  @Override
  public void apply(Stream<InT> elem, Collector<OutT> collector) {
    getDelegate().apply(elem, collector);
  }
}
