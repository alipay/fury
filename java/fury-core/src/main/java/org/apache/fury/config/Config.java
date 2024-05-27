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

package org.apache.fury.config;

import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fury.Fury;
import org.apache.fury.serializer.Serializer;
import org.apache.fury.serializer.TimeSerializers;
import org.apache.fury.util.Preconditions;

/** Config for fury, all {@link Fury} related config can be found here. */
@SuppressWarnings({"rawtypes"})
public class Config implements Serializable {
  private final Language language;
  private final boolean trackingRef;
  private final boolean basicTypesRefIgnored;
  private final boolean stringRefIgnored;
  private final boolean timeRefIgnored;
  private final boolean codeGenEnabled;
  private final boolean checkClassVersion;
  private final CompatibleMode compatibleMode;
  private final boolean checkJdkClassSerializable;
  private final Class<? extends Serializer> defaultJDKStreamSerializerType;
  private final boolean compressString;
  private final boolean compressInt;
  private final boolean compressLong;
  private final LongEncoding longEncoding;
  private final boolean requireClassRegistration;
  private final boolean suppressClassRegistrationWarnings;
  private final boolean registerGuavaTypes;
  private final boolean metaShareEnabled;
  private final boolean scopedMetaShareEnabled;
  private final boolean asyncCompilationEnabled;
  private final boolean deserializeNonexistentClass;
  private final boolean scalaOptimizationEnabled;
  private transient int configHash;
  private final boolean deserializeNonexistentEnumValueAsNull;

  public Config(FuryBuilder builder) {
    language = builder.language;
    trackingRef = builder.trackingRef;
    basicTypesRefIgnored = !trackingRef || builder.basicTypesRefIgnored;
    stringRefIgnored = !trackingRef || builder.stringRefIgnored;
    timeRefIgnored = !trackingRef || builder.timeRefIgnored;
    compressString = builder.compressString;
    compressInt = builder.compressInt;
    longEncoding = builder.longEncoding;
    compressLong = longEncoding != LongEncoding.LE_RAW_BYTES;
    requireClassRegistration = builder.requireClassRegistration;
    suppressClassRegistrationWarnings = builder.suppressClassRegistrationWarnings;
    registerGuavaTypes = builder.registerGuavaTypes;
    codeGenEnabled = builder.codeGenEnabled;
    checkClassVersion = builder.checkClassVersion;
    compatibleMode = builder.compatibleMode;
    checkJdkClassSerializable = builder.checkJdkClassSerializable;
    defaultJDKStreamSerializerType = builder.defaultJDKStreamSerializerType;
    metaShareEnabled = builder.metaShareEnabled;
    scopedMetaShareEnabled = builder.scopedMetaShareEnabled;
    deserializeNonexistentClass = builder.deserializeNonexistentClass;
    if (deserializeNonexistentClass) {
      // Only in meta share mode or compatibleMode, fury knows how to deserialize
      // unexisted class by type info in data.
      Preconditions.checkArgument(metaShareEnabled || compatibleMode == CompatibleMode.COMPATIBLE);
    }
    asyncCompilationEnabled = builder.asyncCompilationEnabled;
    scalaOptimizationEnabled = builder.scalaOptimizationEnabled;
    deserializeNonexistentEnumValueAsNull = builder.deserializeNonexistentEnumValueAsNull;
  }

  public Language getLanguage() {
    return language;
  }

  public boolean trackingRef() {
    return trackingRef;
  }

  public boolean isBasicTypesRefIgnored() {
    return basicTypesRefIgnored;
  }

  public boolean isStringRefIgnored() {
    return stringRefIgnored;
  }

  /** ignore Enum Deserialize array out of bounds return null. */
  public boolean deserializeNonexistentEnumValueAsNull() {
    return deserializeNonexistentEnumValueAsNull;
  }

  /**
   * Whether ignore reference tracking of all time types registered in {@link TimeSerializers} and
   * subclasses of those types when ref tracking is enabled.
   *
   * <p>If ignored, ref tracking of every time type can be enabled by invoke {@link
   * Fury#registerSerializer(Class, Serializer)}, ex:
   *
   * <pre>
   *   fury.registerSerializer(Date.class, new DateSerializer(fury, true));
   * </pre>
   *
   * <p>Note that enabling ref tracking should happen before serializer codegen of any types which
   * contains time fields. Otherwise, those fields will still skip ref tracking.
   */
  public boolean isTimeRefIgnored() {
    return timeRefIgnored;
  }

  public boolean checkClassVersion() {
    return checkClassVersion;
  }

  public CompatibleMode getCompatibleMode() {
    return compatibleMode;
  }

  public boolean checkJdkClassSerializable() {
    return checkJdkClassSerializable;
  }

  public boolean compressString() {
    return compressString;
  }

  public boolean compressInt() {
    return compressInt;
  }

  public boolean compressLong() {
    return compressLong;
  }

  /** Returns long encoding. */
  public LongEncoding longEncoding() {
    return longEncoding;
  }

  public boolean requireClassRegistration() {
    return requireClassRegistration;
  }

  /**
   * Whether suppress class registration warnings. The warnings can be used for security audit, but
   * may be annoying.
   */
  public boolean suppressClassRegistrationWarnings() {
    return suppressClassRegistrationWarnings;
  }

  public boolean registerGuavaTypes() {
    return registerGuavaTypes;
  }

  /**
   * Returns default serializer type for class which implements jdk serialization method such as
   * `writeObject/readObject`.
   */
  public Class<? extends Serializer> getDefaultJDKStreamSerializerType() {
    return defaultJDKStreamSerializerType;
  }

  public boolean isMetaShareEnabled() {
    return metaShareEnabled;
  }

  /**
   * Scoped meta share focuses on a single serialization process. Metadata created or identified
   * during this process is exclusive to it and is not shared with by other serializations.
   */
  public boolean isScopedMetaShareEnabled() {
    return scopedMetaShareEnabled;
  }

  /**
   * Whether deserialize/skip data of un-existed class. If not enabled, an exception will be thrown
   * if class not exist.
   */
  public boolean deserializeNonexistentClass() {
    return deserializeNonexistentClass;
  }

  /**
   * Whether JIT is enabled.
   *
   * @see #isAsyncCompilationEnabled
   */
  public boolean isCodeGenEnabled() {
    return codeGenEnabled;
  }

  /**
   * Whether enable async compilation. If enabled, serialization will use interpreter mode
   * serialization first and switch to jit serialization after async serializer jit for a class \ is
   * finished.
   */
  public boolean isAsyncCompilationEnabled() {
    return asyncCompilationEnabled;
  }

  /** Whether enable scala-specific serialization optimization. */
  public boolean isScalaOptimizationEnabled() {
    return scalaOptimizationEnabled;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Config config = (Config) o;
    return trackingRef == config.trackingRef
        && basicTypesRefIgnored == config.basicTypesRefIgnored
        && stringRefIgnored == config.stringRefIgnored
        && timeRefIgnored == config.timeRefIgnored
        && codeGenEnabled == config.codeGenEnabled
        && checkClassVersion == config.checkClassVersion
        && checkJdkClassSerializable == config.checkJdkClassSerializable
        && compressString == config.compressString
        && compressInt == config.compressInt
        && compressLong == config.compressLong
        && requireClassRegistration == config.requireClassRegistration
        && suppressClassRegistrationWarnings == config.suppressClassRegistrationWarnings
        && registerGuavaTypes == config.registerGuavaTypes
        && metaShareEnabled == config.metaShareEnabled
        && scopedMetaShareEnabled == config.scopedMetaShareEnabled
        && asyncCompilationEnabled == config.asyncCompilationEnabled
        && deserializeNonexistentClass == config.deserializeNonexistentClass
        && scalaOptimizationEnabled == config.scalaOptimizationEnabled
        && language == config.language
        && compatibleMode == config.compatibleMode
        && Objects.equals(defaultJDKStreamSerializerType, config.defaultJDKStreamSerializerType)
        && longEncoding == config.longEncoding;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        language,
        trackingRef,
        basicTypesRefIgnored,
        stringRefIgnored,
        timeRefIgnored,
        codeGenEnabled,
        checkClassVersion,
        compatibleMode,
        checkJdkClassSerializable,
        defaultJDKStreamSerializerType,
        compressString,
        compressInt,
        compressLong,
        longEncoding,
        requireClassRegistration,
        suppressClassRegistrationWarnings,
        registerGuavaTypes,
        metaShareEnabled,
        scopedMetaShareEnabled,
        asyncCompilationEnabled,
        deserializeNonexistentClass,
        scalaOptimizationEnabled);
  }

  private static final AtomicInteger counter = new AtomicInteger(0);
  // Different config instance with equality will be hold only one instance, no memory
  // leak will happen.
  private static final ConcurrentMap<Config, Integer> configIdMap = new ConcurrentHashMap<>();

  public int getConfigHash() {
    if (configHash == 0) {
      configHash = configIdMap.computeIfAbsent(this, k -> counter.incrementAndGet());
    }
    return configHash;
  }
}
