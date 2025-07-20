
/* Compress subroutines for Yabackup.

Copyright (C) 2025 dulikilez <duli4868@gmail.com>

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program. If not, see <https://www.gnu.org/licenses/>.  */


package io.github.kilesduli.yabackup

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.CompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.apache.commons.io.IOUtils
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import org.bukkit.Bukkit.*
import java.io.BufferedOutputStream
import java.util.zip.Deflater
import kotlin.io.path.notExists

enum class CompressType {
    ZSTD,
    ZIP;

    companion object {
        fun toList(): List<String> {
            return CompressType.entries.map { it.name.lowercase() }
        }

        var zstdLevel: Int = 10 // This is a fallback value
            set(value) {
                if (value !in 1..22) {
                    throw IllegalArgumentException("Zstd compression level must be between 1 and 22, inclusive.")
                }
                field = value
            }
        var zipLevel: Int = Deflater.DEFAULT_COMPRESSION // This is a fallback value
            set(value) {
                if (value !in Deflater.BEST_SPEED..Deflater.BEST_COMPRESSION) {
                    throw IllegalArgumentException("Zip compression level must be between ${Deflater.BEST_SPEED} and ${Deflater.BEST_COMPRESSION}, inclusive.")
                }
                field = value
            }
    }

    val suffix: String
        get() = when (this) {
            ZSTD -> ".tar.zst"
            ZIP -> ".zip"
        }

    fun newArchiveEntry(path: Path, name: String): ArchiveEntry {
        return when (this) {
            ZSTD -> TarArchiveEntry(path, name)
            ZIP -> ZipArchiveEntry(path, name)
        }
    }

    fun createArchiveOutputStream(os: FileOutputStream): ArchiveOutputStream<out ArchiveEntry> {
        return when (this) {
            ZSTD -> TarArchiveOutputStream(os).apply {
                setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            }
            ZIP -> ZipArchiveOutputStream(os).apply {
                setMethod(ZipArchiveOutputStream.DEFLATED)
                setLevel(zipLevel)
            }
        }
    }

    fun createCompressorInputStream(os: BufferedOutputStream): CompressorOutputStream<*> {
        return when (this) {
            ZSTD -> ZstdCompressorOutputStream(os, zstdLevel)
            ZIP -> throw Exception("This type does not require secondary compression: $this")
        }
    }
}

fun archiveThenCompress(dest: Path, paths: List<Path>, type: CompressType){
    val middleFile = Paths.get("/tmp/yabackup.tar")
    val destFile = when (type) {
        CompressType.ZSTD -> FileOutputStream(middleFile.toFile())
        CompressType.ZIP -> FileOutputStream(dest.toFile())
    }

    type.createArchiveOutputStream(destFile)
        .use { out ->
            paths.forEach { path ->
                Files.walk(path).forEach {
                    (out as ArchiveOutputStream<ArchiveEntry>)
                        .putArchiveEntry(type.newArchiveEntry(it, it.relativeTo(path.parent).toString()))
                    if (it.isRegularFile()) {
                        FileInputStream(it.toFile()).use { input ->
                            IOUtils.copy(input, out)
                        }
                    }
                    out.closeArchiveEntry()
                }
            }
            out.finish()
        }

    compress(dest, middleFile, type)
    middleFile.deleteIfExists()
}

private fun compress(dest: Path, middleFile: Path, type: CompressType) {
    runCatching {
        if (middleFile.notExists()) return
        val inputstream = FileInputStream(middleFile.toFile())
        val outputstream = FileOutputStream(dest.toFile()).buffered()
        type.createCompressorInputStream(outputstream).use {
            IOUtils.copy(inputstream, it)
            it.close()
            inputstream.close()
        }
    }.onFailure { e ->
        getServer().logger.severe(e.message)
    }
}
