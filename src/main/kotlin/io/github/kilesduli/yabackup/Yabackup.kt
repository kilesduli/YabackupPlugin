package io.github.kilesduli.yabackup

import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Bukkit.*
import org.bukkit.World

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
            withNoAutoSaveThenSaveAllAsync {
                logger.info("TODO: Creating backup archive...")
            }
            return true
        }

        override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>?): List<String> {
            if (args == null) return emptyList()

            fun filterStartWith(size: Int): (String) -> Boolean = { it.startsWith(args[size-1].lowercase()) }

            return when (val size = args.size) {
                1 -> listOf("zstd", "gz", "zip").filter (filterStartWith(size))
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

    val sortedWorlds: List<World>
        get() = server.worlds.sortedBy { it.name }
}
