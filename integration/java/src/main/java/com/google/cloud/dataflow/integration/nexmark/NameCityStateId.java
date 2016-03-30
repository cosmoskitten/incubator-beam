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
import com.google.cloud.dataflow.sdk.coders.StringUtf8Coder;
import com.google.cloud.dataflow.sdk.coders.VarLongCoder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * Result of {@link Query3}.
 */
public class NameCityStateId implements KnownSize, Serializable {
  private static final Coder<Long> LONG_CODER = VarLongCoder.of();
  private static final Coder<String> STRING_CODER = StringUtf8Coder.of();

  public static final Coder<NameCityStateId> CODER = new AtomicCoder<NameCityStateId>() {
    @Override
    public void encode(NameCityStateId value, OutputStream outStream,
        com.google.cloud.dataflow.sdk.coders.Coder.Context context)
        throws CoderException, IOException {
      STRING_CODER.encode(value.name, outStream, Context.NESTED);
      STRING_CODER.encode(value.city, outStream, Context.NESTED);
      STRING_CODER.encode(value.state, outStream, Context.NESTED);
      LONG_CODER.encode(value.id, outStream, Context.NESTED);
    }

    @Override
    public NameCityStateId decode(
        InputStream inStream, com.google.cloud.dataflow.sdk.coders.Coder.Context context)
        throws CoderException, IOException {
      String name = STRING_CODER.decode(inStream, Context.NESTED);
      String city = STRING_CODER.decode(inStream, Context.NESTED);
      String state = STRING_CODER.decode(inStream, Context.NESTED);
      long id = LONG_CODER.decode(inStream, Context.NESTED);
      return new NameCityStateId(name, city, state, id);
    }
  };

  @JsonProperty
  public final String name;

  @JsonProperty
  public final String city;

  @JsonProperty
  public final String state;

  @JsonProperty
  public final long id;

  // For Avro only.
  @SuppressWarnings("unused")
  private NameCityStateId() {
    name = null;
    city = null;
    state = null;
    id = 0;
  }

  public NameCityStateId(String name, String city, String state, long id) {
    this.name = name;
    this.city = city;
    this.state = state;
    this.id = id;
  }

  @Override
  public long sizeInBytes() {
    return name.length() + 1 + city.length() + 1 + state.length() + 1 + 8;
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
