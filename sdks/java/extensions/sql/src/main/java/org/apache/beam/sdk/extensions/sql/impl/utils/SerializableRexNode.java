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
package org.apache.beam.sdk.extensions.sql.impl.utils;

import java.io.Serializable;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;

/** SerializableRexNode. */
public abstract class SerializableRexNode implements Serializable {
  public abstract int getIndex();

  public SerializableRexNode getReferenceNode() {
    throw new UnsupportedOperationException("No reference node in SerializableRexNode.");
  }

  public static SerializableRexNode.Builder builder(RexNode rexNode) {
    return new SerializableRexNode.Builder(rexNode);
  }

  /** SerializableRexNode.Builder. */
  public static class Builder {
    private RexNode rexNode;

    public Builder(RexNode rexNode) {
      this.rexNode = rexNode;
    }

    public SerializableRexNode build() {
      if (rexNode instanceof RexInputRef) {
        return new SerializableRexInputRef((RexInputRef) rexNode);
      } else if (rexNode instanceof RexFieldAccess) {
        return new SerializableRexFieldAccess((RexFieldAccess) rexNode);
      }

      throw new UnsupportedOperationException(
          "Does not support to convert " + rexNode.getType() + " to SerializableRexNode.");
    }
  }
}
