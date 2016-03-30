/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.integration.nexmark;

import com.google.cloud.dataflow.sdk.coders.AtomicCoder;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.CoderException;
import com.google.cloud.dataflow.sdk.coders.VarLongCoder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * Result of {@link Query5}.
 */
public class AuctionCount implements KnownSize, Serializable {
  private static final Coder<Long> LONG_CODER = VarLongCoder.of();

  public static final Coder<AuctionCount> CODER = new AtomicCoder<AuctionCount>() {
    @Override
    public void encode(AuctionCount value, OutputStream outStream,
        com.google.cloud.dataflow.sdk.coders.Coder.Context context)
        throws CoderException, IOException {
      LONG_CODER.encode(value.auction, outStream, Context.NESTED);
      LONG_CODER.encode(value.count, outStream, Context.NESTED);
    }

    @Override
    public AuctionCount decode(
        InputStream inStream, com.google.cloud.dataflow.sdk.coders.Coder.Context context)
        throws CoderException, IOException {
      long auction = LONG_CODER.decode(inStream, Context.NESTED);
      long count = LONG_CODER.decode(inStream, Context.NESTED);
      return new AuctionCount(auction, count);
    }
  };

  @JsonProperty
  public final long auction;

  @JsonProperty
  public final long count;

  // For Avro only.
  @SuppressWarnings("unused")
  private AuctionCount() {
    auction = 0;
    count = 0;
  }

  public AuctionCount(long auction, long count) {
    this.auction = auction;
    this.count = count;
  }

  @Override
  public long sizeInBytes() {
    return 8 + 8;
  }

  @Override
  public String toString() {
    try {
      return NexmarkUtils.MAPPER.writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
}
