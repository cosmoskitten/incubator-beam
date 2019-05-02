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
package org.apache.beam.sdk.extensions.smb.json;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.api.services.bigquery.model.TableRow;
import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.extensions.smb.BucketMetadata;

public class JsonBucketMetadata<SortingKeyT>
    extends BucketMetadata<SortingKeyT, TableRow> {

  @JsonProperty private final String keyField;

  @JsonIgnore private String[] keyPath;

  @JsonCreator
  public JsonBucketMetadata(
      @JsonProperty("numBuckets") int numBuckets,
      @JsonProperty("sortingKeyClass") Class<SortingKeyT> sortingKeyClass,
      @JsonProperty("hashType") BucketMetadata.HashType hashType,
      @JsonProperty("keyField") String keyField)
      throws CannotProvideCoderException {
    super(numBuckets, sortingKeyClass, hashType);
    this.keyField = keyField;
    this.keyPath = keyField.split("\\.");
  }

  @SuppressWarnings("unchecked")
  @Override
  public SortingKeyT extractKey(TableRow value) {
    TableRow node = value;
    for (int i = 0; i < keyPath.length - 1; i++) {
      node = (TableRow) node.get(keyPath[i]);
    }
    return (SortingKeyT) node.get(keyPath[keyPath.length - 1]);
  }
}
