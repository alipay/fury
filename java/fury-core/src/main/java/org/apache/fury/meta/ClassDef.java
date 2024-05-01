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

package org.apache.fury.meta;

import static org.apache.fury.meta.ClassDefEncoder.buildFields;
import static org.apache.fury.type.TypeUtils.COLLECTION_TYPE;
import static org.apache.fury.type.TypeUtils.MAP_TYPE;
import static org.apache.fury.type.TypeUtils.collectionOf;
import static org.apache.fury.type.TypeUtils.mapOf;

import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import org.apache.fury.Fury;
import org.apache.fury.builder.MetaSharedCodecBuilder;
import org.apache.fury.config.CompatibleMode;
import org.apache.fury.config.FuryBuilder;
import org.apache.fury.logging.Logger;
import org.apache.fury.logging.LoggerFactory;
import org.apache.fury.memory.MemoryBuffer;
import org.apache.fury.memory.Platform;
import org.apache.fury.reflect.ReflectionUtils;
import org.apache.fury.reflect.TypeRef;
import org.apache.fury.resolver.ClassResolver;
import org.apache.fury.serializer.CompatibleSerializer;
import org.apache.fury.type.Descriptor;
import org.apache.fury.type.FinalObjectTypeStub;
import org.apache.fury.type.GenericType;
import org.apache.fury.util.Preconditions;

/**
 * Serializable class definition to be sent to other process. So if sender peer and receiver peer
 * has different class definition for same class, such as add/remove fields, we can use this
 * definition to create different serializer to support back/forward compatibility.
 *
 * <p>Note that:
 * <li>If a class is already registered, this definition will contain class id only.
 * <li>Sending class definition is not cheap, should be sent with some kind of meta share mechanism.
 * <li>{@link ObjectStreamClass} doesn't contain any non-primitive field type info, which is not
 *     enough to create serializer in receiver.
 *
 * @see MetaSharedCodecBuilder
 * @see CompatibleMode#COMPATIBLE
 * @see CompatibleSerializer
 * @see FuryBuilder#withMetaContextShare
 * @see ReflectionUtils#getFieldOffset
 */
public class ClassDef implements Serializable {
  private static final Logger LOG = LoggerFactory.getLogger(ClassDef.class);

  static final int SCHEMA_COMPATIBLE_FLAG = 0b10000;
  public static final int SIZE_TWO_BYTES_FLAG = 0b100000;
  static final int EXT_FLAG = 0b1000000;
  // TODO use field offset to sort field, which will hit l1-cache more. Since
  // `objectFieldOffset` is not part of jvm-specification, it may change between different jdk
  // vendor. But the deserialization peer use the class definition to create deserializer, it's OK
  // even field offset or fields order change between jvm process.
  public static final Comparator<Field> FIELD_COMPARATOR =
      (f1, f2) -> {
        long offset1 = Platform.objectFieldOffset(f1);
        long offset2 = Platform.objectFieldOffset(f2);
        long diff = offset1 - offset2;
        if (diff != 0) {
          return (int) diff;
        } else {
          if (!f1.equals(f2)) {
            LOG.warn(
                "Field {} has same offset with {}, please an issue with jdk info to fury", f1, f2);
          }
          int compare = f1.getDeclaringClass().getName().compareTo(f2.getName());
          if (compare != 0) {
            return compare;
          }
          return f1.getName().compareTo(f2.getName());
        }
      };

  private final String className;
  private final List<FieldInfo> fieldsInfo;
  private final Map<String, String> extMeta;
  // Unique id for class def. If class def are same between processes, then the id will
  // be same too.
  private final long id;
  private final byte[] encoded;
  private transient List<Descriptor> descriptors;

  ClassDef(
      String className,
      List<FieldInfo> fieldsInfo,
      Map<String, String> extMeta,
      long id,
      byte[] encoded) {
    this.className = className;
    this.fieldsInfo = fieldsInfo;
    this.extMeta = extMeta;
    this.id = id;
    this.encoded = encoded;
  }

  /**
   * Returns class name.
   *
   * @see Class#getName()
   */
  public String getClassName() {
    return className;
  }

  /** Contain all fields info including all parent classes. */
  public List<FieldInfo> getFieldsInfo() {
    return fieldsInfo;
  }

  /** Returns ext meta for the class. */
  public Map<String, String> getExtMeta() {
    return extMeta;
  }

  /**
   * Returns an unique id for class def. If class def are same between processes, then the id will
   * be same too.
   */
  public long getId() {
    return id;
  }

  public byte[] getEncoded() {
    return encoded;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ClassDef classDef = (ClassDef) o;
    return Objects.equals(className, classDef.className)
        && Objects.equals(fieldsInfo, classDef.fieldsInfo)
        && Objects.equals(extMeta, classDef.extMeta);
  }

  @Override
  public int hashCode() {
    return Objects.hash(className, fieldsInfo, extMeta);
  }

  @Override
  public String toString() {
    return "ClassDef{"
        + "className='"
        + className
        + '\''
        + ", fieldsInfo="
        + fieldsInfo
        + ", extMeta="
        + extMeta
        + ", id="
        + id
        + '}';
  }

  /** Write class definition to buffer. */
  public void writeClassDef(MemoryBuffer buffer) {
    buffer.writeBytes(encoded);
  }

  /** Read class definition from buffer. */
  public static ClassDef readClassDef(ClassResolver classResolver, MemoryBuffer buffer) {
    return ClassDefDecoder.decodeClassDef(classResolver, buffer, buffer.readInt64());
  }

  /** Read class definition from buffer. */
  public static ClassDef readClassDef(
      ClassResolver classResolver, MemoryBuffer buffer, long header) {
    return ClassDefDecoder.decodeClassDef(classResolver, buffer, header);
  }

  public List<Descriptor> getDescriptors(ClassResolver resolver, Class<?> cls) {
    if (descriptors == null) {
      SortedMap<Field, Descriptor> allDescriptorsMap = resolver.getAllDescriptorsMap(cls, true);
      Map<String, Descriptor> descriptorsMap = new HashMap<>();
      for (Map.Entry<Field, Descriptor> e : allDescriptorsMap.entrySet()) {
        if (descriptorsMap.put(
                e.getKey().getDeclaringClass().getName() + "." + e.getKey().getName(), e.getValue())
            != null) {
          throw new IllegalStateException("Duplicate key");
        }
      }
      descriptors = new ArrayList<>(fieldsInfo.size());
      for (ClassDef.FieldInfo fieldInfo : fieldsInfo) {
        Descriptor descriptor =
            descriptorsMap.get(fieldInfo.getDefinedClass() + "." + fieldInfo.getFieldName());
        Descriptor newDesc = fieldInfo.toDescriptor(resolver);
        if (descriptor != null) {
          // Make DescriptorGrouper have consistent order whether field exist or not
          descriptor = descriptor.copyWithTypeName(newDesc.getTypeName());
          descriptors.add(descriptor);
        } else {
          descriptors.add(newDesc);
        }
      }
    }
    return descriptors;
  }

  /**
   * FieldInfo contains all necessary info of a field to execute serialization/deserialization
   * logic.
   */
  public static class FieldInfo implements Serializable {
    /** where are current field defined. */
    private final String definedClass;

    /** Name of a field. */
    private final String fieldName;

    private final FieldType fieldType;

    FieldInfo(String definedClass, String fieldName, FieldType fieldType) {
      this.definedClass = definedClass;
      this.fieldName = fieldName;
      this.fieldType = fieldType;
    }

    /** Returns classname of current field defined. */
    public String getDefinedClass() {
      return definedClass;
    }

    /** Returns name of current field. */
    public String getFieldName() {
      return fieldName;
    }

    public boolean hasTypeTag() {
      return false;
    }

    public short getTypeTag() {
      return -1;
    }

    /** Returns type of current field. */
    public FieldType getFieldType() {
      return fieldType;
    }

    /**
     * Convert this field into a {@link Descriptor}, the corresponding {@link Field} field will be
     * null. Don't invoke this method if class does have <code>fieldName</code> field. In such case,
     * reflection should be used to get the descriptor.
     */
    Descriptor toDescriptor(ClassResolver classResolver) {
      TypeRef<?> typeRef = fieldType.toTypeToken(classResolver);
      // This field doesn't exist in peer class, so any legal modifier will be OK.
      int stubModifiers = ReflectionUtils.getField(getClass(), "fieldName").getModifiers();
      return new Descriptor(typeRef, fieldName, stubModifiers, definedClass);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FieldInfo fieldInfo = (FieldInfo) o;
      return Objects.equals(definedClass, fieldInfo.definedClass)
          && Objects.equals(fieldName, fieldInfo.fieldName)
          && Objects.equals(fieldType, fieldInfo.fieldType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(definedClass, fieldName, fieldType);
    }

    @Override
    public String toString() {
      return "FieldInfo{"
          + "definedClass='"
          + definedClass
          + '\''
          + ", fieldName='"
          + fieldName
          + '\''
          + ", fieldType="
          + fieldType
          + '}';
    }
  }

  public abstract static class FieldType implements Serializable {
    public FieldType(boolean isMonomorphic) {
      this.isMonomorphic = isMonomorphic;
    }

    private final boolean isMonomorphic;

    public boolean isMonomorphic() {
      return isMonomorphic;
    }

    /**
     * Convert a serializable field type to type token. If field type is a generic type with
     * generics, the generics will be built up recursively. The final leaf object type will be built
     * from class id or class stub.
     *
     * @see FinalObjectTypeStub
     */
    public abstract TypeRef<?> toTypeToken(ClassResolver classResolver);

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FieldType fieldType = (FieldType) o;
      return isMonomorphic == fieldType.isMonomorphic;
    }

    @Override
    public int hashCode() {
      return Objects.hash(isMonomorphic);
    }

    public void write(MemoryBuffer buffer) {
      byte header = (byte) (isMonomorphic ? 1 : 0);
      if (this instanceof RegisteredFieldType) {
        short classId = ((RegisteredFieldType) this).getClassId();
        buffer.writeVarUint32Small7(((3 + classId) << 1) | header);
      } else if (this instanceof CollectionFieldType) {
        buffer.writeVarUint32Small7((2 << 1) | header);
        ((CollectionFieldType) this).elementType.write(buffer);
      } else if (this instanceof MapFieldType) {
        buffer.writeVarUint32Small7((1 << 1) | header);
        MapFieldType mapFieldType = (MapFieldType) this;
        mapFieldType.keyType.write(buffer);
        mapFieldType.valueType.write(buffer);
      } else {
        Preconditions.checkArgument(this instanceof ObjectFieldType);
        buffer.writeVarUint32Small7(header);
      }
    }

    public static FieldType read(MemoryBuffer buffer) {
      int header = buffer.readVarUint32Small7();
      boolean isMonomorphic = (header & 0b1) != 0;
      return read(buffer, isMonomorphic, header >>> 1);
    }

    public static FieldType read(MemoryBuffer buffer, boolean isFinal, int typeId) {
      if (typeId == 0) {
        return new ObjectFieldType(isFinal);
      } else if (typeId == 1) {
        return new MapFieldType(isFinal, read(buffer), read(buffer));
      } else if (typeId == 2) {
        return new CollectionFieldType(isFinal, read(buffer));
      } else {
        return new RegisteredFieldType(isFinal, (short) (typeId - 3));
      }
    }
  }

  /** Class for field type which is registered. */
  public static class RegisteredFieldType extends FieldType {
    private final short classId;

    public RegisteredFieldType(boolean isFinal, short classId) {
      super(isFinal);
      this.classId = classId;
    }

    public short getClassId() {
      return classId;
    }

    @Override
    public TypeRef<?> toTypeToken(ClassResolver classResolver) {
      return TypeRef.of(classResolver.getRegisteredClass(classId));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      if (!super.equals(o)) {
        return false;
      }
      RegisteredFieldType that = (RegisteredFieldType) o;
      return classId == that.classId;
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), classId);
    }

    @Override
    public String toString() {
      return "RegisteredFieldType{"
          + "isMonomorphic="
          + isMonomorphic()
          + ", classId="
          + classId
          + '}';
    }
  }

  /**
   * Class for collection field type, which store collection element type information. Nested
   * collection/map generics example:
   *
   * <pre>{@code
   * new TypeToken<Collection<Map<String, String>>>() {}
   * }</pre>
   */
  public static class CollectionFieldType extends FieldType {
    private final FieldType elementType;

    public CollectionFieldType(boolean isFinal, FieldType elementType) {
      super(isFinal);
      this.elementType = elementType;
    }

    public FieldType getElementType() {
      return elementType;
    }

    @Override
    public TypeRef<?> toTypeToken(ClassResolver classResolver) {
      return collectionOf(elementType.toTypeToken(classResolver));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      if (!super.equals(o)) {
        return false;
      }
      CollectionFieldType that = (CollectionFieldType) o;
      return Objects.equals(elementType, that.elementType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), elementType);
    }

    @Override
    public String toString() {
      return "CollectionFieldType{"
          + "elementType="
          + elementType
          + ", isFinal="
          + isMonomorphic()
          + '}';
    }
  }

  /**
   * Class for map field type, which store map key/value type information. Nested map generics
   * example:
   *
   * <pre>{@code
   * new TypeToken<Map<List<String>>, String>() {}
   * }</pre>
   */
  public static class MapFieldType extends FieldType {
    private final FieldType keyType;
    private final FieldType valueType;

    public MapFieldType(boolean isFinal, FieldType keyType, FieldType valueType) {
      super(isFinal);
      this.keyType = keyType;
      this.valueType = valueType;
    }

    public FieldType getKeyType() {
      return keyType;
    }

    public FieldType getValueType() {
      return valueType;
    }

    @Override
    public TypeRef<?> toTypeToken(ClassResolver classResolver) {
      return mapOf(keyType.toTypeToken(classResolver), valueType.toTypeToken(classResolver));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      if (!super.equals(o)) {
        return false;
      }
      MapFieldType that = (MapFieldType) o;
      return Objects.equals(keyType, that.keyType) && Objects.equals(valueType, that.valueType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), keyType, valueType);
    }

    @Override
    public String toString() {
      return "MapFieldType{"
          + "keyType="
          + keyType
          + ", valueType="
          + valueType
          + ", isFinal="
          + isMonomorphic()
          + '}';
    }
  }

  /** Class for field type which isn't registered and not collection/map type too. */
  public static class ObjectFieldType extends FieldType {

    public ObjectFieldType(boolean isFinal) {
      super(isFinal);
    }

    @Override
    public TypeRef<?> toTypeToken(ClassResolver classResolver) {
      return isMonomorphic() ? TypeRef.of(FinalObjectTypeStub.class) : TypeRef.of(Object.class);
    }

    @Override
    public boolean equals(Object o) {
      return super.equals(o);
    }

    @Override
    public int hashCode() {
      return super.hashCode();
    }
  }

  /** Build field type from generics, nested generics will be extracted too. */
  static FieldType buildFieldType(ClassResolver classResolver, Field field) {
    Preconditions.checkNotNull(field);
    Class<?> rawType = field.getType();
    boolean isFinal = classResolver.isMonomorphic(rawType);
    if (Collection.class.isAssignableFrom(rawType)) {
      GenericType genericType = GenericType.build(field.getGenericType());
      return new CollectionFieldType(
          isFinal,
          buildFieldType(
              classResolver,
              genericType.getTypeParameter0() == null
                  ? GenericType.build(Object.class)
                  : genericType.getTypeParameter0()));
    } else if (Map.class.isAssignableFrom(rawType)) {
      GenericType genericType = GenericType.build(field.getGenericType());
      return new MapFieldType(
          isFinal,
          buildFieldType(
              classResolver,
              genericType.getTypeParameter0() == null
                  ? GenericType.build(Object.class)
                  : genericType.getTypeParameter0()),
          buildFieldType(
              classResolver,
              genericType.getTypeParameter1() == null
                  ? GenericType.build(Object.class)
                  : genericType.getTypeParameter1()));
    } else {
      Short classId = classResolver.getRegisteredClassId(rawType);
      if (classId != null && classId != ClassResolver.NO_CLASS_ID) {
        return new RegisteredFieldType(isFinal, classId);
      } else {
        return new ObjectFieldType(isFinal);
      }
    }
  }

  /** Build field type from generics, nested generics will be extracted too. */
  private static FieldType buildFieldType(ClassResolver classResolver, GenericType genericType) {
    Preconditions.checkNotNull(genericType);
    boolean isFinal = genericType.isMonomorphic();
    if (COLLECTION_TYPE.isSupertypeOf(genericType.getTypeRef())) {
      return new CollectionFieldType(
          isFinal,
          buildFieldType(
              classResolver,
              genericType.getTypeParameter0() == null
                  ? GenericType.build(Object.class)
                  : genericType.getTypeParameter0()));
    } else if (MAP_TYPE.isSupertypeOf(genericType.getTypeRef())) {
      return new MapFieldType(
          isFinal,
          buildFieldType(
              classResolver,
              genericType.getTypeParameter0() == null
                  ? GenericType.build(Object.class)
                  : genericType.getTypeParameter0()),
          buildFieldType(
              classResolver,
              genericType.getTypeParameter1() == null
                  ? GenericType.build(Object.class)
                  : genericType.getTypeParameter1()));
    } else {
      Short classId = classResolver.getRegisteredClassId(genericType.getCls());
      if (classId != null && classId != ClassResolver.NO_CLASS_ID) {
        return new RegisteredFieldType(isFinal, classId);
      } else {
        return new ObjectFieldType(isFinal);
      }
    }
  }

  public static ClassDef buildClassDef(Fury fury, Class<?> cls) {
    return buildClassDef(fury, cls, true);
  }

  public static ClassDef buildClassDef(Fury fury, Class<?> cls, boolean resolveParent) {
    return ClassDefEncoder.buildClassDef(
        fury.getClassResolver(), cls, buildFields(fury, cls, resolveParent), new HashMap<>());
  }

  /** Build class definition from fields of class. */
  public static ClassDef buildClassDef(
      ClassResolver classResolver, Class<?> type, List<Field> fields) {
    return buildClassDef(classResolver, type, fields, new HashMap<>());
  }

  public static ClassDef buildClassDef(
      ClassResolver classResolver, Class<?> type, List<Field> fields, Map<String, String> extMeta) {
    return ClassDefEncoder.buildClassDef(classResolver, type, fields, extMeta);
  }
}
