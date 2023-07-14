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

package io.fury.serializer;

import static io.fury.type.TypeUtils.MAP_TYPE;
import static io.fury.type.TypeUtils.getRawType;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import io.fury.Fury;
import io.fury.Language;
import io.fury.collection.IdentityMap;
import io.fury.collection.LazyMap;
import io.fury.collection.Tuple2;
import io.fury.memory.MemoryBuffer;
import io.fury.resolver.ClassInfoCache;
import io.fury.resolver.ClassResolver;
import io.fury.resolver.RefResolver;
import io.fury.type.GenericType;
import io.fury.type.Generics;
import io.fury.type.Type;
import io.fury.type.TypeUtils;
import io.fury.util.Platform;
import io.fury.util.ReflectionUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Serializers for classes implements {@link Collection}. All map serializers must extends {@link
 * MapSerializer}.
 *
 * @author chaokunyang
 */
@SuppressWarnings({"unchecked", "rawtypes", "UnstableApiUsage"})
public class MapSerializers {

  public static class MapSerializer<T extends Map> extends Serializer<T> {
    protected Constructor<?> constructor;
    protected final boolean supportCodegenHook;
    private Serializer keySerializer;
    private Serializer valueSerializer;
    private final ClassInfoCache keyClassInfoWriteCache;
    private final ClassInfoCache keyClassInfoReadCache;
    private final ClassInfoCache valueClassInfoWriteCache;
    private final ClassInfoCache valueClassInfoReadCache;
    // support map subclass whose key or value generics only are available,
    // or one of types is already instantiated in subclass, ex: `Subclass<T> implements Map<String,
    // T>`
    private final IdentityMap<GenericType, Tuple2<GenericType, GenericType>>
        partialGenericKVTypeMap;
    // support subclass whose kv types are instantiated already, such as
    // `Subclass implements Map<String, Long>`.
    // nested generics such as `Subclass extends HashMap<String, List<Integer>>` can only be passed
    // by
    // `pushGenerics` instead of set value serializers.
    private final GenericType mapGenericType;
    private final GenericType objType = fury.getClassResolver().buildGenericType(Object.class);

    public MapSerializer(Fury fury, Class<T> cls) {
      this(fury, cls, !ReflectionUtils.isDynamicGeneratedCLass(cls), true);
    }

    public MapSerializer(
        Fury fury, Class<T> cls, boolean supportCodegenHook, boolean inferGenerics) {
      super(fury, cls);
      this.supportCodegenHook = supportCodegenHook;
      keyClassInfoWriteCache = fury.getClassResolver().nilClassInfoCache();
      keyClassInfoReadCache = fury.getClassResolver().nilClassInfoCache();
      valueClassInfoWriteCache = fury.getClassResolver().nilClassInfoCache();
      valueClassInfoReadCache = fury.getClassResolver().nilClassInfoCache();
      partialGenericKVTypeMap = new IdentityMap<>();
      if (inferGenerics) {
        Tuple2<TypeToken<?>, TypeToken<?>> kvTypes =
            TypeUtils.getMapKeyValueType(TypeToken.of(cls));
        if (getRawType(kvTypes.f0) != Object.class || getRawType(kvTypes.f1) != Object.class) {
          mapGenericType =
              fury.getClassResolver()
                  .buildGenericType(TypeUtils.mapOf(kvTypes.f0, kvTypes.f1).getType());
        } else {
          mapGenericType = null;
        }
      } else {
        mapGenericType = null;
      }
    }

    /**
     * Set key serializer for next serialization, the <code>serializer</code> will be cleared when
     * next serialization finished.
     */
    public void setKeySerializer(Serializer keySerializer) {
      this.keySerializer = keySerializer;
    }

    /**
     * Set value serializer for next serialization, the <code>serializer</code> will be cleared when
     * next serialization finished.
     */
    public void setValueSerializer(Serializer valueSerializer) {
      this.valueSerializer = valueSerializer;
    }

    @Override
    public void write(MemoryBuffer buffer, T value) {
      buffer.writePositiveVarInt(value.size());
      writeHeader(buffer, value);
      writeElements(fury, buffer, value);
    }

    @Override
    public void xwrite(MemoryBuffer buffer, T value) {
      buffer.writePositiveVarInt(value.size());
      xwriteElements(fury, buffer, value);
    }

    protected final void writeElements(Fury fury, MemoryBuffer buffer, T map) {
      Serializer keySerializer = this.keySerializer;
      Serializer valueSerializer = this.valueSerializer;
      // clear the elemSerializer to avoid conflict if the nested
      // serialization has collection field.
      // TODO use generics for compatible serializer.
      this.keySerializer = null;
      this.valueSerializer = null;
      if (keySerializer != null && valueSerializer != null) {
        javaWriteWithKVSerializers(fury, buffer, map, keySerializer, valueSerializer);
      } else if (keySerializer != null) {
        ClassResolver classResolver = fury.getClassResolver();
        RefResolver refResolver = fury.getRefResolver();
        for (Object object : map.entrySet()) {
          Map.Entry entry = (Map.Entry) object;
          fury.writeRef(buffer, entry.getKey(), keySerializer);
          Object value = entry.getValue();
          writeJavaRefOptimized(
              fury, classResolver, refResolver, buffer, value, valueClassInfoWriteCache);
        }
      } else if (valueSerializer != null) {
        ClassResolver classResolver = fury.getClassResolver();
        RefResolver refResolver = fury.getRefResolver();
        for (Object object : map.entrySet()) {
          Map.Entry entry = (Map.Entry) object;
          Object key = entry.getKey();
          writeJavaRefOptimized(
              fury, classResolver, refResolver, buffer, key, keyClassInfoWriteCache);
          fury.writeRef(buffer, entry.getValue(), valueSerializer);
        }
      } else {
        genericJavaWrite(fury, buffer, map);
      }
    }

    private void javaWriteWithKVSerializers(
        Fury fury,
        MemoryBuffer buffer,
        T map,
        Serializer keySerializer,
        Serializer valueSerializer) {
      for (Object object : map.entrySet()) {
        Map.Entry entry = (Map.Entry) object;
        Object key = entry.getKey();
        Object value = entry.getValue();
        fury.writeRef(buffer, key, keySerializer);
        fury.writeRef(buffer, value, valueSerializer);
      }
    }

    private void genericJavaWrite(Fury fury, MemoryBuffer buffer, T map) {
      Generics generics = fury.getGenerics();
      GenericType genericType = generics.nextGenericType();
      if (genericType == null) {
        genericType = mapGenericType;
      }
      if (genericType == null) {
        generalJavaWrite(fury, buffer, map);
      } else {
        GenericType keyGenericType = genericType.getTypeParameter0();
        GenericType valueGenericType = genericType.getTypeParameter1();
        // type parameters count for `Map field` will be 0;
        // type parameters count for `SubMap<V> field` which SubMap is
        // `SubMap<V> implements Map<String, V>` will be 1;
        if (genericType.getTypeParametersCount() < 2) {
          Tuple2<GenericType, GenericType> kvGenericType = getKVGenericType(genericType);
          if (keyGenericType == objType && valueGenericType == objType) {
            generalJavaWrite(fury, buffer, map);
            return;
          }
          keyGenericType = kvGenericType.f0;
          valueGenericType = kvGenericType.f1;
        }
        // Can't avoid push generics repeatedly in loop by stack depth, because push two
        // generic type changed generics stack top, which is depth index, update stack top
        // and depth will have some cost too.
        // Stack depth to avoid push generics repeatedly in loop.
        // Note push two generic type changed generics stack top, which is depth index,
        // stack top should be updated when using for serialization k/v.
        // int depth = fury.getDepth();
        // // depth + 1 to leave a slot for value generics, otherwise value generics will
        // // be overwritten by nested key generics.
        // fury.setDepth(depth + 1);
        // generics.pushGenericType(keyGenericType);
        // fury.setDepth(depth);
        // generics.pushGenericType(valueGenericType);
        boolean keyGenericTypeFinal = keyGenericType.isFinal();
        boolean valueGenericTypeFinal = valueGenericType.isFinal();
        if (keyGenericTypeFinal && valueGenericTypeFinal) {
          javaKVTypesFinalWrite(fury, buffer, map, keyGenericType, valueGenericType, generics);
        } else if (keyGenericTypeFinal) {
          javaKeyTypeFinalWrite(fury, buffer, map, keyGenericType, valueGenericType, generics);
        } else if (valueGenericTypeFinal) {
          javaValueTypeFinalWrite(fury, buffer, map, keyGenericType, valueGenericType, generics);
        } else {
          javaKVTypesNonFinalWrite(fury, buffer, map, keyGenericType, valueGenericType, generics);
        }
      }
    }

    private void javaKVTypesFinalWrite(
        Fury fury,
        MemoryBuffer buffer,
        T map,
        GenericType keyGenericType,
        GenericType valueGenericType,
        Generics generics) {
      Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
      Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
      for (Object object : map.entrySet()) {
        Map.Entry entry = (Map.Entry) object;
        generics.pushGenericType(keyGenericType);
        fury.writeRef(buffer, entry.getKey(), keySerializer);
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        fury.writeRef(buffer, entry.getValue(), valueSerializer);
        generics.popGenericType();
      }
    }

    private void javaKeyTypeFinalWrite(
        Fury fury,
        MemoryBuffer buffer,
        T map,
        GenericType keyGenericType,
        GenericType valueGenericType,
        Generics generics) {
      ClassResolver classResolver = fury.getClassResolver();
      RefResolver refResolver = fury.getRefResolver();
      boolean trackingValueRef = fury.getClassResolver().needToWriteRef(valueGenericType.getCls());
      Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
      for (Object object : map.entrySet()) {
        Map.Entry entry = (Map.Entry) object;
        generics.pushGenericType(keyGenericType);
        fury.writeRef(buffer, entry.getKey(), keySerializer);
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        writeJavaRefOptimized(
            fury,
            classResolver,
            refResolver,
            trackingValueRef,
            buffer,
            entry.getValue(),
            valueClassInfoWriteCache);
        generics.popGenericType();
      }
    }

    private void javaValueTypeFinalWrite(
        Fury fury,
        MemoryBuffer buffer,
        T map,
        GenericType keyGenericType,
        GenericType valueGenericType,
        Generics generics) {
      ClassResolver classResolver = fury.getClassResolver();
      RefResolver refResolver = fury.getRefResolver();
      boolean trackingKeyRef = fury.getClassResolver().needToWriteRef(keyGenericType.getCls());
      Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
      for (Object object : map.entrySet()) {
        Map.Entry entry = (Map.Entry) object;
        generics.pushGenericType(keyGenericType);
        writeJavaRefOptimized(
            fury,
            classResolver,
            refResolver,
            trackingKeyRef,
            buffer,
            entry.getKey(),
            keyClassInfoWriteCache);
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        fury.writeRef(buffer, entry.getValue(), valueSerializer);
        generics.popGenericType();
      }
    }

    private void javaKVTypesNonFinalWrite(
        Fury fury,
        MemoryBuffer buffer,
        T map,
        GenericType keyGenericType,
        GenericType valueGenericType,
        Generics generics) {
      ClassResolver classResolver = fury.getClassResolver();
      RefResolver refResolver = fury.getRefResolver();
      boolean trackingKeyRef = fury.getClassResolver().needToWriteRef(keyGenericType.getCls());
      boolean trackingValueRef = fury.getClassResolver().needToWriteRef(valueGenericType.getCls());
      for (Object object : map.entrySet()) {
        Map.Entry entry = (Map.Entry) object;
        generics.pushGenericType(keyGenericType);
        writeJavaRefOptimized(
            fury,
            classResolver,
            refResolver,
            trackingKeyRef,
            buffer,
            entry.getKey(),
            keyClassInfoWriteCache);
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        writeJavaRefOptimized(
            fury,
            classResolver,
            refResolver,
            trackingValueRef,
            buffer,
            entry.getValue(),
            valueClassInfoWriteCache);
        generics.popGenericType();
      }
    }

    private void generalJavaWrite(Fury fury, MemoryBuffer buffer, T map) {
      ClassResolver classResolver = fury.getClassResolver();
      RefResolver refResolver = fury.getRefResolver();
      for (Object object : map.entrySet()) {
        Map.Entry entry = (Map.Entry) object;
        writeJavaRefOptimized(
            fury, classResolver, refResolver, buffer, entry.getKey(), keyClassInfoWriteCache);
        writeJavaRefOptimized(
            fury, classResolver, refResolver, buffer, entry.getValue(), valueClassInfoWriteCache);
      }
    }

    public static void xwriteElements(Fury fury, MemoryBuffer buffer, Map value) {
      Generics generics = fury.getGenerics();
      GenericType genericType = generics.nextGenericType();
      // TODO(chaokunyang) support map subclass whose key or value generics only are available.
      if (genericType == null || genericType.getTypeParametersCount() != 2) {
        for (Object object : value.entrySet()) {
          Map.Entry entry = (Map.Entry) object;
          fury.xwriteRef(buffer, entry.getKey());
          fury.xwriteRef(buffer, entry.getValue());
        }
      } else {
        // TODO(chaokunyang) use codegen to remove all branches.
        GenericType keyGenericType = genericType.getTypeParameter0();
        GenericType valueGenericType = genericType.getTypeParameter1();
        Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
        Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
        if (!keyGenericType.hasGenericParameters() && !valueGenericType.hasGenericParameters()) {
          for (Object object : value.entrySet()) {
            Map.Entry entry = (Map.Entry) object;
            fury.xwriteRefByNullableSerializer(buffer, entry.getKey(), keySerializer);
            fury.xwriteRefByNullableSerializer(buffer, entry.getValue(), valueSerializer);
          }
        } else if (valueGenericType.hasGenericParameters()) {
          for (Object object : value.entrySet()) {
            Map.Entry entry = (Map.Entry) object;
            fury.xwriteRefByNullableSerializer(buffer, entry.getKey(), keySerializer);
            generics.pushGenericType(valueGenericType);
            fury.xwriteRefByNullableSerializer(buffer, entry.getValue(), valueSerializer);
            generics.popGenericType();
          }
        } else if (keyGenericType.hasGenericParameters()) {
          for (Object object : value.entrySet()) {
            Map.Entry entry = (Map.Entry) object;
            generics.pushGenericType(keyGenericType);
            fury.xwriteRefByNullableSerializer(buffer, entry.getKey(), keySerializer);
            generics.popGenericType();
            fury.xwriteRefByNullableSerializer(buffer, entry.getValue(), valueSerializer);
          }
        } else {
          for (Object object : value.entrySet()) {
            Map.Entry entry = (Map.Entry) object;
            generics.pushGenericType(keyGenericType);
            fury.xwriteRefByNullableSerializer(buffer, entry.getKey(), keySerializer);
            generics.pushGenericType(valueGenericType);
            fury.xwriteRefByNullableSerializer(buffer, entry.getValue(), valueSerializer);
          }
        }
        generics.popGenericType();
      }
    }

    private Tuple2<GenericType, GenericType> getKVGenericType(GenericType genericType) {
      Tuple2<GenericType, GenericType> genericTypes = partialGenericKVTypeMap.get(genericType);
      if (genericTypes == null) {
        TypeToken<?> typeToken = genericType.getTypeToken();
        if (!MAP_TYPE.isSupertypeOf(typeToken)) {
          Tuple2<GenericType, GenericType> typeTuple = Tuple2.of(objType, objType);
          partialGenericKVTypeMap.put(genericType, typeTuple);
          return typeTuple;
        }
        Tuple2<TypeToken<?>, TypeToken<?>> mapKeyValueType =
            TypeUtils.getMapKeyValueType(typeToken);
        genericTypes =
            Tuple2.of(
                fury.getClassResolver().buildGenericType(mapKeyValueType.f0.getType()),
                fury.getClassResolver().buildGenericType(mapKeyValueType.f1.getType()));
        partialGenericKVTypeMap.put(genericType, genericTypes);
      }
      return genericTypes;
    }

    @Override
    public T read(MemoryBuffer buffer) {
      int size = buffer.readPositiveVarInt();
      T map = newMap(buffer, size);
      readElements(buffer, size, map);
      return map;
    }

    @Override
    public T xread(MemoryBuffer buffer) {
      int size = buffer.readPositiveVarInt();
      T map = newMap(buffer, size);
      xreadElements(fury, buffer, map, size);
      return map;
    }

    @SuppressWarnings("unchecked")
    protected final void readElements(MemoryBuffer buffer, int size, T map) {
      Serializer keySerializer = this.keySerializer;
      Serializer valueSerializer = this.valueSerializer;
      // clear the elemSerializer to avoid conflict if the nested
      // serialization has collection field.
      // TODO use generics for compatible serializer.
      this.keySerializer = null;
      this.valueSerializer = null;
      if (keySerializer != null && valueSerializer != null) {
        for (int i = 0; i < size; i++) {
          Object key = fury.readRef(buffer, keySerializer);
          Object value = fury.readRef(buffer, valueSerializer);
          map.put(key, value);
        }
      } else if (keySerializer != null) {
        for (int i = 0; i < size; i++) {
          Object key = fury.readRef(buffer, keySerializer);
          map.put(key, fury.readRef(buffer, keyClassInfoReadCache));
        }
      } else if (valueSerializer != null) {
        for (int i = 0; i < size; i++) {
          Object key = fury.readRef(buffer);
          Object value = fury.readRef(buffer, valueSerializer);
          map.put(key, value);
        }
      } else {
        genericJavaRead(fury, buffer, map, size);
      }
    }

    private void genericJavaRead(Fury fury, MemoryBuffer buffer, T map, int size) {
      Generics generics = fury.getGenerics();
      GenericType genericType = generics.nextGenericType();
      if (genericType == null) {
        genericType = mapGenericType;
      }
      if (genericType == null) {
        generalJavaRead(fury, buffer, map, size);
      } else {
        GenericType keyGenericType = genericType.getTypeParameter0();
        GenericType valueGenericType = genericType.getTypeParameter1();
        if (genericType.getTypeParametersCount() < 2) {
          Tuple2<GenericType, GenericType> kvGenericType = getKVGenericType(genericType);
          if (keyGenericType == objType && valueGenericType == objType) {
            generalJavaRead(fury, buffer, map, size);
            return;
          }
          keyGenericType = kvGenericType.f0;
          valueGenericType = kvGenericType.f1;
        }
        boolean keyGenericTypeFinal = keyGenericType.isFinal();
        boolean valueGenericTypeFinal = valueGenericType.isFinal();
        if (keyGenericTypeFinal && valueGenericTypeFinal) {
          javaKVTypesFinalRead(fury, buffer, map, keyGenericType, valueGenericType, generics, size);
        } else if (keyGenericTypeFinal) {
          javaKeyTypeFinalRead(fury, buffer, map, keyGenericType, valueGenericType, generics, size);
        } else if (valueGenericTypeFinal) {
          javaValueTypeFinalRead(
              fury, buffer, map, keyGenericType, valueGenericType, generics, size);
        } else {
          javaKVTypesNonFinalRead(
              fury, buffer, map, keyGenericType, valueGenericType, generics, size);
        }
        generics.popGenericType();
      }
    }

    private void javaKVTypesFinalRead(
        Fury fury,
        MemoryBuffer buffer,
        T map,
        GenericType keyGenericType,
        GenericType valueGenericType,
        Generics generics,
        int size) {
      Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
      Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
      for (int i = 0; i < size; i++) {
        generics.pushGenericType(keyGenericType);
        Object key = fury.readRef(buffer, keySerializer);
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        Object value = fury.readRef(buffer, valueSerializer);
        generics.popGenericType();
        map.put(key, value);
      }
    }

    private void javaKeyTypeFinalRead(
        Fury fury,
        MemoryBuffer buffer,
        T map,
        GenericType keyGenericType,
        GenericType valueGenericType,
        Generics generics,
        int size) {
      RefResolver refResolver = fury.getRefResolver();
      boolean trackingValueRef = fury.getClassResolver().needToWriteRef(valueGenericType.getCls());
      Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
      for (int i = 0; i < size; i++) {
        generics.pushGenericType(keyGenericType);
        Object key = fury.readRef(buffer, keySerializer);
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        Object value =
            readJavaRefOptimized(
                fury, refResolver, trackingValueRef, buffer, valueClassInfoWriteCache);
        generics.popGenericType();
        map.put(key, value);
      }
    }

    private void javaValueTypeFinalRead(
        Fury fury,
        MemoryBuffer buffer,
        T map,
        GenericType keyGenericType,
        GenericType valueGenericType,
        Generics generics,
        int size) {
      boolean trackingKeyRef = fury.getClassResolver().needToWriteRef(keyGenericType.getCls());
      Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
      RefResolver refResolver = fury.getRefResolver();
      for (int i = 0; i < size; i++) {
        generics.pushGenericType(keyGenericType);
        Object key =
            readJavaRefOptimized(fury, refResolver, trackingKeyRef, buffer, keyClassInfoWriteCache);
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        Object value = fury.readRef(buffer, valueSerializer);
        generics.popGenericType();
        map.put(key, value);
      }
    }

    private void javaKVTypesNonFinalRead(
        Fury fury,
        MemoryBuffer buffer,
        T map,
        GenericType keyGenericType,
        GenericType valueGenericType,
        Generics generics,
        int size) {
      ClassResolver classResolver = fury.getClassResolver();
      RefResolver refResolver = fury.getRefResolver();
      boolean trackingKeyRef = classResolver.needToWriteRef(keyGenericType.getCls());
      boolean trackingValueRef = classResolver.needToWriteRef(valueGenericType.getCls());
      for (int i = 0; i < size; i++) {
        generics.pushGenericType(keyGenericType);
        Object key =
            readJavaRefOptimized(fury, refResolver, trackingKeyRef, buffer, keyClassInfoWriteCache);
        generics.popGenericType();
        generics.pushGenericType(valueGenericType);
        Object value =
            readJavaRefOptimized(
                fury, refResolver, trackingValueRef, buffer, valueClassInfoWriteCache);
        generics.popGenericType();
        map.put(key, value);
      }
    }

    private void generalJavaRead(Fury fury, MemoryBuffer buffer, T map, int size) {
      for (int i = 0; i < size; i++) {
        Object key = fury.readRef(buffer, keyClassInfoReadCache);
        Object value = fury.readRef(buffer, valueClassInfoReadCache);
        map.put(key, value);
      }
    }

    @SuppressWarnings("unchecked")
    public static void xreadElements(Fury fury, MemoryBuffer buffer, Map map, int size) {
      Generics generics = fury.getGenerics();
      GenericType genericType = generics.nextGenericType();
      if (genericType == null || genericType.getTypeParametersCount() != 2) {
        for (int i = 0; i < size; i++) {
          Object key = fury.xreadRef(buffer);
          Object value = fury.xreadRef(buffer);
          map.put(key, value);
        }
      } else {
        // TODO(chaokunyang) use codegen to remove all branches.
        GenericType keyGenericType = genericType.getTypeParameter0();
        GenericType valueGenericType = genericType.getTypeParameter1();
        Serializer keySerializer = keyGenericType.getSerializer(fury.getClassResolver());
        Serializer valueSerializer = valueGenericType.getSerializer(fury.getClassResolver());
        if (!keyGenericType.hasGenericParameters() && !valueGenericType.hasGenericParameters()) {
          for (int i = 0; i < size; i++) {
            Object key = fury.xreadRefByNullableSerializer(buffer, keySerializer);
            Object value = fury.xreadRefByNullableSerializer(buffer, valueSerializer);
            map.put(key, value);
          }
        } else if (valueGenericType.hasGenericParameters()) {
          for (int i = 0; i < size; i++) {
            Object key = fury.xreadRefByNullableSerializer(buffer, keySerializer);
            generics.pushGenericType(valueGenericType);
            Object value = fury.xreadRefByNullableSerializer(buffer, valueSerializer);
            generics.popGenericType();
            map.put(key, value);
          }
        } else if (keyGenericType.hasGenericParameters()) {
          for (int i = 0; i < size; i++) {
            generics.pushGenericType(keyGenericType);
            Object key = fury.xreadRefByNullableSerializer(buffer, keySerializer);
            generics.popGenericType();
            Object value = fury.xreadRefByNullableSerializer(buffer, valueSerializer);
            map.put(key, value);
          }
        } else {
          for (int i = 0; i < size; i++) {
            // FIXME(chaokunyang) nested generics may be get by mistake.
            generics.pushGenericType(keyGenericType);
            Object key = fury.xreadRefByNullableSerializer(buffer, keySerializer);
            generics.pushGenericType(valueGenericType);
            Object value = fury.xreadRefByNullableSerializer(buffer, valueSerializer);
            map.put(key, value);
          }
        }
        generics.popGenericType();
      }
    }

    /**
     * Hook for java serialization codegen, read/write key/value by entrySet.
     *
     * <p>For key/value type which is final, using codegen may get a big performance gain
     *
     * @return true if read/write key/value support calling entrySet method
     */
    public final boolean supportCodegenHook() {
      return supportCodegenHook;
    }

    /**
     * Write data except size and elements.
     *
     * <ol>
     *   In codegen, follows is call order:
     *   <li>write map class if not final
     *   <li>write map size
     *   <li>writeHeader
     *   <li>write keys/values
     * </ol>
     */
    public void writeHeader(MemoryBuffer buffer, T value) {}

    /**
     * Read data except size and elements, return empty map to be filled.
     *
     * <ol>
     *   In codegen, follows is call order:
     *   <li>read map class if not final
     *   <li>read map size
     *   <li>newMap
     *   <li>read keys/values
     * </ol>
     */
    public T newMap(MemoryBuffer buffer, int numElements) {
      if (constructor == null) {
        constructor = ReflectionUtils.newAccessibleNoArgConstructor(type);
      }
      try {
        T instance = (T) constructor.newInstance();
        fury.getRefResolver().reference(instance);
        return instance;
      } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
        throw new IllegalArgumentException(
            "Please provide public no arguments constructor for class " + type, e);
      }
    }

    /** Check null first to avoid ref tracking for some types with ref tracking disabled. */
    private void writeJavaRefOptimized(
        Fury fury,
        ClassResolver classResolver,
        RefResolver refResolver,
        MemoryBuffer buffer,
        Object obj,
        ClassInfoCache classInfoCache) {
      if (!refResolver.writeNullFlag(buffer, obj)) {
        fury.writeRef(buffer, obj, classResolver.getClassInfo(obj.getClass(), classInfoCache));
      }
    }

    private void writeJavaRefOptimized(
        Fury fury,
        ClassResolver classResolver,
        RefResolver refResolver,
        boolean trackingRef,
        MemoryBuffer buffer,
        Object obj,
        ClassInfoCache classInfoCache) {
      if (trackingRef) {
        if (!refResolver.writeNullFlag(buffer, obj)) {
          fury.writeRef(buffer, obj, classResolver.getClassInfo(obj.getClass(), classInfoCache));
        }
      } else {
        if (obj == null) {
          buffer.writeByte(Fury.NULL_FLAG);
        } else {
          buffer.writeByte(Fury.NOT_NULL_VALUE_FLAG);
          fury.writeNonRefT(
              buffer, obj, classResolver.getClassInfo(obj.getClass(), classInfoCache));
        }
      }
    }

    private Object readJavaRefOptimized(
        Fury fury,
        RefResolver refResolver,
        boolean trackingRef,
        MemoryBuffer buffer,
        ClassInfoCache classInfoCache) {
      if (trackingRef) {
        int nextReadRefId = refResolver.tryPreserveRefId(buffer);
        if (nextReadRefId >= Fury.NOT_NULL_VALUE_FLAG) {
          Object obj = fury.readNonRef(buffer, classInfoCache);
          refResolver.setReadObject(nextReadRefId, obj);
          return obj;
        } else {
          return refResolver.getReadObject();
        }
      } else {
        byte headFlag = buffer.readByte();
        if (headFlag == Fury.NULL_FLAG) {
          return null;
        } else {
          return fury.readNonRef(buffer, classInfoCache);
        }
      }
    }
  }

  public static final class HashMapSerializer extends MapSerializer<HashMap> {
    public HashMapSerializer(Fury fury) {
      super(fury, HashMap.class, true, false);
    }

    @Override
    public short getXtypeId() {
      return Type.MAP.getId();
    }

    @Override
    public HashMap newMap(MemoryBuffer buffer, int size) {
      HashMap hashMap = new HashMap(size);
      fury.getRefResolver().reference(hashMap);
      return hashMap;
    }
  }

  public static final class LinkedHashMapSerializer extends MapSerializer<LinkedHashMap> {
    public LinkedHashMapSerializer(Fury fury) {
      super(fury, LinkedHashMap.class, true, false);
    }

    @Override
    public short getXtypeId() {
      return Type.MAP.getId();
    }

    @Override
    public LinkedHashMap newMap(MemoryBuffer buffer, int size) {
      LinkedHashMap hashMap = new LinkedHashMap(size);
      fury.getRefResolver().reference(hashMap);
      return hashMap;
    }
  }

  public static final class LazyMapSerializer extends MapSerializer<LazyMap> {
    public LazyMapSerializer(Fury fury) {
      super(fury, LazyMap.class, true, false);
    }

    @Override
    public short getXtypeId() {
      return Type.MAP.getId();
    }

    @Override
    public LazyMap newMap(MemoryBuffer buffer, int size) {
      LazyMap map = new LazyMap(size);
      fury.getRefResolver().reference(map);
      return map;
    }
  }

  public static class SortedMapSerializer<T extends SortedMap> extends MapSerializer<T> {
    private Constructor<?> constructor;

    public SortedMapSerializer(Fury fury, Class<T> cls) {
      super(fury, cls, true, false);
      if (cls != TreeMap.class) {
        try {
          this.constructor = cls.getConstructor(Comparator.class);
          if (!constructor.isAccessible()) {
            constructor.setAccessible(true);
          }
        } catch (Exception e) {
          throw new UnsupportedOperationException(e);
        }
      }
    }

    @Override
    public void writeHeader(MemoryBuffer buffer, T value) {
      fury.writeRef(buffer, value.comparator());
    }

    @SuppressWarnings("unchecked")
    @Override
    public T newMap(MemoryBuffer buffer, int numElements) {
      T map;
      Comparator comparator = (Comparator) fury.readRef(buffer);
      if (type == TreeMap.class) {
        map = (T) new TreeMap(comparator);
      } else {
        try {
          map = (T) constructor.newInstance(comparator);
        } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
          throw new RuntimeException(e);
        }
      }
      fury.getRefResolver().reference(map);
      return map;
    }
  }

  public static final class EmptyMapSerializer extends MapSerializer<Map<?, ?>> {

    public EmptyMapSerializer(Fury fury, Class<Map<?, ?>> cls) {
      super(fury, cls, false, false);
    }

    @Override
    public void write(MemoryBuffer buffer, Map<?, ?> value) {}

    @Override
    public short getXtypeId() {
      return (short) -Type.MAP.getId();
    }

    @Override
    public void xwrite(MemoryBuffer buffer, Map<?, ?> value) {
      // write length
      buffer.writePositiveVarInt(0);
    }

    @Override
    public Map<?, ?> read(MemoryBuffer buffer) {
      return Collections.EMPTY_MAP;
    }

    @Override
    public Map<?, ?> xread(MemoryBuffer buffer) {
      buffer.readPositiveVarInt();
      return Collections.EMPTY_MAP;
    }
  }

  public static final class EmptySortedMapSerializer extends MapSerializer<SortedMap<?, ?>> {
    public EmptySortedMapSerializer(Fury fury, Class<SortedMap<?, ?>> cls) {
      super(fury, cls, false, false);
    }

    @Override
    public void write(MemoryBuffer buffer, SortedMap<?, ?> value) {}

    @Override
    public SortedMap<?, ?> read(MemoryBuffer buffer) {
      return Collections.emptySortedMap();
    }
  }

  public static final class SingletonMapSerializer extends MapSerializer<Map<?, ?>> {

    public SingletonMapSerializer(Fury fury, Class<Map<?, ?>> cls) {
      super(fury, cls, false, false);
    }

    @Override
    public void write(MemoryBuffer buffer, Map<?, ?> value) {
      Map.Entry entry = value.entrySet().iterator().next();
      fury.writeRef(buffer, entry.getKey());
      fury.writeRef(buffer, entry.getValue());
    }

    @Override
    public short getXtypeId() {
      return (short) -Type.MAP.getId();
    }

    @Override
    public void xwrite(MemoryBuffer buffer, Map<?, ?> value) {
      buffer.writePositiveVarInt(1);
      Map.Entry entry = value.entrySet().iterator().next();
      fury.xwriteRef(buffer, entry.getKey());
      fury.xwriteRef(buffer, entry.getValue());
    }

    @Override
    public Map<?, ?> read(MemoryBuffer buffer) {
      Object key = fury.readRef(buffer);
      Object value = fury.readRef(buffer);
      return Collections.singletonMap(key, value);
    }

    @Override
    public Map<?, ?> xread(MemoryBuffer buffer) {
      buffer.readPositiveVarInt();
      Object key = fury.xreadRef(buffer);
      Object value = fury.xreadRef(buffer);
      return Collections.singletonMap(key, value);
    }
  }

  public static final class ImmutableMapSerializer<T extends ImmutableMap>
      extends MapSerializer<T> {
    private final ReplaceResolveSerializer serializer;

    public ImmutableMapSerializer(Fury fury, Class<T> cls) {
      super(fury, cls, false, false);
      fury.getClassResolver().setSerializer(cls, this);
      serializer = new ReplaceResolveSerializer(fury, cls);
    }

    @SuppressWarnings("unchecked")
    @Override
    public T read(MemoryBuffer buffer) {
      return (T) serializer.read(buffer);
    }

    @Override
    public void write(MemoryBuffer buffer, T value) {
      serializer.write(buffer, value);
    }

    @Override
    public short getXtypeId() {
      return (short) -Type.MAP.getId();
    }

    @Override
    public T xread(MemoryBuffer buffer) {
      int size = buffer.readPositiveVarInt();
      Map map = new HashMap();
      xreadElements(fury, buffer, map, size);
      T immutableMap = (T) ImmutableMap.copyOf(map);
      fury.getRefResolver().reference(immutableMap);
      return immutableMap;
    }
  }

  public static final class ConcurrentHashMapSerializer extends MapSerializer<ConcurrentHashMap> {

    public ConcurrentHashMapSerializer(Fury fury, Class<ConcurrentHashMap> type) {
      super(fury, type, true, false);
    }

    @Override
    public ConcurrentHashMap newMap(MemoryBuffer buffer, int size) {
      ConcurrentHashMap map = new ConcurrentHashMap(size);
      fury.getRefResolver().reference(map);
      return map;
    }

    @Override
    public short getXtypeId() {
      return Fury.NOT_SUPPORT_CROSS_LANGUAGE;
    }
  }

  public static final class ConcurrentSkipListMapSerializer
      extends SortedMapSerializer<ConcurrentSkipListMap> {

    public ConcurrentSkipListMapSerializer(Fury fury, Class<ConcurrentSkipListMap> cls) {
      super(fury, cls);
    }

    @Override
    public ConcurrentSkipListMap newMap(MemoryBuffer buffer, int numElements) {
      Comparator comparator = (Comparator) fury.readRef(buffer);
      ConcurrentSkipListMap map = new ConcurrentSkipListMap(comparator);
      fury.getRefResolver().reference(map);
      return map;
    }

    @Override
    public short getXtypeId() {
      return Fury.NOT_SUPPORT_CROSS_LANGUAGE;
    }
  }

  public static class EnumMapSerializer extends MapSerializer<EnumMap> {
    private final long keyTypeFieldOffset;

    public EnumMapSerializer(Fury fury, Class<EnumMap> cls) {
      // getMapKeyValueType(EnumMap.class) will be `K, V` without Enum as key bound.
      // so no need to infer key generics in init.
      super(fury, cls, true, false);
      Field field = ReflectionUtils.getDeclaredField(EnumMap.class, "keyType");
      keyTypeFieldOffset = ReflectionUtils.getFieldOffset(field);
    }

    @Override
    public void writeHeader(MemoryBuffer buffer, EnumMap value) {
      Class keyType = (Class) Platform.getObject(value, keyTypeFieldOffset);
      fury.getClassResolver().writeClassAndUpdateCache(buffer, keyType);
    }

    @Override
    public EnumMap newMap(MemoryBuffer buffer, int numElements) {
      Class<?> keyType = fury.getClassResolver().readClassAndUpdateCache(buffer);
      return new EnumMap(keyType);
    }
  }

  /**
   * Java serializer to serialize all fields of a map implementation. Note that this serializer
   * won't use element generics and doesn't support JIT, performance won't be the best, but the
   * correctness can be ensured.
   */
  public static final class DefaultJavaMapSerializer<T extends Map> extends MapSerializer<T> {
    private Serializer<T> dataSerializer;

    public DefaultJavaMapSerializer(Fury fury, Class<T> cls) {
      super(fury, cls, false, false);
      Preconditions.checkArgument(
          fury.getLanguage() == Language.JAVA,
          "Python default map serializer should use " + MapSerializer.class);
      fury.getClassResolver().setSerializer(cls, this);
      Class<? extends Serializer> serializerClass =
          fury.getClassResolver()
              .getObjectSerializerClass(
                  cls, sc -> dataSerializer = Serializers.newSerializer(fury, cls, sc));
      dataSerializer = Serializers.newSerializer(fury, cls, serializerClass);
      // No need to set object serializer to this, it will be set in class resolver later.
      // fury.getClassResolver().setSerializer(cls, this);
    }

    @Override
    public void write(MemoryBuffer buffer, T value) {
      dataSerializer.write(buffer, value);
    }

    @Override
    public T read(MemoryBuffer buffer) {
      return dataSerializer.read(buffer);
    }
  }

  /** Map serializer for class with JDK custom serialization methods defined. */
  public static class JDKCompatibleMapSerializer<T extends Map> extends MapSerializer<T> {
    private final Serializer serializer;

    public JDKCompatibleMapSerializer(Fury fury, Class<T> cls) {
      super(fury, cls, false, false);
      // Map which defined `writeReplace` may use this serializer, so check replace/resolve
      // is necessary.
      Class<? extends Serializer> serializerType =
          ClassResolver.useReplaceResolveSerializer(cls)
              ? ReplaceResolveSerializer.class
              : fury.getDefaultJDKStreamSerializerType();
      serializer = Serializers.newSerializer(fury, cls, serializerType);
    }

    @SuppressWarnings("unchecked")
    @Override
    public T read(MemoryBuffer buffer) {
      return (T) serializer.read(buffer);
    }

    @Override
    public void write(MemoryBuffer buffer, T value) {
      serializer.write(buffer, value);
    }
  }

  // TODO(chaokunyang) support ConcurrentSkipListMap.SubMap mo efficiently.
  public static void registerDefaultSerializers(Fury fury) {
    fury.registerSerializer(HashMap.class, new HashMapSerializer(fury));
    fury.getClassResolver()
        .registerSerializer(LinkedHashMap.class, new LinkedHashMapSerializer(fury));
    fury.registerSerializer(TreeMap.class, new SortedMapSerializer<>(fury, TreeMap.class));
    fury.registerSerializer(
        Collections.EMPTY_MAP.getClass(),
        new EmptyMapSerializer(fury, (Class<Map<?, ?>>) Collections.EMPTY_MAP.getClass()));
    fury.registerSerializer(
        Collections.emptySortedMap().getClass(),
        new EmptySortedMapSerializer(
            fury, (Class<SortedMap<?, ?>>) Collections.emptySortedMap().getClass()));
    fury.registerSerializer(
        Collections.singletonMap(null, null).getClass(),
        new SingletonMapSerializer(
            fury, (Class<Map<?, ?>>) Collections.singletonMap(null, null).getClass()));
    fury.registerSerializer(
        ConcurrentHashMap.class, new ConcurrentHashMapSerializer(fury, ConcurrentHashMap.class));
    fury.registerSerializer(
        ConcurrentSkipListMap.class,
        new ConcurrentSkipListMapSerializer(fury, ConcurrentSkipListMap.class));
    fury.registerSerializer(EnumMap.class, new EnumMapSerializer(fury, EnumMap.class));
    fury.registerSerializer(LazyMap.class, new LazyMapSerializer(fury));
  }
}
