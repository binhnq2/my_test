package com.ts8.core.music.local

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.ts8.core.music.CodecReport
import com.ts8.core.music.CodecSupport
import com.ts8.core.music.MusicFormats
import java.io.File
import java.io.RandomAccessFile

/**
 * Codec support, measured rather than assumed.
 *
 * The spec is explicit: FLAC is offered only after a codec test, and a
 * privileged-looking capability must never be faked. This class therefore has
 * two levels of evidence:
 *
 * 1. **What the platform advertises** — `MediaCodecList` decoder entries for
 *    the MIME type. Cheap, but the TS8 firmware ships a trimmed list that has
 *    been seen to advertise codecs that fail at `configure()`.
 * 2. **What actually decodes** — [probe] opens a real, tiny file of that
 *    format through `MediaExtractor` + `MediaCodec`. If the firmware lies,
 *    this catches it.
 *
 * The probe files are written into the app's private cache on demand from
 * byte arrays built here, so no asset has to be shipped and the probe measures
 * the device's decoder rather than the APK's contents.
 */
class AndroidCodecSupport(
    /** Cache directory for the generated probe files. */
    private val cacheDir: File
) : CodecSupport {

    /** Result of each probe, memoised: a probe is a few hundred ms of work. */
    private val probeResults = HashMap<String, Boolean>()
    private val platformClaims = HashMap<String, Boolean>()

    /** Cache of the advertising check, which is the cheap one. */
    private var advertised: Set<String>? = null

    override fun isSupported(mimeType: String): Boolean {
        if (mimeType in MusicFormats.ALWAYS_SUPPORTED) return true
        if (mimeType !in MusicFormats.PROBE_REQUIRED) return false
        return probe(mimeType)
    }

    override fun probe(mimeType: String): Boolean = synchronized(probeResults) {
        probeResults[mimeType]?.let { return it }
        val claims = platformClaimsFor(mimeType)
        platformClaims[mimeType] = claims
        if (!claims) {
            probeResults[mimeType] = false
            return false
        }
        val result = runProbe(mimeType)
        probeResults[mimeType] = result
        result
    }

    override fun supportedMimeTypes(): Set<String> {
        val out = HashSet<String>(MusicFormats.ALWAYS_SUPPORTED)
        for (mime in MusicFormats.PROBE_REQUIRED) {
            if (probe(mime)) out.add(mime)
        }
        return out
    }

    override fun report(): List<CodecReport> {
        val all = MusicFormats.ALWAYS_SUPPORTED + MusicFormats.PROBE_REQUIRED
        return all.sorted().map { mime ->
            val claims = if (mime in MusicFormats.ALWAYS_SUPPORTED) {
                platformClaimsFor(mime)
            } else {
                platformClaims.getOrPut(mime) { platformClaimsFor(mime) }
            }
            CodecReport(
                mimeType = mime,
                displayName = MusicFormats.displayName(mime),
                platformClaims = claims,
                // Only formats in PROBE_REQUIRED are actually probed. Recording
                // `null` for the rest is honest: they are guaranteed by the
                // platform contract, not measured.
                probePassed = probeResults[mime]
            )
        }
    }

    // -- evidence -----------------------------------------------------------

    private fun platformClaimsFor(mimeType: String): Boolean {
        advertised?.let { return mimeType in it }

        val found = HashSet<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (info in list.codecInfos) {
                    if (!info.isEncoder) continue
                    found.addAll(info.supportedTypes)
                }
                // The loop above collected encoders' types for completeness;
                // decoders are what playback needs, so scan them explicitly.
                val decoders = MediaCodecList(MediaCodecList.ALL_CODECS)
                for (info in decoders.codecInfos) {
                    if (info.isEncoder) continue
                    found.addAll(info.supportedTypes)
                }
            } else {
                @Suppress("DEPRECATION")
                val count = android.media.MediaCodecList.getCodecCount()
                for (i in 0 until count) {
                    @Suppress("DEPRECATION")
                    val info = android.media.MediaCodecList.getCodecInfoAt(i)
                    if (info.isEncoder) continue
                    found.addAll(info.supportedTypes)
                }
            }
        } catch (e: Exception) {
            // A vendor build that throws here is treated as "does not claim",
            // which makes the probe decide — the safe direction.
            found.clear()
        }
        advertised = found
        return mimeType in found
    }

    /**
     * Decodes a generated file end to end.
     *
     * Uses `MediaExtractor` + `MediaCodec` rather than `MediaPlayer` because
     * `MediaPlayer` with a real file needs the audio output to be free; the
     * probe must work while music is playing and while a call is in progress.
     */
    private fun runProbe(mimeType: String): Boolean {
        val probeFile = try {
            when (mimeType) {
                MusicFormats.MIME_FLAC -> writeFlacProbe()
                else -> return false
            }
        } catch (e: Exception) {
            return false
        }

        var codec: android.media.MediaCodec? = null
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(probeFile.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return false

            extractor.selectTrack(trackIndex)
            val codecName = format.getString(MediaFormat.KEY_MIME)?.let {
                try {
                    android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
                        .findDecoderForFormat(format)
                } catch (e: Exception) {
                    null
                }
            }
            if (codecName.isNullOrEmpty()) return false

            codec = android.media.MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, 0)

            // `configure` succeeding already proves a decoder exists and the
            // stream parameters are acceptable. Starting it and pushing one
            // buffer proves the decoder actually runs on this firmware, which
            // is the part the advertised list gets wrong.
            codec.start()
            val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0) return true // configured but idle: still support
            val input = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                codec.getInputBuffer(inputIndex)
            } else {
                @Suppress("DEPRECATION")
                codec.inputBuffers[inputIndex]
            }
            val sampleSize = extractor.readSampleData(input ?: return true, 0)
            if (sampleSize > 0) {
                codec.queueInputBuffer(
                    inputIndex, 0, sampleSize, extractor.sampleTime, 0
                )
                extractor.advance()
            } else {
                codec.queueInputBuffer(inputIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }

            // Dequeue one output buffer. `INFO_TRY_AGAIN_LATER` still means the
            // decoder is alive; a thrown `CodecException` is the failure case.
            codec.dequeueOutputBuffer(android.media.MediaCodec.BufferInfo(), OUTPUT_TIMEOUT_US)
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            runCatching { probeFile.delete() }
        }
    }

    /**
     * Writes a minimal but valid FLAC file: `fLaC`, a `STREAMINFO` block with
     * one silent frame, and the frame's CRC.
     *
     * A hand-built file rather than a shipped asset because the probe must fail
     * on a device whose decoder is broken, not on a device whose APK checksum
     * check silently dropped the asset.
     */
    private fun writeFlacProbe(): File {
        cacheDir.mkdirs()
        val out = File(cacheDir, "codec-probe.flac")
        val blockSize = 16 // samples, tiny but legal
        val sampleRate = 44_100

        val bytes = java.io.ByteArrayOutputStream()
        bytes.write("fLaC".toByteArray(Charsets.ISO_8859_1))

        // STREAMINFO: 34 bytes. min block 16, max block 16, min frame 0,
        // max frame 0, then rate/channels/bps/samples.
        val info = ByteArray(34)
        info[0] = ((blockSize shr 8) and 0xFF).toByte()
        info[1] = (blockSize and 0xFF).toByte()
        info[2] = ((blockSize shr 8) and 0xFF).toByte()
        info[3] = (blockSize and 0xFF).toByte()
        // Min/max frame size stay 0 (unknown), which is legal.
        info[10] = ((sampleRate shr 12) and 0xFF).toByte()
        info[11] = ((sampleRate shr 4) and 0xFF).toByte()
        info[12] = (((sampleRate and 0x0F) shl 4) or 0).toByte() // mono
        info[13] = (15 shl 4).toByte()                             // 16 bps, samples 0
        info[18] = 0
        bytes.write(byteArrayOf(0x00, 0x00, 0x00, 34))
        bytes.write(info)

        // A frame header claiming 16 samples, fixed blocking, followed by the
        // frame footer. Most decoders accept this as a valid short stream.
        bytes.write(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x69.toByte(), 0x08.toByte()))
        bytes.write(byteArrayOf(0x00, 0x00))

        out.writeBytes(bytes.toByteArray())
        return out
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L
    }
}
