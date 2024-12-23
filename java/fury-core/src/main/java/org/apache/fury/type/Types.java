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

package org.apache.fury.type;

public class Types {
  public static final int BOOL = 1;
  public static final int INT8 = 2;
  public static final int INT16 = 3;
  public static final int INT32 = 4;
  public static final int VAR_INT32 = 5;
  public static final int INT64 = 6;
  public static final int VAR_INT64 = 7;
  public static final int SLI_INT64 = 8;
  public static final int FLOAT16 = 9;
  public static final int FLOAT32 = 10;
  public static final int FLOAT64 = 11;
  public static final int STRING = 12;
  public static final int ENUM = 13;
  public static final int NAMED_ENUM = 14;
  public static final int STRUCT = 15;
  public static final int POLYMORPHIC_STRUCT = 16;
  public static final int COMPATIBLE_STRUCT = 17;
  public static final int POLYMORPHIC_COMPATIBLE_STRUCT = 18;
  public static final int NAMED_STRUCT = 19;
  public static final int NAMED_POLYMORPHIC_STRUCT = 20;
  public static final int NAMED_COMPATIBLE_STRUCT = 21;
  public static final int NAMED_POLYMORPHIC_COMPATIBLE_STRUCT = 22;
  public static final int EXT = 23;
  public static final int POLYMORPHIC_EXT = 24;
  public static final int NAMED_EXT = 25;
  public static final int NAMED_POLYMORPHIC_EXT = 26;
  public static final int LIST = 27;
  public static final int SET = 28;
  public static final int MAP = 29;
  public static final int DURATION = 30;
  public static final int TIMESTAMP = 31;
  public static final int LOCAL_DATE = 32;
  public static final int DECIMAL = 33;
  public static final int BINARY = 34;
  public static final int ARRAY = 35;
  public static final int BOOL_ARRAY = 36;
  public static final int INT8_ARRAY = 37;
  public static final int INT16_ARRAY = 38;
  public static final int INT32_ARRAY = 39;
  public static final int INT64_ARRAY = 40;
  public static final int FLOAT16_ARRAY = 41;
  public static final int FLOAT32_ARRAY = 42;
  public static final int FLOAT64_ARRAY = 43;
  public static final int ARROW_RECORD_BATCH = 44;
  public static final int ARROW_TABLE = 45;

  public static boolean isStructType(int value) {
    return value == STRUCT
        || value == POLYMORPHIC_STRUCT
        || value == COMPATIBLE_STRUCT
        || value == POLYMORPHIC_COMPATIBLE_STRUCT
        || value == NAMED_STRUCT
        || value == NAMED_POLYMORPHIC_STRUCT
        || value == NAMED_COMPATIBLE_STRUCT
        || value == NAMED_POLYMORPHIC_COMPATIBLE_STRUCT;
  }
}
