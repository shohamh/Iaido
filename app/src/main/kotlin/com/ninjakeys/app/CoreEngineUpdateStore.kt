package com.ninjakeys.app

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Stores only already-verified engine artifacts and keeps one rollback slot. */
class CoreEngineUpdateStore(private val directory: File) {
    private val current = File(directory, CURRENT_NAME)
    private val previous = File(directory, PREVIOUS_NAME)
    private val currentVersionFile = File(directory, CURRENT_VERSION_NAME)
    private val previousVersionFile = File(directory, PREVIOUS_VERSION_NAME)

    fun currentArtifact(): File? = current.takeIf(File::isFile)

    fun previousArtifact(): File? = previous.takeIf(File::isFile)

    fun currentVersion(): String? = currentVersionFile.readVersion()

    fun previousVersion(): String? = previousVersionFile.readVersion()

    fun installVerifiedArtifact(version: String, bytes: ByteArray): File {
        directory.mkdirs()
        val incoming = File(directory, "$CURRENT_NAME.incoming")
        incoming.writeBytes(bytes)
        if (current.isFile) {
            val previousIncoming = File(directory, "$PREVIOUS_NAME.incoming")
            current.copyTo(previousIncoming, overwrite = true)
            moveReplacing(previousIncoming, previous)
            if (currentVersionFile.isFile) {
                currentVersionFile.copyTo(previousVersionFile, overwrite = true)
            }
        }
        moveReplacing(incoming, current)
        writeVersion(currentVersionFile, version)
        return current
    }

    fun rollback(): Boolean {
        if (!previous.isFile) return false
        val rollbackIncoming = File(directory, "$CURRENT_NAME.rollback")
        previous.copyTo(rollbackIncoming, overwrite = true)
        moveReplacing(rollbackIncoming, current)
        if (previousVersionFile.isFile) {
            previousVersionFile.copyTo(currentVersionFile, overwrite = true)
        }
        previous.delete()
        previousVersionFile.delete()
        return true
    }

    private fun writeVersion(target: File, version: String) {
        val incoming = File(target.parentFile, "${target.name}.incoming")
        incoming.writeText(version)
        moveReplacing(incoming, target)
    }

    private fun File.readVersion(): String? = takeIf(File::isFile)?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }

    private companion object {
        const val CURRENT_NAME = "core-engine-current.jar"
        const val PREVIOUS_NAME = "core-engine-previous.jar"
        const val CURRENT_VERSION_NAME = "core-engine-current.version"
        const val PREVIOUS_VERSION_NAME = "core-engine-previous.version"
    }
}
