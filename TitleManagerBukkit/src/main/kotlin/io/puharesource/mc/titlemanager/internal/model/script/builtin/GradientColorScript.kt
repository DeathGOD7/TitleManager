package io.puharesource.mc.titlemanager.internal.model.script.builtin

import dev.tarkan.titlemanager.lib.color.Interpolator
import dev.tarkan.titlemanager.lib.color.InterpolatorUtil
import io.puharesource.mc.titlemanager.api.v3.toJavaColor
import io.puharesource.mc.titlemanager.api.v3.toTitleManagerColor
import io.puharesource.mc.titlemanager.internal.model.script.AnimationScript
import net.md_5.bungee.api.ChatColor
import java.awt.Color
import java.util.regex.Pattern

class GradientColorScript(text: String, index: Int) : AnimationScript(text, index, fadeIn = 0, stay = 1, fadeOut = 0) {
    private val pattern: Pattern =
        """\[(?<colors>.+)](?<precision>\d+)""".toRegex().toPattern()
    private var colors = listOf("#ff0000", "#00ff00").map { Color.decode(it).toTitleManagerColor() }.toList()

    private var precision: Float = 20.0f
    private lateinit var interpolator: Interpolator<dev.tarkan.titlemanager.lib.color.Color>

    override fun generateFrame() {
        done = index + 1 >= precision
        text = ChatColor.of(interpolator.interpolate(index / precision).toJavaColor()).toString()
    }

    override fun decode() {
        super.decode()

        val matcher = pattern.matcher(text)

        if (matcher.find()) {
            colors = matcher.group("colors").split(",")
                .asSequence()
                .map { it.trim() }
                .map { Color.decode(it).toTitleManagerColor() }
                .toList()
            text = matcher.group("precision")
            text.toFloatOrNull()?.let { precision = it }
        }

        interpolator = InterpolatorUtil.createRgbGradientInterpolator(colors, continuous = true)
    }
}
