
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
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists

class Yabackup : JavaPlugin() {
    override fun onEnable() {
        server.pluginManager.addPermission(backupPermission)
        backupCommand.permission = backupPermission.children.keys.firstOrNull { it == "yabackup.backup" }!!
        server.commandMap.register(name, backupCommand)
        server.pluginManager.registerEvents(backupEvent, this)

        setupConfig()

        if (intervalBackupTaskEnabled) {
            logger.info("Interval backup task is enabled.")
            logger.info(
                "First backup will start in ${intervalBackupTaskInitialDelay / (20 * 60)} minute. " +
                "Interval is ${intervalBackupTaskInterval / (20 * 60)} minutes."
            )
            logger.info("Skip backup if no players online: $intervalBackupTaskSkipIfNoPlayers")

            server.scheduler.runTaskTimer(this, Runnable {
                if (intervalBackupTaskSkipIfNoPlayers && server.onlinePlayers.isEmpty()) {
                    return@Runnable
                }
                logger.info("Running interval backup task...")
                this.backupWithPolicy(defaultCompressType, "autobackup")
            }, intervalBackupTaskInitialDelay, intervalBackupTaskInterval) // run every second
        }

        // Suppresses warnings triggered by plugin-initiated world saves via CraftServer.
        NMSReflection.disableCheckAutoSave()

        runCatching {
            CompressType.zstdLevel = config.getInt(Options.COMPRESS_ZSTD_LEVEL)
            CompressType.zipLevel = config.getInt(Options.COMPRESS_ZIP_LEVEL)
            logger.info("Zstd compression level: ${CompressType.zstdLevel}")
            logger.info("Zip compression level: ${CompressType.zipLevel}")
        }.onFailure {
            logger.severe("Failed to set compression level: ${it.message}")
        }
    }

    val backupPermission = Permission(
        "yabackup",
        "Allows player to run backup command",
        PermissionDefault.OP,
        mapOf("yabackup.backup" to true)
    )

    var backupCommand = object : BukkitCommand(
        "backup",
        "Backup worlds and compress(zstd, zip) to archive",
        "/<command> [zstd|zip]",
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

            backupWithPolicy(type, name) { filename ->
                if (sender is Player) {
                    sender.sendMessage("§a[Yabackup] Backup created: $filename")
                }
            }

            return true
        }

        override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>?): List<String> {
            if (args == null) return emptyList()

            fun filterStartWith(size: Int): (String) -> Boolean = { it.startsWith(args[size - 1].lowercase()) }

            return when (val size = args.size) {
                1 -> CompressType.toList().filter(filterStartWith(size))
                else -> emptyList()
            }
        }
    }

    val backupEvent = object : Listener {
        @EventHandler
        fun onPlayerQuit(event: PlayerQuitEvent) {
            if (intervalBackupTaskEnabled && intervalBackupTaskSkipIfNoPlayers && server.onlinePlayers.size == 1) {
                logger.info("Last player ${event.player.name} quit, triggering one-time backup because interval task and skipIfNoPlayers are enabled")
                backupWithPolicy(defaultCompressType, "lastplayer-${event.player.name}-quit")
            } else if (backupOnPlayerQuit) {
                logger.info("Player ${event.player.name} quit, running backup task...")
                backupWithPolicy(defaultCompressType, "${event.player.name}-quit")
            }
        }

        @EventHandler
        fun onPlayerJoin(event: PlayerJoinEvent) {
            if (backupOnPlayerJoin) {
                logger.info("Player ${event.player.name} joined, running backup task...")
                backupWithPolicy(defaultCompressType, "${event.player.name}-join")
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

    fun backupWithPolicy(type: CompressType, extraFileInfo: String, afterBackup: ((String) -> Unit)? = null) = saveAllAndThen {
        runCatching {
            logger.info("Creating backup archive...")
            val paths = sortedWorlds.map { it.worldFolder.toPath() }
            val filename = "${formatCurrentTime()}--${extraFileInfo}${type.suffix}"

            archiveThenCompress(backupsDir.resolve(filename), paths, type)
            logger.info("Created backup archive: $filename")
            afterBackup?.invoke(filename)
        }.onFailure {
            logger.severe("Failed to create backup: ${it.message}")
        }
        runCatching { postBackupPolicy() }.onFailure {
            logger.severe("Failed to run after backup task: ${it.message}")
        }
    }

    fun postBackupPolicy() {
        val deletedFiles = mutableListOf<String>()
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
        }

        val currentSize = sortedBackupFiles.sumOf { it.length() } / 1024 / 1024
        if (backupsDirStorageLimit > 0) {
            val backupsDirStorageLimitMB = backupsDirStorageLimit / 1024 / 1024
            logger.info("Currently using ${currentSize}MB out of ${backupsDirStorageLimitMB}MB.")
        } else {
            logger.info("Currently using ${currentSize}MB of backups directory, storage limit is disabled.")
        }
    }

    fun setupConfig() {
        val header = """
            Yabackup configuration file.

            Below is an explanation of the some options:

            * backup.backups_dir: Path to store backups. Supports both absolute and relative paths.
            * backup.keep_last_n_backups: Set to 0 to kepp all.
            * backup.backups_dir_storage_limit: Set to 0 to disable the storage limit.
            * backup.on_player_join: on join backup.
            * backup.on_player_quit: on quit backup.
            * compress.default_type: Supported values: "zstd", "zip".
            * compress.zstd_level: Valid range: 1-22, Higher means better compression, slower speed.
            * compress.zip_level: Valid range: 0-9. same above zstd.
            * interval_backup_task.initial_delay_minutes: Delay after server startup before first interval backup.
            * interval_backup_task.interval_minutes: Time between each interval backup.
            * interval_backup_task.skip_if_no_players: interval backups will be skipped when no players are online.

            Note:
            1. When both keep_last_n_backups and backups_dir_storage_limit are set,
            the plugin tries to keep the latest N backups within the size limit.
            If the total size still exceeds the limit, older backups will be deleted anyway.
            2. When the last player quits, a backup is still created
            regardless of the value of on_player_quit. This ensures that a backup
            is still created when the last player leaves, even if it happens between
            interval backups.
        """.trimIndent()

        config.options().copyDefaults(true)
        config.options().header(header)
        config.addDefault(Options.BACKUP_BACKUPS_DIR, "./backups")
        config.addDefault(Options.BACKUP_KEEP_LAST_N_BACKUPS, 10)
        config.addDefault(Options.BACKUP_BACKUPS_DIR_STORAGE_LIMIT, 1024) // in MB
        config.addDefault(Options.BACKUP_ON_PLAYER_JOIN, false)
        config.addDefault(Options.BACKUP_ON_PLAYER_QUIT, true)
        config.addDefault(Options.COMPRESS_DEFAULT_TYPE, "zstd")
        config.addDefault(Options.COMPRESS_ZSTD_LEVEL, 10)
        config.addDefault(Options.COMPRESS_ZIP_LEVEL, 6)
        config.addDefault(Options.INTERVAL_BACKUP_TASK_ENABLE, true)
        config.addDefault(Options.INTERVAL_BACKUP_TASK_INITIAL_DELAY_MINUTES, 1)
        config.addDefault(Options.INTERVAL_BACKUP_TASK_INTERVAL_MINUTES, 20)
        config.addDefault(Options.INTERVAL_BACKUP_TASK_SKIP_IF_NO_PLAYERS, true)
        saveConfig()
    }

    val sortedWorlds: List<World>
        get() = server.worlds.sortedBy { it.name }
    val sortedBackupFiles: List<File>
        get() = backupsDir
            .toFile()
            .listFiles()
            .filter { it.name.matches(Regex("""^(\d{8}T\d{6}).*""")) }
            .sortedBy { it.name }
    val backupsDir: Path
        get() {
            val path = Paths.get(config.getString(Options.BACKUP_BACKUPS_DIR)!!)
            if (path.notExists()) {
                path.createDirectories()
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
    val intervalBackupTaskSkipIfNoPlayers: Boolean
        get() = config.getBoolean(Options.INTERVAL_BACKUP_TASK_SKIP_IF_NO_PLAYERS)

    val keepLastNBackups: Int
        get() = config.getInt(Options.BACKUP_KEEP_LAST_N_BACKUPS).coerceAtLeast(0)
    val backupsDirStorageLimit: Long
        get() = config.getLong(Options.BACKUP_BACKUPS_DIR_STORAGE_LIMIT).coerceAtLeast(0) * 1024 * 1024 // in bytes
    val backupOnPlayerJoin: Boolean
        get() = config.getBoolean(Options.BACKUP_ON_PLAYER_JOIN)
    val backupOnPlayerQuit: Boolean
        get() = config.getBoolean(Options.BACKUP_ON_PLAYER_QUIT)
}

object Options {
    const val BACKUP_BACKUPS_DIR = "backup.backups_dir"
    const val BACKUP_KEEP_LAST_N_BACKUPS = "backup.keep_last_n_backups"
    const val BACKUP_BACKUPS_DIR_STORAGE_LIMIT = "backup.backups_dir_storage_limit"
    const val BACKUP_ON_PLAYER_JOIN = "backup.on_player_join"
    const val BACKUP_ON_PLAYER_QUIT = "backup.on_player_quit"
    const val COMPRESS_DEFAULT_TYPE = "compress.default_type"
    const val COMPRESS_ZSTD_LEVEL = "compress.zstd_level"
    const val COMPRESS_ZIP_LEVEL = "compress.zip_level"
    const val INTERVAL_BACKUP_TASK_ENABLE = "interval_backup_task.enable"
    const val INTERVAL_BACKUP_TASK_INITIAL_DELAY_MINUTES = "interval_backup_task.initial_delay_minutes"
    const val INTERVAL_BACKUP_TASK_INTERVAL_MINUTES = "interval_backup_task.interval_minutes"
    const val INTERVAL_BACKUP_TASK_SKIP_IF_NO_PLAYERS = "interval_backup_task.skip_if_no_players"
}

fun formatCurrentTime(): String {
    val formater = "yyyyMMdd'T'HHmmss"
    return LocalDateTime.now().format(DateTimeFormatter.ofPattern(formater))
}
