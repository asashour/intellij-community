// "Add '@JvmName' annotation" "true"
// WITH_STDLIB
interface Bar<T, U>

fun <caret>Bar<Int, Double>.bar() = this

fun Bar<Int, Bar<Long, Bar<Double, String>>>.bar() = this