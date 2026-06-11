package com.fireairplay.receiver.audio

/**
 * Bit-level reader for parsing ALAC compressed frames.
 * Reads individual bits and multi-bit values from a byte array.
 * Used by [AlacDecoder] to parse Rice-coded residuals and LPC coefficients.
 */
class BitReader(private val data: ByteArray, private val offset: Int = 0, private val length: Int = data.size - offset) {

    /** Current byte position within the data buffer */
    private var bytePosition: Int = offset

    /** Current bit position within the current byte (7 = MSB, 0 = LSB) */
    private var bitPosition: Int = 7

    /** Total bits available from the starting offset */
    private val totalBits: Long = length.toLong() * 8L

    /** Number of bits consumed so far */
    private var bitsRead: Long = 0L

    /**
     * Reads a single bit from the stream.
     * @return 0 or 1
     * @throws IllegalStateException if no more bits are available
     */
    fun readBit(): Int {
        check(bitsRead < totalBits) { "BitReader: attempted to read past end of data" }

        val bit = (data[bytePosition].toInt() shr bitPosition) and 1
        bitPosition--
        if (bitPosition < 0) {
            bitPosition = 7
            bytePosition++
        }
        bitsRead++
        return bit
    }

    /**
     * Reads [numBits] bits as an unsigned integer (MSB first).
     * @param numBits number of bits to read (1-32)
     * @return the unsigned integer value
     */
    fun readBits(numBits: Int): Int {
        require(numBits in 1..32) { "readBits: numBits must be 1-32, got $numBits" }

        var result = 0
        for (i in 0 until numBits) {
            result = (result shl 1) or readBit()
        }
        return result
    }

    /**
     * Reads a unary-coded value (count of consecutive 0 bits before a 1 bit).
     * Used in Rice/Golomb coding for ALAC.
     * @param limit maximum value before switching to a different code
     * @return the unary count
     */
    fun readUnary(limit: Int): Int {
        var count = 0
        while (count < limit) {
            val bit = readBit()
            if (bit == 0) {
                return count
            }
            count++
        }
        return count
    }

    /**
     * Unreads (pushes back) a single bit to the stream.
     * This is used by ALAC zero-block compression.
     */
    fun unreadBit() {
        check(bitsRead > 0) { "BitReader: cannot unread past beginning of data" }
        bitsRead--
        if (bitPosition == 7) {
            bytePosition--
            bitPosition = 0
        } else {
            bitPosition++
        }
    }

    /**
     * Reads a signed value using the ALAC sign extension convention.
     * The lowest bit is the sign: 0 = positive, 1 = negative.
     */
    fun readSignedModified(numBits: Int): Int {
        val value = readBits(numBits)
        // ALAC sign extension: if LSB is 1, the value is negative
        return if (value and 1 != 0) {
            -(value shr 1) - 1
        } else {
            value shr 1
        }
    }

    /** Returns the number of bits remaining to be read. */
    fun bitsRemaining(): Long = totalBits - bitsRead

    /** Advances the reader to the next byte boundary. */
    fun alignToByte() {
        if (bitPosition != 7) {
            val skip = bitPosition + 1
            bitsRead += skip
            bitPosition = 7
            bytePosition++
        }
    }

    /** Resets the reader to the beginning. */
    fun reset() {
        bytePosition = offset
        bitPosition = 7
        bitsRead = 0
    }
}
