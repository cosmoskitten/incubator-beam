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
package org.apache.beam.sdk.extensions.sql.meta.provider.datacatalog;

import com.alibaba.fastjson.JSONObject;
import com.google.cloud.datacatalog.Entry;
import com.google.cloud.datacatalog.GcsFilesetSpec;
import java.util.List;
import org.apache.beam.sdk.extensions.sql.meta.Table;

/** Utils to handle GCS entries from Cloud Data Catalog. */
class GcsUtils {

  /** Check if the entry represents a GCS fileset in Data Catalog. */
  static boolean isGcs(Entry entry) {
    return entry.hasGcsFilesetSpec();
  }

  /** Creates a Beam SQL table description from a GCS fileset entry. */
  static Table.Builder tableBuilder(Entry entry) {
    GcsFilesetSpec gcsFilesetSpec = entry.getGcsFilesetSpec();
    List<String> filePatterns = gcsFilesetSpec.getFilePatternsList();

    // We support exactly one 'file_patterns' field and nothing else at the moment
    if (filePatterns.size() != 1) {
      throw new UnsupportedOperationException(
          "Unable to parse GCS entry '" + entry.getName() + "'");
    }

    String filePattern = filePatterns.get(0);

    if (!filePattern.startsWith("gs://")) {
      throw new UnsupportedOperationException(
          "Unsupported file pattern. "
              + "Only file patterns with 'gs://' schema are supported at the moment.");
    }

    return Table.builder()
        .type("text")
        .location(filePattern)
        .properties(new JSONObject())
        .comment("");
  }
}
