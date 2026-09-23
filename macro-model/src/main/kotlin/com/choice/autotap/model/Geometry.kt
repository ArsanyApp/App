package com.choice.autotap.model

import kotlinx.serialization.Serializable

/**
 * A point stored as a fraction of the screen size (0.0 = left/top edge, 1.0 = right/bottom edge).
 * Storing fractions instead of pixels lets a macro survive rotation and resolution changes.
 */
@Serializable
data class NormalizedPoint(val x: Float, val y: Float) {
    fun clamped(): NormalizedPoint = NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/** Physical screen size in pixels at the moment of playback or editing. */
@Serializable
data class ScreenSize(val widthPx: Int, val heightPx: Int) {
    init {
        require(widthPx > 0 && heightPx > 0) { "Screen size must be positive: ${widthPx}x$heightPx" }
    }
}

/** A point in real screen pixels. */
data class PixelPoint(val x: Float, val y: Float)

/** Converts between normalized (percentage) coordinates and real pixels. */
object CoordinateScaler {

    /** Fraction -> pixel. The result is always inside the screen: [0, width-1] x [0, height-1]. */
    fun toPixels(point: NormalizedPoint, screen: ScreenSize): PixelPoint {
        val p = point.clamped()
        return clampToScreen(PixelPoint(p.x * screen.widthPx, p.y * screen.heightPx), screen)
    }

    /** Pixel -> fraction, clamped to [0, 1]. */
    fun toNormalized(xPx: Float, yPx: Float, screen: ScreenSize): NormalizedPoint =
        NormalizedPoint(xPx / screen.widthPx, yPx / screen.heightPx).clamped()

    /** Moves a normalized point by a pixel offset measured on [screen]. */
    fun offsetByPixels(point: NormalizedPoint, dxPx: Float, dyPx: Float, screen: ScreenSize): NormalizedPoint {
        val px = toPixels(point, screen)
        return toNormalized(px.x + dxPx, px.y + dyPx, screen)
    }

    fun clampToScreen(point: PixelPoint, screen: ScreenSize): PixelPoint = PixelPoint(
        point.x.coerceIn(0f, (screen.widthPx - 1).toFloat()),
        point.y.coerceIn(0f, (screen.heightPx - 1).toFloat()),
    )
}
