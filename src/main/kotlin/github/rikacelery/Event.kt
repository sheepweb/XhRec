package github.rikacelery

sealed interface Event {
    fun url():String
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
}