package com.msmobile.aurum.capture

import java.io.ByteArrayOutputStream

/**
 * Copies the original camera EXIF (APP1 segment, byte for byte, including GPS and maker notes) into a newly encoded
 * JPEG, replacing the encoder's own APP0/APP1 headers.
 *
 * The thumbnail (IFD1) is unlinked because it shows the unprocessed, unrotated photo; orientation and dimensions are
 * fixed afterwards with ExifInterface.
 */
object ExifTransplant {

    fun transplant(source: ByteArray, target: ByteArray): ByteArray {
        val exif = findSegments(source).firstOrNull { it.isExif(source) } ?: return target
        val app1 = source.copyOfRange(exif.start, exif.end)
        unlinkThumbnail(app1)

        val out = ByteArrayOutputStream(target.size + app1.size)
        out.write(target, 0, 2) // SOI
        out.write(app1)
        val targetSegments = findSegments(target)
        val firstKept = targetSegments.firstOrNull { it.marker != APP0 && it.marker != APP1 }?.start
            ?: targetSegments.lastOrNull()?.end
            ?: 2
        out.write(target, firstKept, target.size - firstKept)
        return out.toByteArray()
    }

    private data class Segment(val marker: Int, val start: Int, val end: Int)

    /** Marker segments between SOI and SOS. Stops at SOS, where entropy-coded data begins. */
    private fun findSegments(jpeg: ByteArray): List<Segment> {
        require(jpeg.size >= 4 && jpeg.u8(0) == 0xFF && jpeg.u8(1) == 0xD8) { "Not a JPEG" }
        val segments = mutableListOf<Segment>()
        var pos = 2
        while (pos + 4 <= jpeg.size && jpeg.u8(pos) == 0xFF) {
            val marker = jpeg.u8(pos + 1)
            if (marker == SOS) break
            val length = (jpeg.u8(pos + 2) shl 8) or jpeg.u8(pos + 3)
            segments += Segment(marker, pos, pos + 2 + length)
            pos += 2 + length
        }
        return segments
    }

    private fun Segment.isExif(jpeg: ByteArray): Boolean =
        marker == APP1 && end - start > 10 && EXIF_HEADER.indices.all { jpeg[start + 4 + it] == EXIF_HEADER[it] }

    /** Sets IFD0's "next IFD" offset to 0, so readers no longer find IFD1 (the thumbnail). */
    private fun unlinkThumbnail(app1: ByteArray) {
        val tiff = 4 + EXIF_HEADER.size
        val littleEndian = app1[tiff] == 'I'.code.toByte()
        fun read16(at: Int) = if (littleEndian) app1.u8(at) or (app1.u8(at + 1) shl 8) else (app1.u8(at) shl 8) or app1.u8(at + 1)
        fun read32(at: Int) = if (littleEndian) read16(at) or (read16(at + 2) shl 16) else (read16(at) shl 16) or read16(at + 2)

        val ifd0 = tiff + read32(tiff + 4)
        if (ifd0 + 2 > app1.size) return
        val nextIfdPointer = ifd0 + 2 + read16(ifd0) * 12
        if (nextIfdPointer + 4 > app1.size) return
        for (i in 0 until 4) app1[nextIfdPointer + i] = 0
    }

    private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF

    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val SOS = 0xDA
    private val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
}
