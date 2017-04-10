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

package org.apache.beam.runners.apex.translation;

import org.apache.beam.runners.apex.translation.operators.ApexGroupByKeyOperator;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;

/**
 * {@link GroupByKey} translation to Apex operator.
 */
class GroupByKeyTranslator<K, V> implements TransformTranslator<GroupByKey<K, V>> {
  private static final long serialVersionUID = 1L;

  @Override
  public void translate(GroupByKey<K, V> transform, TranslationContext context) {
    PCollection<KV<K, V>> input = context.getInput();
    ApexGroupByKeyOperator<K, V> group = new ApexGroupByKeyOperator<>(context.getPipelineOptions(),
        input, context.getStateBackend()
        );
    context.addOperator(group, group.output);
    context.addStream(input, group.input);
  }

}
