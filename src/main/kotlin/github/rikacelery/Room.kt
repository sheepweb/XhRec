package github.rikacelery

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class Room(
    val name: String, val id: Long,
    //config
    var quality: String,
    //config
    @Serializable(with = DurationMillisSerializer::class)
    var timeLimit: Duration = Duration.INFINITE,
    @Serializable(with = SizeStrSerializer::class)
//    1000 -> KB
//    1G200M -> 1200000000
//    120000000 -> 120M
    var sizeLimit: Long = 0,
    val lastSeen: String? = null,
    var autoPay: Boolean = false,
)

object DurationMillisSerializer : KSerializer<Duration> {
    override val descriptor = PrimitiveSerialDescriptor("DurationMillis", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Duration) {
        if (value == Duration.INFINITE) {
            encoder.encodeLong(0) // INFINITE
        } else {
            encoder.encodeLong(value.inWholeMilliseconds)
        }
    }

    override fun deserialize(decoder: Decoder): Duration {
        val millis = decoder.decodeLong()
        return if (millis == 0L) Duration.INFINITE else millis.milliseconds
    }
}

object SizeStrSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SizeStr", PrimitiveKind.STRING)

    // 定义单位映射
    // 包含二进制 (1024) 和 十进制 (1000) 以便反序列化时兼容
    // 注意：'B' 和 'Bi' 都代表字节基数，但为了输出统一，我们主要使用带 'i' 的二进制单位作为标准输出
    private val binaryUnits = listOf(
        Triple('T', "Ti", (1L shl 40)), // 1024^4
        Triple('G', "Gi", (1L shl 30)), // 1024^3
        Triple('M', "Mi", (1L shl 20)), // 1024^2
        Triple('K', "Ki", (1L shl 10)), // 1024^1
        Triple('B', "Bi", 1L)    // 1024^0
    )

    // 十进制单位用于兼容解析 (如用户输入 "1G" 而不是 "1Gi")
    private val decimalUnits = listOf(
        'T' to 1_000_000_000_000L,
        'G' to 1_000_000_000L,
        'M' to 1_000_000L,
        'K' to 1_000L,
        'B' to 1L
    )

    // 构建解析用的 Map：支持 "Ti", "Gi", "T", "G" 等所有后缀
    private val parseUnitMap: Map<String, Long> = buildMap {
        // 添加二进制单位 (Ti, Gi...)
        for ((short, longName, value) in binaryUnits) {
            put(longName, value) // "Ti" -> value
            if (short != 'B') {
                put(short.toString(), value) // "T" -> value (兼容写法，视为二进制或近似)
            } else {
                put("B", value) // "B" -> 1
            }
        }
        // 注意：如果用户输入 "1G"，上面已经映射为 1024^3。
        // 如果严格需要区分 "1G"(1000) 和 "1Gi"(1024)，我们需要更复杂的解析逻辑。
        // 通常在实际工程中，不带 'i' 的单位常被混用。
        // 为了严格满足你之前 "1G200M -> 1200000000" (十进制) 的需求，
        // 我们需要在解析时优先匹配长后缀 ("Gi"), 如果只有短后缀 ("G")，
        // 这里存在歧义。

        // 【修正策略】：
        // 为了同时满足你之前的十进制例子和现在的二进制需求：
        // 1. 显式带 'i' 的 (Ti, Gi...) -> 1024 进制
        // 2. 不带 'i' 的 (T, G, M, K, B) -> 1000 进制 (保留你之前的行为)
        // 3. 纯数字 -> Bytes

        clear() // 清空重建

        // 1. 注册二进制 (高优先级，因为后缀更长)
        binaryUnits.forEach { (_, longName, value) ->
            put(longName, value)
        }

        // 2. 注册十进制 (短后缀)
        // 注意：'B' 和 'Bi' 冲突处理。如果输入 "100Bi" 走上面，"100B" 走下面。
        decimalUnits.forEach { (char, value) ->
            val key = char.toString()
            // 只有当该key没有被二进制占用时才放入，或者强制覆盖？
            // "B" 在二进制里也是 1，在十进制里也是 1，没冲突。
            // "K" vs "Ki": 不同。
            put(key, value)
        }
    }

    private val serializeUnits = listOf(
        "Ti" to (1L shl 40),
        "Gi" to (1L shl 30),
        "Mi" to (1L shl 20),
        "Ki" to (1L shl 10),
        "Bi" to 1L
    )

    override fun serialize(encoder: Encoder, value: Long) {
        if (value <= 0) {
            encoder.encodeString("0Bi")
            return
        }

        val result1 = run{
            var remaining = value
            val result = StringBuilder()

            for ((unitName, divisor) in serializeUnits) {
                if (remaining >= divisor) {
                    val count = remaining / divisor
                    result.append(count).append(unitName)
                    remaining %= divisor
                }
            }
            result
        }.toString()
        val result2 = run{
            var remaining = value
            val result = StringBuilder()

            for ((unitName, divisor) in decimalUnits) {
                if (remaining >= divisor) {
                    val count = remaining / divisor
                    result.append(count).append(unitName)
                    remaining %= divisor
                }
            }
            result
        }.toString()

        encoder.encodeString(if (result1.length<result2.length) result1 else result2)
    }

    override fun deserialize(decoder: Decoder): Long {
        val input = decoder.decodeString().trim()
        // 统一转为大写处理，但要注意 "i" 是小写，所以转大写后 "Gi" -> "GI"
        // 我们的 Map 需要适配大小写不敏感，或者输入规范大小写。
        // 为了健壮性，我们在解析时做大小写归一化。

        val normalizedInput = input.uppercase() // "1Gi" -> "1GI", "1G" -> "1G"

        // 重新构建一个全大写的查找表用于解析
        val upperCaseUnitMap = parseUnitMap.mapKeys { it.key.uppercase() }

        // 1. 尝试纯数字
        return input.toLongOrNull() ?: parseComplexSize(normalizedInput, upperCaseUnitMap)
    }

    private fun parseComplexSize(input: String, unitMap: Map<String, Long>): Long {
        var totalBytes = 0L
        var currentNumber = ""

        // 由于输入可能包含 "GI" (来自 Gi) 或 "G"，我们需要贪心匹配最长的单位后缀
        // 但简单的逐字符扫描很难处理双字符单位。
        // 策略：遍历字符串，如果是数字则累积；如果是字母，则尝试匹配单位。

        val keys = unitMap.keys.sortedByDescending { it.length } // 优先匹配长的 "GI" 而不是 "G"

        var i = 0
        while (i < input.length) {
            val char = input[i]

            if (char.isDigit()) {
                currentNumber += char
                i++
            } else if (char.isLetter()) {
                // 尝试从当前位置匹配一个单位
                var matched = false
                for (key in keys) {
                    if (input.startsWith(key, i)) {
                        if (currentNumber.isEmpty()) {
                            throw IllegalArgumentException("Missing number before unit '$key' at pos $i in $input")
                        }
                        val value = currentNumber.toLongOrNull()
                            ?: throw IllegalArgumentException("Invalid number '$currentNumber'")

                        totalBytes += value * unitMap[key]!!
                        currentNumber = ""
                        i += key.length
                        matched = true
                        break
                    }
                }

                if (!matched) {
                    throw IllegalArgumentException("Unknown unit starting at character '${input[i]}' in $input")
                }
            } else if (char.isWhitespace()) {
                i++ // 跳过空格
            } else {
                throw IllegalArgumentException("Invalid character '$char' in $input")
            }
        }

        if (currentNumber.isNotEmpty()) {
            throw IllegalArgumentException("Trailing number without unit: $currentNumber")
        }

        return totalBytes
    }
}