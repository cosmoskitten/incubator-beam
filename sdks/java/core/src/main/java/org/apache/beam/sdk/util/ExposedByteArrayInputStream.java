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
package org.apache.beam.sdk.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * {@link ByteArrayInputStream} that allows accessing the entire internal buffer without copying.
 */
public class ExposedByteArrayInputStream extends ByteArrayInputStream {

  public ExposedByteArrayInputStream(byte[] buf) {
    super(buf);
  }

  /** Read all remaining bytes. */
  public byte[] readAll() throws IOException {
    if (pos == 0 && count == buf.length) {
      pos = count;
      return buf;
    }
    byte[] ret = new byte[count - pos];
    super.read(ret);
    return ret;
  }

  @Override
  public void close() {
    try {
      super.close();
    } catch (IOException exn) {
      throw new RuntimeException("Unexpected IOException closing ByteArrayInputStream", exn);
    }
  }
}
