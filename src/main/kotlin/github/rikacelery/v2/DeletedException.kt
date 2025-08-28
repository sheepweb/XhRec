package github.rikacelery.v2

class DeletedException(val name: String):Throwable("model($name) already deleted") {

}
