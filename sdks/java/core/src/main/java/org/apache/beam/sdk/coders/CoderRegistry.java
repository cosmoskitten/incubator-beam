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
package org.apache.beam.sdk.coders;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.CannotProvideCoderException.ReasonCode;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.util.CoderUtils;
import org.apache.beam.sdk.util.common.ReflectHelpers;
import org.apache.beam.sdk.util.common.ReflectHelpers.ObjectsClassComparator;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.TimestampedValue;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link CoderRegistry} allows creating a {@link Coder} for a given Java {@link Class class} or
 * {@link TypeDescriptor type descriptor}.
 *
 * <p>Creation of the {@link Coder} is delegated to one of the many registered
 * {@link CoderFactory coder factories} based upon the registration order.
 *
 * <p>By default, the {@link CoderFactory coder factory} precedence order is as follows:
 * <ul>
 *   <li>Coder factories registered programmatically with
 *       {@link CoderRegistry#registerCoderFactory(CoderFactory)}.</li>
 *   <li>Standard coder factories for common Java (Byte, Double, List, ...) and
 *       Apache Beam (KV, ...) types.</li>
 *   <li>Coder factories registered automatically through a {@link CoderFactoryRegistrar} using
 *       a {@link ServiceLoader}. Note that the {@link ServiceLoader} registration order is
 *       consistent but may change due to the addition or removal of libraries exposed
 *       to the application. This can impact the coder returned if multiple coder factories
 *       are capable of supplying a coder for the specified type.</li>
 * </ul>
 *
 * <p>Note that if multiple {@link CoderFactory coder factories} can provide a {@link Coder} for
 * a given type, the precedence order above defines which {@link CoderFactory} is chosen.
 */
public class CoderRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(CoderRegistry.class);
  private static final List<CoderFactory> REGISTERED_CODER_FACTORIES;

  /** A {@link CoderFactory} for common Java SDK and Apache Beam SDK types. */
  private static class CommonTypes implements CoderFactory {
    private final Map<Class<?>, CoderFactory> commonTypesToCoderFactories;

    private CommonTypes() {
      ImmutableMap.Builder<Class<?>, CoderFactory> builder = ImmutableMap.builder();
      builder.put(Byte.class,
          CoderFactories.fromStaticMethods(Byte.class, ByteCoder.class));
      builder.put(BitSet.class,
          CoderFactories.fromStaticMethods(BitSet.class, BitSetCoder.class));
      builder.put(Double.class,
          CoderFactories.fromStaticMethods(Double.class, DoubleCoder.class));
      builder.put(Instant.class,
          CoderFactories.fromStaticMethods(Instant.class, InstantCoder.class));
      builder.put(Integer.class,
          CoderFactories.fromStaticMethods(Integer.class, VarIntCoder.class));
      builder.put(Iterable.class,
          CoderFactories.fromStaticMethods(Iterable.class, IterableCoder.class));
      builder.put(KV.class,
          CoderFactories.fromStaticMethods(KV.class, KvCoder.class));
      builder.put(List.class,
          CoderFactories.fromStaticMethods(List.class, ListCoder.class));
      builder.put(Long.class,
          CoderFactories.fromStaticMethods(Long.class, VarLongCoder.class));
      builder.put(Map.class,
          CoderFactories.fromStaticMethods(Map.class, MapCoder.class));
      builder.put(Set.class,
          CoderFactories.fromStaticMethods(Set.class, SetCoder.class));
      builder.put(String.class,
          CoderFactories.fromStaticMethods(String.class, StringUtf8Coder.class));
      builder.put(TimestampedValue.class,
          CoderFactories.fromStaticMethods(
              TimestampedValue.class, TimestampedValue.TimestampedValueCoder.class));
      builder.put(Void.class,
          CoderFactories.fromStaticMethods(Void.class, VoidCoder.class));
      builder.put(byte[].class,
          CoderFactories.fromStaticMethods(byte[].class, ByteArrayCoder.class));
      builder.put(IntervalWindow.class,
          CoderFactories.forCoder(
              TypeDescriptor.of(IntervalWindow.class), IntervalWindow.getCoder()));
      commonTypesToCoderFactories = builder.build();
    }

    @Override
    public <T> Coder<T> create(TypeDescriptor<T> typeDescriptor,
        List<? extends Coder<?>> componentCoders) throws CannotProvideCoderException {
      CoderFactory factory = commonTypesToCoderFactories.get(typeDescriptor.getRawType());
      if (factory == null) {
        throw new CannotProvideCoderException(
            String.format("%s is not one of the common types.", typeDescriptor));
      }
      return factory.create(typeDescriptor, componentCoders);
    }
  }

  static {
    // Register the standard coders first so they are chosen as the default
    List<CoderFactory> codersToRegister = new ArrayList<>();
    codersToRegister.add(new CommonTypes());
    // Enumerate all the CoderRegistrars in a deterministic order, adding all coders to register
    Set<CoderFactoryRegistrar> registrars = Sets.newTreeSet(ObjectsClassComparator.INSTANCE);
    registrars.addAll(Lists.newArrayList(
        ServiceLoader.load(CoderFactoryRegistrar.class, ReflectHelpers.findClassLoader())));
    for (CoderFactoryRegistrar registrar : registrars) {
        codersToRegister.addAll(registrar.getCoderFactories());
    }

    REGISTERED_CODER_FACTORIES = ImmutableList.copyOf(codersToRegister);
  }

  /**
   * Creates a CoderRegistry containing registrations for all standard coders part of the core Java
   * Apache Beam SDK and also any registrations provided by
   * {@link CoderFactoryRegistrar coder registrars}.
   *
   * <p>Multiple registrations which can produce a coder for a given type result in a Coder created
   * by the (in order of precedence):
   * <ul>
   *   <li>{@link CoderFactory coder factory} registered programmatically through
   *   {@link CoderRegistry#registerCoderFactory}.</li>
   *   <li>{@link CoderFactory coder factory} for core types found within the Apache Beam Java SDK
   *   being used.</li>
   *   <li>The {@link CoderFactory coder factory} from the {@link CoderFactoryRegistrar}
   *   with the lexicographically smallest {@link Class#getName() class name} being used.</li>
   * </ul>
   */
  public static CoderRegistry createDefault() {
    return new CoderRegistry();
  }

  private CoderRegistry() {
    coderFactories = new LinkedList<>(REGISTERED_CODER_FACTORIES);
  }

  /**
   * Registers {@code coderFactory} as a potential {@link CoderFactory} which can produce
   * {@code Coder} instances.
   *
   * <p>This method prioritizes this {@link CoderFactory} over all prior registered coders.
   *
   * <p>See {@link CoderFactories} for common {@link CoderFactory} patterns.
   */
  public void registerCoderFactory(CoderFactory coderFactory) {
    coderFactories.addFirst(coderFactory);
  }

  /**
   * Registers the provided {@link Coder} for the given class.
   *
   * <p>Note that this is equivalent to {@code registerCoderForType(TypeDescriptor.of(clazz))}. See
   * {@link #registerCoderForType(TypeDescriptor, Coder)} for further details.
   */
  public void registerCoder(Class<?> clazz, Coder<?> coder) {
    registerCoderForType(TypeDescriptor.of(clazz), coder);
  }

  /**
   * Registers the provided {@link Coder} for the given type.
   *
   * <p>Note that this is equivalent to
   * {@code registerCoderFactory(CoderFactories.forCoder(type, coder))}. See
   * {@link #registerCoderFactory} and {@link CoderFactories#forCoder} for further details.
   */
  public void registerCoderForType(TypeDescriptor<?> type, Coder<?> coder) {
    registerCoderFactory(CoderFactories.forCoder(type, coder));
  }

  /**
   * Returns the {@link Coder} to use by default for values of the given class.
   */
  public <T> Coder<T> getDefaultCoder(Class<T> clazz)
      throws CannotProvideCoderException {
    return getDefaultCoder(TypeDescriptor.of(clazz));
  }

  /**
   * Returns the {@link Coder} to use by default for values of the given type, where the given input
   * type uses the given {@link Coder}.
   *
   * @throws CannotProvideCoderException if there is no default Coder.
   */
  public <InputT, OutputT> Coder<OutputT> getDefaultCoder(
      TypeDescriptor<OutputT> typeDescriptor,
      TypeDescriptor<InputT> inputTypeDescriptor,
      Coder<InputT> inputCoder)
      throws CannotProvideCoderException {
    checkArgument(typeDescriptor != null);
    checkArgument(inputTypeDescriptor != null);
    checkArgument(inputCoder != null);
    return getCoderFromTypeDescriptor(
        typeDescriptor, getTypeToCoderBindings(inputTypeDescriptor.getType(), inputCoder));
  }

  /**
   * Returns the {@link Coder} to use on elements produced by this function, given the {@link Coder}
   * used for its input elements.
   */
  public <InputT, OutputT> Coder<OutputT> getDefaultOutputCoder(
      SerializableFunction<InputT, OutputT> fn, Coder<InputT> inputCoder)
      throws CannotProvideCoderException {

    ParameterizedType fnType = (ParameterizedType)
        TypeDescriptor.of(fn.getClass()).getSupertype(SerializableFunction.class).getType();

    return getDefaultCoder(
        fn.getClass(),
        SerializableFunction.class,
        ImmutableMap.of(fnType.getActualTypeArguments()[0], inputCoder),
        SerializableFunction.class.getTypeParameters()[1]);
  }

  /**
   * Returns the {@link Coder} to use for the specified type parameter specialization of the
   * subclass, given {@link Coder Coders} to use for all other type parameters (if any).
   *
   * @throws CannotProvideCoderException if there is no default Coder.
   */
  public <T, OutputT> Coder<OutputT> getDefaultCoder(
      Class<? extends T> subClass,
      Class<T> baseClass,
      Map<Type, ? extends Coder<?>> knownCoders,
      TypeVariable<?> param)
      throws CannotProvideCoderException {

    Map<Type, Coder<?>> inferredCoders = getDefaultCoders(subClass, baseClass, knownCoders);

    @SuppressWarnings("unchecked")
    Coder<OutputT> paramCoderOrNull = (Coder<OutputT>) inferredCoders.get(param);
    if (paramCoderOrNull != null) {
      return paramCoderOrNull;
    } else {
      throw new CannotProvideCoderException(
          "Cannot infer coder for type parameter " + param.getName());
    }
  }

  /**
   * Returns the {@link Coder} to use by default for values of the given type.
   *
   * @throws CannotProvideCoderException if a {@link Coder} cannot be provided
   */
  public <T> Coder<T> getDefaultCoder(TypeDescriptor<T> type) throws CannotProvideCoderException {
    return getCoderFromTypeDescriptor(type, Collections.<Type, Coder<?>>emptyMap());
  }

  /////////////////////////////////////////////////////////////////////////////

  /**
   * Returns a {@code Map} from each of {@code baseClass}'s type parameters to the {@link Coder} to
   * use by default for it, in the context of {@code subClass}'s specialization of
   * {@code baseClass}.
   *
   * <p>If no {@link Coder} can be inferred for a particular type parameter, then that type variable
   * will be absent from the returned {@code Map}.
   *
   * <p>For example, if {@code baseClass} is {@code Map.class}, where {@code Map<K, V>} has type
   * parameters {@code K} and {@code V}, and {@code subClass} extends {@code Map<String, Integer>}
   * then the result will map the type variable {@code K} to a {@code Coder<String>} and the
   * type variable {@code V} to a {@code Coder<Integer>}.
   *
   * <p>The {@code knownCoders} parameter can be used to provide known {@link Coder Coders} for any
   * of the parameters; these will be used to infer the others.
   *
   * <p>Note that inference is attempted for every type variable. For a type
   * {@code MyType<One, Two, Three>} inference will be attempted for all of {@code One},
   * {@code Two}, {@code Three}, even if the requester only wants a {@link Coder} for {@code Two}.
   *
   * <p>For this reason {@code getDefaultCoders} (plural) does not throw an exception if a
   * {@link Coder} for a particular type variable cannot be inferred, but merely omits the entry
   * from the returned {@code Map}. It is the responsibility of the caller (usually
   * {@link #getCoderFromTypeDescriptor} to extract the desired coder or throw a
   * {@link CannotProvideCoderException} when appropriate.
   *
   * @param subClass the concrete type whose specializations are being inferred
   * @param baseClass the base type, a parameterized class
   * @param knownCoders a map corresponding to the set of known {@link Coder Coders} indexed by
   * parameter name
   */
  private <T> Map<Type, Coder<?>> getDefaultCoders(
      Class<? extends T> subClass,
      Class<T> baseClass,
      Map<Type, ? extends Coder<?>> knownCoders) {
    TypeVariable<Class<T>>[] typeParams = baseClass.getTypeParameters();
    Coder<?>[] knownCodersArray = new Coder<?>[typeParams.length];
    for (int i = 0; i < typeParams.length; i++) {
      knownCodersArray[i] = knownCoders.get(typeParams[i]);
    }
    Coder<?>[] resultArray = getDefaultCoders(
      subClass, baseClass, knownCodersArray);
    Map<Type, Coder<?>> result = new HashMap<>();
    for (int i = 0; i < typeParams.length; i++) {
      if (resultArray[i] != null) {
        result.put(typeParams[i], resultArray[i]);
      }
    }
    return result;
  }

  /**
   * Returns an array listing, for each of {@code baseClass}'s type parameters, the {@link Coder} to
   * use by default for it, in the context of {@code subClass}'s specialization of
   * {@code baseClass}.
   *
   * <p>If a {@link Coder} cannot be inferred for a type variable, its slot in the resulting array
   * will be {@code null}.
   *
   * <p>For example, if {@code baseClass} is {@code Map.class}, where {@code Map<K, V>} has type
   * parameters {@code K} and {@code V} in that order, and {@code subClass} extends
   * {@code Map<String, Integer>} then the result will contain a {@code Coder<String>} and a
   * {@code Coder<Integer>}, in that order.
   *
   * <p>The {@code knownCoders} parameter can be used to provide known {@link Coder Coders} for any
   * of the type parameters. These will be used to infer the others. If non-null, the length of this
   * array must match the number of type parameters of {@code baseClass}, and simply be filled with
   * {@code null} values for each type parameters without a known {@link Coder}.
   *
   * <p>Note that inference is attempted for every type variable. For a type
   * {@code MyType<One, Two, Three>} inference will will be attempted for all of {@code One},
   * {@code Two}, {@code Three}, even if the requester only wants a {@link Coder} for {@code Two}.
   *
   * <p>For this reason {@code getDefaultCoders} (plural) does not throw an exception if a
   * {@link Coder} for a particular type variable cannot be inferred. Instead, it results in a
   * {@code null} in the array. It is the responsibility of the caller (usually
   * {@link #getCoderFromTypeDescriptor} to extract the desired coder or throw a
   * {@link CannotProvideCoderException} when appropriate.
   *
   * @param subClass the concrete type whose specializations are being inferred
   * @param baseClass the base type, a parameterized class
   * @param knownCoders an array corresponding to the set of base class type parameters. Each entry
   *        can be either a {@link Coder} (in which case it will be used for inference) or
   *        {@code null} (in which case it will be inferred). May be {@code null} to indicate the
   *        entire set of parameters should be inferred.
   * @throws IllegalArgumentException if baseClass doesn't have type parameters or if the length of
   *         {@code knownCoders} is not equal to the number of type parameters of {@code baseClass}.
   */
  private <T> Coder<?>[] getDefaultCoders(
      Class<? extends T> subClass,
      Class<T> baseClass,
      @Nullable Coder<?>[] knownCoders) {
    Type type = TypeDescriptor.of(subClass).getSupertype(baseClass).getType();
    if (!(type instanceof ParameterizedType)) {
      throw new IllegalArgumentException(type + " is not a ParameterizedType");
    }
    ParameterizedType parameterizedType = (ParameterizedType) type;
    Type[] typeArgs = parameterizedType.getActualTypeArguments();
    if (knownCoders == null) {
      knownCoders = new Coder<?>[typeArgs.length];
    } else if (typeArgs.length != knownCoders.length) {
      throw new IllegalArgumentException(
          String.format("Class %s has %d parameters, but %d coders are requested.",
              baseClass.getCanonicalName(), typeArgs.length, knownCoders.length));
    }

    Map<Type, Coder<?>> context = new HashMap<>();
    for (int i = 0; i < knownCoders.length; i++) {
      if (knownCoders[i] != null) {
        try {
          verifyCompatible(knownCoders[i], typeArgs[i]);
        } catch (IncompatibleCoderException exn) {
          throw new IllegalArgumentException(
              String.format("Provided coders for type arguments of %s contain incompatibilities:"
                  + " Cannot encode elements of type %s with coder %s",
                  baseClass,
                  typeArgs[i], knownCoders[i]),
              exn);
        }
        context.putAll(getTypeToCoderBindings(typeArgs[i], knownCoders[i]));
      }
    }

    Coder<?>[] result = new Coder<?>[typeArgs.length];
    for (int i = 0; i < knownCoders.length; i++) {
      if (knownCoders[i] != null) {
        result[i] = knownCoders[i];
      } else {
        try {
          result[i] = getCoderFromTypeDescriptor(TypeDescriptor.of(typeArgs[i]), context);
        } catch (CannotProvideCoderException exc) {
          result[i] = null;
        }
      }
    }
    return result;
  }


  /**
   * Thrown when a {@link Coder} cannot possibly encode a type, yet has been proposed as a
   * {@link Coder} for that type.
   */
  @VisibleForTesting static class IncompatibleCoderException extends RuntimeException {
    private Coder<?> coder;
    private Type type;

    IncompatibleCoderException(String message, Coder<?> coder, Type type) {
      super(message);
      this.coder = coder;
      this.type = type;
    }

    IncompatibleCoderException(String message, Coder<?> coder, Type type, Throwable cause) {
      super(message, cause);
      this.coder = coder;
      this.type = type;
    }

    public Coder<?> getCoder() {
      return coder;
    }

    public Type getType() {
      return type;
    }
  }

  /**
   * Returns {@code true} if the given {@link Coder} can possibly encode elements
   * of the given type.
   */
  @VisibleForTesting static <T, CoderT extends Coder<T>, CandidateT>
  void verifyCompatible(CoderT coder, Type candidateType) throws IncompatibleCoderException {

    // Various representations of the coder's class
    @SuppressWarnings("unchecked")
    Class<CoderT> coderClass = (Class<CoderT>) coder.getClass();
    TypeDescriptor<CoderT> coderDescriptor = TypeDescriptor.of(coderClass);

    // Various representations of the actual coded type
    @SuppressWarnings("unchecked")
    TypeDescriptor<T> codedDescriptor = CoderUtils.getCodedType(coderDescriptor);
    @SuppressWarnings("unchecked")
    Class<T> codedClass = (Class<T>) codedDescriptor.getRawType();
    Type codedType = codedDescriptor.getType();

    // Various representations of the candidate type
    @SuppressWarnings("unchecked")
    TypeDescriptor<CandidateT> candidateDescriptor =
        (TypeDescriptor<CandidateT>) TypeDescriptor.of(candidateType);
    @SuppressWarnings("unchecked")
    Class<CandidateT> candidateClass = (Class<CandidateT>) candidateDescriptor.getRawType();

    // If coder has type Coder<T> where the actual value of T is lost
    // to erasure, then we cannot rule it out.
    if (candidateType instanceof TypeVariable) {
      return;
    }

    // If the raw types are not compatible, we can certainly rule out
    // coder compatibility
    if (!codedClass.isAssignableFrom(candidateClass)) {
      throw new IncompatibleCoderException(
          String.format("Cannot encode elements of type %s with coder %s because the"
              + " coded type %s is not assignable from %s",
              candidateType, coder, codedClass, candidateType),
          coder, candidateType);
    }
    // we have established that this is a covariant upcast... though
    // coders are invariant, we are just checking one direction
    @SuppressWarnings("unchecked")
    TypeDescriptor<T> candidateOkDescriptor = (TypeDescriptor<T>) candidateDescriptor;

    // If the coded type is a parameterized type where any of the actual
    // type parameters are not compatible, then the whole thing is certainly not
    // compatible.
    if ((codedType instanceof ParameterizedType) && !isNullOrEmpty(coder.getCoderArguments())) {
      ParameterizedType parameterizedSupertype = ((ParameterizedType)
           candidateOkDescriptor.getSupertype(codedClass).getType());
      Type[] typeArguments = parameterizedSupertype.getActualTypeArguments();
      List<? extends Coder<?>> typeArgumentCoders = coder.getCoderArguments();
      if (typeArguments.length < typeArgumentCoders.size()) {
        throw new IncompatibleCoderException(
            String.format("Cannot encode elements of type %s with coder %s:"
                + " the generic supertype %s has %s type parameters, which is less than the"
                + " number of coder arguments %s has (%s).",
                candidateOkDescriptor, coder,
                parameterizedSupertype, typeArguments.length,
                coder, typeArgumentCoders.size()),
            coder, candidateOkDescriptor.getType());
      }
      for (int i = 0; i < typeArgumentCoders.size(); i++) {
        try {
          verifyCompatible(
              typeArgumentCoders.get(i),
              candidateDescriptor.resolveType(typeArguments[i]).getType());
        } catch (IncompatibleCoderException exn) {
          throw new IncompatibleCoderException(
              String.format("Cannot encode elements of type %s with coder %s"
                  + " because some component coder is incompatible",
                  candidateType, coder),
              coder, candidateType, exn);
        }
      }
    }
  }

  private static boolean isNullOrEmpty(Collection<?> c) {
    return c == null || c.size() == 0;
  }

  /**
   * The map of classes to the CoderFactories to use to create their
   * default Coders.
   */
  private LinkedList<CoderFactory> coderFactories;

  /**
   * Returns a {@link Coder} to use by default for values of the given type,
   * in a context where the given types use the given coders.
   *
   * @throws CannotProvideCoderException if a coder cannot be provided
   */
  private <T> Coder<T> getCoderFromTypeDescriptor(
      TypeDescriptor<T> typeDescriptor, Map<Type, Coder<?>> typeCoderBindings)
      throws CannotProvideCoderException {
    Type type = typeDescriptor.getType();
    Coder<?> coder;
    if (typeCoderBindings.containsKey(type)) {
      coder = typeCoderBindings.get(type);
    } else if (type instanceof Class<?>) {
      coder = getCoderFromFactories(typeDescriptor, Collections.<Coder<?>>emptyList());
    } else if (type instanceof ParameterizedType) {
      coder = getCoderFromParameterizedType((ParameterizedType) type, typeCoderBindings);
    } else if (type instanceof TypeVariable) {
      coder = getCoderFromFactories(typeDescriptor, Collections.<Coder<?>>emptyList());
    } else if (type instanceof WildcardType) {
      // No default coder for an unknown generic type.
      throw new CannotProvideCoderException(
          String.format("Cannot provide a coder for type variable %s"
          + " (declared by %s) because the actual type is unknown due to erasure.",
          type,
          ((TypeVariable<?>) type).getGenericDeclaration()),
          ReasonCode.TYPE_ERASURE);
    } else {
      throw new RuntimeException(
          "Internal error: unexpected kind of Type: " + type);
    }

    LOG.debug("Default coder for {}: {}", typeDescriptor, coder);
    @SuppressWarnings("unchecked")
    Coder<T> result = (Coder<T>) coder;
    return result;
  }

  /**
   * Returns a {@link Coder} to use for values of the given
   * parameterized type, in a context where the given types use the
   * given {@link Coder Coders}.
   *
   * @throws CannotProvideCoderException if no coder can be provided
   */
  private Coder<?> getCoderFromParameterizedType(
      ParameterizedType type,
      Map<Type, Coder<?>> typeCoderBindings)
          throws CannotProvideCoderException {

    List<Coder<?>> typeArgumentCoders = new ArrayList<>();
    for (Type typeArgument : type.getActualTypeArguments()) {
      try {
        Coder<?> typeArgumentCoder =
            getCoderFromTypeDescriptor(TypeDescriptor.of(typeArgument), typeCoderBindings);
        typeArgumentCoders.add(typeArgumentCoder);
      } catch (CannotProvideCoderException exc) {
        throw new CannotProvideCoderException(
            String.format("Cannot provide coder for parameterized type %s: %s",
                type,
                exc.getMessage()),
            exc);
      }
    }
    return getCoderFromFactories(TypeDescriptor.of(type), typeArgumentCoders);
  }

  /**
   * Attempts to create a {@link Coder} from any registered {@link CoderFactory} returning
   * the first successfully created instance.
   */
  private Coder<?> getCoderFromFactories(
      TypeDescriptor<?> typeDescriptor, List<Coder<?>> typeArgumentCoders)
      throws CannotProvideCoderException {
    List<CannotProvideCoderException> suppressedExceptions = new ArrayList<>();
    for (CoderFactory coderFactory : coderFactories) {
      try {
        return coderFactory.create(typeDescriptor, typeArgumentCoders);
      } catch (CannotProvideCoderException e) {
        // Add all failures as suppressed exceptions.
        suppressedExceptions.add(e);
      }
    }

    // Build up the error message and list of causes.
    StringBuilder messageBuilder = new StringBuilder()
        .append("Unable to provide a Coder for ").append(typeDescriptor).append(".\n")
        .append("  Building a Coder using a registered CoderFactory failed.\n")
        .append("  See suppressed exceptions for detailed failures.");
    CannotProvideCoderException exceptionOnFailure =
        new CannotProvideCoderException(messageBuilder.toString());
    for (CannotProvideCoderException suppressedException : suppressedExceptions) {
      exceptionOnFailure.addSuppressed(suppressedException);
    }
    throw exceptionOnFailure;
  }

  /**
   * Returns an immutable {@code Map} from each of the type variables
   * embedded in the given type to the corresponding types
   * in the given {@link Coder}.
   */
  private Map<Type, Coder<?>> getTypeToCoderBindings(Type type, Coder<?> coder) {
    checkArgument(type != null);
    checkArgument(coder != null);
    if (type instanceof TypeVariable || type instanceof Class) {
      return ImmutableMap.<Type, Coder<?>>of(type, coder);
    } else if (type instanceof ParameterizedType) {
      return getTypeToCoderBindings((ParameterizedType) type, coder);
    } else {
      return ImmutableMap.of();
    }
  }

  /**
   * Returns an immutable {@code Map} from the type arguments of the parameterized type to their
   * corresponding {@link Coder Coders}, and so on recursively for their type parameters.
   *
   * <p>This method is simply a specialization to break out the most
   * elaborate case of {@link #getTypeToCoderBindings(Type, Coder)}.
   */
  private Map<Type, Coder<?>> getTypeToCoderBindings(ParameterizedType type, Coder<?> coder) {
    List<Type> typeArguments = Arrays.asList(type.getActualTypeArguments());
    List<? extends Coder<?>> coderArguments = coder.getCoderArguments();

    if ((coderArguments == null) || (typeArguments.size() != coderArguments.size())) {
      return ImmutableMap.of();
    } else {
      Map<Type, Coder<?>> typeToCoder = Maps.newHashMap();

      typeToCoder.put(type, coder);

      for (int i = 0; i < typeArguments.size(); i++) {
        Type typeArgument = typeArguments.get(i);
        Coder<?> coderArgument = coderArguments.get(i);
        if (coderArgument != null) {
          typeToCoder.putAll(getTypeToCoderBindings(typeArgument, coderArgument));
        }
      }

      return ImmutableMap.<Type, Coder<?>>builder().putAll(typeToCoder).build();
    }

  }
}
