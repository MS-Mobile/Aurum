package com.msmobile.aurum.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class ExifTransplantTest {

    @Test
    fun transplant_sourceWithExif_replacesEncoderHeadersWithOriginalExif() {
        // Arrange
        val source = jpeg(exifSegment(littleEndian = true), dqtSegment(0x11))
        val target = jpeg(jfifSegment(), dqtSegment(0x22))

        // Act
        val result = ExifTransplant.transplant(source, target)

        // Assert
        val expectedExif = exifSegment(littleEndian = true, nextIfdOffset = 0)
        assertArrayEquals(jpeg(expectedExif, dqtSegment(0x22)), result)
    }

    @Test
    fun transplant_bigEndianExif_unlinksThumbnail() {
        // Arrange
        val source = jpeg(exifSegment(littleEndian = false))
        val target = jpeg(jfifSegment(), dqtSegment(0x22))

        // Act
        val result = ExifTransplant.transplant(source, target)

        // Assert
        assertArrayEquals(jpeg(exifSegment(littleEndian = false, nextIfdOffset = 0), dqtSegment(0x22)), result)
    }

    @Test
    fun transplant_sourceWithoutExif_returnsTargetUnchanged() {
        // Arrange
        val source = jpeg(jfifSegment())
        val target = jpeg(jfifSegment(), dqtSegment(0x22))

        // Act
        val result = ExifTransplant.transplant(source, target)

        // Assert
        assertEquals(target.toList(), result.toList())
    }

    /** SOI, the given segments, then a minimal SOS with entropy data and EOI. */
    private fun jpeg(vararg segments: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        segments.forEach { write(it) }
        write(segment(0xDA, byteArrayOf(1, 2, 3)))
        write(byteArrayOf(0x55, 0x66, 0xFF.toByte(), 0xD9.toByte()))
    }.toByteArray()

    private fun jfifSegment() = segment(0xE0, "JFIF".toByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))

    private fun dqtSegment(fill: Int) = segment(0xDB, ByteArray(5) { fill.toByte() })

    /** APP1 Exif with a TIFF header, IFD0 holding one entry, and IFD0's next-IFD offset (the thumbnail link). */
    private fun exifSegment(littleEndian: Boolean, nextIfdOffset: Int = 26): ByteArray {
        val tiff = ByteArrayOutputStream()
        fun u16(v: Int) = if (littleEndian) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = if (littleEndian) u16(v and 0xFFFF) + u16(v ushr 16) else u16(v ushr 16) + u16(v and 0xFFFF)
        tiff.write(if (littleEndian) "II".toByteArray() else "MM".toByteArray())
        tiff.write(u16(42))
        tiff.write(u32(8))
        tiff.write(u16(1)) // one entry
        tiff.write(u16(0x0112) + u16(3) + u32(1) + u16(6) + u16(0)) // Orientation = 6
        tiff.write(u32(nextIfdOffset))
        return segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + tiff.toByteArray())
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }
}
