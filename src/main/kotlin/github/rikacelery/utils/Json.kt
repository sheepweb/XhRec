@file:Suppress("Unused", "FunctionName")
package github.rikacelery.utils

import io.github.nomisrev.JsonPath
import io.github.nomisrev.path
import io.github.nomisrev.select
import io.github.nomisrev.string
import kotlinx.serialization.json.*

fun JsonElement.asString(): String {
    return this.jsonPrimitive.content
}
fun JsonElement.asInt(): Int {
    return this.jsonPrimitive.int
}
fun JsonElement.asLong(): Long {
    return this.jsonPrimitive.long
}
fun JsonElement.asDouble(): Double {
    return this.jsonPrimitive.double
}
fun JsonElement.asBoolean(): Boolean {
    return this.jsonPrimitive.boolean
}
fun JsonElement.Int(name: String): Int {
    if (this.jsonObject.containsKey(name)) {
        return this.jsonObject[name]!!.jsonPrimitive.int
    }else{
        throw Exception("$name not found")
    }
}
fun JsonElement.Long(name: String): Long {
    if (this.jsonObject.containsKey(name)) {
        return this.jsonObject[name]!!.jsonPrimitive.long
    }else{
        throw Exception("$name not found")
    }
}
fun JsonElement.Double(name: String): Double {
    if (this.jsonObject.containsKey(name)) {
        return this.jsonObject[name]!!.jsonPrimitive.double
    }else{
        throw Exception("$name not found")
    }
}
fun JsonElement.Boolean(name: String): Boolean {
    if (this.jsonObject.containsKey(name)) {
        return this.jsonObject[name]!!.jsonPrimitive.boolean
    }else{
        throw Exception("$name not found")
    }
}
fun JsonElement.String(name: String): String {
    if (this.jsonObject.containsKey(name)) {
        return this.jsonObject[name]!!.jsonPrimitive.content
    }else{
        throw Exception("$name not found")
    }
}
fun JsonElement.Json(name: String): JsonElement {
    if (this.jsonObject.containsKey(name)) {
        return this.jsonObject[name]!!
    }else{
        throw Exception("$name not found")
    }
}
fun JsonElement.JsonArray(name: String): JsonArray {
    if (this.jsonObject.containsKey(name)) {
        return this.jsonObject[name]!!.jsonArray
    }else{
        throw Exception("$name not found")
    }
}
fun JsonElement.JsonObject(name: String): JsonObject {
    if (this.jsonObject.containsKey(name)) {
        return this.jsonObject[name]!!.jsonObject
    }else{
        throw Exception("$name not found")
    }
}
fun JsonElement.Path(jsonPath: String): List<JsonElement> {
    return JsonPath.path(jsonPath).getAll(this)
}
fun JsonElement.PathFirst(jsonPath: String): JsonElement {
    return JsonPath.path(jsonPath).getAll(this).first()
}
fun JsonElement.PathSingle(jsonPath: String): JsonElement {
    return JsonPath.path(jsonPath).getAll(this).single()
}