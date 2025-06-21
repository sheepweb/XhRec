package github.rikacelery

sealed interface Event {
    fun url():String
    class LiveSegmentInit(val url: String, room: Room) : Event {
        override fun url(): String {
            return url
        }
    }
    class LiveSegmentData(val url: String, val initUrl: String, room: Room) : Event {
        override fun url(): String {
            return url
        }
    }
}