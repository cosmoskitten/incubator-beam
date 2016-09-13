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

package org.apache.beam.runners.apex.translators;

import java.util.List;

import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;

import com.datatorrent.lib.stream.StreamMerger;
import com.google.common.collect.Lists;

/**
 * Flatten.FlattenPCollectionList translation to Apex operator.
 * TODO: support more than two streams
 */
public class FlattenPCollectionTranslator<T> implements
    TransformTranslator<Flatten.FlattenPCollectionList<T>> {
  private static final long serialVersionUID = 1L;

  @Override
  public void translate(Flatten.FlattenPCollectionList<T> transform, TranslationContext context) {
    PCollection<T> firstCollection = null;
    PCollectionList<T> input = context.getInput();
    List<PCollection<T>> collections = input.getAll();
    List<PCollection<T>> remainingCollections = Lists.newArrayList();
    while (!collections.isEmpty()) {
      for (PCollection<T> collection : collections) {
        if (null == firstCollection) {
          firstCollection = collection;
        } else {
          StreamMerger<T> operator = new StreamMerger<>();
          context.addStream(firstCollection, operator.data1);
          context.addStream(collection, operator.data2);
          if (collections.size() > 2) {
            PCollection<T> resultCollection = intermediateCollection(collection, collection.getCoder());
            context.addOperator(operator, operator.out, resultCollection);
            remainingCollections.add(resultCollection);
          } else {
            // final stream merge
            context.addOperator(operator, operator.out);
          }
          firstCollection = null;
        }
      }
      if (firstCollection != null) {
        // push to next merge level
        remainingCollections.add(firstCollection);
      }
      if (remainingCollections.size() > 1) {
        collections = remainingCollections;
        remainingCollections = Lists.newArrayList();
      } else {
        collections = Lists.newArrayList();
      }
    }
  }

  public static <T> PCollection<T> intermediateCollection(PCollection<T> input, Coder<T> outputCoder) {
    PCollection<T> output = PCollection.createPrimitiveOutputInternal(input.getPipeline(), input.getWindowingStrategy(), input.isBounded());
    output.setCoder(outputCoder);
    return output;
  }

}
