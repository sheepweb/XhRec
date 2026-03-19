package github.rikacelery

import github.rikacelery.utils.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

sealed interface Event {
    fun url(): String
    class LiveSegmentInit(val url: String) : Event {
        override fun url(): String {
            return url
        }
    }

    class LiveSegmentData(val url: String) : Event {
        override fun url(): String {
            return url
        }
    }

    class CmdFinish() : Event {
        override fun url(): String {
            return ""
        }
    }

    class WSEvent(val data: JsonObject) : Event {

        override fun url(): String {
            return ""
        }

        override fun toString(): String {
            val channel = data.PathSingleOrNull("push.channel")?.asString()
            val type = data.PathSingleOrNull("push.pub.data.message.type")?.asString()
            if (channel?.startsWith("newChatMessage") == true) {
                val detail = data.PathSingle("push.pub.data.message.details.detail")
                when (type) {
                    "lovense" -> {
                        return "WSEvent(type=$type,power=${detail.String("power")},time=${detail.Int("time")})"
                    }

                    "tip" -> {
                        return "WSEvent(type=$type,${detail.String("source")},${detail.String("body")},${detail.jsonObject["tipData"]?.toString() ?: ""})"
                    }

                    "text" -> {
                        return "WSEvent(type=$type,${detail.String("body")})"
                    }
                }
            }
            return "WSEvent()"
        }
    }
}