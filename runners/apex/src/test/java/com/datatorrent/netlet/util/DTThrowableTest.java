/*
 * Copyright (c) 2013 DataTorrent, Inc. ALL Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.netlet.util;

import org.junit.Test;

import static com.datatorrent.netlet.util.DTThrowable.rethrow;

public class DTThrowableTest
{
  @Test
  public void testRethrow_Throwable()
  {
    try {
    }
    catch (Throwable th) {
      rethrow(th);
    }
  }

  @Test
  public void testRethrow_Exception()
  {
    try {
    }
    catch (Exception th) {
      rethrow(th);
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void testRethrow_Error()
  {
    try {
    }
    catch (Error th) {
      rethrow(th);
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void testRethrow_RuntimeException()
  {
    try {
    }
    catch (RuntimeException th) {
      rethrow(th);
    }
  }

}