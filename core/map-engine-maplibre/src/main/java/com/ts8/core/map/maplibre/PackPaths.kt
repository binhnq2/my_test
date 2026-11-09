package com.ts8.core.map.maplibre

import java.io.File

/**
 * Builds the URLs MapLibre Native is handed for local pack data.
 *
 * **Why this is not string concatenation at the call site.** It used to be, and
 * it produced two silent failures that no amount of UI could explain:
 *
 * 1. The glyph URL was emitted with no scheme at all
 *    (`/data/.../fonts/{fontstack}/{range}.pbf`). MapLibre's `LocalFileSource`
 *    accepts **only** `file://` (see `lfs.cpp`, `acceptsURL`), and every other
 *    source rejects the URL too, so the chain ends in
 *    `"Unsupported resource request."` and no label is ever drawn.
 * 2. A pack path containing a space or a non-ASCII character was pasted into
 *    `mbtiles://` raw. The native side percent-decodes what follows the scheme,
 *    so an unencoded `%` or a raw space yields either a wrong path or a
 *    malformed URL — a blank map with no error anywhere.
 *
 * Both are fixed by encoding exactly once, here, with the one rule both native
 * sources agree on: decode the bytes after the scheme. `'/'` is left literal so
 * paths stay readable in a log, and everything outside the unreserved set
 * becomes UTF-8 `%XX`.
 */
object PackPaths {

    /**
     * Percent-encodes a filesystem path, leaving `/` and the unreserved set
     * (`A-Z a-z 0-9 - . _ ~`) untouched.
     *
     * `~` is left alone deliberately: it is unreserved in RFC 3986 and the
     * native decoder restores it, so round-tripping is exact.
     */
    fun encodePath(path: String): String {
        val out = StringBuilder(path.length + 16)
        for (byte in path.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (isLiteral(c)) {
                out.append(c)
            } else {
                out.append('%')
                out.append(HEX[b ushr 4])
                out.append(HEX[b and 0x0F])
            }
        }
        return out.toString()
    }

    /** `mbtiles://` + an absolute path, encoded. */
    fun mbtilesUrl(absolutePath: String): String = MBTILES_PROTOCOL + encodePath(absolutePath)

    /**
     * `file://` + an absolute path, encoded.
     *
     * A filesystem path always starts with `/`, so the result reads
     * `file:///data/...` — the empty authority form the native decoder expects
     * (it strips `file://` and keeps the leading slash).
     */
    fun fileUrl(absolutePath: String): String = FILE_PROTOCOL + encodePath(absolutePath)

    /**
     * The glyph URL template for a directory.
     *
     * `{fontstack}` and `{range}` stay literal: MapLibre substitutes them as
     * text **before** the URL reaches a file source, so encoding the braces
     * would leave the template unsubstituted and every glyph request would 404.
     */
    fun glyphsTemplate(directory: File): String =
        fileUrl(directory.absolutePath) + "/{fontstack}/{range}.pbf"

    /** `file://` URL for a single file. */
    fun fileUrl(file: File): String = fileUrl(file.absolutePath)

    private fun isLiteral(c: Char): Boolean = when (c) {
        in 'a'..'z', in 'A'..'Z', in '0'..'9' -> true
        '-', '.', '_', '~', '/' -> true
        else -> false
    }

    private const val MBTILES_PROTOCOL = "mbtiles://"
    private const val FILE_PROTOCOL = "file://"
    private val HEX = "0123456789ABCDEF".toCharArray()
}
