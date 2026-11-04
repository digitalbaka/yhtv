package com.yunhu.pad.api

import java.io.ByteArrayOutputStream

/**
 * 极简 Protobuf 编解码器（仅实现云湖 API 用到的 wire type 0/2）。
 * 遵循 proto3 约定：默认值不编码。
 */
class ProtoWriter {
    private val buf = ByteArrayOutputStream()

    private fun writeRawVarint(v0: Long) {
        var v = v0
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                buf.write(v.toInt())
                return
            } else {
                buf.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
    }

    private fun writeTag(field: Int, wire: Int) = writeRawVarint(((field shl 3) or wire).toLong())

    /** varint 字段（int32/int64/uint64/bool/enum），proto3 默认值跳过 */
    fun varint(field: Int, value: Long): ProtoWriter {
        if (value != 0L) {
            writeTag(field, 0)
            writeRawVarint(value)
        }
        return this
    }

    /** 强制写入 varint（即使为 0） */
    fun varintForce(field: Int, value: Long): ProtoWriter {
        writeTag(field, 0)
        writeRawVarint(value)
        return this
    }

    fun string(field: Int, value: String?): ProtoWriter {
        if (!value.isNullOrEmpty()) {
            writeTag(field, 2)
            val b = value.toByteArray(Charsets.UTF_8)
            writeRawVarint(b.size.toLong())
            buf.write(b)
        }
        return this
    }

    fun bytes(field: Int, value: ByteArray?): ProtoWriter {
        if (value != null && value.isNotEmpty()) {
            writeTag(field, 2)
            writeRawVarint(value.size.toLong())
            buf.write(value)
        }
        return this
    }

    /** 嵌套 message */
    fun msg(field: Int, encoded: ByteArray?): ProtoWriter = bytes(field, encoded)

    fun toByteArray(): ByteArray = buf.toByteArray()
}

class ProtoReader(private val data: ByteArray) {
    private var pos = 0

    fun hasMore(): Boolean = pos < data.size

    /** 返回 tag 原始值：field = tag ushr 3, wire = tag and 7 */
    fun readTag(): Int = readVarint().toInt()

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 70) throw IllegalStateException("varint too long")
        }
        throw IllegalStateException("unexpected eof in varint")
    }

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        require(len >= 0 && pos + len <= data.size) { "bad length $len" }
        val r = data.copyOfRange(pos, pos + len)
        pos += len
        return r
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    fun skip(wire: Int) {
        when (wire) {
            0 -> readVarint()
            1 -> skipFixed(8)
            2 -> {
                val l = readVarint().toInt()
                require(l >= 0 && pos + l <= data.size) { "bad length $l" }
                pos += l
            }
            3 -> {
                // proto2 group 起始：递归读到匹配的 end-group，避免把 group 内容当成普通 tag
                while (true) {
                    if (!hasMore()) throw IllegalStateException("unexpected eof in group")
                    val tag = readTag()
                    when (tag and 7) {
                        3 -> skip(3)
                        4 -> return
                        else -> skip(tag and 7)
                    }
                }
            }
            // end-group：由外层 skip(3) 消费；出现在普通字段位置说明报文边界异常，
            // 这里直接忽略当前 tag，继续按 tag 结构解析，避免整条消息解析失败。
            4 -> return
            5 -> skipFixed(4)
            else -> throw IllegalStateException("unsupported wire type $wire")
        }
    }

    private fun skipFixed(n: Int) {
        require(pos + n <= data.size) { "bad fixed length" }
        pos += n
    }
}
