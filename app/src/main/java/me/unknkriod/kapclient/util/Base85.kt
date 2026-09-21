package me.unknkriod.kapclient.util

object Base85 {
    private val ENCODING_TABLE = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J',
        'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T',
        'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd',
        'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n',
        'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x',
        'y', 'z', '.', '-', '+', '=', '^', '!', '/', '*',
        '?', '&', '<', '>', '(', ')', '[', ']', '{', '}',
        '@', '%', '$', '#', ':'
    )

    private val DECODING_TABLE = IntArray(256) { -1 }

    init {
        for (i in ENCODING_TABLE.indices) {
            DECODING_TABLE[ENCODING_TABLE[i].code] = i
        }
    }

    fun decode(text: String): ByteArray {
        val cleanText = text.filter { it.code < 256 && DECODING_TABLE[it.code] != -1 }
        val length = cleanText.length
        if (length == 0) return ByteArray(0)
        if (length % 5 != 0) {
            android.util.Log.e("Base85", "Invalid length: $length. String start: ${cleanText.take(20)}")
            throw IllegalArgumentException("Invalid Base85 length: $length")
        }

        val size = (length / 5) * 4
        val result = ByteArray(size)

        var outIndex = 0
        for (i in 0 until length step 5) {
            var value = 0L
            for (j in 4 downTo 0) {
                val code = cleanText[i + j].code
                value = value * 85 + DECODING_TABLE[code]
            }

            result[outIndex++] = ((value shr 24) and 0xFF).toByte()
            result[outIndex++] = ((value shr 16) and 0xFF).toByte()
            result[outIndex++] = ((value shr 8) and 0xFF).toByte()
            result[outIndex++] = (value and 0xFF).toByte()
        }

        return result
    }
}
