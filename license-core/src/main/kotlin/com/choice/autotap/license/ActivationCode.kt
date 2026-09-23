package com.choice.autotap.license

/**
 * Activation code format CAT-XXXX-XXXX-XXXX, identical to the server (license-server/src/code.ts).
 * 12 Crockford base-32 characters: 11 random + 1 check character (sum((2i+1)*v[i]) mod 32).
 * Checking the format locally rejects typos before any network request.
 */
object ActivationCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val PREFIX = "CAT"
    private const val RANDOM_LEN = 11
    private const val BODY_LEN = RANDOM_LEN + 1

    fun checkChar(randomPart: String): Char {
        var sum = 0
        randomPart.forEachIndexed { i, c -> sum += (2 * i + 1) * ALPHABET.indexOf(c) }
        return ALPHABET[sum % 32]
    }

    /** Canonical 12-character body, or null when [input] is not a well-formed code. */
    fun normalize(input: String): String? {
        if (input.length > 64) return null
        var s = input.uppercase().filterNot { it.isWhitespace() || it == '-' || it == '_' }
        if (s.length == PREFIX.length + BODY_LEN && s.startsWith(PREFIX)) s = s.substring(PREFIX.length)
        if (s.length != BODY_LEN) return null
        s = s.replace('O', '0').replace('I', '1').replace('L', '1')
        if (s.any { it !in ALPHABET }) return null
        if (checkChar(s.substring(0, RANDOM_LEN)) != s[RANDOM_LEN]) return null
        return s
    }

    fun format(body: String): String = "$PREFIX-${body.substring(0, 4)}-${body.substring(4, 8)}-${body.substring(8, 12)}"

    /** Normalized display form, e.g. "cat 7k4p x92m q3td" -> "CAT-7K4P-X92M-Q3TD", or null if malformed. */
    fun canonical(input: String): String? = normalize(input)?.let(::format)

    /** Builds a code from 11 random bytes (used by tests to share vectors with the server). */
    fun fromBytes(bytes: ByteArray): String {
        require(bytes.size >= RANDOM_LEN)
        val body = buildString { for (i in 0 until RANDOM_LEN) append(ALPHABET[bytes[i].toInt() and 31]) }
        return format(body + checkChar(body))
    }

    /**
     * Formats text while the user types: keeps valid characters, inserts dashes.
     * "cat7k4px92m" -> "CAT-7K4P-X92M". Never longer than a full code.
     */
    fun formatWhileTyping(input: String): String {
        var s = input.uppercase().filter { it.isLetterOrDigit() }
        if (s.startsWith(PREFIX)) s = s.substring(PREFIX.length)
        s = s.take(BODY_LEN)
        if (s.isEmpty()) return if (input.isBlank()) "" else "$PREFIX-"
        return PREFIX + "-" + s.chunked(4).joinToString("-")
    }
}
