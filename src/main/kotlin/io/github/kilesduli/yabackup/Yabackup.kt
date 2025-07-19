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
        setupConfig()
        server.commandMap.register(name, backupCommand)
        NMSReflection.disableCheckAutoSave()

        if (intervalBackupTaskEnabled) {
            logger.info("Interval backup task is enabled.")
            logger.info("First backup will start in ${intervalBackupTaskInitialDelay / (20 * 60)} minute. " +
                    "Interval is ${intervalBackupTaskInterval / (20 * 60)} minutes.")

            server.scheduler.runTaskTimer(this, Runnable {
                logger.info("Running backup task...")
                this.backupWorlds(defaultCompressType, "autobackup")
            }, intervalBackupTaskInitialDelay, intervalBackupTaskInterval) // run every second
        }

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
        config.addDefault("backups_dir", "./backups")
        config.addDefault("compress.type", "zstd")
        config.addDefault("interval_backup_task.enable", true)
        config.addDefault("interval_backup_task.initial_delay_minutes", 1)
        config.addDefault("interval_backup_task.interval_minutes", 20)
        saveConfig()
    }


    val sortedWorlds: List<World>
        get() = server.worlds.sortedBy { it.name }
    val backupsDir: Path
        get() {
            val path = Paths.get(config.getString("backups_dir")!!)
            if (path.notExists()) {
                path.createDirectory()
            }
            return path
        }
    val defaultCompressType: CompressType
        get() = CompressType.valueOf(config.getString("compress.type")?.uppercase()!!)

    val intervalBackupTaskEnabled: Boolean
        get() = config.getBoolean("interval_backup_task.enable")
    val intervalBackupTaskInitialDelay: Long
        get() = config.getLong("interval_backup_task.initial_delay_minutes") * 60 * 20 // in ticks
    val intervalBackupTaskInterval: Long
        get() = config.getLong("interval_backup_task.interval_minutes") * 60 * 20 // in ticks
}

fun formatCurrentTime(): String {
    val formater = "yyyyMMdd'T'HHmmss"
    return LocalDateTime.now().format(DateTimeFormatter.ofPattern(formater))
}
