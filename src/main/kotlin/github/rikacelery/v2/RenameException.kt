package github.rikacelery.v2

class RenameException(val newName: String):Throwable("Rename to $newName") {

}
