package io.fury.format.encoder;

import io.fury.format.row.binary.BinaryArray;
import io.fury.format.row.binary.BinaryMap;
import org.apache.arrow.vector.types.pojo.Field;

/**
 * Encoder to encode/decode object in the map container by toMap/fromMap row.
 *
 * @author ganrunsheng
 */
public interface MapEncoder<T> extends Encoder<T> {
  Field keyField();

  Field valueField();

  T fromMap(BinaryArray keyArray, BinaryArray valueArray);

  default T fromMap(BinaryMap binaryMap) {
    return fromMap(binaryMap.keyArray(), binaryMap.valueArray());
  }

  BinaryMap toMap(T obj);
}
