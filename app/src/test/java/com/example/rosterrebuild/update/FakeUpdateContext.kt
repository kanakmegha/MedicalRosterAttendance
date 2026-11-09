package com.example.rosterrebuild.update

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy

/**
 * Test-only in-memory Context that backs SharedPreferences with proxies,
 * so AppUpdateManager / UpdateConfigStore can be exercised on the JVM
 * (unit tests run with `unitTests.isReturnDefaultValues = true`).
 */
class FakeUpdateContext : ContextWrapper(null) {

    val values = HashMap<String, Any>()

    private val editor: SharedPreferences.Editor by lazy {
        Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    values[args[0] as String] = args[1] as String
                    editor
                }
                "putLong" -> {
                    values[args[0] as String] = args[1] as Long
                    editor
                }
                "remove" -> {
                    values.remove(args[0] as String)
                    editor
                }
                "clear" -> {
                    values.clear()
                    editor
                }
                "commit" -> true
                else -> null // apply() returns void; Proxy translates null fine for void.
            }
        } as SharedPreferences.Editor
    }

    private val prefs: SharedPreferences by lazy {
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> (values[args[0] as String] ?: args[1])
                "getLong" -> (values[args[0] as String] ?: args[1])
                "getBoolean" -> (values[args[0] as String] ?: args[1])
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs

    /** Simulates devices without a download service (returns null for every system service). */
    override fun getSystemService(name: String): Any? = null
}
