package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import com.cobblemonguard.util.ReflectCache
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.server.network.ServerPlayerEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.util.Optional
import java.util.UUID

/**
 * Sincroniza el progreso de FTB Quests (tareas, misiones completadas y recompensas
 * reclamadas) del equipo PERSONAL del jugador, con el mismo flujo que el resto de los
 * datos: se serializa al salir / auto-save / apagado y se aplica al entrar, protegido por
 * el lock entre servidores. Así una recompensa reclamada en un backend figura como
 * reclamada en el otro y no se puede cobrar dos veces.
 *
 * ── API real verificada con javap (FTB Quests 2101.1.36, FTB Teams 2101.1.11) ──────────
 *   ServerQuestFile.INSTANCE                         campo estático público
 *   BaseQuestFile.getTeamData(PlayerEntity)          → Optional<TeamData> (equipo ACTUAL)
 *   TeamData.getTeamId()                             → UUID
 *   TeamData.serializeNBT()                          → SNBTCompoundTag (extiende NbtCompound)
 *   TeamData.deserializeNBT(SNBTCompoundTag)         REEMPLAZA todo (limpia los mapas antes)
 *   TeamData.clearCachedProgress() / markDirty()
 *   TeamData.getOnlineMembers()                      → Collection<ServerPlayerEntity>
 *   SyncTeamDataMessage(TeamData)                    CustomPayload que refresca el libro
 *   NetworkManager.sendToPlayers(Iterable, CustomPayload)   (Architectury)
 * El equipo personal de FTB Teams se crea con el UUID del jugador
 * (TeamManagerImpl.createPlayerTeam(playerId, ...)), así que es el MISMO id en todos los
 * backends. Cobblemon Quests Reloaded aplica los datos exactamente con esta secuencia en su
 * sync por MongoDB (QuestMongoSync.applyTeamData), que acá no se usa.
 *
 * Reflexión SOLO para cruzar la frontera de FTB (sus nombres no se ofuscan); los tipos de
 * Minecraft (NbtCompound, ServerPlayerEntity) se usan directo, como en AccessorySync.
 *
 * ── Grupos (/ftbteams party) ───────────────────────────────────────────────────────────
 * Si el jugador está en un grupo, FTB usa el progreso del GRUPO, que es compartido entre
 * varios jugadores que pueden estar en backends distintos a la vez. Eso no se puede
 * proteger con un lock por jugador, así que NO se sincroniza: serialize devuelve null (la
 * columna en MySQL queda como estaba, ver PlayerDataRepository) y deserialize no toca nada.
 */
object FtbQuestsSync {

    private const val NBT_SIZE_LIMIT = 50L * 1024 * 1024
    private const val CLAVE_EQUIPO = "team"
    private const val CLAVE_ID = "team_id"

    private val serverQuestFileClass: Class<*>? by lazy {
        runCatching { Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile") }.getOrNull()
    }

    val isLoaded: Boolean by lazy {
        (serverQuestFileClass != null).also { loaded ->
            if (loaded) CobblemonGuard.logger.info("[Misiones] FTB Quests detectado — el progreso de misiones se sincroniza.")
            else CobblemonGuard.logger.info("[Misiones] FTB Quests no está instalado — no se sincronizan misiones.")
        }
    }

    /** Grupos ya avisados, para no llenar el log en cada auto-save. */
    private val avisadosGrupo = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()

    // ── Serializar ─────────────────────────────────────────────────────────

    fun serialize(player: ServerPlayerEntity): ByteArray? {
        if (!isLoaded) return null
        return runCatching {
            val teamData = equipoActual(player) ?: return null
            val teamId = teamIdDe(teamData) ?: return null
            if (teamId != player.uuid) {
                avisarGrupo(player)
                return null
            }
            val nbt = invocar(teamData, "serializeNBT") as? NbtCompound ?: run {
                CobblemonGuard.logger.error("[Misiones] serializeNBT no devolvió un NbtCompound para ${player.name.string}.")
                return null
            }
            val root = NbtCompound()
            root.putString(CLAVE_ID, teamId.toString())
            root.put(CLAVE_EQUIPO, nbt)
            val baos = ByteArrayOutputStream()
            NbtIo.writeCompressed(root, baos)
            baos.toByteArray()
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Misiones] Error serializando ${player.name.string}: ${e.message}", e)
        }.getOrNull()
    }

    // ── Aplicar ────────────────────────────────────────────────────────────

    fun deserialize(player: ServerPlayerEntity, data: ByteArray): Resultado {
        if (!isLoaded) return Resultado.OMITIDO
        return runCatching {
            val root = NbtIo.readCompressed(ByteArrayInputStream(data), NbtSizeTracker.of(NBT_SIZE_LIMIT))
            if (!root.contains(CLAVE_EQUIPO)) {
                CobblemonGuard.logger.error("[Misiones] El blob de ${player.name.string} no tiene la clave '$CLAVE_EQUIPO'.")
                return Resultado.FALLO
            }

            // FTB Quests SI está instalado: no encontrar el archivo de misiones o el equipo
            // del jugador es la API rota, y dejarlo jugar con el progreso local es la vía para
            // reclamar dos veces la misma recompensa. Por eso FALLO y no OMITIDO.
            val teamData = equipoActual(player) ?: run {
                CobblemonGuard.logger.error("[Misiones] No se encontró el equipo de FTB Quests de ${player.name.string}.")
                return Resultado.FALLO
            }
            val teamId = teamIdDe(teamData) ?: run {
                CobblemonGuard.logger.error("[Misiones] No se pudo leer el id del equipo de ${player.name.string}.")
                return Resultado.FALLO
            }
            if (teamId != player.uuid) {
                // Está en un grupo de ESTE backend: su progreso activo es el del grupo, que no
                // se sincroniza. El equipo personal no se toca.
                avisarGrupo(player)
                return Resultado.OMITIDO
            }

            val guardado = root.getCompound(CLAVE_EQUIPO)
            val snbtClass = Class.forName("dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag")
            val tag = (ReflectCache.constructor(snbtClass)?.newInstance() as? NbtCompound) ?: run {
                CobblemonGuard.logger.error("[Misiones] No se pudo crear un SNBTCompoundTag.")
                return Resultado.FALLO
            }
            for (key in guardado.keys) {
                guardado.get(key)?.let { tag.put(key, it.copy()) }
            }

            val deserializar = ReflectCache.method(teamData.javaClass, "deserializeNBT", 1) {
                it.parameterTypes[0].isAssignableFrom(snbtClass)
            } ?: run {
                CobblemonGuard.logger.error("[Misiones] TeamData no tiene deserializeNBT(SNBTCompoundTag) (${teamData.javaClass.name}).")
                return Resultado.FALLO
            }
            deserializar.invoke(teamData, tag)
            invocar(teamData, "clearCachedProgress")
            invocar(teamData, "markDirty")
            refrescarCliente(teamData, player)
            Resultado.APLICADO
        }.getOrElse { e ->
            CobblemonGuard.logger.error("[Misiones] Error aplicando las misiones de ${player.name.string}: ${e.message}", e)
            Resultado.FALLO
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** TeamData del equipo ACTUAL del jugador (personal o grupo), o null. */
    private fun equipoActual(player: ServerPlayerEntity): Any? {
        val cls = serverQuestFileClass ?: return null
        val file = runCatching { cls.getField("INSTANCE").get(null) }.getOrNull() ?: return null
        val getTeamData: Method = ReflectCache.method(file.javaClass, "getTeamData", 1) {
            it.parameterTypes[0].isAssignableFrom(ServerPlayerEntity::class.java)
        } ?: return null
        val opt = getTeamData.invoke(file, player) as? Optional<*> ?: return null
        return opt.orElse(null)
    }

    private fun teamIdDe(teamData: Any): UUID? = invocar(teamData, "getTeamId") as? UUID

    private fun invocar(target: Any, nombre: String): Any? {
        val m = ReflectCache.method(target.javaClass, nombre, 0)
            ?: throw NoSuchMethodException("${target.javaClass.name}.$nombre()")
        return m.invoke(target)
    }

    /**
     * El libro del cliente ya recibió los datos LOCALES de este backend al conectarse
     * (FTB los manda en su propio evento de ingreso, antes de que GUARD cargue de MySQL).
     * Se le vuelve a mandar el equipo con los datos recién aplicados.
     */
    private fun refrescarCliente(teamData: Any, player: ServerPlayerEntity) {
        runCatching {
            val msgClass = Class.forName("dev.ftb.mods.ftbquests.net.SyncTeamDataMessage")
            val msg = msgClass.constructors.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0].isInstance(teamData)
            }?.newInstance(teamData) ?: error("sin constructor SyncTeamDataMessage(TeamData)")
            val miembros = (invocar(teamData, "getOnlineMembers") as? Collection<*>)
                ?.takeIf { it.isNotEmpty() } ?: listOf(player)
            val nm = Class.forName("dev.architectury.networking.NetworkManager")
            val enviar = ReflectCache.method(nm, "sendToPlayers", 2) {
                Iterable::class.java.isAssignableFrom(it.parameterTypes[0]) && it.parameterTypes[1].isInstance(msg)
            } ?: error("sin NetworkManager.sendToPlayers(Iterable, CustomPayload)")
            enviar.invoke(null, miembros, msg)
        }.onFailure { e ->
            // Los datos YA quedaron aplicados en el servidor; solo el libro del cliente queda
            // desactualizado hasta el próximo cambio. No es motivo para expulsar a nadie.
            CobblemonGuard.logger.warn("[Misiones] Progreso aplicado a ${player.name.string}, pero no se pudo refrescar su libro: ${e.message}")
        }
    }

    private fun avisarGrupo(player: ServerPlayerEntity) {
        if (avisadosGrupo.add(player.uuid)) {
            CobblemonGuard.logger.info(
                "[Misiones] ${player.name.string} está en un grupo de FTB Teams: el progreso del grupo no se " +
                "sincroniza entre servidores (queda local en este backend)."
            )
        }
    }
}
