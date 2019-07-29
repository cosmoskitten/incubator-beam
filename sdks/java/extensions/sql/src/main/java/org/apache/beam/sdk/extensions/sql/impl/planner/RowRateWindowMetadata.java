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
package org.apache.beam.sdk.extensions.sql.impl.planner;

import java.lang.reflect.Method;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.Metadata;
import org.apache.calcite.rel.metadata.MetadataDef;
import org.apache.calcite.rel.metadata.MetadataHandler;
import org.apache.calcite.rel.metadata.RelMetadataQuery;

/** This is a metadata used for row count and rate estimation. */
public interface RowRateWindowMetadata extends Metadata {
  Method METHOD = Types.lookupMethod(RowRateWindowMetadata.class, "getRowRateWindow");

  MetadataDef<RowRateWindowMetadata> DEF =
      MetadataDef.of(RowRateWindowMetadata.class, RowRateWindowMetadata.Handler.class, METHOD);

  RowRateWindow getRowRateWindow();

  /** Handler API. */
  interface Handler extends MetadataHandler<RowRateWindowMetadata> {
    RowRateWindow getRowRateWindow(RelNode r, RelMetadataQuery mq);
  }
}
