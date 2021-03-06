/*
 * Copyright (C) 2020 Slack Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.slack.moshi.interop.gson

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.lang.reflect.Type
import com.google.gson.stream.JsonReader as GsonReader
import com.google.gson.stream.JsonWriter as GsonWriter

/**
 * Connects this [Moshi] instance to a [Gson] instance for interop. This should be called with the
 * final versions of the input instances and then the returned instances should be used.
 *
 * [moshiBuildHook] can be configured optionally to add anything to the builder _after_ the interop
 * factory is added, which is useful for testing or adding the `KotlinJsonAdapterFactory`.
 */
public fun Moshi.interopWith(
  gson: Gson,
  moshiClassChecker: MoshiClassChecker = DefaultMoshiClassChecker,
  moshiBuildHook: Moshi.Builder.() -> Unit = {}
): Pair<Moshi, Gson> {
  val interop = MoshiGsonInterop(this, gson, moshiClassChecker, moshiBuildHook)
  return interop.moshi to interop.gson
}

/** A simple functional interface for indicating when a class should be serialized with Moshi. */
public fun interface MoshiClassChecker {
  /**
   * Returns true if [rawType] should be serialized with Moshi or false if it should be serialized
   * with Gson.
   */
  public fun shouldUseMoshi(rawType: Class<*>): Boolean
}

/**
 * The default [MoshiClassChecker] behavior. This checks a few things when deciding:
 * * Is it a "built in" type like primitives or String? -> Moshi
 * * Is it annotated with [@JsonClass][JsonClass]? -> Moshi
 * * Is it an enum with no [@SerializedName][SerializedName]-annotated members? -> Moshi
 * * Else -> Gson
 */
public object DefaultMoshiClassChecker : MoshiClassChecker {
  override fun shouldUseMoshi(rawType: Class<*>): Boolean {
    return when {
      // Moshi can handle all these natively
      rawType in MOSHI_BUILTIN_TYPES -> true
      // It's a moshi type, let Moshi handle it
      rawType.isAnnotationPresent(JsonClass::class.java) -> true
      rawType.isEnum -> {
        // If it has no Gson @SerializedName annotations, we can use Moshi
        @Suppress("UNCHECKED_CAST")
        val constants: Array<out Enum<*>> = rawType.enumConstants as Array<out Enum<*>>
        for (constant in constants) {
          if (rawType.getField(constant.name).isAnnotationPresent(SerializedName::class.java)) {
            // Return early
            return false
          }
        }
        true
      }
      else -> false
    }
  }
}

private class MoshiGsonInterop(
  seedMoshi: Moshi,
  seedGson: Gson,
  moshiClassChecker: MoshiClassChecker,
  moshiBuildHook: (Moshi.Builder) -> Unit
) {

  val moshi: Moshi = seedMoshi.newBuilder()
    .add(MoshiGsonInteropJsonAdapterFactory(this, moshiClassChecker))
    .apply(moshiBuildHook)
    .build()

  val gson: Gson = seedGson.newBuilder()
    .registerTypeAdapterFactory(MoshiGsonInteropTypeAdapterFactory(this, moshiClassChecker))
    .create()
}

private val MOSHI_BUILTIN_TYPES = setOf(
  Boolean::class.javaPrimitiveType,
  Boolean::class.javaObjectType,
  Byte::class.javaPrimitiveType,
  Byte::class.javaObjectType,
  Char::class.javaPrimitiveType,
  Character::class.javaObjectType,
  Double::class.javaPrimitiveType,
  Double::class.javaObjectType,
  Float::class.javaPrimitiveType,
  Float::class.javaObjectType,
  Int::class.javaPrimitiveType,
  Integer::class.javaObjectType,
  Long::class.javaPrimitiveType,
  Long::class.javaObjectType,
  Short::class.javaPrimitiveType,
  Short::class.javaObjectType,
  Void::class.javaPrimitiveType,
  Void::class.javaObjectType,
  String::class.java,
  Any::class.java
)

/**
 * An interop-ing [JsonAdapter.Factory] that tries to intelligently defer to a `gson` instance for
 * appropriate types.
 */
private class MoshiGsonInteropJsonAdapterFactory(
  private val interop: MoshiGsonInterop,
  private val moshiClassChecker: MoshiClassChecker
) : JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
    if (annotations.isNotEmpty() || type !is Class<*>) return null
    return if (moshiClassChecker.shouldUseMoshi(type)) {
      moshi.nextAdapter<Any>(this, type, annotations)
    } else {
      GsonDelegatingJsonAdapter(interop.gson.getAdapter(type)).nullSafe()
    }
  }
}

internal class GsonDelegatingJsonAdapter<T>(
  private val delegate: TypeAdapter<T>
) : JsonAdapter<T>() {
  override fun fromJson(reader: JsonReader): T? {
    return reader.nextSource().inputStream().reader().use(delegate::fromJson)
  }

  override fun toJson(writer: JsonWriter, value: T?) {
    writer.valueSink().outputStream().writer().use { delegate.toJson(it, value) }
  }
}

/**
 * An interop-ing [TypeAdapterFactory] that tries to intelligently defer to a `moshi` instance for
 * appropriate types.
 */
private class MoshiGsonInteropTypeAdapterFactory(
  private val interop: MoshiGsonInterop,
  private val moshiClassChecker: MoshiClassChecker
) : TypeAdapterFactory {
  override fun <T> create(gson: Gson, typeToken: TypeToken<T>): TypeAdapter<T>? {
    val type = typeToken.type
    if (type !is Class<*>) return null

    @Suppress("UNCHECKED_CAST")
    return if (moshiClassChecker.shouldUseMoshi(type)) {
      MoshiDelegatingTypeAdapter(interop.moshi.adapter<Any>(type)).nullSafe()
    } else {
      gson.getDelegateAdapter(this, typeToken)
    } as TypeAdapter<T>
  }
}

internal class MoshiDelegatingTypeAdapter<T>(
  private val delegate: JsonAdapter<T>
) : TypeAdapter<T>() {
  override fun write(writer: GsonWriter, value: T?) {
    val serializedValue = delegate.toJson(value)
    writer.jsonValue(serializedValue)
  }

  override fun read(reader: GsonReader): T? {
    val jsonValue = JsonParser.parseReader(reader).toJsonValue()
    return delegate.fromJsonValue(jsonValue)
  }
}

/** Converts a [JsonElement] to a simple object for use with [JsonAdapter.fromJsonValue]. */
private fun JsonElement.toJsonValue(): Any? {
  return when (this) {
    is JsonArray -> {
      map { it.toJsonValue() }
    }
    is JsonObject -> {
      entrySet().associate { (key, elementValue) ->
        key to elementValue.toJsonValue()
      }
    }
    is JsonPrimitive -> {
      when {
        isBoolean -> asBoolean
        isNumber -> asNumber
        isString -> asString
        else -> error("Unknown type: $this")
      }
    }
    is JsonNull -> null
    else -> error("Not possible")
  }
}
