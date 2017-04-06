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
package org.apache.beam.sdk.testing;

import java.io.Serializable;

/**
 * Track the place where an assertion is defined.
 * This is necessary because the stack trace of a Throwable is a transient attribute, and can't
 * be serialized. {@link PAssertionSite} helps track the stack trace of the place where an
 * assertion is issued.
 */
public class PAssertionSite implements Serializable {
  private final String message;
  private final SerializableStackTraceElement[] creationStackTrace;

  static PAssertionSite capture(String message) {
    return new PAssertionSite(message, new Throwable().getStackTrace());
  }

  PAssertionSite() {
    this(null, new StackTraceElement[0]);
  }

  PAssertionSite(String message, StackTraceElement[] creationStackTrace) {
    this.message = message;
    this.creationStackTrace = toSerializableStackTraceElementArray(creationStackTrace);
  }

  private SerializableStackTraceElement[] toSerializableStackTraceElementArray(
      StackTraceElement[] input) {
    SerializableStackTraceElement[] result = new SerializableStackTraceElement[input.length];
    for (int i = 0; i < input.length; i++) {
      result[i] = new SerializableStackTraceElement(
          input[i].getClassName(),
          input[i].getMethodName(),
          input[i].getFileName(),
          input[i].getLineNumber());
    }
    return result;
  }

  private StackTraceElement[] toStackTraceElementArray(SerializableStackTraceElement[] input) {
    StackTraceElement[] result = new StackTraceElement[input.length];
    for (int i = 0; i < input.length; i++) {
      result[i] = input[i].getStackTraceElement();
    }
    return result;
  }

  public AssertionError wrap(Throwable t) {
    AssertionError res =
        new AssertionError(
            message.isEmpty() ? t.getMessage() : (message + ": " + t.getMessage()), t);
    res.setStackTrace(toStackTraceElementArray(creationStackTrace));
    return res;
  }

  public AssertionError wrap(String message) {
    String outputMessage = (this.message == null || this.message.isEmpty())
        ? message : (this.message + ": " + message);
    AssertionError res = new AssertionError(outputMessage);
    res.setStackTrace(toStackTraceElementArray(creationStackTrace));
    return res;
  }

  static final class SerializableStackTraceElement implements Serializable {
    private String declaringClass;
    private String methodName;
    private String fileName;
    private int    lineNumber;

    private SerializableStackTraceElement() {
      this(null, null, null, 0);
    }

    SerializableStackTraceElement(String declaringClass, String methodName,
        String fileName, int lineNumber) {
      this.declaringClass = declaringClass;
      this.methodName = methodName;
      this.fileName = fileName;
      this.lineNumber = lineNumber;
    }
    public StackTraceElement getStackTraceElement() {
      return new StackTraceElement(declaringClass, methodName, fileName, lineNumber);
    }
  }
}
