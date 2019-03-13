/*
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

package org.apache.nifi.serialization.record.util;

import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.record.DataType;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.type.ArrayDataType;
import org.apache.nifi.serialization.record.type.ChoiceDataType;
import org.apache.nifi.serialization.record.type.MapDataType;
import org.apache.nifi.serialization.record.type.RecordDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class DataTypeUtils {
    private static final Logger logger = LoggerFactory.getLogger(DataTypeUtils.class);

    // Regexes for parsing Floating-Point numbers
    private static final String OptionalSign  = "[\\-\\+]?";
    private static final String Infinity = "(Infinity)";
    private static final String NotANumber = "(NaN)";

    private static final String Base10Digits = "\\d+";
    private static final String Base10Decimal = "\\." + Base10Digits;
    private static final String OptionalBase10Decimal = "(\\.\\d*)?";

    private static final String Base10Exponent = "[eE]" + OptionalSign + Base10Digits;
    private static final String OptionalBase10Exponent = "(" + Base10Exponent + ")?";

    private static final String  doubleRegex =
        OptionalSign +
        "(" +
            Infinity + "|" +
            NotANumber + "|"+
            "(" + Base10Digits + OptionalBase10Decimal + ")" + "|" +
            "(" + Base10Digits + OptionalBase10Decimal + Base10Exponent + ")" + "|" +
            "(" + Base10Decimal + OptionalBase10Exponent + ")" +
        ")";

    private static final Pattern FLOATING_POINT_PATTERN = Pattern.compile(doubleRegex);

    private static final TimeZone gmt = TimeZone.getTimeZone("gmt");

    private static final Supplier<DateFormat> DEFAULT_DATE_FORMAT = () -> getDateFormat(RecordFieldType.DATE.getDefaultFormat());
    private static final Supplier<DateFormat> DEFAULT_TIME_FORMAT = () -> getDateFormat(RecordFieldType.TIME.getDefaultFormat());
    private static final Supplier<DateFormat> DEFAULT_TIMESTAMP_FORMAT = () -> getDateFormat(RecordFieldType.TIMESTAMP.getDefaultFormat());

    public static Object convertType(final Object value, final DataType dataType, final String fieldName) {
        return convertType(value, dataType, fieldName, StandardCharsets.UTF_8);
    }

    public static Object convertType(final Object value, final DataType dataType, final String fieldName, final Charset charset) {
        return convertType(value, dataType, DEFAULT_DATE_FORMAT, DEFAULT_TIME_FORMAT, DEFAULT_TIMESTAMP_FORMAT, fieldName, charset);
    }

    public static DateFormat getDateFormat(final RecordFieldType fieldType, final Supplier<DateFormat> dateFormat,
        final Supplier<DateFormat> timeFormat, final Supplier<DateFormat> timestampFormat) {
        switch (fieldType) {
            case DATE:
                return dateFormat.get();
            case TIME:
                return timeFormat.get();
            case TIMESTAMP:
                return timestampFormat.get();
        }

        return null;
    }

    public static Object convertType(final Object value, final DataType dataType, final Supplier<DateFormat> dateFormat, final Supplier<DateFormat> timeFormat,
                                     final Supplier<DateFormat> timestampFormat, final String fieldName) {
        return convertType(value, dataType, dateFormat, timeFormat, timestampFormat, fieldName, StandardCharsets.UTF_8);
    }

    public static Object convertType(final Object value, final DataType dataType, final Supplier<DateFormat> dateFormat, final Supplier<DateFormat> timeFormat,
        final Supplier<DateFormat> timestampFormat, final String fieldName, final Charset charset) {

        if (value == null) {
            return null;
        }

        switch (dataType.getFieldType()) {
            case BIGINT:
                return toBigInt(value, fieldName);
            case BOOLEAN:
                return toBoolean(value, fieldName);
            case BYTE:
                return toByte(value, fieldName);
            case CHAR:
                return toCharacter(value, fieldName);
            case DATE:
                return toDate(value, dateFormat, fieldName);
            case DOUBLE:
                return toDouble(value, fieldName);
            case FLOAT:
                return toFloat(value, fieldName);
            case INT:
                return toInteger(value, fieldName);
            case LONG:
                return toLong(value, fieldName);
            case SHORT:
                return toShort(value, fieldName);
            case STRING:
                return toString(value, () -> getDateFormat(dataType.getFieldType(), dateFormat, timeFormat, timestampFormat), charset);
            case TIME:
                return toTime(value, timeFormat, fieldName);
            case TIMESTAMP:
                return toTimestamp(value, timestampFormat, fieldName);
            case ARRAY:
                return toArray(value, fieldName, ((ArrayDataType)dataType).getElementType(), charset);
            case MAP:
                return toMap(value, fieldName);
            case RECORD:
                final RecordDataType recordType = (RecordDataType) dataType;
                final RecordSchema childSchema = recordType.getChildSchema();
                return toRecord(value, childSchema, fieldName, charset);
            case CHOICE: {
                final ChoiceDataType choiceDataType = (ChoiceDataType) dataType;
                final DataType chosenDataType = chooseDataType(value, choiceDataType);
                if (chosenDataType == null) {
                    throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass()
                        + " for field " + fieldName + " to any of the following available Sub-Types for a Choice: " + choiceDataType.getPossibleSubTypes());
                }

                return convertType(value, chosenDataType, fieldName, charset);
            }
        }

        return null;
    }


    public static boolean isCompatibleDataType(final Object value, final DataType dataType) {
        switch (dataType.getFieldType()) {
            case ARRAY:
                return isArrayTypeCompatible(value, ((ArrayDataType) dataType).getElementType());
            case BIGINT:
                return isBigIntTypeCompatible(value);
            case BOOLEAN:
                return isBooleanTypeCompatible(value);
            case BYTE:
                return isByteTypeCompatible(value);
            case CHAR:
                return isCharacterTypeCompatible(value);
            case DATE:
                return isDateTypeCompatible(value, dataType.getFormat());
            case DOUBLE:
                return isDoubleTypeCompatible(value);
            case FLOAT:
                return isFloatTypeCompatible(value);
            case INT:
                return isIntegerTypeCompatible(value);
            case LONG:
                return isLongTypeCompatible(value);
            case RECORD: {
                final RecordSchema schema = ((RecordDataType) dataType).getChildSchema();
                return isRecordTypeCompatible(schema, value);
            }
            case SHORT:
                return isShortTypeCompatible(value);
            case TIME:
                return isTimeTypeCompatible(value, dataType.getFormat());
            case TIMESTAMP:
                return isTimestampTypeCompatible(value, dataType.getFormat());
            case STRING:
                return isStringTypeCompatible(value);
            case MAP:
                return isMapTypeCompatible(value);
            case CHOICE: {
                final DataType chosenDataType = chooseDataType(value, (ChoiceDataType) dataType);
                return chosenDataType != null;
            }
        }

        return false;
    }

    public static DataType chooseDataType(final Object value, final ChoiceDataType choiceType) {
        for (final DataType subType : choiceType.getPossibleSubTypes()) {
            if (isCompatibleDataType(value, subType)) {
                if (subType.getFieldType() == RecordFieldType.CHOICE) {
                    return chooseDataType(value, (ChoiceDataType) subType);
                }

                return subType;
            }
        }

        return null;
    }

    public static Record toRecord(final Object value, final RecordSchema recordSchema, final String fieldName) {
        return toRecord(value, recordSchema, fieldName, StandardCharsets.UTF_8);
    }

    public static Record toRecord(final Object value, final RecordSchema recordSchema, final String fieldName, final Charset charset) {
        if (value == null) {
            return null;
        }

        if (value instanceof Record) {
            return ((Record) value);
        }

        if (value instanceof Map) {
            if (recordSchema == null) {
                throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass()
                    + " to Record for field " + fieldName + " because the value is a Map but no Record Schema was provided");
            }

            final Map<?, ?> map = (Map<?, ?>) value;
            final Map<String, Object> coercedValues = new LinkedHashMap<>();

            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                final Object keyValue = entry.getKey();
                if (keyValue == null) {
                    continue;
                }

                final String key = keyValue.toString();
                final Optional<DataType> desiredTypeOption = recordSchema.getDataType(key);
                if (!desiredTypeOption.isPresent()) {
                    continue;
                }

                final Object rawValue = entry.getValue();
                final Object coercedValue = convertType(rawValue, desiredTypeOption.get(), fieldName, charset);
                coercedValues.put(key, coercedValue);
            }

            return new MapRecord(recordSchema, coercedValues);
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Record for field " + fieldName);
    }

    public static Record toRecord(final Object value, final String fieldName) {
        return toRecord(value, fieldName, StandardCharsets.UTF_8);
    }

    public static RecordSchema inferSchema(final Map<String, Object> values, final String fieldName, final Charset charset) {
        if (values == null) {
            return null;
        }

        final List<RecordField> inferredFieldTypes = new ArrayList<>();
        final Map<String, Object> coercedValues = new LinkedHashMap<>();

        for (final Map.Entry<?, ?> entry : values.entrySet()) {
            final Object keyValue = entry.getKey();
            if (keyValue == null) {
                continue;
            }

            final String key = keyValue.toString();
            final Object rawValue = entry.getValue();
            final DataType inferredDataType = inferDataType(rawValue, RecordFieldType.STRING.getDataType());

            final RecordField recordField = new RecordField(key, inferredDataType, true);
            inferredFieldTypes.add(recordField);

            final Object coercedValue = convertType(rawValue, inferredDataType, fieldName, charset);
            coercedValues.put(key, coercedValue);
        }

        final RecordSchema inferredSchema = new SimpleRecordSchema(inferredFieldTypes);
        return inferredSchema;
    }

    public static Record toRecord(final Object value, final String fieldName, final Charset charset) {
        if (value == null) {
            return null;
        }

        if (value instanceof Record) {
            return ((Record) value);
        }

        final List<RecordField> inferredFieldTypes = new ArrayList<>();
        if (value instanceof Map) {
            final Map<?, ?> map = (Map<?, ?>) value;
            final Map<String, Object> coercedValues = new LinkedHashMap<>();

            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                final Object keyValue = entry.getKey();
                if (keyValue == null) {
                    continue;
                }

                final String key = keyValue.toString();
                final Object rawValue = entry.getValue();
                final DataType inferredDataType = inferDataType(rawValue, RecordFieldType.STRING.getDataType());

                final RecordField recordField = new RecordField(key, inferredDataType, true);
                inferredFieldTypes.add(recordField);

                final Object coercedValue = convertType(rawValue, inferredDataType, fieldName, charset);
                coercedValues.put(key, coercedValue);
            }

            final RecordSchema inferredSchema = new SimpleRecordSchema(inferredFieldTypes);
            return new MapRecord(inferredSchema, coercedValues);
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Record for field " + fieldName);
    }

    public static DataType inferDataType(final Object value, final DataType defaultType) {
        if (value == null) {
            return defaultType;
        }

        if (value instanceof String) {
            return RecordFieldType.STRING.getDataType();
        }

        if (value instanceof Record) {
            final RecordSchema schema = ((Record) value).getSchema();
            return RecordFieldType.RECORD.getRecordDataType(schema);
        }

        if (value instanceof Number) {
            if (value instanceof Long) {
                return RecordFieldType.LONG.getDataType();
            }
            if (value instanceof Integer) {
                return RecordFieldType.INT.getDataType();
            }
            if (value instanceof Short) {
                return RecordFieldType.SHORT.getDataType();
            }
            if (value instanceof Byte) {
                return RecordFieldType.BYTE.getDataType();
            }
            if (value instanceof Float) {
                return RecordFieldType.FLOAT.getDataType();
            }
            if (value instanceof Double) {
                return RecordFieldType.DOUBLE.getDataType();
            }
            if (value instanceof BigInteger) {
                return RecordFieldType.BIGINT.getDataType();
            }
        }

        if (value instanceof Boolean) {
            return RecordFieldType.BOOLEAN.getDataType();
        }
        if (value instanceof java.sql.Time) {
            return RecordFieldType.TIME.getDataType();
        }
        if (value instanceof java.sql.Timestamp) {
            return RecordFieldType.TIMESTAMP.getDataType();
        }
        if (value instanceof java.util.Date) {
            return RecordFieldType.DATE.getDataType();
        }
        if (value instanceof Character) {
            return RecordFieldType.CHAR.getDataType();
        }

        // A value of a Map could be either a Record or a Map type. In either case, it must have Strings as keys.
        if (value instanceof Map) {
            final Map<String, ?> map = (Map<String, ?>) value;
            return inferRecordDataType(map);
//            // Check if all types are the same.
//            if (map.isEmpty()) {
//                return RecordFieldType.MAP.getMapDataType(RecordFieldType.STRING.getDataType());
//            }
//
//            Object valueFromMap = null;
//            Class<?> valueClass = null;
//            for (final Object val : map.values()) {
//                if (val == null) {
//                    continue;
//                }
//
//                valueFromMap = val;
//                final Class<?> currentValClass = val.getClass();
//                if (valueClass == null) {
//                    valueClass = currentValClass;
//                } else {
//                    // If we have two elements that are of different types, then we cannot have a Map. Must be a Record.
//                    if (valueClass != currentValClass) {
//                        return inferRecordDataType(map);
//                    }
//                }
//            }
//
//            // All values appear to be of the same type, so assume that it's a map.
//            final DataType elementDataType = inferDataType(valueFromMap, RecordFieldType.STRING.getDataType());
//            return RecordFieldType.MAP.getMapDataType(elementDataType);
        }
        if (value instanceof Object[]) {
            final Object[] array = (Object[]) value;

            DataType mergedDataType = null;
            for (final Object arrayValue : array) {
                final DataType inferredDataType = inferDataType(arrayValue, RecordFieldType.STRING.getDataType());
                mergedDataType = mergeDataTypes(mergedDataType, inferredDataType);
            }

            if (mergedDataType == null) {
                mergedDataType = RecordFieldType.STRING.getDataType();
            }

            return RecordFieldType.ARRAY.getArrayDataType(mergedDataType);
        }
        if (value instanceof Iterable) {
            final Iterable iterable = (Iterable<?>) value;

            DataType mergedDataType = null;
            for (final Object arrayValue : iterable) {
                final DataType inferredDataType = inferDataType(arrayValue, RecordFieldType.STRING.getDataType());
                mergedDataType = mergeDataTypes(mergedDataType, inferredDataType);
            }

            if (mergedDataType == null) {
                mergedDataType = RecordFieldType.STRING.getDataType();
            }

            return RecordFieldType.ARRAY.getArrayDataType(mergedDataType);
        }

        return defaultType;
    }

    private static DataType inferRecordDataType(final Map<String, ?> map) {
        final List<RecordField> fields = new ArrayList<>(map.size());
        for (final Map.Entry<String, ?> entry : map.entrySet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();

            final DataType dataType = inferDataType(value, RecordFieldType.STRING.getDataType());
            final RecordField field = new RecordField(key, dataType, true);
            fields.add(field);
        }

        final RecordSchema schema = new SimpleRecordSchema(fields);
        return RecordFieldType.RECORD.getRecordDataType(schema);
    }

    /**
     * Check if the given record structured object compatible with the schema.
     * @param schema record schema, schema validation will not be performed if schema is null
     * @param value the record structured object, i.e. Record or Map
     * @return True if the object is compatible with the schema
     */
    private static boolean isRecordTypeCompatible(RecordSchema schema, Object value) {

        if (value == null) {
            return false;
        }

        if (!(value instanceof Record) && !(value instanceof Map)) {
            return false;
        }

        if (schema == null) {
            return true;
        }

        for (final RecordField childField : schema.getFields()) {
            final Object childValue;
            if (value instanceof Record) {
                childValue = ((Record) value).getValue(childField);
            } else {
                childValue = ((Map) value).get(childField.getFieldName());
            }

            if (childValue == null && !childField.isNullable()) {
                logger.debug("Value is not compatible with schema because field {} has a null value, which is not allowed in the schema", childField.getFieldName());
                return false;
            }
            if (childValue == null) {
                continue; // consider compatible
            }

            if (!isCompatibleDataType(childValue, childField.getDataType())) {
                return false;
            }
        }
        return true;
    }

    public static Object[] toArray(final Object value, final String fieldName, final DataType elementDataType) {
        return toArray(value, fieldName, elementDataType, StandardCharsets.UTF_8);
    }

    public static Object[] toArray(final Object value, final String fieldName, final DataType elementDataType, final Charset charset) {
        if (value == null) {
            return null;
        }

        if (value instanceof Object[]) {
            return (Object[]) value;
        }

        if (value instanceof String && RecordFieldType.BYTE.getDataType().equals(elementDataType)) {
            byte[] src = ((String) value).getBytes(charset);
            Byte[] dest = new Byte[src.length];
            for (int i = 0; i < src.length; i++) {
                dest[i] = src[i];
            }
            return dest;
        }

        if (value instanceof byte[]) {
            byte[] src = (byte[]) value;
            Byte[] dest = new Byte[src.length];
            for (int i = 0; i < src.length; i++) {
                dest[i] = src[i];
            }
            return dest;
        }

        if (value instanceof List) {
            final List<?> list = (List<?>)value;
            return list.toArray();
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Object Array for field " + fieldName);
    }

    public static boolean isArrayTypeCompatible(final Object value, final DataType elementDataType) {
        return value != null
                // Either an object array or a String to be converted to byte[]
                && (value instanceof Object[]
                || (value instanceof String && RecordFieldType.BYTE.getDataType().equals(elementDataType)));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(final Object value, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof Map) {
            final Map<?, ?> original = (Map<?, ?>) value;

            boolean keysAreStrings = true;
            for (final Object key : original.keySet()) {
                if (!(key instanceof String)) {
                    keysAreStrings = false;
                }
            }

            if (keysAreStrings) {
                return (Map<String, Object>) value;
            }

            final Map<String, Object> transformed = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : original.entrySet()) {
                final Object key = entry.getKey();
                if (key == null) {
                    transformed.put(null, entry.getValue());
                } else {
                    transformed.put(key.toString(), entry.getValue());
                }
            }

            return transformed;
        }

        if (value instanceof Record) {
            final Record record = (Record) value;
            final RecordSchema recordSchema = record.getSchema();
            if (recordSchema == null) {
                throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type Record to Map for field " + fieldName
                    + " because Record does not have an associated Schema");
            }

            final Map<String, Object> map = new LinkedHashMap<>();
            for (final String recordFieldName : recordSchema.getFieldNames()) {
                map.put(recordFieldName, record.getValue(recordFieldName));
            }

            return map;
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Map for field " + fieldName);
    }

    /**
     * Creates a native Java object from a given object of a specified type. Non-scalar (complex, nested, etc.) data types are processed iteratively/recursively, such that all
     * included objects are native Java objects, rather than Record API objects or implementation-specific objects.
     * @param value The object to be converted
     * @param dataType The type of the provided object
     * @return An object representing a native Java conversion of the given input object
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object convertRecordFieldtoObject(final Object value, final DataType dataType) {

        if (value == null) {
            return null;
        }

        if (value instanceof Record) {
            Record record = (Record) value;
            RecordSchema recordSchema = record.getSchema();
            if (recordSchema == null) {
                throw new IllegalTypeConversionException("Cannot convert value of type Record to Map because Record does not have an associated Schema");
            }

            final Map<String, Object> recordMap = new LinkedHashMap<>();
            for (RecordField field : recordSchema.getFields()) {
                final DataType fieldDataType = field.getDataType();
                final String fieldName = field.getFieldName();
                Object fieldValue = record.getValue(fieldName);

                if (fieldValue == null) {
                    recordMap.put(fieldName, null);
                } else if (isScalarValue(fieldDataType, fieldValue)) {
                    recordMap.put(fieldName, fieldValue);
                } else if (fieldDataType instanceof RecordDataType) {
                    Record nestedRecord = (Record) fieldValue;
                    recordMap.put(fieldName, convertRecordFieldtoObject(nestedRecord, fieldDataType));
                } else if (fieldDataType instanceof MapDataType) {
                    recordMap.put(fieldName, convertRecordMapToJavaMap((Map) fieldValue, ((MapDataType)fieldDataType).getValueType()));

                } else if (fieldDataType instanceof ArrayDataType) {
                    recordMap.put(fieldName, convertRecordArrayToJavaArray((Object[])fieldValue, ((ArrayDataType) fieldDataType).getElementType()));
                } else {
                    throw new IllegalTypeConversionException("Cannot convert value [" + fieldValue + "] of type " + fieldDataType.toString()
                            + " to Map for field " + fieldName + " because the type is not supported");
                }
            }
            return recordMap;
        } else if (value instanceof Map) {
            return convertRecordMapToJavaMap((Map) value, ((MapDataType) dataType).getValueType());
        } else if (dataType != null && isScalarValue(dataType, value)) {
            return value;
        } else if (value instanceof Object[] && dataType instanceof ArrayDataType) {
            // This is likely a Map whose values are represented as an array. Return a new array with each element converted to a Java object
            return convertRecordArrayToJavaArray((Object[]) value, ((ArrayDataType) dataType).getElementType());
        }

        throw new IllegalTypeConversionException("Cannot convert value of class " + value.getClass().getName() + " because the type is not supported");
    }


    public static Map<String, Object> convertRecordMapToJavaMap(final Map<String, Object> map, DataType valueDataType) {

        if (map == null) {
            return null;
        }

        Map<String, Object> resultMap = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            resultMap.put(entry.getKey(), convertRecordFieldtoObject(entry.getValue(), valueDataType));
        }
        return resultMap;
    }

    public static Object[] convertRecordArrayToJavaArray(final Object[] array, DataType elementDataType) {

        if (array == null || array.length == 0 || isScalarValue(elementDataType, array[0])) {
            return array;
        } else {
            // Must be an array of complex types, build an array of converted values
            Object[] resultArray = new Object[array.length];
            for (int i = 0; i < array.length; i++) {
                resultArray[i] = convertRecordFieldtoObject(array[i], elementDataType);
            }
            return resultArray;
        }
    }

    public static boolean isMapTypeCompatible(final Object value) {
        return value != null && value instanceof Map;
    }


    public static String toString(final Object value, final Supplier<DateFormat> format) {
        return toString(value, format, StandardCharsets.UTF_8);
    }

    public static String toString(final Object value, final Supplier<DateFormat> format, final Charset charset) {
        if (value == null) {
            return null;
        }

        if (value instanceof String) {
            return (String) value;
        }

        if (format == null && value instanceof java.util.Date) {
            return String.valueOf(((java.util.Date) value).getTime());
        }

        if (value instanceof java.util.Date) {
            return formatDate((java.util.Date) value, format);
        }

        if (value instanceof byte[]) {
            return new String((byte[])value, charset);
        }

        if (value instanceof Byte[]) {
            Byte[] src = (Byte[]) value;
            byte[] dest = new byte[src.length];
            for(int i=0;i<src.length;i++) {
                dest[i] = src[i];
            }
            return new String(dest, charset);
        }
        if (value instanceof Object[]) {
            Object[] o = (Object[]) value;
            if (o.length > 0) {

                byte[] dest = new byte[o.length];
                for (int i = 0; i < o.length; i++) {
                    dest[i] = (byte) o[i];
                }
                return new String(dest, charset);
            } else {
                return ""; // Empty array = empty string
            }
        }

        return value.toString();
    }

    private static String formatDate(final java.util.Date date, final Supplier<DateFormat> formatSupplier) {
        final DateFormat dateFormat = formatSupplier.get();
        if (dateFormat == null) {
            return String.valueOf((date).getTime());
        }

        return dateFormat.format(date);
    }

    public static String toString(final Object value, final String format) {
        return toString(value, format, StandardCharsets.UTF_8);
    }

    public static String toString(final Object value, final String format, final Charset charset) {
        if (value == null) {
            return null;
        }

        if (value instanceof String) {
            return (String) value;
        }

        if (format == null && value instanceof java.util.Date) {
            return String.valueOf(((java.util.Date) value).getTime());
        }

        if (value instanceof java.sql.Date) {
            return getDateFormat(format).format((java.util.Date) value);
        }
        if (value instanceof java.sql.Time) {
            return getDateFormat(format).format((java.util.Date) value);
        }
        if (value instanceof java.sql.Timestamp) {
            return getDateFormat(format).format((java.util.Date) value);
        }
        if (value instanceof java.util.Date) {
            return getDateFormat(format).format((java.util.Date) value);
        }

        if (value instanceof Object[]) {
            return Arrays.toString((Object[]) value);
        }

        if (value instanceof byte[]) {
            return new String((byte[]) value, charset);
        }

        return value.toString();
    }

    public static boolean isStringTypeCompatible(final Object value) {
        return value != null;
    }

    public static java.sql.Date toDate(final Object value, final Supplier<DateFormat> format, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof java.util.Date) {
            java.util.Date _temp = (java.util.Date)value;
            return new Date(_temp.getTime());
        }

        if (value instanceof Date) {
            return (Date) value;
        }

        if (value instanceof Number) {
            final long longValue = ((Number) value).longValue();
            return new Date(longValue);
        }

        if (value instanceof String) {
            try {
                final String string = ((String) value).trim();
                if (string.isEmpty()) {
                    return null;
                }

                if (format == null) {
                    return new Date(Long.parseLong(string));
                }

                final DateFormat dateFormat = format.get();
                if (dateFormat == null) {
                    return new Date(Long.parseLong(string));
                }
                final java.util.Date utilDate = dateFormat.parse(string);
                return new Date(utilDate.getTime());
            } catch (final ParseException | NumberFormatException e) {
                throw new IllegalTypeConversionException("Could not convert value [" + value
                    + "] of type java.lang.String to Date because the value is not in the expected date format: " + format + " for field " + fieldName);
            }
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Date for field " + fieldName);
    }

    public static boolean isDateTypeCompatible(final Object value, final String format) {
        if (value == null) {
            return false;
        }

        if (value instanceof java.util.Date || value instanceof Number) {
            return true;
        }

        if (value instanceof String) {
            if (format == null) {
                return isInteger((String) value);
            }

            try {
                getDateFormat(format).parse((String) value);
                return true;
            } catch (final ParseException e) {
                return false;
            }
        }

        return false;
    }

    private static boolean isInteger(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    public static Time toTime(final Object value, final Supplier<DateFormat> format, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof Time) {
            return (Time) value;
        }

        if (value instanceof Number) {
            final long longValue = ((Number) value).longValue();
            return new Time(longValue);
        }

        if (value instanceof String) {
            try {
                final String string = ((String) value).trim();
                if (string.isEmpty()) {
                    return null;
                }

                if (format == null) {
                    return new Time(Long.parseLong(string));
                }

                final DateFormat dateFormat = format.get();
                if (dateFormat == null) {
                    return new Time(Long.parseLong(string));
                }
                final java.util.Date utilDate = dateFormat.parse(string);
                return new Time(utilDate.getTime());
            } catch (final ParseException e) {
                throw new IllegalTypeConversionException("Could not convert value [" + value
                    + "] of type java.lang.String to Time for field " + fieldName + " because the value is not in the expected date format: " + format);
            }
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Time for field " + fieldName);
    }

    public static DateFormat getDateFormat(final String format) {
        if (format == null) {
            return null;
        }
        final DateFormat df = new SimpleDateFormat(format);
        df.setTimeZone(gmt);
        return df;
    }

    public static boolean isTimeTypeCompatible(final Object value, final String format) {
        return isDateTypeCompatible(value, format);
    }

    public static Timestamp toTimestamp(final Object value, final Supplier<DateFormat> format, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof java.util.Date) {
            return new Timestamp(((java.util.Date)value).getTime());
        }

        if (value instanceof Timestamp) {
            return (Timestamp) value;
        }

        if (value instanceof Number) {
            final long longValue = ((Number) value).longValue();
            return new Timestamp(longValue);
        }

        if (value instanceof String) {
            try {
                final String string = ((String) value).trim();
                if (string.isEmpty()) {
                    return null;
                }

                if (format == null) {
                    return new Timestamp(Long.parseLong(string));
                }

                final DateFormat dateFormat = format.get();
                if (dateFormat == null) {
                    return new Timestamp(Long.parseLong(string));
                }
                final java.util.Date utilDate = dateFormat.parse(string);
                return new Timestamp(utilDate.getTime());
            } catch (final ParseException e) {
                throw new IllegalTypeConversionException("Could not convert value [" + value
                    + "] of type java.lang.String to Timestamp for field " + fieldName + " because the value is not in the expected date format: " + format);
            }
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Timestamp for field " + fieldName);
    }

    public static boolean isTimestampTypeCompatible(final Object value, final String format) {
        return isDateTypeCompatible(value, format);
    }


    public static BigInteger toBigInt(final Object value, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Long) {
            return BigInteger.valueOf((Long) value);
        }
        if (value instanceof Integer) {
            return BigInteger.valueOf(((Integer) value).longValue());
        }
        if (value instanceof Short) {
            return BigInteger.valueOf(((Short) value).longValue());
        }
        if (value instanceof String) {
            try {
                return new BigInteger((String) value);
            } catch (NumberFormatException nfe) {
                throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to BigInteger for field " + fieldName
                        + ", value is not a valid representation of BigInteger", nfe);
            }
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to BigInteger for field " + fieldName);
    }

    public static boolean isBigIntTypeCompatible(final Object value) {
        return value instanceof BigInteger
                || value instanceof Long
                || value instanceof Integer
                || value instanceof Short
                || value instanceof String;
    }

    public static Boolean toBoolean(final Object value, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            final String string = (String) value;
            if (string.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            } else if (string.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Boolean for field " + fieldName);
    }

    public static boolean isBooleanTypeCompatible(final Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return true;
        }
        if (value instanceof String) {
            final String string = (String) value;
            return string.equalsIgnoreCase("true") || string.equalsIgnoreCase("false");
        }
        return false;
    }

    public static Double toDouble(final Object value, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        if (value instanceof String) {
            return Double.parseDouble((String) value);
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Double for field " + fieldName);
    }

    public static boolean isDoubleTypeCompatible(final Object value) {
        return isNumberTypeCompatible(value, s -> isDouble(s));
    }

    private static boolean isNumberTypeCompatible(final Object value, final Predicate<String> stringPredicate) {
        if (value == null) {
            return false;
        }

        if (value instanceof Number) {
            return true;
        }

        if (value instanceof String) {
            return stringPredicate.test((String) value);
        }

        return false;
    }

    public static Float toFloat(final Object value, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }

        if (value instanceof String) {
            return Float.parseFloat((String) value);
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Float for field " + fieldName);
    }

    public static boolean isFloatTypeCompatible(final Object value) {
        return isNumberTypeCompatible(value, s -> isFloatingPoint(s));
    }

    private static boolean isFloatingPoint(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        if (!FLOATING_POINT_PATTERN.matcher(value).matches()) {
            return false;
        }

        // Just to ensure that the exponents are in range, etc.
        try {
            Float.parseFloat(value);
        } catch (final NumberFormatException nfe) {
            return false;
        }

        return true;
    }

    private static boolean isDouble(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        if (!FLOATING_POINT_PATTERN.matcher(value).matches()) {
            return false;
        }

        // Just to ensure that the exponents are in range, etc.
        try {
            Double.parseDouble(value);
        } catch (final NumberFormatException nfe) {
            return false;
        }

        return true;
    }

    public static Long toLong(final Object value, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        if (value instanceof String) {
            return Long.parseLong((String) value);
        }

        if (value instanceof java.util.Date) {
            return ((java.util.Date) value).getTime();
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Long for field " + fieldName);
    }

    public static boolean isLongTypeCompatible(final Object value) {
        if (value == null) {
            return false;
        }

        if (value instanceof Number) {
            return true;
        }

        if (value instanceof java.util.Date) {
            return true;
        }

        if (value instanceof String) {
            return isIntegral((String) value, Long.MIN_VALUE, Long.MAX_VALUE);
        }

        return false;
    }

    private static boolean isIntegral(final String value, final long minValue, final long maxValue) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        int initialPosition = 0;
        final char firstChar = value.charAt(0);
        if (firstChar == '+' || firstChar == '-') {
            initialPosition = 1;

            if (value.length() == 1) {
                return false;
            }
        }

        for (int i = initialPosition; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }

        try {
            final long longValue = Long.parseLong(value);
            return longValue >= minValue && longValue <= maxValue;
        } catch (final NumberFormatException nfe) {
            // In case the value actually exceeds the max value of a Long
            return false;
        }
    }


    public static Integer toInteger(final Object value, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        if (value instanceof String) {
            return Integer.parseInt((String) value);
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Integer for field " + fieldName);
    }

    public static boolean isIntegerTypeCompatible(final Object value) {
        return isNumberTypeCompatible(value, s -> isIntegral(s, Integer.MIN_VALUE, Integer.MAX_VALUE));
    }


    public static Short toShort(final Object value, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return ((Number) value).shortValue();
        }

        if (value instanceof String) {
            return Short.parseShort((String) value);
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Short for field " + fieldName);
    }

    public static boolean isShortTypeCompatible(final Object value) {
        return isNumberTypeCompatible(value, s -> isIntegral(s, Short.MIN_VALUE, Short.MAX_VALUE));
    }

    public static Byte toByte(final Object value, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return ((Number) value).byteValue();
        }

        if (value instanceof String) {
            return Byte.parseByte((String) value);
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Byte for field " + fieldName);
    }

    public static boolean isByteTypeCompatible(final Object value) {
        return isNumberTypeCompatible(value, s -> isIntegral(s, Byte.MIN_VALUE, Byte.MAX_VALUE));
    }


    public static Character toCharacter(final Object value, final String fieldName) {
        if (value == null) {
            return null;
        }

        if (value instanceof Character) {
            return ((Character) value);
        }

        if (value instanceof CharSequence) {
            final CharSequence charSeq = (CharSequence) value;
            if (charSeq.length() == 0) {
                throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass()
                    + " to Character because it has a length of 0 for field " + fieldName);
            }

            return charSeq.charAt(0);
        }

        throw new IllegalTypeConversionException("Cannot convert value [" + value + "] of type " + value.getClass() + " to Character for field " + fieldName);
    }

    public static boolean isCharacterTypeCompatible(final Object value) {
        return value != null && (value instanceof Character || (value instanceof CharSequence && ((CharSequence) value).length() > 0));
    }

    public static RecordSchema merge(final RecordSchema thisSchema, final RecordSchema otherSchema) {
        if (thisSchema == null) {
            return otherSchema;
        }
        if (otherSchema == null) {
            return thisSchema;
        }
        if (thisSchema == otherSchema) {
            return thisSchema;
        }

        final List<RecordField> otherFields = otherSchema.getFields();
        if (otherFields.isEmpty()) {
            return thisSchema;
        }

        final List<RecordField> thisFields = thisSchema.getFields();
        if (thisFields.isEmpty()) {
            return otherSchema;
        }

        final Map<String, Integer> fieldIndices = new HashMap<>();
        final List<RecordField> fields = new ArrayList<>();
        for (int i = 0; i < thisFields.size(); i++) {
            final RecordField field = thisFields.get(i);

            final Integer index = Integer.valueOf(i);

            fieldIndices.put(field.getFieldName(), index);
            for (final String alias : field.getAliases()) {
                fieldIndices.put(alias, index);
            }

            fields.add(field);
        }

        for (final RecordField otherField : otherFields) {
            Integer fieldIndex = fieldIndices.get(otherField.getFieldName());

            // Find the field in 'thisSchema' that corresponds to 'otherField',
            // if one exists.
            if (fieldIndex == null) {
                for (final String alias : otherField.getAliases()) {
                    fieldIndex = fieldIndices.get(alias);
                    if (fieldIndex != null) {
                        break;
                    }
                }
            }

            // If there is no field with the same name then just add 'otherField'.
            if (fieldIndex == null) {
                fields.add(otherField);
                continue;
            }

            // Merge the two fields, if necessary
            final RecordField thisField = fields.get(fieldIndex);
            if (isMergeRequired(thisField, otherField)) {
                final RecordField mergedField = merge(thisField, otherField);
                fields.set(fieldIndex, mergedField);
            }
        }

        return new SimpleRecordSchema(fields);
    }


    private static boolean isMergeRequired(final RecordField thisField, final RecordField otherField) {
        if (!thisField.getDataType().equals(otherField.getDataType())) {
            return true;
        }

        if (!thisField.getAliases().equals(otherField.getAliases())) {
            return true;
        }

        if (!Objects.equals(thisField.getDefaultValue(), otherField.getDefaultValue())) {
            return true;
        }

        return false;
    }

    public static RecordField merge(final RecordField thisField, final RecordField otherField) {
        final String fieldName = thisField.getFieldName();
        final Set<String> aliases = new HashSet<>();
        aliases.addAll(thisField.getAliases());
        aliases.addAll(otherField.getAliases());

        final Object defaultValue;
        if (thisField.getDefaultValue() == null && otherField.getDefaultValue() != null) {
            defaultValue = otherField.getDefaultValue();
        } else {
            defaultValue = thisField.getDefaultValue();
        }

        final DataType dataType = mergeDataTypes(thisField.getDataType(), otherField.getDataType());
        return new RecordField(fieldName, dataType, defaultValue, aliases, thisField.isNullable() || otherField.isNullable());
    }

    public static DataType mergeDataTypes(final DataType thisDataType, final DataType otherDataType) {
        if (thisDataType == null) {
            return otherDataType;
        }

        if (otherDataType == null) {
            return thisDataType;
        }

        if (thisDataType.equals(otherDataType)) {
            return thisDataType;
        } else {
            // If one type is 'wider' than the other (such as an INT and a LONG), just use the wider type (LONG, in this case),
            // rather than using a CHOICE of the two.
            final Optional<DataType> widerType = getWiderType(thisDataType, otherDataType);
            if (widerType.isPresent()) {
                return widerType.get();
            }

            final Set<DataType> possibleTypes = new LinkedHashSet<>();
            if (thisDataType.getFieldType() == RecordFieldType.CHOICE) {
                possibleTypes.addAll(((ChoiceDataType) thisDataType).getPossibleSubTypes());
            } else {
                possibleTypes.add(thisDataType);
            }

            if (otherDataType.getFieldType() == RecordFieldType.CHOICE) {
                possibleTypes.addAll(((ChoiceDataType) otherDataType).getPossibleSubTypes());
            } else {
                possibleTypes.add(otherDataType);
            }

            return RecordFieldType.CHOICE.getChoiceDataType(new ArrayList<>(possibleTypes));
        }
    }

    public static Optional<DataType> getWiderType(final DataType thisDataType, final DataType otherDataType) {
        final RecordFieldType thisFieldType = thisDataType.getFieldType();
        final RecordFieldType otherFieldType = otherDataType.getFieldType();

        final int thisIntTypeValue = getIntegerTypeValue(thisFieldType);
        final int otherIntTypeValue = getIntegerTypeValue(otherFieldType);
        if (thisIntTypeValue > -1 && otherIntTypeValue > -1) {
            if (thisIntTypeValue > otherIntTypeValue) {
                return Optional.of(thisDataType);
            }

            return Optional.of(otherDataType);
        }

        switch (thisFieldType) {
            case FLOAT:
                if (otherFieldType == RecordFieldType.DOUBLE) {
                    return Optional.of(otherDataType);
                }
                break;
            case DOUBLE:
                if (otherFieldType == RecordFieldType.FLOAT) {
                    return Optional.of(thisDataType);
                }
                break;


            case CHAR:
                if (otherFieldType == RecordFieldType.STRING) {
                    return Optional.of(otherDataType);
                }
                break;
            case STRING:
                if (otherFieldType == RecordFieldType.CHAR) {
                    return Optional.of(thisDataType);
                }
                break;
        }

        return Optional.empty();
    }

    private static int getIntegerTypeValue(final RecordFieldType fieldType) {
        switch (fieldType) {
            case BIGINT:
                return 4;
            case LONG:
                return 3;
            case INT:
                return 2;
            case SHORT:
                return 1;
            case BYTE:
                return 0;
            default:
                return -1;
        }
    }

    public static boolean isScalarValue(final DataType dataType, final Object value) {
        final RecordFieldType fieldType = dataType.getFieldType();

        final RecordFieldType chosenType;
        if (fieldType == RecordFieldType.CHOICE) {
            final ChoiceDataType choiceDataType = (ChoiceDataType) dataType;
            final DataType chosenDataType = chooseDataType(value, choiceDataType);
            if (chosenDataType == null) {
                return false;
            }

            chosenType = chosenDataType.getFieldType();
        } else {
            chosenType = fieldType;
        }

        switch (chosenType) {
            case ARRAY:
            case MAP:
            case RECORD:
                return false;
        }

        return true;
    }

    public static Charset getCharset(String charsetName) {
        if(charsetName == null) {
            return StandardCharsets.UTF_8;
        } else {
            return Charset.forName(charsetName);
        }
    }
}
