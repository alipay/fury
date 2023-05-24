/*
 * Copyright 2023 The Fury authors
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fury.io;

import io.fury.memory.MemoryBuffer;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * OutputStream based on {@link MemoryBuffer}.
 *
 * @author chaokunyang
 */
public class FuryOutputStream extends OutputStream {
  private final MemoryBuffer buffer;

  public FuryOutputStream(MemoryBuffer buffer) {
    this.buffer = buffer;
  }

  public void write(int b) {
    buffer.writeByte((byte) b);
  }

  public void write(byte[] bytes, int offset, int length) {
    buffer.writeBytes(bytes, offset, length);
  }

  public void write(ByteBuffer byteBuffer, int numBytes) {
    buffer.write(byteBuffer, numBytes);
  }
}
