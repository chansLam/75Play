// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.connect

/**
 * 字节模式、纠错等级 M、版本 1–6 的二维码。只画下载链接，不引入第三方编码库。
 * 模块坐标原点在左上，true 为深色。
 */
internal class QrCode private constructor(
    val size: Int,
    private val modules: BooleanArray,
) {
    fun dark(x: Int, y: Int): Boolean = modules[y * size + x]

    companion object {
        fun encode(payload: ByteArray): QrCode {
            val version = versionFor(payload.size)
            val spec = SPECS[version]
            val size = 17 + 4 * version
            val function = BooleanArray(size * size)
            val raw = BooleanArray(size * size)
            drawPatterns(raw, function, size, spec.align)
            placeData(raw, function, size, codewords(payload, spec))
            var bestScore = Int.MAX_VALUE
            var best = raw
            for (mask in 0..7) {
                val candidate = applyMask(raw, function, size, mask)
                drawFormat(candidate, size, mask)
                val score = penalty(candidate, size)
                if (score < bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
            return QrCode(size, best)
        }

        private fun versionFor(length: Int): Int {
            for (version in 1..6) {
                val capacity = SPECS[version].dataBytes * 8
                if (4 + 8 + length * 8 <= capacity) return version
            }
            throw IllegalArgumentException("payload does not fit a version 1-6 code")
        }

        private fun codewords(payload: ByteArray, spec: Spec): IntArray {
            val bits = Bits()
            bits.append(0b0100, 4)
            bits.append(payload.size, 8)
            payload.forEach { bits.append(it.toInt() and 0xFF, 8) }
            val capacity = spec.dataBytes * 8
            bits.append(0, minOf(4, capacity - bits.length))
            while (bits.length % 8 != 0) bits.append(0, 1)
            var pad = 0
            while (bits.length < capacity) {
                bits.append(if (pad == 0) 0xEC else 0x11, 8)
                pad = pad xor 1
            }
            val data = bits.toBytes()
            val blocks = List(spec.blocks) { index ->
                val start = index * spec.dataPerBlock
                data.copyOfRange(start, start + spec.dataPerBlock)
            }
            val ecc = blocks.map { reedSolomon(it, spec.eccPerBlock) }
            val out = ArrayList<Int>(spec.dataBytes + spec.eccPerBlock * spec.blocks)
            for (i in 0 until spec.dataPerBlock) blocks.forEach { out.add(it[i]) }
            for (i in 0 until spec.eccPerBlock) ecc.forEach { out.add(it[i]) }
            return out.toIntArray()
        }

        private fun drawPatterns(modules: BooleanArray, function: BooleanArray, size: Int, align: IntArray) {
            fun set(x: Int, y: Int, dark: Boolean) {
                function[y * size + x] = true
                modules[y * size + x] = dark
            }
            fun finder(x0: Int, y0: Int) {
                for (dy in -1..7) for (dx in -1..7) {
                    val x = x0 + dx
                    val y = y0 + dy
                    if (x !in 0 until size || y !in 0 until size) continue
                    val on = dx in 0..6 && dy in 0..6 &&
                        (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4))
                    set(x, y, on)
                }
            }
            finder(0, 0)
            finder(size - 7, 0)
            finder(0, size - 7)
            for (i in 0 until size) {
                if (!function[6 * size + i]) set(i, 6, i % 2 == 0)
                if (!function[i * size + 6]) set(6, i, i % 2 == 0)
            }
            for (cy in align) for (cx in align) {
                if (function[cy * size + cx]) continue
                for (dy in -2..2) for (dx in -2..2) {
                    set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                }
            }
            for (i in 0..5) {
                set(8, i, false)
                set(i, 8, false)
            }
            set(8, 7, false)
            set(8, 8, false)
            set(7, 8, false)
            for (i in 0..7) set(size - 1 - i, 8, false)
            for (i in 0..6) set(8, size - 1 - i, false)
            set(8, size - 8, true)
        }

        private fun placeData(modules: BooleanArray, function: BooleanArray, size: Int, words: IntArray) {
            var bit = 0
            val total = words.size * 8
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!function[y * size + x] && bit < total) {
                            val dark = ((words[bit ushr 3] ushr (7 - (bit and 7))) and 1) != 0
                            modules[y * size + x] = dark
                            bit++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(raw: BooleanArray, function: BooleanArray, size: Int, mask: Int): BooleanArray {
            val out = raw.copyOf()
            for (y in 0 until size) for (x in 0 until size) {
                val i = y * size + x
                if (!function[i] && maskFlips(mask, x, y)) out[i] = !out[i]
            }
            return out
        }

        private fun maskFlips(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (y / 2 + x / 3) % 2 == 0
            5 -> x * y % 2 + x * y % 3 == 0
            6 -> (x * y % 2 + x * y % 3) % 2 == 0
            else -> ((x + y) % 2 + x * y % 3) % 2 == 0
        }

        private fun drawFormat(modules: BooleanArray, size: Int, mask: Int) {
            val bits = formatBits(mask)
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            fun set(x: Int, y: Int, dark: Boolean) { modules[y * size + x] = dark }
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun formatBits(mask: Int): Int {
            var rem = mask shl 10
            val gen = 0x537
            for (i in 14 downTo 10) {
                if (((rem ushr i) and 1) != 0) rem = rem xor (gen shl (i - 10))
            }
            return ((mask shl 10) or (rem and 0x3FF)) xor 0x5412
        }

        private fun penalty(modules: BooleanArray, size: Int): Int {
            var score = 0
            fun at(x: Int, y: Int) = modules[y * size + x]
            for (y in 0 until size) score += runPenalty(size) { x -> at(x, y) }
            for (x in 0 until size) score += runPenalty(size) { y -> at(x, y) }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val v = at(x, y)
                if (v == at(x + 1, y) && v == at(x, y + 1) && v == at(x + 1, y + 1)) score += 3
            }
            score += finderLike(modules, size)
            val dark = modules.count { it }
            val total = size * size
            score += kotlin.math.abs(dark * 20 - total * 10) / total * 10
            return score
        }

        private fun runPenalty(size: Int, cell: (Int) -> Boolean): Int {
            var score = 0
            var run = 1
            for (i in 1 until size) {
                if (cell(i) == cell(i - 1)) {
                    run++
                    if (run == 5) score += 3 else if (run > 5) score += 1
                } else {
                    run = 1
                }
            }
            return score
        }

        private fun finderLike(modules: BooleanArray, size: Int): Int {
            val left = intArrayOf(0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1)
            val right = intArrayOf(1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0)
            var score = 0
            fun matches(bits: IntArray, start: Int, pattern: IntArray): Boolean {
                for (k in pattern.indices) if (bits[start + k] != pattern[k]) return false
                return true
            }
            fun scan(bits: IntArray) {
                if (bits.size < 11) return
                for (i in 0..bits.size - 11) {
                    if (matches(bits, i, left) || matches(bits, i, right)) score += 40
                }
            }
            for (y in 0 until size) {
                val row = IntArray(size) { x -> if (modules[y * size + x]) 1 else 0 }
                scan(row)
            }
            for (x in 0 until size) {
                val col = IntArray(size) { y -> if (modules[y * size + x]) 1 else 0 }
                scan(col)
            }
            return score
        }

        private fun reedSolomon(data: IntArray, eccLen: Int): IntArray {
            val gen = generator(eccLen)
            val res = IntArray(data.size + eccLen)
            data.copyInto(res)
            for (i in data.indices) {
                val lead = res[i]
                if (lead == 0) continue
                for (j in gen.indices) res[i + j] = res[i + j] xor mul(gen[j], lead)
            }
            return res.copyOfRange(data.size, res.size)
        }

        private fun generator(degree: Int): IntArray {
            var poly = intArrayOf(1)
            for (i in 0 until degree) {
                val root = EXP[i]
                val next = IntArray(poly.size + 1)
                for (j in poly.indices) {
                    next[j] = next[j] xor mul(poly[j], root)
                    next[j + 1] = next[j + 1] xor poly[j]
                }
                poly = next
            }
            return poly.reversedArray()
        }

        private fun mul(a: Int, b: Int): Int {
            if (a == 0 || b == 0) return 0
            return EXP[LOG[a] + LOG[b]]
        }

        private val EXP = IntArray(512)
        private val LOG = IntArray(256)

        init {
            var value = 1
            for (i in 0 until 255) {
                EXP[i] = value
                LOG[value] = i
                value = value shl 1
                if (value >= 0x100) value = value xor 0x11D
            }
            for (i in 255 until 512) EXP[i] = EXP[i - 255]
        }

        private data class Spec(val eccPerBlock: Int, val blocks: Int, val dataPerBlock: Int, val align: IntArray) {
            val dataBytes: Int get() = blocks * dataPerBlock
        }

        private val SPECS = arrayOf(
            Spec(0, 0, 0, intArrayOf()),
            Spec(10, 1, 16, intArrayOf()),
            Spec(16, 1, 28, intArrayOf(6, 18)),
            Spec(26, 1, 44, intArrayOf(6, 22)),
            Spec(18, 2, 32, intArrayOf(6, 26)),
            Spec(24, 2, 43, intArrayOf(6, 30)),
            Spec(16, 4, 27, intArrayOf(6, 34)),
        )
    }

    private class Bits {
        private val bits = ArrayList<Int>()
        val length: Int get() = bits.size

        fun append(value: Int, count: Int) {
            for (shift in count - 1 downTo 0) bits.add((value ushr shift) and 1)
        }

        fun toBytes(): IntArray {
            val out = IntArray(bits.size / 8)
            for (i in bits.indices) if (bits[i] == 1) out[i ushr 3] = out[i ushr 3] or (1 shl (7 - (i and 7)))
            return out
        }
    }
}
