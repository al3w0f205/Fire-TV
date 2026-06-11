package com.fireairplay.receiver.audio

import android.util.Log

/**
 * Pure Kotlin ALAC (Apple Lossless Audio Codec) decoder.
 *
 * This implementation decodes ALAC frames to 16-bit PCM samples without any native (C/NDK)
 * dependencies. It is based on the Apple open-source ALAC reference decoder and the
 * reverse-engineered specifications used by shairport/DroidAirPlay.
 *
 * ALAC frame structure:
 * - 3 unused bits
 * - 1 bit: hasSize flag
 * - 1 bit: uncompressed flag
 * - 1 bit: isNotCompressed
 * - If hasSize: 32 bits for output samples count
 * - If compressed: prediction params + Rice-coded residuals + LPC reconstruction
 * - If uncompressed: raw PCM samples
 *
 * Limitations of this pure Kotlin implementation:
 * - Supports 16-bit stereo and mono (the most common AirPlay configuration)
 * - May use more CPU than a native C decoder on lower-end Fire TV Sticks
 */
class AlacDecoder {

    companion object {
        private const val TAG = "AlacDecoder"

        // ALAC constants
        private const val ALAC_MAX_CHANNELS = 2
        private const val ALAC_ESCAPE_CODE = 0x1FF  // 9 bits all set — escape for Rice overflow
        private const val DEFAULT_FRAME_LENGTH = 352  // Standard AirPlay frame length
        private const val DEFAULT_SAMPLE_SIZE = 16    // 16-bit audio
        private const val DEFAULT_RICE_HISTORY_MULT = 40
        private const val DEFAULT_RICE_INITIAL_HISTORY = 10
        private const val DEFAULT_RICE_LIMIT = 14
    }

    // Codec configuration (from the magic cookie / ALAC specific config in SDP)
    var frameLength: Int = DEFAULT_FRAME_LENGTH
        private set
    var sampleSize: Int = DEFAULT_SAMPLE_SIZE
        private set
    var numChannels: Int = 2
        private set
    var sampleRate: Int = 44100
        private set
    var maxFrameBytes: Int = 0
        private set
    var avgBitRate: Int = 0
        private set

    // Rice coding parameters (from the config)
    private var riceHistoryMult: Int = DEFAULT_RICE_HISTORY_MULT
    private var riceInitialHistory: Int = DEFAULT_RICE_INITIAL_HISTORY
    private var riceLimit: Int = DEFAULT_RICE_LIMIT

    // Working buffers for prediction
    private var predictor: Array<IntArray> = Array(ALAC_MAX_CHANNELS) { IntArray(0) }
    private var shiftBuffer: IntArray = IntArray(0)

    private var isInitialized = false

    /**
     * Initialize the decoder with the ALAC magic cookie / specific config.
     *
     * The config is typically extracted from the SDP in the RTSP ANNOUNCE.
     * Format: 96 352 0 16 40 10 14 2 255 0 0 44100
     * Which maps to: payloadType frameLength compatibleVersion sampleSize
     *                riceHistoryMult riceInitialHistory riceLimit numChannels
     *                maxRun maxFrameBytes avgBitRate sampleRate
     *
     * @param fmtp the "a=fmtp:" line content split into integers
     */
    fun initialize(fmtp: List<Int>) {
        if (fmtp.size >= 12) {
            // fmtp[0] = payload type (96)
            frameLength = fmtp[1]       // 352
            // fmtp[2] = compatible version (0)
            sampleSize = fmtp[3]        // 16
            riceHistoryMult = fmtp[4]   // 40
            riceInitialHistory = fmtp[5] // 10
            riceLimit = fmtp[6]         // 14
            numChannels = fmtp[7]       // 2
            // fmtp[8] = maxRun (255)
            maxFrameBytes = fmtp[9]     // 0
            avgBitRate = fmtp[10]       // 0
            sampleRate = fmtp[11]       // 44100
        } else {
            Log.w(TAG, "Incomplete fmtp, using defaults. Got ${fmtp.size} values.")
        }

        // Allocate prediction buffers
        predictor = Array(numChannels.coerceAtMost(ALAC_MAX_CHANNELS)) {
            IntArray(frameLength + 16)
        }
        shiftBuffer = IntArray(frameLength)

        isInitialized = true
        Log.i(TAG, "Initialized: ${sampleRate}Hz, ${sampleSize}bit, ${numChannels}ch, frameLen=$frameLength")
    }

    /**
     * Initialize with default AirPlay parameters (44100Hz, 16-bit, stereo, 352 samples/frame).
     */
    fun initializeDefault() {
        initialize(listOf(96, 352, 0, 16, 40, 10, 14, 2, 255, 0, 0, 44100))
    }

    /**
     * Decodes a single ALAC frame into 16-bit PCM samples.
     *
     * @param input the compressed ALAC frame data (after AES decryption if applicable)
     * @param inputOffset starting offset within the input array
     * @param inputLength number of bytes of compressed data
     * @return ShortArray containing interleaved 16-bit PCM samples, or null on error
     */
    fun decode(input: ByteArray, inputOffset: Int = 0, inputLength: Int = input.size): ShortArray? {
        if (!isInitialized) {
            Log.e(TAG, "Decoder not initialized")
            return null
        }

        return try {
            val reader = BitReader(input, inputOffset, inputLength)
            decodeFrame(reader)
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
            // Return silence on error to avoid audio glitches
            ShortArray(frameLength * numChannels)
        }
    }

    /**
     * Core frame decoding logic.
     */
    private fun decodeFrame(reader: BitReader): ShortArray {
        // Read ALAC element tag (3 bits)
        val tag = reader.readBits(3)
        // AirPlay ALAC frames are typically tag 1 (CPE - stereo) or tag 0 (SCE - mono).
        val channels = if (tag == 1) 2 else 1

        // 4 bits unused element instance data
        reader.readBits(4)
        // 12 bits unused header size
        reader.readBits(12)

        val hasSize = reader.readBit() != 0
        val uncompressedBytes = reader.readBits(2)
        val isNotCompressed = reader.readBit() != 0

        // Determine output sample count
        val outputSamples = if (hasSize) {
            reader.readBits(32)
        } else {
            frameLength
        }

        val output = ShortArray(outputSamples * channels)
        val readSampleSize = sampleSize - (uncompressedBytes * 8) + if (channels == 2) 1 else 0

        if (isNotCompressed) {
            // Uncompressed frame — read raw PCM samples
            decodeUncompressed(reader, output, outputSamples, channels)
        } else {
            // Compressed frame — decode with LPC prediction and Rice coding
            decodeCompressed(reader, output, outputSamples, channels, uncompressedBytes, readSampleSize)
        }

        return output
    }

    /**
     * Decodes an uncompressed ALAC frame (raw 16-bit PCM).
     */
    private fun decodeUncompressed(
        reader: BitReader,
        output: ShortArray,
        numSamples: Int,
        channels: Int
    ) {
        // Shift unused bits to align with the sample size
        val shift = 32 - sampleSize
        for (i in 0 until numSamples * channels) {
            val sample = reader.readBits(sampleSize)
            // Sign-extend from sampleSize bits to 16 bits
            val signExtended = (sample shl shift) shr shift
            output[i] = signExtended.toShort()
        }
    }

    /**
     * Decodes a compressed ALAC frame using Rice coding and LPC prediction.
     */
    private fun decodeCompressed(
        reader: BitReader,
        output: ShortArray,
        numSamples: Int,
        channels: Int,
        uncompressedBytes: Int,
        readSampleSize: Int
    ) {
        val interlacingShift = if (channels == 2) reader.readBits(8) else 0
        val interlacingLeftWeight = if (channels == 2) reader.readBits(8) else 0

        // Read per-channel prediction parameters
        val mode = IntArray(channels)
        val denShift = IntArray(channels)
        val pbFactor = IntArray(channels)
        val predOrder = IntArray(channels)
        val coefficients = Array(channels) { IntArray(0) }

        for (ch in 0 until channels) {
            mode[ch] = reader.readBits(4)
            denShift[ch] = reader.readBits(4)
            val riceModifier = reader.readBits(3)
            pbFactor[ch] = (riceModifier * riceHistoryMult) / 4
            predOrder[ch] = reader.readBits(5)

            // Read prediction coefficients
            coefficients[ch] = IntArray(predOrder[ch])
            for (j in 0 until predOrder[ch]) {
                // Coefficients are standard two's complement 16-bit integers
                coefficients[ch][j] = reader.readBits(16).toShort().toInt()
            }
        }

        // If there are uncompressed bytes, read them (usually 0 in typical AirPlay streams)
        val uncompressed = Array(channels) { IntArray(numSamples) }
        if (uncompressedBytes > 0) {
            for (i in 0 until numSamples) {
                for (ch in 0 until channels) {
                    uncompressed[ch][i] = reader.readBits(uncompressedBytes * 8)
                }
            }
        }

        // Read Rice-coded residuals for each channel
        val residuals = Array(channels) { ch ->
            decodeRiceResiduals(reader, numSamples, riceInitialHistory, riceHistoryMult, riceLimit, pbFactor[ch], readSampleSize)
        }

        // Apply LPC prediction to reconstruct samples for each channel
        for (ch in 0 until channels) {
            if (predOrder[ch] >= 0) {
                applyLpcPrediction(
                    residuals[ch], numSamples, readSampleSize,
                    coefficients[ch], predOrder[ch],
                    denShift[ch]
                )
            }
        }

        // Interleave channels and apply stereo decorrelation if needed
        if (channels == 2) {
            // Apply stereo decorrelation
            unmixStereo(residuals[0], residuals[1], numSamples, interlacingShift, interlacingLeftWeight)
            // Restore uncompressed bits and Interleave L/R
            for (i in 0 until numSamples) {
                var left = residuals[0][i]
                var right = residuals[1][i]

                if (uncompressedBytes > 0) {
                    val shift = uncompressedBytes * 8
                    val mask = (1 shl shift) - 1
                    left = (left shl shift) or (uncompressed[0][i] and mask)
                    right = (right shl shift) or (uncompressed[1][i] and mask)
                }

                output[i * 2] = clamp16(left).toShort()
                output[i * 2 + 1] = clamp16(right).toShort()
            }
        } else {
            // Mono — restore uncompressed bits and copy
            for (i in 0 until numSamples) {
                var sample = residuals[0][i]

                if (uncompressedBytes > 0) {
                    val shift = uncompressedBytes * 8
                    val mask = (1 shl shift) - 1
                    sample = (sample shl shift) or (uncompressed[0][i] and mask)
                }

                output[i] = clamp16(sample).toShort()
            }
        }
    }

    /**
     * Decodes Rice/Golomb coded residuals from the bitstream.
     *
     * ALAC uses an adaptive Rice coding scheme where the Rice parameter (k)
     * is adjusted based on the history of decoded values.
     */
    private fun decodeRiceResiduals(
        reader: BitReader,
        numSamples: Int,
        initialHistory: Int,
        historyMult: Int, 
        riceLimit: Int,   
        pbFactor: Int,    
        readSampleSize: Int
    ): IntArray {
        val output = IntArray(numSamples)
        var history = initialHistory
        var signModifier = 0

        var i = 0
        while (i < numSamples) {
            // ALAC Apple Lossless Rice K calculation
            val historyVal = (history shr 9) + 3
            val leadingZeros = Integer.numberOfLeadingZeros(historyVal)
            
            val riceKModifier = riceLimit
            var k = 31 - riceKModifier - leadingZeros

            if (k < 0) k += riceKModifier
            else k = riceKModifier

            // entropy_decode_value
            val unary = reader.readUnary(9) 

            var decodedValue = 0
            if (unary > 8) {
                // Escape code: read the value directly
                val rawValue = reader.readBits(readSampleSize)
                decodedValue = rawValue and (0xFFFFFFFF.ushr(32 - readSampleSize)).toInt()
            } else {
                var x = unary
                if (k != 1) {
                    val extraBits = reader.readBits(k)
                    x *= ((1 shl k) - 1)
                    if (extraBits > 1) {
                        x += extraBits - 1
                    } else {
                        reader.unreadBit()
                    }
                }
                decodedValue = x
            }

            // Apply sign modifier and finalized ALAC sign extension
            val valueWithSign = decodedValue + signModifier
            var finalValue = (valueWithSign + 1) / 2
            if ((valueWithSign and 1) != 0) {
                finalValue *= -1
            }

            output[i] = finalValue
            signModifier = 0

            // Update history using the channel-specific pbFactor as the multiplier
            history += (decodedValue * pbFactor) - ((history * pbFactor) shr 9)
            if (decodedValue > 0xFFFF) {
                history = 0xFFFF
            }

            // special case, for compressed blocks of 0
            if (history < 128 && i + 1 < numSamples) {
                signModifier = 1
                
                val lz = Integer.numberOfLeadingZeros(history)
                val blockK = lz + ((history + 16) / 64) - 24
                val riceKMask = (1 shl riceKModifier) - 1
                
                val blockUnary = reader.readUnary(9)
                var blockSize = 0
                if (blockUnary > 8) {
                    val rawValue = reader.readBits(16) // block size is always encoded in 16 bits
                    blockSize = rawValue and 0xFFFF
                } else {
                    var x = blockUnary
                    if (blockK != 1) {
                        val extraBits = reader.readBits(blockK)
                        x *= ((1 shl blockK) - 1) and riceKMask
                        if (extraBits > 1) {
                            x += extraBits - 1
                        } else {
                            reader.unreadBit()
                        }
                    }
                    blockSize = x
                }

                if (blockSize > 0) {
                    // output array is already initialized to 0, so just skip indices
                    i += blockSize
                }

                if (blockSize > 0xFFFF) {
                    signModifier = 0
                }

                history = 0
            }

            i++
        }

        return output
    }

    /**
     * Applies LPC (Linear Predictive Coding) prediction to reconstruct
     * the original samples from the decoded residuals.
     */
    private fun applyLpcPrediction(
        errorBuffer: IntArray,
        numSamples: Int,
        readSampleSize: Int,
        coefficients: IntArray,
        order: Int,
        denShift: Int
    ) {
        if (numSamples <= 0) return

        val out = IntArray(numSamples)

        // first sample always copies
        out[0] = errorBuffer[0]

        if (order == 0) {
            if (numSamples <= 1) return
            System.arraycopy(errorBuffer, 1, out, 1, numSamples - 1)
            System.arraycopy(out, 0, errorBuffer, 0, numSamples)
            return
        }

        if (order == 0x1f) {
            if (numSamples <= 1) return
            for (i in 0 until numSamples - 1) {
                val prevValue = out[i]
                val errorValue = errorBuffer[i + 1]
                out[i + 1] = signExtend32(prevValue + errorValue, readSampleSize)
            }
            System.arraycopy(out, 0, errorBuffer, 0, numSamples)
            return
        }

        // read warm-up samples
        if (order > 0) {
            for (i in 0 until order) {
                val val32 = out[i] + errorBuffer[i + 1]
                out[i + 1] = signExtend32(val32, readSampleSize)
            }
        }

        // general case adaptive FIR prediction
        if (order > 0) {
            for (i in order + 1 until numSamples) {
                var sum = 0
                val errorVal = errorBuffer[i]
                
                val outBase = i - order - 1
                
                for (j in 0 until order) {
                    sum += (out[outBase + order - j] - out[outBase]) * coefficients[j]
                }
                
                var outVal = (1 shl (denShift - 1)) + sum
                outVal = outVal shr denShift
                outVal = outVal + out[outBase] + errorVal
                outVal = signExtend32(outVal, readSampleSize)
                
                out[outBase + order + 1] = outVal
                
                // Adaptive coefficient update
                var currentErrorVal = errorVal
                if (currentErrorVal > 0) {
                    var predictorNum = order - 1
                    while (predictorNum >= 0 && currentErrorVal > 0) {
                        var cval = out[outBase] - out[outBase + order - predictorNum]
                        val sign = if (cval < 0) -1 else if (cval > 0) 1 else 0
                        
                        coefficients[predictorNum] -= sign
                        cval *= sign // absolute value
                        
                        currentErrorVal -= ((cval shr denShift) * (order - predictorNum))
                        predictorNum--
                    }
                } else if (currentErrorVal < 0) {
                    var predictorNum = order - 1
                    while (predictorNum >= 0 && currentErrorVal < 0) {
                        var cval = out[outBase] - out[outBase + order - predictorNum]
                        val sign = -(if (cval < 0) -1 else if (cval > 0) 1 else 0)
                        
                        coefficients[predictorNum] -= sign
                        cval *= sign // absolute negative value
                        
                        currentErrorVal -= ((cval shr denShift) * (order - predictorNum))
                        predictorNum--
                    }
                }
            }
        }
        
        System.arraycopy(out, 0, errorBuffer, 0, numSamples)
    }

    private fun signExtend32(value: Int, bits: Int): Int {
        val shift = 32 - bits
        return (value shl shift) shr shift
    }

    /**
     * Applies stereo decorrelation to recover left and right channels.
     *
     * AirPlay/ALAC encodes stereo as mid/side:
     *   encoded[0] = (L + R) / 2      (mid)
     *   encoded[1] = L - R            (side / difference)
     *
     * We invert this to get:
     *   L = mid + (side + 1) / 2
     *   R = L - side
     */
    private fun unmixStereo(
        left: IntArray,
        right: IntArray,
        numSamples: Int,
        mixBits: Int,
        mixRes: Int
    ) {
        if (mixRes == 0) return // No decorrelation needed

        for (i in 0 until numSamples) {
            val a = left[i]   // This is actually the "sum" channel
            val b = right[i]  // This is the "difference" channel

            // Apply the matrix to unmix
            val rightSample = a - ((b * mixRes) shr mixBits)
            val leftSample = rightSample + b

            left[i] = leftSample
            right[i] = rightSample
        }
    }

    /**
     * Clamps an integer sample to the 16-bit signed range [-32768, 32767].
     */
    private fun clamp16(value: Int): Int {
        return value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }
}
