package com.trae.androidmcp

import android.util.Log
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlin.experimental.xor

/**
 * 最小化 ADB 客户端：连接本机 adbd（无线调试），执行 shell 命令。
 *
 * 作为无障碍服务的备选通道。连接目标通常为 127.0.0.1:5555（需先在 PC 上
 * 执行 adb tcpip 5555，或开启无线调试后查看端口）。
 *
 * 协议实现参考 ADB 协议规范：CNXN/AUTH/OPEN/OKAY/WRTE/CLSE。
 */
class AdbShellClient {

    companion object {
        private const val TAG = "AdbShellClient"

        // ADB 命令字（ASCII 小端）
        private const val CMD_CNXN = 0x4e584e43
        private const val CMD_AUTH = 0x48545541
        private const val CMD_OPEN = 0x4e45504f
        private const val CMD_OKAY = 0x59414b4f
        private const val CMD_CLSE = 0x45534c43
        private const val CMD_WRTE = 0x45545257

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        private const val PROTOCOL_VERSION = 0x01000000
        private const val MAX_DATA = 4096

        @Volatile
        var connected: Boolean = false
            private set
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var localId = 1u
    private var keyPair: KeyPair? = null

    fun connect(target: String, timeoutMs: Int = 5000) {
        val parts = target.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 5555

        socket = Socket().apply {
            connect(InetSocketAddress(host, port), timeoutMs)
        }
        input = DataInputStream(socket!!.getInputStream())
        output = socket!!.getOutputStream()

        handshake()
        connected = true
    }

    fun close() {
        connected = false
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        input = null
        output = null
    }

    /**
     * 执行 shell 命令并返回 stdout+stderr 合并输出。
     * 命令中包含空格/管道等特殊字符时由 adbd 的 shell 解析。
     */
    fun exec(command: String): String {
        val sid = open("shell:$command")
        val out = StringBuilder()
        try {
            while (true) {
                val msg = readMessage()
                when (msg.cmd) {
                    CMD_WRTE -> {
                        msg.data?.let { out.append(String(it, Charsets.UTF_8)) }
                        send(CMD_OKAY, sid.toInt(), msg.arg0)
                    }
                    CMD_CLSE -> break
                    CMD_OKAY -> {}
                    else -> Log.w(TAG, "Unexpected cmd during exec: ${msg.cmd}")
                }
            }
        } finally {
            try {
                send(CMD_CLSE, sid.toInt(), sid.toInt())
            } catch (_: Throwable) {
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // ADB 握手
    // ------------------------------------------------------------------

    private fun handshake() {
        // 1. 发送 CNXN
        val banner = "host::features=shell_v2,cmd,stat_v2,apex,abb,abb_exec,fixed_push_mkdir,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,remount_shell,shell_v2,cmd,ls_v2".toByteArray()
        send(CMD_CNXN, PROTOCOL_VERSION, MAX_DATA, banner)

        // 2. 循环处理 AUTH / CNXN
        var attempts = 0
        while (true) {
            val msg = readMessage()
            when (msg.cmd) {
                CMD_CNXN -> return // 握手成功
                CMD_AUTH -> {
                    attempts++
                    if (attempts > 5) throw IllegalStateException("ADB auth failed after 5 attempts")
                    handleAuth(msg.arg0, msg.data ?: ByteArray(0))
                }
                else -> throw IllegalStateException("Unexpected handshake cmd: ${msg.cmd}")
            }
        }
    }

    private fun handleAuth(type: Int, data: ByteArray) {
        when (type) {
            AUTH_TOKEN -> {
                // 尝试签名；若失败则回传 token（适用于 ro.adb.secure=0）
                val signed = try {
                    signWithRsa(data)
                } catch (_: Throwable) {
                    null
                }
                if (signed != null) {
                    send(CMD_AUTH, AUTH_SIGNATURE, 0, signed)
                } else {
                    // 设备允许免签名，直接回传 token
                    send(CMD_AUTH, AUTH_TOKEN, 0, data)
                }
            }
            AUTH_SIGNATURE -> {
                // 签名未通过，发送公钥供用户授权
                val pubKey = getPublicKeyBytes()
                send(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, pubKey)
                throw IllegalStateException("ADB 公钥已发送，请在设备上确认授权后重试")
            }
            else -> {
                // 未知认证类型，回传 token
                send(CMD_AUTH, AUTH_TOKEN, 0, data)
            }
        }
    }

    private fun signWithRsa(token: ByteArray): ByteArray {
        val kp = getOrCreateKeyPair()
        val sig = Signature.getInstance("SHA1withRSA").apply {
            initSign(kp.private)
            update(token)
        }
        return sig.sign()
    }

    private fun getOrCreateKeyPair(): KeyPair {
        keyPair?.let { return it }
        val kpg = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }
        return kpg.generateKeyPair().also { keyPair = it }
    }

    /** ADB 公钥格式：Base64(modLen:4 + modulus + expLen:4 + exponent) + " user@host" */
    private fun getPublicKeyBytes(): ByteArray {
        val kp = getOrCreateKeyPair()
        val pub = kp.public as RSAPublicKey
        val mod = pub.modulus.toByteArray()
        val exp = pub.publicExponent.toByteArray()

        // 去掉前导零
        val cleanMod = if (mod.isNotEmpty() && mod[0] == 0.toByte()) mod.copyOfRange(1, mod.size) else mod
        val cleanExp = if (exp.isNotEmpty() && exp[0] == 0.toByte()) exp.copyOfRange(1, exp.size) else exp

        val payload = ByteArray(4 + cleanMod.size + 4 + cleanExp.size)
        // 4 字节 modulus 长度（小端）
        writeLen(payload, 0, cleanMod.size)
        System.arraycopy(cleanMod, 0, payload, 4, cleanMod.size)
        // 4 字节 exponent 长度（小端）
        writeLen(payload, 4 + cleanMod.size, cleanExp.size)
        System.arraycopy(cleanExp, 0, payload, 4 + cleanMod.size + 4, cleanExp.size)

        val b64 = android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)
        return "$b64 android-mcp@device\u0000".toByteArray(Charsets.US_ASCII)
    }

    private fun writeLen(buf: ByteArray, off: Int, len: Int) {
        buf[off] = (len and 0xFF).toByte()
        buf[off + 1] = ((len shr 8) and 0xFF).toByte()
        buf[off + 2] = ((len shr 16) and 0xFF).toByte()
        buf[off + 3] = ((len shr 24) and 0xFF).toByte()
    }

    // ------------------------------------------------------------------
    // 流/服务管理
    // ------------------------------------------------------------------

    private fun open(service: String): UInt {
        val id = localId++
        val data = (service + "\u0000").toByteArray(Charsets.UTF_8)
        send(CMD_OPEN, id.toInt(), 0, data)

        // 等待 OKAY 或 CLSE
        while (true) {
            val msg = readMessage()
            when (msg.cmd) {
                CMD_OKAY -> return msg.arg1.toUInt()
                CMD_CLSE -> throw IllegalStateException("Service open refused: $service")
                else -> Log.w(TAG, "Unexpected cmd during open: ${msg.cmd}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 报文读写
    // ------------------------------------------------------------------

    private data class AdbMessage(
        val cmd: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray?
    )

    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val out = output ?: throw IllegalStateException("Not connected")
        val checksum = data.sumOf { it.toInt() and 0xFF } and 0xFFFFFFFF.toInt()
        val magic = cmd xor -1

        // 24 字节头部
        val header = ByteArray(24)
        writeInt(header, 0, cmd)
        writeInt(header, 4, arg0)
        writeInt(header, 8, arg1)
        writeInt(header, 12, data.size)
        writeInt(header, 16, checksum)
        writeInt(header, 20, magic)

        synchronized(out) {
            out.write(header)
            if (data.isNotEmpty()) out.write(data)
            out.flush()
        }
    }

    private fun readMessage(): AdbMessage {
        val inp = input ?: throw IllegalStateException("Not connected")
        val header = ByteArray(24)
        var off = 0
        while (off < 24) {
            val n = inp.read(header, off, 24 - off)
            if (n < 0) throw IllegalStateException("Connection closed")
            off += n
        }
        val cmd = readInt(header, 0)
        val arg0 = readInt(header, 4)
        val arg1 = readInt(header, 8)
        val dataLen = readInt(header, 12)

        val data = if (dataLen > 0) {
            val buf = ByteArray(dataLen)
            var dOff = 0
            while (dOff < dataLen) {
                val n = inp.read(buf, dOff, dataLen - dOff)
                if (n < 0) throw IllegalStateException("Connection closed mid-read")
                dOff += n
            }
            buf
        } else null

        return AdbMessage(cmd, arg0, arg1, data)
    }

    private fun writeInt(buf: ByteArray, off: Int, value: Int) {
        buf[off] = (value and 0xFF).toByte()
        buf[off + 1] = ((value shr 8) and 0xFF).toByte()
        buf[off + 2] = ((value shr 16) and 0xFF).toByte()
        buf[off + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readInt(buf: ByteArray, off: Int): Int {
        return ((buf[off].toInt() and 0xFF)) or
                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16) or
                ((buf[off + 3].toInt() and 0xFF) shl 24)
    }
}
