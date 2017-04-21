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

package org.apache.beam.runners.dataflow.util;

import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CustomCoder;
import org.apache.beam.sdk.coders.IterableCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.LengthPrefixCoder;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.util.CloudObject;
import org.apache.beam.sdk.util.PropertyNames;
import org.apache.beam.sdk.util.SerializableUtils;
import org.apache.beam.sdk.util.StringUtils;
import org.apache.beam.sdk.util.Structs;
import org.apache.beam.sdk.util.WindowedValue;

/** Utilities for converting an object to a {@link CloudObject}. */
public class CloudObjects {
  private CloudObjects() {}

  static final Map<Class<? extends Coder>, CloudObjectTranslator<? extends Coder>>
      CODER_TRANSLATORS = defaultCoderTranslators();

  private static Map<Class<? extends Coder>, CloudObjectTranslator<? extends Coder>>
      defaultCoderTranslators() {
    return ImmutableMap.<Class<? extends Coder>, CloudObjectTranslator<? extends Coder>>builder()
        .put(GlobalWindow.Coder.class, CloudObjectTranslators.globalWindow())
        .put(IntervalWindow.IntervalWindowCoder.class, CloudObjectTranslators.intervalWindow())
        .put(ByteArrayCoder.class, CloudObjectTranslators.bytes())
        .put(VarLongCoder.class, CloudObjectTranslators.varInt())
        .put(LengthPrefixCoder.class, CloudObjectTranslators.lengthPrefix())
        .put(IterableCoder.class, CloudObjectTranslators.stream())
        .put(KvCoder.class, CloudObjectTranslators.pair())
        .put(WindowedValue.FullWindowedValueCoder.class, CloudObjectTranslators.windowedValue())
        .build();
  }

  public static CloudObject asCloudObject(Coder<?> coder) {
    CloudObjectTranslator<Coder<?>> translator =
        (CloudObjectTranslator<Coder<?>>) CODER_TRANSLATORS.get(coder.getClass());
    if (translator != null) {
      return translator.toCloudObject(coder);
    } else if (coder instanceof CustomCoder) {
      return customCoderAsCloudObject((CustomCoder<?>) coder);
    }
    throw new IllegalArgumentException(
        String.format(
            "Non-Custom %s with no registered %s", Coder.class, CloudObjectTranslator.class));
  }

  private static CloudObject customCoderAsCloudObject(CustomCoder<?> coder) {
    CloudObject result = CloudObject.forClass(CustomCoder.class);
    Structs.addString(result, "type", coder.getClass().getName());
    Structs.addString(
        result,
        "serialized_coder",
        StringUtils.byteArrayToJsonString(SerializableUtils.serializeToByteArray(coder)));

    return result;
  }

  public static void addComponentCodersToCloudObject(
      CloudObject target, List<Coder<?>> components) {
    List<CloudObject> componentCoders = new ArrayList<>();
    for (Coder<?> coder : components) {
      componentCoders.add(asCloudObject(coder));
    }
    Structs.addList(target, PropertyNames.COMPONENT_ENCODINGS, componentCoders);
  }
}
