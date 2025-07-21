
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
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.Deflater

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

    fun createArchiveOutputStream(os: OutputStream): ArchiveOutputStream<out ArchiveEntry> {
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

    fun createCompressorOutputStream(os: OutputStream): CompressorOutputStream<*> {
        return when (this) {
            ZSTD -> ZstdCompressorOutputStream(os, zstdLevel)
            ZIP -> throw Exception("This type does not require secondary compression: $this")
        }
    }

    fun convertFilePathToOutputStream(path: Path): BufferedOutputStream {
        return when (this) {
            CompressType.ZSTD -> this.createCompressorOutputStream(FileOutputStream(path.toFile())).buffered()
            CompressType.ZIP -> FileOutputStream(path.toFile()).buffered()
        }
    }
}

fun archiveThenCompress(dest: Path, paths: List<Path>, type: CompressType) {
    type.createArchiveOutputStream(type.convertFilePathToOutputStream(dest))
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
        }
}
