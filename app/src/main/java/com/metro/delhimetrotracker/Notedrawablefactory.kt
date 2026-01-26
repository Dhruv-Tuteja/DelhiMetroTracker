package com.metro.delhimetrotracker.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * Utility class for creating circular node drawables dynamically
 */
object NodeDrawableFactory {

    /**
     * Creates a solid filled circle drawable
     */
    fun createSolidCircle(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    /**
     * Creates a hollow circle drawable with a stroke
     */
    fun createHollowCircle(strokeColor: Int, strokeWidth: Int = 2): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(strokeWidth, strokeColor)
        }
    }

    /**
     * Creates a glow effect circle drawable
     */
    fun createGlowCircle(color: Int, alpha: Float = 0.15f): GradientDrawable {
        val glowColor = adjustAlpha(color, alpha)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(glowColor)
        }
    }

    /**
     * Adjusts the alpha value of a color
     */
    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = Math.round(255 * factor)
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }
}