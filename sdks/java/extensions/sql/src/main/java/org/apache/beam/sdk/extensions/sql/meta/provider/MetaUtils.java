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

package org.apache.beam.sdk.extensions.sql.meta.provider;

import java.util.ArrayList;
import java.util.List;
import org.apache.beam.sdk.extensions.sql.BeamRowSqlType;
import org.apache.beam.sdk.extensions.sql.meta.Column;
import org.apache.beam.sdk.extensions.sql.meta.Table;

/**
 * Utility methods for metadata.
 */
public class MetaUtils {
  public static BeamRowSqlType getBeamSqlRecordTypeFromTable(Table table) {
    List<String> columnNames = new ArrayList<>(table.getColumns().size());
    List<Integer> columnTypes = new ArrayList<>(table.getColumns().size());
    for (Column column : table.getColumns()) {
      columnNames.add(column.getName());
      columnTypes.add(column.getType());
    }
    return BeamRowSqlType.create(columnNames, columnTypes);
  }
}
