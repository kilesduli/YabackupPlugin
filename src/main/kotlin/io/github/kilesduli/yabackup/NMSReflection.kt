
/* Access the built-in save method implemented by NMS(net.minecraft.server) via
   reflection for Yabackup.

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
                // running '/save-all flush' invokes `saveWorld`, followed by `flushSave`.
                // This behavior behaves correctly only in versions 1.10 through 1.13.
                // In versions prior to 1.10, it may cause the server to hang due to infinite chunk saving;
                // however, this issue has only been observed on modified servers and does not occur on the official (vanilla) server.
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

