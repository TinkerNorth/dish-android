// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.testing

private const val APP_PACKAGE_PREFIX = "com.tinkernorth.dish."

// Defines the app's own classes (main and test) again from their class files and leaves every
// other class to its parent. MockK's inline mocking rewrites a mocked class for the rest of the
// JVM, and every method of the rewritten class then allocates on every call, on a real instance
// as much as on a mock; a class defined here was never rewritten.
private class FreshAppClassLoader(
    private val appClasses: ClassLoader,
) : ClassLoader(appClasses) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> =
        synchronized(this) {
            val loaded = findLoadedClass(name) ?: defineOrDelegate(name)
            if (resolve) resolveClass(loaded)
            loaded
        }

    private fun defineOrDelegate(name: String): Class<*> {
        if (!name.startsWith(APP_PACKAGE_PREFIX)) return appClasses.loadClass(name)
        val resource = name.replace('.', '/') + ".class"
        val bytes = appClasses.getResourceAsStream(resource)?.use { it.readBytes() } ?: return appClasses.loadClass(name)
        return defineClass(name, bytes, 0, bytes.size)
    }
}

/**
 * A new instance of [type], made by its no-argument constructor in a class loader of its own that
 * defines every app class it reaches afresh, so an allocation test measures the source's code
 * rather than what an earlier test class's MockK mocking left in the JVM. Reach it only through
 * JDK interfaces (Runnable, LongSupplier): the app types it names are not the caller's.
 */
fun freshAppInstanceOf(type: Class<*>): Any {
    val appClasses = checkNotNull(type.classLoader) { "${type.name} is a JDK class, not an app class" }
    val loader = FreshAppClassLoader(appClasses)
    val fresh = loader.loadClass(type.name)
    val constructor = fresh.getDeclaredConstructor()
    constructor.isAccessible = true
    return constructor.newInstance()
}
