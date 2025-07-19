package io.github.kilesduli.yabackup

import org.bukkit.Bukkit
import org.bukkit.World
import java.lang.reflect.Field
import java.lang.reflect.Method

object NMSReflection {
    private val craftBukkit: String by lazy {
        Bukkit.getServer().javaClass.`package`.name
    }

    fun <T> lazyLoadAndCatch(block: () -> T): Lazy<T> = lazy {
        runCatching(block).getOrElse { e ->
            throw RuntimeException("Failed to load class or method", e)
        }
    }

    private val craftServerProxy: Class<*> by lazyLoadAndCatch {
        Class.forName("$craftBukkit.CraftServer")
    }

    private val craftWorldProxy: Class<*> by lazyLoadAndCatch {
        Class.forName("$craftBukkit.CraftWorld")
    }

    private val savePlayersProxy: Method by lazyLoadAndCatch {
        craftServerProxy.getMethod("savePlayers")
    }

    private val craftWorldGetHandleProxy: Method by lazyLoadAndCatch {
        craftWorldProxy.getMethod("getHandle")
    }

    private val saveWorldProxy: Method by lazyLoadAndCatch {
        craftWorldGetHandleProxy.invoke(Bukkit.getWorlds().first()).javaClass.methods
            .firstOrNull { it.name == "save" }!!
    }

    private val flushSaveProxy: Method by lazyLoadAndCatch {
        craftWorldGetHandleProxy.invoke(Bukkit.getWorlds().first()).javaClass.getMethod("flushSave")
    }

    fun saveWorld(world: World) {
        runCatching {
            val serverLevel = craftWorldGetHandleProxy.invoke(world)
            when (saveWorldProxy.parameterCount) {
                // saveWorld :: save(@Nullable ProgressListener progress, boolean flush, boolean skipSave, boolean close)
                4 -> saveWorldProxy.invoke(serverLevel, null, true, false, false)

                // This only works in 1.14+
                // saveWorld :: save(@Nullable ProgressListener progress, boolean flush, boolean skipSave)
                3 -> saveWorldProxy.invoke(serverLevel, null, true, false)

                // According to the Minecraft 1.12 source code,
                // calling '/save-all flush' will first invoke `saveWorld`, then `flushSave`.
                // This behavior only works correctly in versions 1.10 through 1.12.
                // In versions prior to 1.10, it may cause the server to endlessly save chunks until it hangs,
                // though this issue does not occur on the official (vanilla) server.
                2 -> {
                    // saveWorld :: save(boolean flush, @Nullable IProgressUpdate iprogressupdate)
                    saveWorldProxy.invoke(serverLevel, true, null)
                    // flushSave :: save()
                    flushSaveProxy.invoke(serverLevel)
                }

                else -> throw Exception("Unexpected number of parameters in saveWorld method: " + saveWorldProxy.parameterCount)
            }
        }.onFailure { it.printStackTrace() }
    }

    fun savePlayers() {
        runCatching {
            savePlayersProxy.invoke(Bukkit.getServer())
        }.onFailure { it.printStackTrace() }
    }

    fun disableCheckAutoSave(flag: Boolean = true) {
        runCatching {
            val server = craftServerProxy.cast(Bukkit.getServer())
            val field: Field = server.javaClass.getDeclaredField("printSaveWarning")
            field.isAccessible = true
            field.setBoolean(server, flag)
        }.onFailure { it.printStackTrace() }
    }
}

