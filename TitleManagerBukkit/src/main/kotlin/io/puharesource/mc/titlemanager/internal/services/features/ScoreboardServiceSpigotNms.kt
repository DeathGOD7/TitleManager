package io.puharesource.mc.titlemanager.internal.services.features

import io.puharesource.mc.titlemanager.TitleManagerPlugin
import io.puharesource.mc.titlemanager.api.v2.animation.Animation
import io.puharesource.mc.titlemanager.api.v2.animation.AnimationPart
import io.puharesource.mc.titlemanager.api.v2.animation.SendableAnimation
import io.puharesource.mc.titlemanager.internal.config.TMConfigMain
import io.puharesource.mc.titlemanager.internal.extensions.modify
import io.puharesource.mc.titlemanager.internal.model.animation.EasySendableAnimation
import io.puharesource.mc.titlemanager.internal.model.animation.PartBasedSendableAnimation
import io.puharesource.mc.titlemanager.internal.model.scoreboard.ScoreboardRepresentation
import io.puharesource.mc.titlemanager.internal.reflections.ChatComponentText
import io.puharesource.mc.titlemanager.internal.reflections.ChatSerializer
import io.puharesource.mc.titlemanager.internal.reflections.NMSClassProvider
import io.puharesource.mc.titlemanager.internal.reflections.NMSManager
import io.puharesource.mc.titlemanager.internal.reflections.PacketPlayOutScoreboardDisplayObjective
import io.puharesource.mc.titlemanager.internal.reflections.PacketPlayOutScoreboardObjective
import io.puharesource.mc.titlemanager.internal.reflections.PacketPlayOutScoreboardScore
import io.puharesource.mc.titlemanager.internal.reflections.PacketPlayOutScoreboardTeam
import io.puharesource.mc.titlemanager.internal.reflections.sendNMSPacket
import io.puharesource.mc.titlemanager.internal.services.animation.AnimationsService
import io.puharesource.mc.titlemanager.internal.services.placeholder.PlaceholderService
import io.puharesource.mc.titlemanager.internal.services.storage.PlayerInfoService
import io.puharesource.mc.titlemanager.internal.services.task.SchedulerService
import org.apache.commons.lang.RandomStringUtils
import org.bukkit.ChatColor
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class ScoreboardServiceSpigotNms @Inject constructor(
    private val plugin: io.puharesource.mc.titlemanager.TitleManagerPlugin,
    private val config: TMConfigMain,
    private val placeholderService: PlaceholderService,
    private val schedulerService: SchedulerService,
    private val animationsService: AnimationsService,
    private val playerInfoService: PlayerInfoService
) : ScoreboardService {
    private val classPacketPlayOutScoreboardDisplayObjective = PacketPlayOutScoreboardDisplayObjective()
    private val classPacketPlayOutScoreboardScore = PacketPlayOutScoreboardScore()

    private val provider: NMSClassProvider
        get() = NMSManager.getClassProvider()

    private val playerScoreboards: MutableMap<Player, ScoreboardRepresentation> = ConcurrentHashMap()
    private val playerScoreboardUpdateTasks: MutableMap<Player, Int> = ConcurrentHashMap()
    private val playerTeamCache: MutableMap<Player, Array<String>> = ConcurrentHashMap()

    override fun startPlayerTasks() {
        plugin.server.onlinePlayers.forEach {
            if (playerInfoService.isScoreboardEnabled(it)) {
                toggleScoreboardInWorld(it, it.world)
            }
        }
    }

    override fun stopPlayerTasks() {
        plugin.server.onlinePlayers.forEach {
            removeScoreboard(it)
        }
    }

    override fun hasScoreboard(player: Player): Boolean {
        return playerScoreboards.containsKey(player)
    }

    override fun giveScoreboard(player: Player) {
        sendPacketCreateScoreboardTeams(player)

        if (!hasScoreboard(player)) {
            playerScoreboards[player] = ScoreboardRepresentation()
            startUpdateTask(player)
        }
    }

    override fun giveDefaultScoreboard(player: Player) {
        if (!hasScoreboard(player)) {
            giveScoreboard(player)

            setProcessedScoreboardTitle(player, config.scoreboard.title)

            config.scoreboard.lines.forEachIndexed { index, line ->
                setProcessedScoreboardValue(player, index + 1, line)
            }
        }
    }

    override fun removeScoreboard(player: Player) {
        sendPacketRemoveScoreboardTeams(player)
        clearTeamCache(player)

        playerScoreboards.remove(player)?.let { scoreboard ->
            stopUpdateTask(player)

            removeRunningScoreboardTitleAnimation(player)
            (1..15).forEach { removeRunningScoreboardValueAnimation(player, it) }

            removeScoreboardWithName(player, scoreboard.name)
        }
    }

    override fun getScoreboardTitle(player: Player): String? {
        return playerScoreboards[player]?.title
    }

    override fun setScoreboardTitle(player: Player, title: String, withPlaceholders: Boolean) {
        var processedTitle = title

        if (withPlaceholders) {
            processedTitle = placeholderService.replaceText(player, processedTitle)
        }

        playerScoreboards[player]?.title = processedTitle
    }

    override fun setProcessedScoreboardTitle(player: Player, title: String) {
        removeRunningScoreboardTitleAnimation(player)
        val parts = animationsService.textToAnimationParts(title)

        createScoreboardTitleSendableAnimation(parts, player, withPlaceholders = true).start()
    }

    override fun getScoreboardValue(player: Player, index: Int): String? {
        require(index in 1..15) { "Index needs to be in the range of 1 to 15 (1 and 15 inclusive). Index provided: $index" }

        return playerScoreboards[player]?.get(index)
    }

    override fun setScoreboardValue(player: Player, index: Int, value: String, withPlaceholders: Boolean) {
        require(index in 1..15) { "Index needs to be in the range of 1 to 15 (1 and 15 inclusive). Index provided: $index" }

        var processedValue = value

        if (withPlaceholders) {
            processedValue = placeholderService.replaceText(player, processedValue)
        }

        playerScoreboards[player]?.set(index, processedValue)
    }

    override fun setProcessedScoreboardValue(player: Player, index: Int, value: String) {
        require(index in 1..15) { "Index needs to be in the range of 1 to 15 (1 and 15 inclusive). Index provided: $index" }

        removeRunningScoreboardValueAnimation(player, index)

        val parts = animationsService.textToAnimationParts(value)

        createScoreboardValueSendableAnimation(parts, player, index, withPlaceholders = true).start()
    }

    override fun removeScoreboardValue(player: Player, index: Int) {
        require(index in 1..15) { "Index needs to be in the range of 1 to 15 (1 and 15 inclusive). Index provided: $index" }

        playerScoreboards[player]?.remove(index)
    }

    override fun createScoreboardTitleSendableAnimation(animation: Animation, player: Player, withPlaceholders: Boolean): SendableAnimation {
        return EasySendableAnimation(
            schedulerService,
            animation,
            player,
            {
                setScoreboardTitle(player, it.text, withPlaceholders = withPlaceholders)
            },
            isContinuous = true,
            tickRate = config.bandwidth.scoreboardMsPerTick,
            fixedOnStop = {
                removeRunningScoreboardTitleAnimation(player)
            },
            fixedOnStart = { receiver, sendableAnimation ->
                setRunningScoreboardTitleAnimation(receiver, sendableAnimation)
            }
        )
    }

    override fun createScoreboardValueSendableAnimation(animation: Animation, player: Player, index: Int, withPlaceholders: Boolean): SendableAnimation {
        return EasySendableAnimation(
            schedulerService,
            animation,
            player,
            {
                setScoreboardValue(player, index, it.text, withPlaceholders = withPlaceholders)
            },
            isContinuous = true,
            tickRate = config.bandwidth.scoreboardMsPerTick,
            fixedOnStop = {
                removeRunningScoreboardTitleAnimation(player)
            },
            fixedOnStart = { receiver, sendableAnimation ->
                setRunningScoreboardValueAnimation(receiver, index, sendableAnimation)
            }
        )
    }

    override fun createScoreboardTitleSendableAnimation(parts: List<AnimationPart<*>>, player: Player, withPlaceholders: Boolean): SendableAnimation {
        return PartBasedSendableAnimation(
            schedulerService,
            parts,
            player,
            {
                setScoreboardTitle(player, it.text, withPlaceholders = withPlaceholders)
            },
            isContinuous = true,
            tickRate = config.bandwidth.scoreboardMsPerTick,
            fixedOnStop = {
                removeRunningScoreboardTitleAnimation(player)
            },
            fixedOnStart = { receiver, animation ->
                setRunningScoreboardTitleAnimation(receiver, animation)
            }
        )
    }

    override fun createScoreboardValueSendableAnimation(parts: List<AnimationPart<*>>, player: Player, index: Int, withPlaceholders: Boolean): SendableAnimation {
        return PartBasedSendableAnimation(
            schedulerService,
            parts,
            player,
            {
                setScoreboardValue(player, index, it.text, withPlaceholders = withPlaceholders)
            },
            isContinuous = true,
            tickRate = config.bandwidth.scoreboardMsPerTick,
            fixedOnStop = {
                removeRunningScoreboardTitleAnimation(player)
            },
            fixedOnStart = { receiver, animation ->
                setRunningScoreboardValueAnimation(receiver, index, animation)
            }
        )
    }

    override fun isScoreboardDisabledWorld(world: World) = config.scoreboard.disabledWorlds.any { disabledWorldName -> disabledWorldName.equals(world.name, ignoreCase = true) }

    override fun toggleScoreboardInWorld(player: Player, world: World) {
        if (!playerInfoService.isScoreboardEnabled(player)) {
            return
        }

        val isDisabledWorld = isScoreboardDisabledWorld(world)

        if (isDisabledWorld && hasScoreboard(player)) {
            removeScoreboard(player)
        } else if (!isDisabledWorld) {
            giveDefaultScoreboard(player)
        }
    }

    override fun populateTeamCache(player: Player) {
        val array = arrayOfNulls<String>(16)
        for (i in array.indices) {
            array[i] = RandomStringUtils.randomAlphanumeric(16)
        }

        playerTeamCache[player] = array as Array<String>
    }

    override fun clearTeamCache(player: Player) {
        playerTeamCache.remove(player)
    }

    override fun getTeamCache(player: Player): Array<String> {
        if (!playerTeamCache.containsKey(player)) {
            populateTeamCache(player)
        }

        return playerTeamCache[player]!!
    }

    private fun sendPacketCreateScoreboardTeams(player: Player) {
        val teamNameCache = getTeamCache(player)

        for (i in teamNameCache.indices) {
            val teamCreatePacket = PacketPlayOutScoreboardTeam<Any>()

            teamCreatePacket.name = teamNameCache[i]
            teamCreatePacket.players = listOf(ChatColor.COLOR_CHAR.toString() + i.toChar().lowercase())
            teamCreatePacket.mode = PacketPlayOutScoreboardTeam.MODE_TEAM_CREATE

            player.sendNMSPacket(teamCreatePacket.handle)
        }
    }

    private fun sendPacketRemoveScoreboardTeams(player: Player) {
        val teamNameCache = getTeamCache(player)

        for (i in teamNameCache.indices) {
            val teamRemovePacket = PacketPlayOutScoreboardTeam<Any>()

            teamRemovePacket.name = teamNameCache[i]
            teamRemovePacket.mode = PacketPlayOutScoreboardTeam.MODE_TEAM_REMOVED

            player.sendNMSPacket(teamRemovePacket.handle)
        }
    }

    private fun sendPacketCreateScoreboardWithName(player: Player, scoreboardName: String) {
        val packet = PacketPlayOutScoreboardObjective<Number, Any>()

        packet.objectiveName = scoreboardName
        packet.mode = 0
        packet.value = when {
            NMSManager.versionIndex > 9 -> ChatSerializer.deserializeLegacyText(scoreboardName)
            NMSManager.versionIndex > 6 -> NMSManager.getClassProvider().getIChatComponent("")
            else -> ""
        }

        if (NMSManager.versionIndex > 0) {
            packet.type = 0
        }

        player.sendNMSPacket(packet.handle)
    }

    private fun sendPacketDisplayScoreboardWithName(player: Player, scoreboardName: String) {
        val packet = classPacketPlayOutScoreboardDisplayObjective.createInstance()

        classPacketPlayOutScoreboardDisplayObjective.positionField.modify { setInt(packet, 1) }
        classPacketPlayOutScoreboardDisplayObjective.nameField.modify { set(packet, scoreboardName) }

        player.sendNMSPacket(packet)
    }

    private fun sendPacketSetScoreboardTitleWithName(player: Player, title: String, scoreboardName: String) {
        val packet = PacketPlayOutScoreboardObjective<Number, Any>()

        packet.objectiveName = scoreboardName
        packet.mode = 2
        packet.value = when {
            NMSManager.versionIndex > 9 ->
                ChatSerializer.deserializeLegacyText(title)
            NMSManager.versionIndex > 6 ->
                NMSManager.getClassProvider().getIChatComponent(title)
            else ->
                if (title.length > 32) {
                    title.substring(0, 32)
                } else {
                    title
                }
        }

        if (NMSManager.versionIndex > 0) {
            packet.type = 0
        }

        player.sendNMSPacket(packet.handle)
    }

    private fun sendPacketSetScoreboardValueWithName(player: Player, index: Int, value: String, scoreboardName: String) {
        val packet = classPacketPlayOutScoreboardScore.createInstance()
        val teamPacket: PacketPlayOutScoreboardTeam<*>

        if (NMSManager.versionIndex > 6) {
            teamPacket = PacketPlayOutScoreboardTeam<Any>()
            teamPacket.text = if (NMSManager.versionIndex < 10) ChatComponentText().constructor.newInstance(value) else ChatSerializer.deserializeLegacyText(value)
        } else {
            teamPacket = PacketPlayOutScoreboardTeam<String>()
            teamPacket.text = value
        }

        val teamNameCache = getTeamCache(player)

        teamPacket.name = teamNameCache[index]
        teamPacket.mode = PacketPlayOutScoreboardTeam.MODE_TEAM_UPDATED

        classPacketPlayOutScoreboardScore.scoreNameField.modify { set(packet, ChatColor.COLOR_CHAR.toString() + index.toChar().lowercase()) }

        if (NMSManager.versionIndex > 0) {
            classPacketPlayOutScoreboardScore.actionField.modify { set(packet, provider["EnumScoreboardAction"].handle.enumConstants[0]) }
        } else {
            classPacketPlayOutScoreboardScore.actionField.modify { set(packet, 0) }
        }

        classPacketPlayOutScoreboardScore.objectiveNameField.modify { set(packet, scoreboardName) }
        classPacketPlayOutScoreboardScore.valueField.modify { setInt(packet, 15 - index) }

        player.sendNMSPacket(teamPacket.handle)
        player.sendNMSPacket(packet)
    }

    private fun removeScoreboardWithName(player: Player, scoreboardName: String) {
        val packet = PacketPlayOutScoreboardObjective<Number, Any>()

        packet.objectiveName = scoreboardName
        packet.mode = 1

        player.sendNMSPacket(packet.handle)
    }

    private fun startUpdateTask(player: Player) {
        if (playerScoreboards.containsKey(player) && !playerScoreboardUpdateTasks.containsKey(player)) {
            playerScoreboardUpdateTasks[player] = schedulerService.schedule(
                {
                    val scoreboard = playerScoreboards[player]

                    if (scoreboard == null) {
                        stopUpdateTask(player)
                        return@schedule
                    }

                    if (!scoreboard.isUpdatePending.get()) {
                        return@schedule
                    }

                    val currentScoreboardName = scoreboard.name

                    scoreboard.generateNewScoreboardName()
                    val newScoreboardName = scoreboard.name

                    sendPacketCreateScoreboardWithName(player, newScoreboardName)
                    sendPacketSetScoreboardTitleWithName(player, scoreboard.title, newScoreboardName)

                    (1..15).mapNotNull { scoreboard.get(it) }.forEachIndexed { index, text ->
                        sendPacketSetScoreboardValueWithName(player, index + (15 - scoreboard.size) + 1, text, newScoreboardName)
                    }

                    scoreboard.isUpdatePending.set(false)

                    sendPacketDisplayScoreboardWithName(player, newScoreboardName)
                    removeScoreboardWithName(player, currentScoreboardName)
                },
                1,
                1
            )
        }
    }

    private fun stopUpdateTask(player: Player) {
        playerScoreboardUpdateTasks.remove(player)?.let {
            schedulerService.cancel(it)
        }
    }

    private fun setRunningAnimation(player: Player, path: String, animation: SendableAnimation) {
        player.setMetadata("running-$path-animation", FixedMetadataValue(plugin, animation))
    }

    private fun removeRunningAnimation(player: Player, path: String) {
        val fullPath = "running-$path-animation"

        if (player.hasMetadata(fullPath)) {
            (player.getMetadata(fullPath).first().value() as? SendableAnimation)?.stop()

            player.removeMetadata(fullPath, plugin)
        }
    }

    private fun setRunningScoreboardTitleAnimation(player: Player, animation: SendableAnimation) = setRunningAnimation(player, "scoreboardtitle", animation)
    private fun removeRunningScoreboardTitleAnimation(player: Player) = removeRunningAnimation(player, "scoreboardtitle")

    private fun setRunningScoreboardValueAnimation(player: Player, index: Int, animation: SendableAnimation) = setRunningAnimation(player, "scoreboardvalue$index", animation)
    private fun removeRunningScoreboardValueAnimation(player: Player, index: Int) = removeRunningAnimation(player, "scoreboardvalue$index")
}
