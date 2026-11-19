package com.aripin.webcam.protocol

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketProtocol {
    val MAGIC = byteArrayOf('D'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), 'M'.code.toByte())
    const val HEADER_SIZE = 17

    const val TYPE_H264_NAL: Byte = 0x01
    const val TYPE_MJPEG_FRAME: Byte = 0x02
    const val TYPE_PCM_AUDIO: Byte = 0x03
    const val TYPE_HEARTBEAT: Byte = 0x04
    const val TYPE_STREAM_INFO: Byte = 0x05

    fun buildPacket(type: Byte, ptsUs: Long, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        val packet = ByteArray(HEADER_SIZE + length)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC)
        buffer.put(type)
        buffer.putLong(ptsUs)
        buffer.putInt(length)
        System.arraycopy(payload, offset, packet, HEADER_SIZE, length)
        return packet
    }

    fun writePacket(out: OutputStream, type: Byte, ptsUs: Long, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(MAGIC)
        header.put(type)
        header.putLong(ptsUs)
        header.putInt(length)
        out.write(header.array())
        if (length > 0) {
            out.write(payload, offset, length)
        }
        out.flush()
    }
}
