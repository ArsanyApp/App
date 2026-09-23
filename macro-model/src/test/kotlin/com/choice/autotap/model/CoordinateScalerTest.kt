package com.choice.autotap.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateScalerTest {

    private val phone = ScreenSize(1080, 2400)

    @Test
    fun `normalized center maps to pixel center`() {
        val px = CoordinateScaler.toPixels(NormalizedPoint(0.5f, 0.5f), phone)
        assertEquals(540f, px.x, 0.001f)
        assertEquals(1200f, px.y, 0.001f)
    }

    @Test
    fun `round trip pixel to normalized and back`() {
        val n = CoordinateScaler.toNormalized(270f, 1800f, phone)
        assertEquals(0.25f, n.x, 1e-6f)
        assertEquals(0.75f, n.y, 1e-6f)
        val px = CoordinateScaler.toPixels(n, phone)
        assertEquals(270f, px.x, 0.01f)
        assertEquals(1800f, px.y, 0.01f)
    }

    @Test
    fun `same percentage scales to a different resolution`() {
        val n = CoordinateScaler.toNormalized(540f, 1200f, phone)
        val tablet = ScreenSize(1600, 2560)
        val px = CoordinateScaler.toPixels(n, tablet)
        assertEquals(800f, px.x, 0.01f)
        assertEquals(1280f, px.y, 0.01f)
    }

    @Test
    fun `rotation keeps relative position`() {
        val n = CoordinateScaler.toNormalized(108f, 240f, phone) // 10% / 10%
        val landscape = ScreenSize(2400, 1080)
        val px = CoordinateScaler.toPixels(n, landscape)
        assertEquals(240f, px.x, 0.01f)
        assertEquals(108f, px.y, 0.01f)
    }

    @Test
    fun `points outside the screen are clamped`() {
        val n = CoordinateScaler.toNormalized(-50f, 5000f, phone)
        assertEquals(0f, n.x, 0f)
        assertEquals(1f, n.y, 0f)
        val px = CoordinateScaler.toPixels(NormalizedPoint(1f, 1f), phone)
        assertEquals(1079f, px.x, 0f)
        assertEquals(2399f, px.y, 0f)
    }

    @Test
    fun `offset by pixels moves the normalized point`() {
        val moved = CoordinateScaler.offsetByPixels(NormalizedPoint(0.5f, 0.5f), 0f, -240f, phone)
        assertEquals(0.5f, moved.x, 1e-6f)
        assertEquals(0.4f, moved.y, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero screen size is rejected`() {
        ScreenSize(0, 100)
    }
}
