/*
 * Copyright 2023 The Fury Authors
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

package io.fury.benchmark;

import com.google.common.base.Preconditions;
import io.fury.Fury;
import io.fury.memory.MemoryBuffer;
import io.fury.serializer.StringSerializer;
import io.fury.util.Platform;
import io.fury.util.ReflectionUtils;
import io.fury.util.StringUtils;
import org.openjdk.jmh.Main;

public class NewJava11StringSuite {

  static String str = StringUtils.random(10);
  static byte[] strBytes;
  static byte coder;

  static {
    if (Platform.JAVA_VERSION > 8) {
      strBytes =
          (byte[]) Platform.getObject(str, ReflectionUtils.getFieldOffset(String.class, "value"));
      coder = Platform.getByte(str, ReflectionUtils.getFieldOffset(String.class, "coder"));
    }
  }

  private static final long STRING_VALUE_FIELD_OFFSET =
      ReflectionUtils.getFieldOffset(String.class, "value");;
  private static final long STRING_CODER_FIELD_OFFSET =
      ReflectionUtils.getFieldOffset(String.class, "coder");

  private static String stubStr = new String(new char[] {Character.MAX_VALUE, Character.MIN_VALUE});
  private static Fury fury =
      Fury.builder().withStringCompressed(true).requireClassRegistration(false).build();
  private static StringSerializer stringSerializer = new StringSerializer(fury);
  private static MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(512);

  static {
    stringSerializer.writeJavaString(buffer, str);
  }

  // @Benchmark
  public Object createJDK11StringByCopyStr() {
    return new String(str);
  }

  // @Benchmark
  public Object createJDK11StringByUnsafe() {
    String str = new String(stubStr);
    Platform.putObject(str, STRING_VALUE_FIELD_OFFSET, strBytes);
    Platform.putObject(str, STRING_CODER_FIELD_OFFSET, coder);
    return str;
  }

  // @Benchmark
  public Object createJDK8StringByMethodHandle() {
    return StringSerializer.newJava11StringByZeroCopy(coder, strBytes);
  }

  // @Benchmark
  public Object createJDK8StringByFury() {
    buffer.readerIndex(0);
    return stringSerializer.readJavaString(buffer);
  }

  public static void main(String[] args) throws Exception {
    Preconditions.checkArgument(new NewJava11StringSuite().createJDK11StringByUnsafe().equals(str));
    if (args.length == 0) {
      String commandLine = "io.*NewJava11StringSuite.* -f 3 -wi 5 -i 3 -t 1 -w 2s -r 2s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }
}
