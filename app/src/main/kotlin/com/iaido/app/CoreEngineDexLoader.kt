package com.iaido.app

import android.content.Context
import dalvik.system.DexClassLoader

/** Loads a staged engine class, rolling back once when the new artifact cannot load. */
class CoreEngineDexLoader(
    private val context: Context,
    private val store: CoreEngineUpdateStore,
) {
    private var cachedArtifactSignature: String? = null
    private var cachedLoader: ClassLoader? = null

    fun load(className: String): Class<*>? {
        val current = store.currentArtifact() ?: return null
        return tryLoad(current, className) ?: run {
            if (!store.rollback()) return null
            clearCache()
            store.currentArtifact()?.let { tryLoad(it, className) }
        }
    }

    private fun tryLoad(artifact: java.io.File, className: String): Class<*>? = runCatching {
        loaderFor(artifact).loadClass(className)
    }.getOrNull()

    private fun loaderFor(artifact: java.io.File): ClassLoader {
        val signature = "${artifact.absolutePath}:${artifact.length()}:${artifact.lastModified()}"
        if (signature != cachedArtifactSignature) {
            cachedLoader = UpdateFirstDexClassLoader(
                artifact.absolutePath,
                context.codeCacheDir.absolutePath,
                null,
                context.classLoader,
            )
            cachedArtifactSignature = signature
        }
        return cachedLoader!!
    }

    private fun clearCache() {
        cachedArtifactSignature = null
        cachedLoader = null
    }

    private class UpdateFirstDexClassLoader(
        dexPath: String,
        optimizedDirectory: String,
        librarySearchPath: String?,
        parent: ClassLoader,
    ) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith(DYNAMIC_PACKAGE)) {
                findLoadedClass(name)?.let { return it }
                runCatching { findClass(name) }.getOrNull()?.let { loaded ->
                    if (resolve) resolveClass(loaded)
                    return loaded
                }
            }
            return super.loadClass(name, resolve)
        }
    }

    private companion object {
        const val DYNAMIC_PACKAGE = "com.iaido.dynamic."
    }
}
