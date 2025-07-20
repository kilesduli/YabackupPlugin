
/* Yet another backup plugin, but support zstd.

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

import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectory
import kotlin.io.path.notExists

class Yabackup : JavaPlugin() {
    override fun onEnable() {
        server.commandMap.register(name, backupCommand)

        setupConfig()

        if (intervalBackupTaskEnabled) {
            logger.info("Interval backup task is enabled.")
            logger.info("First backup will start in ${intervalBackupTaskInitialDelay / (20 * 60)} minute. " +
                    "Interval is ${intervalBackupTaskInterval / (20 * 60)} minutes.")

            server.scheduler.runTaskTimer(this, Runnable {
                logger.info("Running backup task...")
                this.backupWithPolicy(defaultCompressType, "autobackup")
            }, intervalBackupTaskInitialDelay, intervalBackupTaskInterval) // run every second
        }

        // Suppresses warnings triggered by plugin-initiated world saves via CraftServer.
        NMSReflection.disableCheckAutoSave()

    }

    var backupCommand = object: BukkitCommand(
        "backup",
        "Backup worlds and compress(zstd, zip, gz) to archive",
        "/<command> <arg>",
        listOf()
    ) {
        override fun execute(
            sender: CommandSender,
            commandLabel: String,
            args: Array<out String>?
        ): Boolean {
            val type = if (args!!.isNotEmpty()) {
                CompressType.valueOf(args[0].uppercase())
            } else {
                defaultCompressType
            }

            val name = if (sender is Player) {
                sender.name
            } else {
                "console"
            }

            backupWithPolicy(type,name)
            return true
        }

        override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>?): List<String> {
            if (args == null) return emptyList()

            fun filterStartWith(size: Int): (String) -> Boolean = { it.startsWith(args[size-1].lowercase()) }

            return when (val size = args.size) {
                1 -> CompressType.toList().filter (filterStartWith(size))
                else -> emptyList()
            }
        }
    }

    fun saveAllAndThen(task: Runnable) {
        val worlds = sortedWorlds
        val worldFlags = worlds.map { it.isAutoSave }

        logger.info("Saving all worlds and player data...")
        NMSReflection.savePlayers()
        logger.info("Saved all players")

        worlds.forEach { world ->
            // Before Minecraft 1.14, '/save-all' temporarily enabled auto-save,
            // then restored the previous state after saving.
            // In later versions, the command appears to have no effect.
            world.isAutoSave = true
            NMSReflection.saveWorld(world)
            logger.info("Saved world: ${world.name}")
        }

        server.scheduler.runTaskAsynchronously(this, Runnable {
            task.run()
            // restore auto save
            worlds.forEachIndexed { index, world ->
                world.isAutoSave = worldFlags[index]
            }
        })
    }

    fun backupWithPolicy(type: CompressType, extraFileInfo: String) = saveAllAndThen {
        runCatching {
            logger.info("Creating backup archive...")
            val paths = sortedWorlds.map { it.worldFolder.toPath() }
            val filename = "${formatCurrentTime()}--${extraFileInfo}${type.suffix()}"

            archiveThenCompress(backupsDir.resolve(filename), paths, type)
            logger.info("Created backup archive: $filename")
        }.onFailure {
            logger.severe("Failed to create backup: ${it.message}")
        }
        runCatching { postBackupPolicy() }.onFailure {
            logger.severe("Failed to run after backup task: ${it.message}")
        }
    }

    fun postBackupPolicy() {
        val deletedFiles= mutableListOf<String>()
        if (keepLastNBackups > 0) {
            val files = sortedBackupFiles
            val toDelete = if (files.size <= keepLastNBackups) {
                emptyList()
            } else {
                files.subList(0, files.size - keepLastNBackups)
            }
            toDelete.forEach { it.delete() }
            toDelete.map { it.name }.toCollection(deletedFiles)
        }

        if (backupsDirStorageLimit > 0) {
            val files = sortedBackupFiles
            val totalSize = files.sumOf { it.length() }
            if (totalSize > backupsDirStorageLimit) {
                val toDelete = mutableListOf<File>()
                var sizeToFree = totalSize - backupsDirStorageLimit
                for (file in files) {
                    if (sizeToFree <= 0) break
                    toDelete.add(file)
                    sizeToFree -= file.length()
                }
                toDelete.forEach { it.delete() }
                toDelete.map { it.name }.toCollection(deletedFiles)
            }
        }

        if (deletedFiles.isNotEmpty()) {
            logger.info("Deleted old backups: ${deletedFiles.joinToString(", ")}")
            return
        }
    }

    fun setupConfig() {
        config.options().copyDefaults(true)
        config.options().header("Yabackup Configuration\n")
        config.addDefault(Options.BACKUP_BACKUPS_DIR, "./backups")
        config.addDefault(Options.BACKUP_BACKUPS_DIR_STORAGE_LIMIT, 1024) // in MB
        config.addDefault(Options.BACKUP_KEEP_LAST_N_BACKUPS, 10)
        config.addDefault(Options.COMPRESS_DEFAULT_TYPE, "zstd")
        config.addDefault(Options.INTERVAL_BACKUP_TASK_ENABLE, true)
        config.addDefault(Options.INTERVAL_BACKUP_TASK_INITIAL_DELAY_MINUTES, 1)
        config.addDefault(Options.INTERVAL_BACKUP_TASK_INTERVAL_MINUTES, 20)
        saveConfig()
    }

    val sortedWorlds: List<World>
        get() = server.worlds.sortedBy { it.name }
    val sortedBackupFiles: List<File>
        get() = backupsDir
            .toFile()
            .listFiles()
            .filter { it.name.matches(Regex("""^(\d{8}T\d{6}).*"""))}
            .sortedBy { it.name }
    val backupsDir: Path
        get() {
            val path = Paths.get(config.getString(Options.BACKUP_BACKUPS_DIR)!!)
            if (path.notExists()) {
                path.createDirectory()
            }
            return path
        }
    val defaultCompressType: CompressType
        get() = CompressType.valueOf(config.getString(Options.COMPRESS_DEFAULT_TYPE)?.uppercase()!!)

    val intervalBackupTaskEnabled: Boolean
        get() = config.getBoolean(Options.INTERVAL_BACKUP_TASK_ENABLE)
    val intervalBackupTaskInitialDelay: Long
        get() = config.getLong(Options.INTERVAL_BACKUP_TASK_INITIAL_DELAY_MINUTES) * 60 * 20 // in ticks
    val intervalBackupTaskInterval: Long
        get() = config.getLong(Options.INTERVAL_BACKUP_TASK_INTERVAL_MINUTES) * 60 * 20 // in ticks

    val keepLastNBackups: Int
        get() = config.getInt(Options.BACKUP_KEEP_LAST_N_BACKUPS).coerceAtLeast(0)
    val backupsDirStorageLimit: Long
        get() = config.getLong(Options.BACKUP_BACKUPS_DIR_STORAGE_LIMIT).coerceAtLeast(0) * 1024 * 1024 // in bytes
}

object Options {
    const val BACKUP_BACKUPS_DIR = "backup.backups_dir"
    const val BACKUP_KEEP_LAST_N_BACKUPS = "backup.keep_last_n_backups"
    const val BACKUP_BACKUPS_DIR_STORAGE_LIMIT = "backup.backups_dir_storage_limit"
    const val COMPRESS_DEFAULT_TYPE = "compress.default_type"
    const val INTERVAL_BACKUP_TASK_ENABLE = "interval_backup_task.enable"
    const val INTERVAL_BACKUP_TASK_INITIAL_DELAY_MINUTES = "interval_backup_task.initial_delay_minutes"
    const val INTERVAL_BACKUP_TASK_INTERVAL_MINUTES = "interval_backup_task.interval_minutes"
}

fun formatCurrentTime(): String {
    val formater = "yyyyMMdd'T'HHmmss"
    return LocalDateTime.now().format(DateTimeFormatter.ofPattern(formater))
}
