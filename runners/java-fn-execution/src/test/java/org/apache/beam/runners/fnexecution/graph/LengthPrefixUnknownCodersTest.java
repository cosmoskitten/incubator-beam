/*
 * Copyright (C) 2017 Google Inc.
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
package org.apache.beam.runners.fnexecution.graph;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import org.apache.beam.model.pipeline.v1.RunnerApi.Components;
import org.apache.beam.model.pipeline.v1.RunnerApi.MessageWithComponents;
import org.apache.beam.runners.core.construction.CoderTranslation;
import org.apache.beam.runners.core.construction.RehydratedComponents;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.coders.CustomCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.LengthPrefixCoder;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.util.WindowedValue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Tests for {@link LengthPrefixUnknownCoders}. */
@RunWith(Parameterized.class)
public class LengthPrefixUnknownCodersTest {

  private static class UnknownCoder extends CustomCoder<String> {
    private static final Coder<?> INSTANCE = new UnknownCoder();
    @Override
    public void encode(String value, OutputStream outStream) throws CoderException, IOException {
    }

    @Override
    public String decode(InputStream inStream) throws CoderException, IOException {
      return "";
    }

    @Override
    public int hashCode() {
      return 1278890232;
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof  UnknownCoder;
    }
  }

  @Parameters
  public static Collection<Object[]> data() {
    return ImmutableList.of(
        /** Test wrapping unknown coders with {@code LengthPrefixCoder}. */
        new Object[] {
            WindowedValue.getFullCoder(
                KvCoder.of(UnknownCoder.INSTANCE, UnknownCoder.INSTANCE),
                GlobalWindow.Coder.INSTANCE),
            WindowedValue.getFullCoder(
                KvCoder.of(LengthPrefixCoder.of(UnknownCoder.INSTANCE),
                    LengthPrefixCoder.of(UnknownCoder.INSTANCE)),
                GlobalWindow.Coder.INSTANCE),
            false
        },
        /**
         * Test bypassing unknown coders that are already wrapped with
         * {@code LengthPrefixCoder}.
         */
        new Object[] {
            WindowedValue.getFullCoder(
                KvCoder.of(UnknownCoder.INSTANCE,
                    LengthPrefixCoder.of(UnknownCoder.INSTANCE)),
                GlobalWindow.Coder.INSTANCE),
            WindowedValue.getFullCoder(
                KvCoder.of(LengthPrefixCoder.of(UnknownCoder.INSTANCE),
                    LengthPrefixCoder.of(UnknownCoder.INSTANCE)),
                GlobalWindow.Coder.INSTANCE),
            false
        },
        /** Test replacing unknown coders with {@code LengthPrefixCoder<ByteArray>}. */
        new Object[] {
            WindowedValue.getFullCoder(
                KvCoder.of(LengthPrefixCoder.of(UnknownCoder.INSTANCE),
                    UnknownCoder.INSTANCE),
                GlobalWindow.Coder.INSTANCE),
            WindowedValue.getFullCoder(
                KvCoder.of(LengthPrefixCoder.of(ByteArrayCoder.of()),
                    LengthPrefixCoder.of(ByteArrayCoder.of())),
                GlobalWindow.Coder.INSTANCE),
            true
        },
        /** Test skipping a top level length prefix coder. */
        new Object[] {
            LengthPrefixCoder.of(UnknownCoder.INSTANCE),
            LengthPrefixCoder.of(UnknownCoder.INSTANCE),
            false
        },
        /** Test replacing a top level length prefix coder with byte array coder. */
        new Object[] {
            LengthPrefixCoder.of(UnknownCoder.INSTANCE),
            LengthPrefixCoder.of(ByteArrayCoder.of()),
            true
        }
    );
  }

  @Parameter
  public Coder<?> original;

  @Parameter(1)
  public Coder<?> expected;

  @Parameter(2)
  public boolean replaceWithByteArray;

  @Test
  public void test() throws IOException {
    MessageWithComponents originalCoderProto = CoderTranslation.toProto(original);
    Components.Builder builder = originalCoderProto.getComponents().toBuilder();
    String coderId = LengthPrefixUnknownCoders.generateUniqueId("rootTestId",
        originalCoderProto.getComponents().getCodersMap().keySet());
    builder.putCoders(coderId, originalCoderProto.getCoder());
    MessageWithComponents updatedCoderProto = LengthPrefixUnknownCoders.forCoder(
        coderId, builder.build(), replaceWithByteArray);
    assertEquals(expected,
        CoderTranslation.fromProto(updatedCoderProto.getCoder(),
            RehydratedComponents.forComponents(updatedCoderProto.getComponents())));
  }
}
