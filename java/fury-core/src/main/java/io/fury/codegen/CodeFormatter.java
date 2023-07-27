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

package io.fury.codegen;

/**
 * Code formatter to format generated code for better readbility.
 *
 * @author chaokunyang
 */
public class CodeFormatter {

  /** Format code to add line number for debug. */
  public static String format(String code) {
    StringBuilder codeBuilder = new StringBuilder(code.length());
    String[] split = code.split("\n", -1);
    int lineCount = 0;
    for (int i = 0; i < split.length; i++) {
      lineCount++;
      codeBuilder.append(String.format("/* %04d */ ", lineCount));
      codeBuilder.append(split[i]).append('\n');
    }
    if (code.charAt(code.length() - 1) == '\n') {
      return codeBuilder.toString();
    } else {
      // remove extra newline character
      return codeBuilder.substring(0, codeBuilder.length() - 1);
    }
  }
}
