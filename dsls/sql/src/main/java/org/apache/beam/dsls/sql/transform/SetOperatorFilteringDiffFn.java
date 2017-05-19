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

package org.apache.beam.dsls.sql.transform;

import org.apache.beam.dsls.sql.schema.BeamSQLRow;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.join.CoGbkResult;
import org.apache.beam.sdk.transforms.join.CoGroupByKey;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.TupleTag;

/**
 * Function to filter the `diff` from a {@link CoGroupByKey} result.
 */
public class SetOperatorFilteringDiffFn
    implements SerializableFunction<KV<BeamSQLRow, CoGbkResult>, Boolean> {

  private TupleTag<BeamSQLRow> leftTag;
  private TupleTag<BeamSQLRow> rightTag;

  public SetOperatorFilteringDiffFn(TupleTag<BeamSQLRow> leftTag, TupleTag<BeamSQLRow> rightTag) {
    this.leftTag = leftTag;
    this.rightTag = rightTag;
  }

  @Override public Boolean apply(KV<BeamSQLRow, CoGbkResult> input) {
    Iterable<BeamSQLRow> leftRows = input.getValue().getAll(leftTag);
    Iterable<BeamSQLRow> rightRows = input.getValue().getAll(rightTag);

    return leftRows.iterator().hasNext() && rightRows.iterator().hasNext();
  }

}
