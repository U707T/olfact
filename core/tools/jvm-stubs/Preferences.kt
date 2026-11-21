package androidx.datastore.preferences.core

import androidx.datastore.core.DataStore

abstract class Preferences {
    class Key<T>(val name: String) {
        override fun equals(other: Any?): Boolean = other is Key<*> && name == other.name
        override fun hashCode(): Int = name.hashCode()
    }

    abstract fun asMap(): Map<Key<*>, Any>
    fun toMutablePreferences() = MutablePreferences(asMap().toMutableMap())
}

class MutablePreferences(private val values: MutableMap<Key<*>, Any>) : Preferences() {
    override fun asMap() = values.toMap()
    operator fun <T> set(key: Key<T>, value: T) { values[key] = value as Any }
    fun <T> remove(key: Key<T>) { values.remove(key) }
    fun clear() { values.clear() }
}

fun emptyPreferences(): Preferences = MutablePreferences(mutableMapOf())
fun booleanPreferencesKey(name: String) = Preferences.Key<Boolean>(name)
fun floatPreferencesKey(name: String) = Preferences.Key<Float>(name)
fun intPreferencesKey(name: String) = Preferences.Key<Int>(name)
fun longPreferencesKey(name: String) = Preferences.Key<Long>(name)
fun stringPreferencesKey(name: String) = Preferences.Key<String>(name)
fun stringSetPreferencesKey(name: String) = Preferences.Key<Set<String>>(name)
suspend fun DataStore<Preferences>.edit(transform: suspend (MutablePreferences) -> Unit): Preferences =
    updateData { it.toMutablePreferences().also { next -> transform(next) } }
