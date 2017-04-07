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
package org.beam.dsls.sql.transform;

import org.apache.beam.sdk.transforms.DoFn;
import org.beam.dsls.sql.schema.BeamSQLRow;

/**
 * A test PTransform to display output in console.
 *
 */
public class BeamSQLOutputToConsoleFn extends DoFn<BeamSQLRow, Void> {
  /**
   *
   */
  private static final long serialVersionUID = -1256111753670606705L;

  private String stepName;

  public BeamSQLOutputToConsoleFn(String stepName) {
    super();
    this.stepName = stepName;
  }

  @ProcessElement
  public void processElement(ProcessContext c) {
    System.out.println("Output: " + c.element().getDataMap());
  }

}
