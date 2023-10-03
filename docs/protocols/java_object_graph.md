# Java Serialization
The data are serialized using little endian order overall.

## Basic types
### boolean
- size: 1 byte
- format: 0 for `false`, 1 for `true`

### byte
- size: 1 byte
- format: write as pure byte.

### short
- size: 2 byte
- byte order: little endian order

### char
- size: 2 byte
- byte order: little endian order

### int
- size: 1~5 byte
- positive int format: first bit in every byte indicate whether has next byte. if first bit is set i.e. `b & 0x80 == 0x80`, then next byte should be read util first bit is unset.
- Negative number will be converted to positive number by ` (v << 1) ^ (v >> 31)` to reduce cost of small negative numbers.

### long
- size: 1~9 byte
- positive long format: first bit in every byte indicate whether has next byte. if first bit is set i.e. `b & 0x80 == 0x80`, then next byte should be read util first bit is unset.
- Negative number will be converted to positive number by ` (v << 1) ^ (v >> 63)` to reduce cost of small negative numbers.

### float
- size: 4 byte
- format: convert float to 4 bytes int by `Float.floatToRawIntBits`, then write as binary by little endian order.

### double
- size: 8 byte
- format: convert double to 8 bytes int by `Double.doubleToRawLongBits`, then write as binary by little endian order.

## String
Format:
- one byte for encoding: 0 for `ascii`, 1 for `utf-16`, 2 for `utf-8`.
- positive varint for encoded string binary length.
- encoded string binary data based on encoding: `ascii/utf-16/utf-8`.

Which encoding to choose:
- For JDK8: fury detect `ascii` at runtime, if string is `ascii` string, then use `ascii` encoding, otherwise use `utf-16`.
- For JDK9+: fury use `coder` in `String` object for encoding, `ascii`/`utf-16` will be used for encoding.
- If the string is encoded by `utf-8`, then fury will use `utf-8` to decode the data. But currently fury doesn't enable utf-8 encoding by default for java. Cross-language string serialization of fury use `utf-8` by default.

## Collection


## Map


## Object








