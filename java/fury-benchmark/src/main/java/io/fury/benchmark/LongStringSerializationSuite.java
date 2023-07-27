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

import io.fury.benchmark.state.FstState;
import io.fury.benchmark.state.FuryState;
import io.fury.benchmark.state.HessionState;
import io.fury.benchmark.state.JDKState;
import io.fury.benchmark.state.KryoState;
import io.fury.benchmark.state.ProtostuffState;
import java.io.ByteArrayOutputStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.Throughput)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class LongStringSerializationSuite {
  @Benchmark
  public Object kryo_serializeLongStr(KryoState.DataState state) {
    state.output.setPosition(0);
    state.kryo.writeClassAndObject(state.output, state.data.longStr);
    return state.output;
  }

  @Benchmark
  public Object fury_serializeLongStr(FuryState.DataState state) {
    state.buffer.writerIndex(0);
    state.fury.serialize(state.buffer, state.data.longStr);
    return state.buffer;
  }

  @Benchmark
  public byte[] fst_serializeLongStr(FstState.DataState state, Blackhole bh) {
    return FstState.FstBenchmarkState.serialize(bh, state, state.data.longStr);
  }

  @Benchmark
  public ByteArrayOutputStream hession_serializeLongStr(HessionState.DataState state) {
    state.bos.reset();
    state.out.reset();
    HessionState.serialize(state.out, state.data.longStr);
    return state.bos;
  }

  @Benchmark
  public byte[] protostuff_serializeLongStr(ProtostuffState.ReadLongStrState state) {
    return ProtostuffState.serialize(state.data.longStr, state.schema, state.buffer);
  }

  @Benchmark
  public ByteArrayOutputStream jdk_serializeLongStr(JDKState.DataState state) {
    state.bos.reset();
    JDKState.serialize(state.bos, state.data.longStr);
    return state.bos;
  }
}
