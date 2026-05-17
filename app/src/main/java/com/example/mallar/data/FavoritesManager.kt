package com.example.mallar.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages favorite stores using SharedPreferences.
 * Exposes a reactive StateFlow so Compose UI recomposes on changes.
 */
object FavoritesManager {

    private const val PREFS_NAME = "mallar_favorites"
    private const val KEY_FAVORITES = "favorite_brands"

    private lateinit var prefs: SharedPreferences

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _favorites.value = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
    }

    fun isFavorite(brand: String): Boolean = _favorites.value.contains(brand)

    fun toggleFavorite(brand: String): Boolean {
        val current = _favorites.value.toMutableSet()
        val added = if (current.contains(brand)) {
            current.remove(brand)
            false
        } else {
            current.add(brand)
            true
        }
        _favorites.value = current.toSet()
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
        return added
    }

    fun addFavorite(brand: String) {
        val current = _favorites.value.toMutableSet()
        current.add(brand)
        _favorites.value = current.toSet()
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
    }

    fun removeFavorite(brand: String) {
        val current = _favorites.value.toMutableSet()
        current.remove(brand)
        _favorites.value = current.toSet()
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
    }
}
