package android.net

/** Compile-time URI shape for app data/API JVM tests; no Android grant behavior is simulated. */
class Uri private constructor(private val value: String) {
    override fun toString(): String = value
    companion object { fun parse(value: String) = Uri(value) }
}
