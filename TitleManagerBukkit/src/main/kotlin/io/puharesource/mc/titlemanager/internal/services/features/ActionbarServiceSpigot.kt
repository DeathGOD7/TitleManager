package io.puharesource.mc.titlemanager.internal.services.features

import io.puharesource.mc.titlemanager.TitleManagerPlugin
import io.puharesource.mc.titlemanager.api.v2.animation.Animation
import io.puharesource.mc.titlemanager.api.v2.animation.AnimationPart
import io.puharesource.mc.titlemanager.api.v2.animation.SendableAnimation
import io.puharesource.mc.titlemanager.internal.extensions.getTitleManagerPlayer
import io.puharesource.mc.titlemanager.internal.model.animation.EasySendableAnimation
import io.puharesource.mc.titlemanager.internal.model.animation.PartBasedSendableAnimation
import io.puharesource.mc.titlemanager.internal.services.animation.AnimationsService
import io.puharesource.mc.titlemanager.internal.services.placeholder.PlaceholderService
import io.puharesource.mc.titlemanager.internal.services.task.SchedulerService
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import javax.inject.Inject

class ActionbarServiceSpigot @Inject constructor(
    private val plugin: TitleManagerPlugin,
    private val placeholderService: PlaceholderService,
    private val animationsService: AnimationsService,
    private val schedulerService: SchedulerService
) : ActionbarService {
    override fun sendProcessedActionbar(player: Player, text: String) {
        val parts = animationsService.textToAnimationParts(placeholderService.replaceText(player, text))

        if (parts.size == 1 && parts.first().getPart() is String) {
            sendActionbar(player, text = parts.first().getPart() as String, withPlaceholders = false)
        } else if (parts.size == 1 && parts.first().getPart() is Animation) {
            createActionbarSendableAnimation(parts.first().getPart() as Animation, player, withPlaceholders = false).start()
        } else if (parts.isNotEmpty()) {
            createActionbarSendableAnimation(parts, player, withPlaceholders = false).start()
        }
    }

    override fun sendActionbar(player: Player, text: String, withPlaceholders: Boolean) {
        var processedText = text

        if (withPlaceholders) {
            processedText = placeholderService.replaceText(player, processedText)
        }

        player.getTitleManagerPlayer().sendActionbarMessage(processedText)
    }

    override fun clearActionbar(player: Player) {
        sendActionbar(player, " ")
    }

    override fun createActionbarSendableAnimation(animation: Animation, player: Player, withPlaceholders: Boolean): SendableAnimation {
        return EasySendableAnimation(
            schedulerService,
            animation,
            player,
            {
                sendActionbar(player, it.text, withPlaceholders = withPlaceholders)
            },
            onStop = {
                clearActionbar(player)
            },
            fixedOnStop = {
                removeRunningActionbarAnimation(it)
            },
            fixedOnStart = { receiver, sendableAnimation ->
                setRunningActionbarAnimation(receiver, sendableAnimation)
            }
        )
    }

    override fun createActionbarSendableAnimation(parts: List<AnimationPart<*>>, player: Player, withPlaceholders: Boolean): SendableAnimation {
        return PartBasedSendableAnimation(
            schedulerService,
            parts,
            player,
            {
                sendActionbar(player, it.text, withPlaceholders = withPlaceholders)
            },
            onStop = {
                clearActionbar(player)
            },
            fixedOnStop = {
                removeRunningActionbarAnimation(it)
            },
            fixedOnStart = { receiver, animation ->
                setRunningActionbarAnimation(receiver, animation)
            }
        )
    }

    private fun setRunningActionbarAnimation(player: Player, animation: SendableAnimation) {
        player.setMetadata("running-actionbar-animation", FixedMetadataValue(plugin, animation))
    }

    private fun removeRunningActionbarAnimation(player: Player) {
        val fullPath = "running-actionbar-animation"

        if (player.hasMetadata(fullPath)) {
            val animation = player.getMetadata(fullPath).first().value() as SendableAnimation

            animation.stop()

            player.removeMetadata(fullPath, plugin)
        }
    }
}
