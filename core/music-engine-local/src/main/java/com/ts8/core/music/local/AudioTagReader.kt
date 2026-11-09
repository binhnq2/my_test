package com.ts8.core.music.local

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Metadata (title, artist, album, duration) read straight out of the container.
 *
 * Why not `MediaMetadataRetriever`? On this firmware it is a thin wrapper over
 * a vendor extractor, it costs 20–80 ms per file, and on some OGG files it
 * returns null instead of the tags. A first scan of a 2 000-track USB stick
 * through it takes over a minute *and* blocks the metadata thread the whole
 * time. Reading the container headers directly costs 1–3 ms per file, is pure
 * Kotlin (so it is unit-tested on the workstation against synthetic files with
 * known contents), and never returns a half-decoded tag.
 *
 * The Android-side scanner still keeps `MediaMetadataRetriever` as a fallback
 * for files this reader cannot parse — see `MediaStoreScanner`. Honest layering:
 * fast path first, platform path when needed, and "unknown" when both fail.
 *
 * **No file is read end to end.** Every path seeks to the header and, for
 * formats whose duration lives in a trailing page (OGG), the last 32 KiB.
 */
object AudioTagReader {

    /** Header sizes are bounded so a corrupt length field cannot exhaust memory. */
    private const val MAX_ID3_TAG_BYTES = 8 * 1024 * 1024
    private const val MAX_VORBIS_COMMENT_BYTES = 4 * 1024 * 1024
    private const val MAX_MP4_ATOM_DEPTH = 8
    private const val OGG_HEAD_BYTES = 8 * 1024
    private const val OGG_TAIL_BYTES = 32 * 1024

    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val trackNumber: Int = 0,
        val year: Int = 0,
        /** 0 when the container does not state a duration. */
        val durationMs: Long = 0
    ) {
        val isEmpty: Boolean
            get() = title == null && artist == null && album == null && durationMs == 0L
    }

    /**
     * Reads tags from [file].
     *
     * The format is detected by magic bytes rather than by extension: the TS8
     * sees plenty of `.mp3` files that are really AAC in an ADTS stream, and
     * trusting the name gives a puzzled driver an empty title.
     *
     * @return tags, or null when the file is unreadable or not a container this
     *         reader understands. Never throws.
     */
    fun read(file: File): Tags? {
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                when (detect(raf)) {
                    Container.MP3 -> readMp3(raf)
                    Container.FLAC -> readFlac(raf)
                    Container.OGG -> readOgg(raf)
                    Container.WAV -> readWav(raf)
                    Container.MP4 -> readMp4(raf)
                    Container.UNKNOWN -> null
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            // A malformed length field can produce a negative seek or an
            // absurd allocation; that is data, not a reason to stop scanning.
            null
        }
    }

    // -- format detection ---------------------------------------------------

    private enum class Container { MP3, FLAC, OGG, WAV, MP4, UNKNOWN }

    private fun detect(raf: RandomAccessFile): Container {
        if (raf.length() < 8) return Container.UNKNOWN
        val head = ByteArray(12)
        raf.seek(0)
        val n = readFully(raf, head)
        if (n < 8) return Container.UNKNOWN

        // "ID3" — but an MP3 without an ID3 tag starts with a frame sync.
        if (head.startsWith("ID3", 0)) return Container.MP3
        // MPEG audio frame sync: eleven set bits.
        if (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) return Container.MP3
        if (head.startsWith("fLaC", 0)) return Container.FLAC
        if (head.startsWith("OggS", 0)) return Container.OGG
        if (head.startsWith("RIFF", 0) && head.startsWith("WAVE", 8)) return Container.WAV
        if (head.startsWith("ftyp", 4)) return Container.MP4
        return Container.UNKNOWN
    }

    private fun ByteArray.startsWith(text: String, offset: Int): Boolean {
        if (offset < 0 || offset + text.length > size) return false
        for (i in text.indices) {
            if (this[offset + i] != text[i].code.toByte()) return false
        }
        return true
    }

    // -- MP3 / ID3v2 --------------------------------------------------------

    /**
     * ID3v2 header, then the first audio frame.
     *
     * Duration comes from the Xing/Info frame when present (exact, and the only
     * way to be right for VBR files), otherwise from a bitrate estimate scaled
     * by the audio payload size (exact for CBR, close for VBR).
     */
    private fun readMp3(raf: RandomAccessFile): Tags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var track = 0
        var year = 0
        var audioStart = 0L

        val header = ByteArray(10)
        raf.seek(0)
        if (raf.read(header) == 10 && header.startsWith("ID3", 0)) {
            val version = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            // Syncsafe integer: seven bits per byte, high bit always clear.
            val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                ((header[7].toInt() and 0x7F) shl 14) or
                ((header[8].toInt() and 0x7F) shl 7) or
                (header[9].toInt() and 0x7F)

            var bodyStart = 10L
            if (flags and 0x40 != 0) {
                // Extended header: its own version-dependent size field.
                val ext = ByteArray(4)
                raf.seek(bodyStart)
                if (raf.read(ext) == 4) {
                    val extSize = if (version >= 4) syncsafe(ext, 0) else readIntBE(ext, 0, 4)
                    bodyStart += 4 + extSize
                }
            }

            if (tagSize in 1..MAX_ID3_TAG_BYTES && bodyStart + tagSize <= raf.length()) {
                val body = ByteArray(tagSize)
                raf.seek(bodyStart)
                val n = readFully(raf, body)
                if (n > 0) {
                    // ID3v2.2 uses three-character frame ids and a three-byte
                    // size field. Rather than a second parser for a format
                    // almost no real file uses, tag parsing is skipped there and
                    // the scanner falls back to the platform retriever; the
                    // duration below still works either way.
                    if (version != 2) {
                        val map = parseId3Frames(body.copyOf(n), version)
                        title = map["TIT2"]
                        artist = map["TPE1"]
                        album = map["TALB"]
                        track = map["TRCK"]?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
                        year = map["TDRC"]?.take(4)?.toIntOrNull()
                            ?: map["TYER"]?.trim()?.toIntOrNull() ?: 0
                    }
                }
                audioStart = bodyStart + tagSize
            } else {
                // Corrupt size: let frame synchronisation find the audio.
                audioStart = 10L
            }
        }

        val audio = readMp3Audio(raf, audioStart)
        return Tags(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            artist = artist?.trim()?.takeIf { it.isNotEmpty() },
            album = album?.trim()?.takeIf { it.isNotEmpty() },
            trackNumber = track,
            year = year,
            durationMs = audio?.let { if (it.xingDurationMs > 0) it.xingDurationMs else it.durationMs } ?: 0L
        )
    }

    private data class Mp3Audio(val durationMs: Long, val xingDurationMs: Long)

    private fun readMp3Audio(raf: RandomAccessFile, start: Long): Mp3Audio? {
        val len = raf.length()
        var pos = start.coerceIn(0L, len)
        var probes = 0
        while (pos + 4 <= len && probes < 256) {
            raf.seek(pos)
            val b = ByteArray(4)
            if (readFully(raf, b) < 4) return null
            val frame = parseFrameHeader(b)
            if (frame == null) {
                // Not a sync word — resynchronise one byte at a time. Bounded so
                // a garbage file does not turn into a linear scan of the disk.
                pos++
                probes++
                continue
            }

            var xingDuration = 0L
            val audioBytes = (len - pos).coerceAtLeast(0)
            val sideBytes = minOf(frame.frameBytes - 4, 200)
            if (sideBytes > 16) {
                val side = ByteArray(sideBytes)
                raf.seek(pos + 4)
                val n = readFully(raf, side)
                if (n > 12) {
                    val at = findAscii(side, n, "Xing", "Info")
                    if (at >= 0 && at + 12 <= n) {
                        val flags = readIntBE(side, at + 4, 4)
                        if (flags and 0x1 != 0) {
                            val frames = readIntBE(side, at + 8, 4).toLong() and 0xFFFFFFFFL
                            if (frames > 0) {
                                xingDuration = frames * frame.samplesPerFrame * 1000L /
                                    frame.sampleRate
                            }
                        }
                    }
                }
            }

            val estimated = if (frame.bitrateBps > 0) {
                audioBytes * 8000L / frame.bitrateBps
            } else {
                0L
            }
            return Mp3Audio(estimated, xingDuration)
        }
        return null
    }

    /** First index of any of [needles] within `buf[0 until len]`, or -1. */
    private fun findAscii(buf: ByteArray, len: Int, vararg needles: String): Int {
        for (i in 0 until len - 4) {
            for (needle in needles) {
                if (i + needle.length > len) continue
                var hit = true
                for (k in needle.indices) {
                    if (buf[i + k] != needle[k].code.toByte()) { hit = false; break }
                }
                if (hit) return i
            }
        }
        return -1
    }

    private data class FrameHeader(
        val bitrateBps: Long,
        val sampleRate: Int,
        val samplesPerFrame: Int,
        val frameBytes: Int
    )

    /** MPEG audio frame header (ISO/IEC 11172-3 and 13818-3). */
    private fun parseFrameHeader(b: ByteArray): FrameHeader? {
        val h = readIntBE(b, 0, 4)
        if (h and 0xFFE00000.toInt() != 0xFFE00000.toInt()) return null

        val versionBits = (h ushr 19) and 0x3
        val layerBits = (h ushr 17) and 0x3
        val bitrateIndex = (h ushr 12) and 0xF
        val sampleIndex = (h ushr 10) and 0x3
        val padding = (h ushr 9) and 0x1

        // 0x1 is the reserved MPEG version; 0 is MPEG 2.5.
        if (versionBits == 1) return null
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleIndex == 3) return null

        val mpeg1 = versionBits == 3
        val layer = when (layerBits) {
            1 -> 3
            2 -> 2
            3 -> 1
            else -> return null
        }

        val sampleRate = when (sampleIndex) {
            0 -> if (mpeg1) 44100 else if (versionBits == 2) 22050 else 11025
            1 -> if (mpeg1) 48000 else if (versionBits == 2) 24000 else 12000
            2 -> if (mpeg1) 32000 else if (versionBits == 2) 16000 else 8000
            else -> return null
        }

        val bitrateKbps: Int = if (mpeg1) {
            when (layer) {
                1 -> MPEG1_LAYER1
                2 -> MPEG1_LAYER2
                else -> MPEG1_LAYER3
            }.getOrElse(bitrateIndex) { return null }
        } else {
            // Parenthesised: `if (…) A else B.method()` binds the method call to
            // B only, which silently turned the two table types into a common
            // Serializable supertype.
            (if (layer == 1) MPEG2_LAYER1 else MPEG2_LAYER23)
                .getOrElse(bitrateIndex) { return null }
        }

        val samplesPerFrame = when (layer) {
            1 -> 384
            2 -> 1152
            else -> if (mpeg1) 1152 else 576
        }

        // Layer I frames are counted in 4-byte slots; II and III in bytes.
        val frameBytes = if (layer == 1) {
            (12L * bitrateKbps * 1000L / sampleRate + padding).toInt() * 4
        } else {
            (samplesPerFrame / 8L * bitrateKbps * 1000L / sampleRate + padding).toInt()
        }

        return FrameHeader(
            bitrateBps = bitrateKbps * 1000L,
            sampleRate = sampleRate,
            samplesPerFrame = samplesPerFrame,
            frameBytes = frameBytes.coerceAtLeast(4)
        )
    }

    private val MPEG1_LAYER1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val MPEG1_LAYER2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val MPEG1_LAYER3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_LAYER1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val MPEG2_LAYER23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /**
     * Walks ID3v2.3 / v2.4 frames.
     *
     * The two versions differ in the frame size encoding (v2.4 is syncsafe) and
     * in which text encodings are legal, so both are handled explicitly.
     */
    private fun parseId3Frames(body: ByteArray, version: Int): Map<String, String> {
        val out = HashMap<String, String>(8)
        var pos = 0
        while (pos + 10 <= body.size) {
            // A zero-filled padding region ends the frame list.
            if (body[pos] == 0.toByte()) break
            for (k in 0 until 4) {
                val c = body[pos + k].toInt() and 0xFF
                if (c !in 0x41..0x5A && c !in 0x30..0x39) return out
            }
            val id = String(body, pos, 4, Charsets.ISO_8859_1)
            val size = if (version >= 4) syncsafe(body, pos + 4) else readIntBE(body, pos + 4, 4)
            if (size <= 0 || pos + 10 + size > body.size) break

            if (out[id] == null) {
                val text = decodeId3Text(body, pos + 10, size)
                if (!text.isNullOrBlank()) out[id] = text
            }
            pos += 10 + size
        }
        return out
    }

    /** Decodes one text frame: first byte is the encoding, the rest is payload. */
    private fun decodeId3Text(body: ByteArray, offset: Int, size: Int): String? {
        if (size < 2 || offset + size > body.size) return null
        val encoding = body[offset].toInt() and 0xFF
        val start = offset + 1
        val length = size - 1
        val text = when (encoding) {
            0 -> String(body, start, length, Charsets.ISO_8859_1)
            1 -> decodeUtf16(body, start, length, littleEndian = true)
            2 -> decodeUtf16(body, start, length, littleEndian = false)
            3 -> String(body, start, length, Charsets.UTF_8)
            else -> null
        } ?: return null
        // Multi-value frames separate with NUL; the first value is enough for
        // a launcher row and keeps invisible characters out of the UI.
        return text.substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }
    }

    private fun decodeUtf16(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        littleEndian: Boolean
    ): String? {
        var start = offset
        var end = offset + length
        if (end - start < 2) return null
        var le = littleEndian
        // A BOM overrides the frame's declared byte order.
        val b0 = bytes[start].toInt() and 0xFF
        val b1 = bytes[start + 1].toInt() and 0xFF
        if (b0 == 0xFF && b1 == 0xFE) { le = true; start += 2 }
        else if (b0 == 0xFE && b1 == 0xFF) { le = false; start += 2 }
        val usable = end - start
        if (usable < 2) return null
        val buf = ByteBuffer.wrap(bytes, start, usable and 1.inv())
            .order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        return buf.asCharBuffer().toString()
    }

    // -- FLAC ---------------------------------------------------------------

    /**
     * FLAC metadata blocks. `STREAMINFO` carries the exact duration — total
     * samples and sample rate, no estimation — and `VORBIS_COMMENT` the tags.
     */
    private fun readFlac(raf: RandomAccessFile): Tags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var track = 0
        var year = 0
        var durationMs = 0L

        raf.seek(4) // past "fLaC"
        var last = false
        var guard = 0
        while (!last && guard++ < 64) {
            val header = ByteArray(4)
            if (readFully(raf, header) < 4) break
            last = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            when (type) {
                0 -> {
                    // STREAMINFO is exactly 34 bytes; anything else is corrupt.
                    if (length != 34) { raf.skipBytes(length); continue }
                    val info = ByteArray(34)
                    if (readFully(raf, info) < 18) return Tags(durationMs = durationMs)
                    // Bytes 10..17 hold sampleRate(20) | channels(3) | bps(5) |
                    // totalSamples(36).
                    val b10 = info[10].toLong() and 0xFF
                    val b11 = info[11].toLong() and 0xFF
                    val b12 = info[12].toLong() and 0xFF
                    val b13 = info[13].toLong() and 0xFF
                    val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 shr 4)
                    val totalSamples = ((b13 and 0x0F) shl 32) or
                        ((info[14].toLong() and 0xFF) shl 24) or
                        ((info[15].toLong() and 0xFF) shl 16) or
                        ((info[16].toLong() and 0xFF) shl 8) or
                        (info[17].toLong() and 0xFF)
                    if (sampleRate > 0 && totalSamples > 0) {
                        durationMs = totalSamples * 1000L / sampleRate
                    }
                }
                4 -> {
                    if (length in 1..MAX_VORBIS_COMMENT_BYTES) {
                        val block = ByteArray(length)
                        val n = readFully(raf, block)
                        if (n > 0) {
                            val f = parseVorbisComments(block, n)
                            title = f["TITLE"] ?: f["TIT2"]
                            artist = f["ARTIST"] ?: f["TPE1"]
                            album = f["ALBUM"] ?: f["TALB"]
                            track = f["TRACKNUMBER"]?.toIntOrNull()
                                ?: f["TRACK"]?.toIntOrNull() ?: 0
                            year = f["DATE"]?.take(4)?.toIntOrNull() ?: 0
                        }
                    } else {
                        raf.skipBytes(length)
                    }
                }
                else -> raf.skipBytes(length)
            }
        }

        return Tags(
            title = title, artist = artist, album = album,
            trackNumber = track, year = year, durationMs = durationMs
        )
    }

    /**
     * Vorbis comment block, shared by FLAC and OGG.
     *
     * Layout: vendor length (LE32), vendor string, comment count (LE32), then
     * for each comment a length (LE32) followed by `KEY=value`.
     */
    private fun parseVorbisComments(
        block: ByteArray,
        length: Int,
        offset: Int = 0
    ): Map<String, String> {
        val out = HashMap<String, String>(8)
        if (length < 8 || offset < 0) return out
        // `end` rather than `length` so callers can hand in a window inside a
        // larger buffer (the OGG path does) without copying it first.
        val end = minOf(offset + length, block.size)
        var p = offset
        val vendorLen = readIntLE(block, p, 4)
        if (vendorLen < 0 || p + 4 + vendorLen > end) return out
        p += 4 + vendorLen
        if (p + 4 > end) return out
        val count = readIntLE(block, p, 4)
        p += 4
        if (count < 0 || count > 4096) return out
        var i = 0
        while (i < count && p + 4 <= end) {
            val len = readIntLE(block, p, 4)
            p += 4
            if (len < 0 || p + len > end) break
            val entry = String(block, p, len, Charsets.UTF_8)
            p += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                if (!out.containsKey(key)) out[key] = entry.substring(eq + 1)
            }
            i++
        }
        return out
    }

    // -- OGG (Vorbis / Opus) ------------------------------------------------

    /**
     * OGG duration comes from the granule position of the **last** page, which
     * is why this reader seeks to the tail instead of walking every page: a
     * two-hour podcast is a hundred thousand pages.
     */
    private fun readOgg(raf: RandomAccessFile): Tags {
        var sampleRate = 0
        var preSkip = 0L
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var track = 0
        var year = 0

        val headSize = minOf(raf.length(), OGG_HEAD_BYTES.toLong()).toInt()
        val head = ByteArray(headSize)
        raf.seek(0)
        val headRead = readFully(raf, head)
        if (headRead < 32) return Tags()

        // The identification packet starts after the first page header. Page
        // headers vary in length with the segment table, so the packet magic is
        // located rather than assumed to sit at a fixed offset.
        val idAt = findAscii(head, minOf(headRead, 512), "OpusHead", "\u0001vorbis")
        val isOpus = idAt >= 0 && head.startsWith("OpusHead", idAt)

        if (isOpus) {
            // Opus always decodes at 48 kHz regardless of the source rate.
            sampleRate = 48000
            if (idAt + 12 <= headRead) {
                preSkip = ((head[idAt + 10].toLong() and 0xFF)) or
                    ((head[idAt + 11].toLong() and 0xFF) shl 8)
            }
        } else if (idAt >= 0) {
            // Vorbis identification packet: version(4) channels(1) rate(4 LE).
            val rateAt = idAt + 1 + 11
            if (rateAt + 4 <= headRead) sampleRate = readIntLE(head, rateAt, 4)
        }

        // The comment packet follows in the second page; finding its magic in
        // the head window covers both formats without page walking.
        val commentAt = findAscii(head, headRead, "OpusTags", "\u0003vorbis")
        if (commentAt >= 0) {
            val payloadAt = commentAt + (if (head.startsWith("OpusTags", commentAt)) 8 else 7)
            if (payloadAt < headRead) {
                val f = parseVorbisComments(head, headRead - payloadAt, payloadAt)
                title = f["TITLE"]
                artist = f["ARTIST"]
                album = f["ALBUM"]
                track = f["TRACKNUMBER"]?.toIntOrNull() ?: 0
                year = f["DATE"]?.take(4)?.toIntOrNull() ?: 0
            }
        }

        // Last page granule.
        val len = raf.length()
        val tailSize = minOf(len, OGG_TAIL_BYTES.toLong()).toInt()
        val tail = ByteArray(tailSize)
        raf.seek(len - tailSize)
        val tailRead = readFully(raf, tail)
        var lastPage = -1
        var i = tailRead - 27
        while (i >= 0) {
            if (tail[i] == 'O'.code.toByte() && tail[i + 1] == 'g'.code.toByte() &&
                tail[i + 2] == 'g'.code.toByte() && tail[i + 3] == 'S'.code.toByte()
            ) {
                lastPage = i
                break
            }
            i--
        }

        var durationMs = 0L
        if (lastPage >= 0 && sampleRate > 0 && lastPage + 14 <= tailRead) {
            // Granule position is a signed 64-bit LE at offset 6 of the page.
            val granule = readLongLE(tail, lastPage + 6)
            val samples = granule - preSkip
            if (samples > 0) durationMs = samples * 1000L / sampleRate
        }

        return Tags(
            title = title, artist = artist, album = album,
            trackNumber = track, year = year, durationMs = durationMs
        )
    }

    // -- WAV ----------------------------------------------------------------

    /** RIFF chunks: `fmt ` gives the byte rate, `data` gives the payload size. */
    private fun readWav(raf: RandomAccessFile): Tags {
        var byteRate = 0L
        var dataSize = -1L
        var pos = 12L
        val len = raf.length()
        val chunk = ByteArray(8)
        var guard = 0
        while (pos + 8 <= len && guard++ < 64) {
            raf.seek(pos)
            if (readFully(raf, chunk) < 8) break
            val id = String(chunk, 0, 4, Charsets.ISO_8859_1)
            val size = readIntLE(chunk, 4, 4).toLong() and 0xFFFFFFFFL

            if (id == "fmt " && size >= 12) {
                val fmt = ByteArray(minOf(size, 40L).toInt())
                if (readFully(raf, fmt) >= 12) {
                    byteRate = readIntLE(fmt, 8, 4).toLong() and 0xFFFFFFFFL
                }
            } else if (id == "data") {
                dataSize = size
                break
            }
            // Chunks are word-aligned.
            pos += 8 + size + (size and 1L)
        }

        val duration = if (byteRate > 0 && dataSize > 0) dataSize * 1000L / byteRate else 0L
        return Tags(durationMs = duration)
    }

    // -- MP4 / M4A ----------------------------------------------------------

    /**
     * MP4 atom walk. `mvhd` holds the duration; tags live in
     * `moov/udta/meta/ilst`. Atoms are located by their own size field, never by
     * a fixed offset, because `mdat` precedes `moov` in some files.
     */
    private fun readMp4(raf: RandomAccessFile): Tags {
        var movieDurationMs = 0L
        var itemTags: Tags? = null

        fun walk(start: Long, end: Long, depth: Int) {
            if (depth > MAX_MP4_ATOM_DEPTH) return
            var pos = start
            val header = ByteArray(8)
            var guard = 0
            while (pos + 8 <= end && guard++ < 4096) {
                raf.seek(pos)
                if (readFully(raf, header) < 8) return
                var size = readIntBE(header, 0, 4).toLong() and 0xFFFFFFFFL
                val type = String(header, 4, 4, Charsets.ISO_8859_1)
                var headerLen = 8L
                if (size == 1L) {
                    val ext = ByteArray(8)
                    if (readFully(raf, ext) < 8) return
                    size = readLongBE(ext, 0, 8)
                    headerLen = 16L
                } else if (size == 0L) {
                    size = end - pos
                }
                if (size < headerLen) return
                val bodyStart = pos + headerLen
                val bodyEnd = (pos + size).coerceAtMost(end)

                when (type) {
                    "moov", "udta" -> walk(bodyStart, bodyEnd, depth + 1)
                    // `meta` is a full atom plus a 4-byte version/flags prefix.
                    "meta" -> walk(bodyStart + 4, bodyEnd, depth + 1)
                    "mvhd" -> movieDurationMs = readMvhd(raf, bodyStart, bodyEnd)
                    "ilst" -> itemTags = readIlst(raf, bodyStart, bodyEnd)
                }
                pos += size
            }
        }

        walk(0, raf.length(), 0)

        val tags = itemTags ?: Tags()
        return Tags(
            title = tags.title, artist = tags.artist, album = tags.album,
            trackNumber = tags.trackNumber, year = tags.year,
            durationMs = if (tags.durationMs > 0) tags.durationMs else movieDurationMs
        )
    }

    private fun readMvhd(raf: RandomAccessFile, start: Long, end: Long): Long {
        val size = minOf(end - start, 120L).toInt()
        if (size < 20) return 0
        val buf = ByteArray(size)
        raf.seek(start)
        val n = readFully(raf, buf)
        if (n < 20) return 0
        return if ((buf[0].toInt() and 0xFF) == 1) {
            if (n < 32) return 0
            val timescale = readIntBE(buf, 20, 4).toLong() and 0xFFFFFFFFL
            val duration = readLongBE(buf, 24, 8)
            if (timescale > 0 && duration > 0) duration * 1000L / timescale else 0L
        } else {
            val timescale = readIntBE(buf, 12, 4).toLong() and 0xFFFFFFFFL
            val duration = readIntBE(buf, 16, 4).toLong() and 0xFFFFFFFFL
            if (timescale > 0 && duration > 0) duration * 1000L / timescale else 0L
        }
    }

    private fun readIlst(raf: RandomAccessFile, start: Long, end: Long): Tags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var track = 0
        var year = 0

        var pos = start
        val header = ByteArray(8)
        var guard = 0
        while (pos + 8 <= end && guard++ < 128) {
            raf.seek(pos)
            if (readFully(raf, header) < 8) break
            val size = readIntBE(header, 0, 4).toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size < 8) break
            val bodyStart = pos + 8
            val bodyEnd = (pos + size).coerceAtMost(end)

            when (type) {
                "©nam" -> title = readIlstText(raf, bodyStart, bodyEnd)
                "©ART" -> artist = readIlstText(raf, bodyStart, bodyEnd)
                "©alb" -> album = readIlstText(raf, bodyStart, bodyEnd)
                "©day" -> year = readIlstText(raf, bodyStart, bodyEnd)
                    ?.take(4)?.toIntOrNull() ?: 0
                "trkn" -> track = readIlstTrkn(raf, bodyStart, bodyEnd)
            }
            pos += size
        }
        return Tags(title, artist, album, track, year, 0)
    }

    /** `ilst` entry → `data` atom → UTF-8 payload after 8 bytes of type/locale. */
    private fun readIlstText(raf: RandomAccessFile, start: Long, end: Long): String? {
        val payload = readDataAtom(raf, start, end) ?: return null
        if (payload.size <= 8) return null
        return String(payload, 8, payload.size - 8, Charsets.UTF_8)
            .trim().takeIf { it.isNotEmpty() }
    }

    private fun readIlstTrkn(raf: RandomAccessFile, start: Long, end: Long): Int {
        val payload = readDataAtom(raf, start, end) ?: return 0
        // `trkn` payload: 2 reserved, 2 track number, 2 total, 2 reserved.
        if (payload.size < 12) return 0
        return ((payload[10].toInt() and 0xFF) shl 8) or (payload[11].toInt() and 0xFF)
    }

    private fun readDataAtom(raf: RandomAccessFile, start: Long, end: Long): ByteArray? {
        var pos = start
        val header = ByteArray(8)
        var guard = 0
        while (pos + 8 <= end && guard++ < 8) {
            raf.seek(pos)
            if (readFully(raf, header) < 8) return null
            val size = readIntBE(header, 0, 4).toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size < 8) return null
            val bodyStart = pos + 8
            val bodyEnd = (pos + size).coerceAtMost(end)
            if (type == "data" && bodyEnd > bodyStart) {
                val buf = ByteArray((bodyEnd - bodyStart).toInt())
                raf.seek(bodyStart)
                val n = readFully(raf, buf)
                return if (n > 0) buf.copyOf(n) else null
            }
            pos += size
        }
        return null
    }

    // -- primitives ---------------------------------------------------------

    /** Syncsafe 28-bit integer used by ID3v2 sizes. */
    private fun syncsafe(b: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > b.size) return 0
        return ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)
    }

    private fun readIntBE(b: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length > 4 || offset + length > b.size) return 0
        var v = 0
        for (i in 0 until length) v = (v shl 8) or (b[offset + i].toInt() and 0xFF)
        return v
    }

    private fun readIntLE(b: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length > 4 || offset + length > b.size) return 0
        var v = 0
        for (i in length - 1 downTo 0) v = (v shl 8) or (b[offset + i].toInt() and 0xFF)
        return v
    }

    private fun readLongBE(b: ByteArray, offset: Int, length: Int): Long {
        if (offset < 0 || length > 8 || offset + length > b.size) return 0
        var v = 0L
        for (i in 0 until length) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
        return v
    }

    private fun readLongLE(b: ByteArray, offset: Int, length: Int = 8): Long {
        if (offset < 0 || length > 8 || offset + length > b.size) return 0
        var v = 0L
        for (i in length - 1 downTo 0) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
        return v
    }

    /** Reads as much as fits; returns the byte count actually read. */
    private fun readFully(raf: RandomAccessFile, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = raf.read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }
}
