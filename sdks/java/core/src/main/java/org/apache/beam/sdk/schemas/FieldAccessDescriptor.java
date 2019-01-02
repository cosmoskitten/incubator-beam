/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.schemas;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoOneOf;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.schemas.FieldAccessDescriptor.FieldDescriptor.Qualifier;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.Schema.TypeName;


/**
 * Used inside of a {@link org.apache.beam.sdk.transforms.DoFn} to describe which fields in a schema
 * type need to be accessed for processing.
 *
 * <p>This class always puts the selected fields in a deterministic order.
 */
@Experimental(Kind.SCHEMAS)
@AutoValue
public abstract class FieldAccessDescriptor implements Serializable {
  @AutoValue
  public abstract static class FieldDescriptor {
    public static class ListQualifier {}
    public static class MapQualifier {}

    @AutoOneOf(Qualifier.Kind.class)
    public abstract static class Qualifier {
      public enum Kind {LIST, MAP};
      public abstract Kind getKind();
      public abstract ListQualifier getList();
      public abstract MapQualifier getMap();
      public static Qualifier of(ListQualifier qualifier) {
        return AutoOneOf_FieldAccessDescriptor_FieldDescriptor_Qualifier.list(qualifier);
      }
      public static Qualifier of(MapQualifier qualifier) {
        return AutoOneOf_FieldAccessDescriptor_FieldDescriptor_Qualifier.map(qualifier);
      }
    }

    @Nullable
    abstract String getFieldName();

    @Nullable
    abstract Integer getFieldId();

    abstract List<Qualifier> getQualifiers();

    static Builder builder() {
      return new AutoValue_FieldAccessDescriptor_FieldDescriptor.Builder()
          .setQualifiers(Collections.emptyList());
    }

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setFieldName(@Nullable String name);
      abstract Builder setFieldId(@Nullable Integer id);
      abstract Builder setQualifiers(List<Qualifier> qualifiers);
      abstract FieldDescriptor build();
    }
    abstract Builder toBuilder();
  }

  @AutoValue.Builder
  abstract static class Builder {
    abstract Builder setAllFields(boolean allFields);

    abstract Builder setFieldsAccessed(Set<FieldDescriptor> fieldsAccessed);

    abstract Builder setNestedFieldsAccessed(
        Map<FieldDescriptor, FieldAccessDescriptor> nestedFieldsAccessedById);

    abstract Builder setFieldInsertionOrder(boolean insertionOrder);

    abstract FieldAccessDescriptor build();
  }

  abstract boolean getAllFields();

  abstract Set<FieldDescriptor> getFieldsAccessed();

  abstract Map<FieldDescriptor, FieldAccessDescriptor> getNestedFieldsAccessed();

  abstract boolean getFieldInsertionOrder();

  abstract Builder toBuilder();

  static Builder builder() {
    return new AutoValue_FieldAccessDescriptor.Builder()
        .setAllFields(false)
        .setFieldInsertionOrder(false)
        .setFieldsAccessed(Collections.emptySet())
        .setNestedFieldsAccessed(Collections.emptyMap());
  }

  // Return a descriptor that accesses all fields in a row.
  public static FieldAccessDescriptor withAllFields() {
    return builder().setAllFields(true).build();
  }

  /**
   * Return a descriptor that access the specified fields.
   *
   * <p>By default, if the field is a nested row (or a container containing a row), all fields of
   * said rows are accessed. The syntax for a field name allows specifying nested fields and
   * wildcards, as specified in the file-level Javadoc. withNestedField can also be called to
   * specify recursive field access.
   */
  public static FieldAccessDescriptor withFieldNames(String... names) {
    return withFieldNames(Arrays.asList(names));
  }

  /**
   * Return a descriptor that access the specified fields.
   *
   * <p>By default, if the field is a nested row (or a container containing a row), all fields of
   * said rows are accessed. The syntax for a field name allows specifying nested fields and
   * wildcards, as specified in the file-level Javadoc. withNestedField can also be called to
   * specify recursive field access.
   */
  public static FieldAccessDescriptor withFieldNames(Iterable<String> fieldNames) {
    List<FieldAccessDescriptor> fields = Lists.newArrayList();
    for (String name : fieldNames) {
      fields.add(FieldAccessDescriptorParser.parse(name));
    }
    /*
    List<FieldAccessDescriptor> fields = StreamSupport.stream(fieldNames.spliterator(), false)
        .map(FieldAccessDescriptorParser::parse)
        .collect(Collectors.toList());*/
    return union(fields);
  }

  /**
   * Return a descriptor that access the specified fields.
   *
   * <p>By default, if the field is a nested row (or a container containing a row), all fields of
   * said rows are accessed. For finer-grained acccess to nested rows, call withNestedField and pass
   * in a recursive {@link FieldAccessDescriptor}.
   */
  public static FieldAccessDescriptor withFieldIds(Integer... ids) {
    return withFieldIds(Arrays.asList(ids));
  }

  /**
   * Return a descriptor that access the specified fields.
   *
   * <p>By default, if the field is a nested row (or a container containing a row), all fields of
   * said rows are accessed. For finer-grained acccess to nested rows, call withNestedField and pass
   * in a recursive {@link FieldAccessDescriptor}.
   */
  public static FieldAccessDescriptor withFieldIds(Iterable<Integer> ids) {
    List<FieldDescriptor> fields = StreamSupport.stream(ids.spliterator(), false)
        .map(n -> FieldDescriptor.builder().setFieldId(n).build())
        .collect(Collectors.toList());
    return withFields(fields);
  }

  public static FieldAccessDescriptor withFields(FieldDescriptor... fields) {
    return withFields(Arrays.asList(fields));
  }

  public static FieldAccessDescriptor withFields(Iterable<FieldDescriptor> fields) {
    return builder().setFieldsAccessed(Sets.newLinkedHashSet(fields)).build();
  }

  // Union a set of FieldAccessDescriptors. This function currenty only supports descriptors with
  // containing named fields, not those containing ids.
  private static FieldAccessDescriptor union(
      Iterable<FieldAccessDescriptor> fieldAccessDescriptors) {
    Set<FieldDescriptor> fieldsAccessed = Sets.newHashSet();
    Multimap<FieldDescriptor, FieldAccessDescriptor> nestedFieldsAccessed =
        ArrayListMultimap.create();
    for (FieldAccessDescriptor fieldAccessDescriptor : fieldAccessDescriptors) {
      if (fieldAccessDescriptor.getAllFields()) {
        return FieldAccessDescriptor.withAllFields();
      }

      for (FieldDescriptor field : fieldAccessDescriptor.getFieldsAccessed()) {
        if (field.getFieldId() != null) {
          throw new IllegalArgumentException("union doesn't support id fields.");
        }
        fieldsAccessed.add(field);
      }
      for (Map.Entry<FieldDescriptor, FieldAccessDescriptor> nested
          : fieldAccessDescriptor.getNestedFieldsAccessed().entrySet()) {
        FieldDescriptor field = nested.getKey();
        nestedFieldsAccessed.put(field, nested.getValue());
      }
    }
    FieldAccessDescriptor fieldAccessDescriptor = FieldAccessDescriptor.withFields(fieldsAccessed);
    for (Map.Entry<FieldDescriptor, Collection<FieldAccessDescriptor>> entry
        : nestedFieldsAccessed.asMap().entrySet()) {
      if (fieldsAccessed.contains(entry.getKey())) {
        // We're already reading the entire field, so no need to specify nested fields.
        continue;
      }
      // If there are multiple subdescriptors for this field (e.g. a.b, a.c, a.d), union them
      // together and create a new nested field description.
      fieldAccessDescriptor = fieldAccessDescriptor.withNestedField(
          entry.getKey(), union(entry.getValue()));
    }
    return fieldAccessDescriptor;
  }

  /** Return an empty {@link FieldAccessDescriptor}. */
  public static FieldAccessDescriptor create() {
    return builder().build();
  }

  /**
   * Return a descriptor that access the specified nested field. The nested field must be of type
   * {@link Schema.TypeName#ROW}, and the fieldAccess argument specifies what fields of the nested
   * type will be accessed.
   */
  public FieldAccessDescriptor withNestedField(
      int nestedFieldId, FieldAccessDescriptor fieldAccess) {
    FieldDescriptor field = FieldDescriptor.builder().setFieldId(nestedFieldId).build();
    return withNestedField(field, fieldAccess);
  }

  /**
   * Return a descriptor that access the specified nested field. The nested field must be of type
   * {@link Schema.TypeName#ROW}, and the fieldAccess argument specifies what fields of the nested
   * type will be accessed.
   */
  public FieldAccessDescriptor withNestedField(
      String nestedFieldName, FieldAccessDescriptor fieldAccess) {
    FieldDescriptor field = FieldDescriptor.builder().setFieldName(nestedFieldName).build();
    return withNestedField(field, fieldAccess);
  }

  public FieldAccessDescriptor withNestedField(
      FieldDescriptor field, FieldAccessDescriptor fieldAccess) {
    Map<FieldDescriptor, FieldAccessDescriptor> newNestedFieldAccess =
        ImmutableMap.<FieldDescriptor, FieldAccessDescriptor>builder()
            .putAll(getNestedFieldsAccessed())
            .put(field, fieldAccess)
            .build();
    return toBuilder().setNestedFieldsAccessed(newNestedFieldAccess).build();
  }

  /**
   * By default, fields are sorted by name. If this is set, they will instead be sorted by insertion
   * order. All sorting happens in the {@link #resolve(Schema)} method.
   */
  public FieldAccessDescriptor withOrderByFieldInsertionOrder() {
    return toBuilder().setFieldInsertionOrder(true).build();
  }

  public boolean allFields() {
    return getAllFields();
  }

  public Set<Integer> fieldIdsAccessed() {
    return getFieldsAccessed().stream()
        .map(FieldDescriptor::getFieldId)
        .collect(Collectors.toSet());
  }

  public Map<Integer, FieldAccessDescriptor> nestedFields() {
    return getNestedFieldsAccessed().entrySet().stream()
        .collect(Collectors.toMap(
            f -> f.getKey().getFieldId(),
            f -> f.getValue()));
  }

  // After resolution, fields are always ordered by their field name.
  public FieldAccessDescriptor resolve(Schema schema) {
    Set<FieldDescriptor> resolvedFieldIdsAccessed = resolveFieldIdsAccessed(schema);
    Map<FieldDescriptor, FieldAccessDescriptor> resolvedNestedFieldsAccessed =
        resolveNestedFieldsAccessed(schema);

    checkState(
        !getAllFields() || resolvedNestedFieldsAccessed.isEmpty(),
        "nested fields cannot be set if allFields is also set");

    // If a recursive access is set for any nested fields, remove those fields from
    // fieldIdsAccessed.
    resolvedFieldIdsAccessed.removeAll(resolvedNestedFieldsAccessed.keySet());

    return builder()
        .setAllFields(getAllFields())
        .setFieldsAccessed(resolvedFieldIdsAccessed)
        .setNestedFieldsAccessed(resolvedNestedFieldsAccessed)
        .build();
  }

  private Set<FieldDescriptor> resolveFieldIdsAccessed(Schema schema) {
    Set<FieldDescriptor> fields;
    if (getFieldInsertionOrder()) {
      fields = Sets.newLinkedHashSet();
    } else {
      fields = Sets.newTreeSet(Comparator.comparing(FieldDescriptor::getFieldId));
    }

    for (FieldDescriptor field : getFieldsAccessed()) {
      validateFieldDescriptor(schema, field);
      if (field.getFieldId() == null) {
        field = field.toBuilder().setFieldId(schema.indexOf(field.getFieldName())).build();
      }
      fields.add(field);
    }
    return fields;
  }

  private static Schema getFieldSchema(FieldType type) {
    if (TypeName.ROW.equals(type.getTypeName())) {
      return type.getRowSchema();
    } else if (TypeName.ARRAY.equals(type.getTypeName())
        && TypeName.ROW.equals(type.getCollectionElementType().getTypeName())) {
      return type.getCollectionElementType().getRowSchema();
    } else if (TypeName.MAP.equals(type.getTypeName())
        && TypeName.ROW.equals(type.getMapValueType().getTypeName())) {
      return type.getMapValueType().getRowSchema();
    } else {
      throw new IllegalArgumentException(
          "FieldType " + type + " must be either a row or " + " a container containing rows");
    }
  }

  private Map<FieldDescriptor, FieldAccessDescriptor> resolveNestedFieldsAccessed(Schema schema) {
    Map<FieldDescriptor, FieldAccessDescriptor> nestedFields;
    if (getFieldInsertionOrder()) {
      nestedFields = Maps.newLinkedHashMap();
    } else {
      nestedFields = Maps.newTreeMap(Comparator.comparing(FieldDescriptor::getFieldId));
    }

    for (Map.Entry<FieldDescriptor, FieldAccessDescriptor> entry
        : getNestedFieldsAccessed().entrySet()) {
      FieldDescriptor fieldDescriptor = entry.getKey();
      FieldAccessDescriptor fieldAccessDescriptor = entry.getValue();
      validateFieldDescriptor(schema, fieldDescriptor);
      if (entry.getKey().getFieldId() == null) {
        fieldDescriptor = fieldDescriptor.toBuilder()
            .setFieldId(schema.indexOf(fieldDescriptor.getFieldName()))
            .build();
      }

      FieldType fieldType = schema.getField(fieldDescriptor.getFieldId()).getType();
      while (fieldType.getTypeName().isCollectionType() ||  fieldType.getTypeName().isMapType()) {
        if (fieldType.getTypeName().isCollectionType()) {
          fieldType = fieldType.getCollectionElementType();
        } else if (fieldType.getTypeName().isMapType()) {
          fieldType = fieldType.getMapValueType();
        }
      }

      fieldAccessDescriptor = fieldAccessDescriptor.resolve(getFieldSchema(fieldType));
      nestedFields.put(fieldDescriptor, fieldAccessDescriptor);
    }

    return nestedFields;
  }

  private static void validateFieldDescriptor(Schema schema, FieldDescriptor fieldDescriptor) {
    Integer fieldId = fieldDescriptor.getFieldId();
    if (fieldId != null) {
      if (fieldId < 0 || fieldId >= schema.getFieldCount()) {
        throw new IllegalArgumentException("Invalid field id " + fieldId + " for schema " + schema);
      }
    }
    // If qualifiers were specified, validate them.
    Field field = (fieldId != null)
        ? schema.getField(fieldId) : schema.getField(fieldDescriptor.getFieldName());
    FieldType fieldType = field.getType();
    for (Qualifier qualifier : fieldDescriptor.getQualifiers()) {
      switch (qualifier.getKind()) {
        case LIST:
          checkArgument(fieldType.getTypeName().equals(TypeName.ARRAY));
          fieldType = fieldType.getCollectionElementType();
          break;
        case MAP:
          checkArgument(fieldType.getTypeName().equals(TypeName.MAP));
          // TODO: We should support a way to extract map keys.
          fieldType = fieldType.getMapValueType();
          break;
        default:
          break;
      }
    }
  }
}
