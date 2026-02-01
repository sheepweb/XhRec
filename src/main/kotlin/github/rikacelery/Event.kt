package github.rikacelery

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

    /**
     * 文件切分标记事件
     * 当达到录制时间限制时发送此事件，通知消费者完成当前文件并开始新文件
     */
    object FileSplit : Event {
        override fun url(): String {
            return ""
        }
    }
}