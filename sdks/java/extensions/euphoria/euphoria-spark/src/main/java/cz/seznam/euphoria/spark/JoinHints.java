/**
 * Copyright 2016-2017 Seznam.cz, a.s.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cz.seznam.euphoria.spark;

import cz.seznam.euphoria.core.annotation.audience.Audience;
import cz.seznam.euphoria.core.client.operator.JoinHint;

@Audience(Audience.Type.CLIENT)
public class JoinHints {

  private static final BroadcastHashJoin BROADCAST_HASH_JOIN = new BroadcastHashJoin();

  public static BroadcastHashJoin broadcastHashJoin() {
    return BROADCAST_HASH_JOIN;
  }

  /**
   * Brodcast left side to all executors
   */
  public static class BroadcastHashJoin implements JoinHint {

    private BroadcastHashJoin() {

    }

    @Override
    public int hashCode() {
      return 0;
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof BroadcastHashJoin;
    }
  }

}
