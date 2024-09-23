/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fury.graalvm;

import org.apache.fury.Fury;
import org.apache.fury.config.CompatibleMode;

public class ScopedCompatibleExample {
  private static Fury fury;

  static {
    fury = createFury();
  }

  private static Fury createFury() {
    Fury fury =
        Fury.builder()
            .withName(ScopedCompatibleExample.class.getName())
            .requireClassRegistration(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withScopedMetaShare(true)
            .build();
    // register and generate serializer code.
    fury.register(Foo.class, true);
    return fury;
  }

  public static void main(String[] args) {
    Example.test(fury);
    fury = createFury();
    Example.test(fury);
    System.out.println("CompatibleExample succeed");
  }
}
