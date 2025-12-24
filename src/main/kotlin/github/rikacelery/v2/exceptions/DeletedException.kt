package github.rikacelery.v2.exceptions

class DeletedException(val name: String):Throwable("model($name) already deleted")
