package io.github.kilesduli.yabackup

import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectory
import kotlin.io.path.notExists

class Yabackup : JavaPlugin() {
    override fun onEnable() {
        // register the command
        server.commandMap.register(name, backupCommand)
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
                CompressType.ZSTD
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
            world.isAutoSave = false
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
            val filename = "${formatCurrentTime()}-${extraFileInfo}${type.suffix()}"

            archiveThenCompress(backupDir.resolve(filename), paths, type)
            logger.info("Created backup archive: $filename")
        }.onFailure { e ->
            logger.severe("Failed to create backup: ${e.message}")
        }
    }


    val sortedWorlds: List<World>
        get() = server.worlds.sortedBy { it.name }
    val backupDir: Path
        get() {
            val path = Paths.get("./backups")
            if (path.notExists()) {
                path.createDirectory()
            }
            return path
        }
}
