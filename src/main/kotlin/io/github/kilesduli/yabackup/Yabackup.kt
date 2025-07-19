package io.github.kilesduli.yabackup

import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
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
                this.backupWorlds(defaultCompressType, "autobackup")
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

            backupWorlds(type,name)
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

    fun withNoAutoSaveThenSaveAllAsync(task: Runnable) {
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

    fun backupWorlds(type: CompressType, extraFileInfo: String) = withNoAutoSaveThenSaveAllAsync {
        runCatching {
            logger.info("Creating backup archive...")
            val paths = sortedWorlds.map { it.worldFolder.toPath() }
            val filename = "${formatCurrentTime()}--${extraFileInfo}${type.suffix()}"

            archiveThenCompress(backupsDir.resolve(filename), paths, type)
            logger.info("Created backup archive: $filename")
        }.onFailure { e ->
            logger.severe("Failed to create backup: ${e.message}")
        }
    }

    fun setupConfig() {
        config.options().copyDefaults(true)
        config.options().header("Yabackup Configuration\n")
        config.addDefault(Options.BACKUP_BACKUPS_DIR, "./backups")
        config.addDefault(Options.COMPRESS_TYPE, "zstd")
        config.addDefault(Options.INTERVAL_BACKUP_TASK_ENABLE, true)
        config.addDefault(Options.INTERVAL_BACKUP_TASK_INITIAL_DELAY_MINUTES, 1)
        config.addDefault(Options.INTERVAL_BACKUP_TASK_INTERVAL_MINUTES, 20)
        saveConfig()
    }


    val sortedWorlds: List<World>
        get() = server.worlds.sortedBy { it.name }
    val backupsDir: Path
        get() {
            val path = Paths.get(config.getString(Options.BACKUP_BACKUPS_DIR)!!)
            if (path.notExists()) {
                path.createDirectory()
            }
            return path
        }
    val defaultCompressType: CompressType
        get() = CompressType.valueOf(config.getString(Options.COMPRESS_TYPE)?.uppercase()!!)

    val intervalBackupTaskEnabled: Boolean
        get() = config.getBoolean(Options.INTERVAL_BACKUP_TASK_ENABLE)
    val intervalBackupTaskInitialDelay: Long
        get() = config.getLong(Options.INTERVAL_BACKUP_TASK_INITIAL_DELAY_MINUTES) * 60 * 20 // in ticks
    val intervalBackupTaskInterval: Long
        get() = config.getLong(Options.INTERVAL_BACKUP_TASK_INTERVAL_MINUTES) * 60 * 20 // in ticks
}

object Options {
    const val BACKUP_BACKUPS_DIR = "backup.backups_dir"
    const val COMPRESS_TYPE = "compress.type"
    const val INTERVAL_BACKUP_TASK_ENABLE = "interval_backup_task.enable"
    const val INTERVAL_BACKUP_TASK_INITIAL_DELAY_MINUTES = "interval_backup_task.initial_delay_minutes"
    const val INTERVAL_BACKUP_TASK_INTERVAL_MINUTES = "interval_backup_task.interval_minutes"
}

fun formatCurrentTime(): String {
    val formater = "yyyyMMdd'T'HHmmss"
    return LocalDateTime.now().format(DateTimeFormatter.ofPattern(formater))
}
