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
package org.beam.dsls.sql.schema;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.coders.DefaultCoder;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;

/**
 * Field type information in {@link BeamSQLRow}.
 *
 */
@DefaultCoder(AvroCoder.class)
public class BeamSQLRecordType implements Serializable {
  /**
   *
   */
  private static final long serialVersionUID = -5318734648766104712L;
  private List<String> fieldsName = new ArrayList<>();
  private List<String> fieldsType = new ArrayList<>();

  public static BeamSQLRecordType from(RelDataType tableInfo) {
    BeamSQLRecordType record = new BeamSQLRecordType();
    for (RelDataTypeField f : tableInfo.getFieldList()) {
      record.fieldsName.add(f.getName());
      record.fieldsType.add(f.getType().getSqlTypeName().getName());
    }
    return record;
  }

  public int size() {
    return fieldsName.size();
  }

  public List<String> getFieldsName() {
    return fieldsName;
  }

  public void setFieldsName(List<String> fieldsName) {
    this.fieldsName = fieldsName;
  }

  public List<String> getFieldsType() {
    return fieldsType;
  }

  public void setFieldsType(List<String> fieldsType) {
    this.fieldsType = fieldsType;
  }

  @Override
  public String toString() {
    return "RecordType [fieldsName=" + fieldsName + ", fieldsType=" + fieldsType + "]";
  }

}
