package app.jingqi.guard.system.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.flyfishxu.kadb.cert.KadbCert
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the ADB host private key encrypted by Android Keystore and out of backups. */
internal class AdbIdentityStore(context: Context) {
    private val identityFile = File(context.noBackupFilesDir, FILE_NAME)
    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        CryptoProviderBootstrap.ensureBouncyCastleX509Available()
        val restored = runCatching { readEncryptedIdentity() }
            .getOrNull()
            ?.let { (certificate, privateKey) ->
                validateCertificate(certificate) &&
                    runCatching { KadbCert.set(certificate, privateKey) }.isSuccess
            } == true
        if (restored) {
            loaded = true
            return
        }

        identityFile.delete()
        val (certificate, privateKey) = KadbCert.get(
            cn = "JingQi",
            ou = "Local ADB pairing",
            o = "JingQi Guard",
            l = "Local device",
            st = "Local device",
            c = "CN",
            notAfter = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3_650)
        )
        writeEncryptedIdentity(certificate, privateKey)
        loaded = true
    }

    private fun readEncryptedIdentity(): Pair<ByteArray, ByteArray>? {
        if (!identityFile.isFile) return null
        val outer = DataInputStream(identityFile.inputStream().buffered())
        val magic = ByteArray(MAGIC.size)
        outer.use { input ->
            input.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "Unsupported ADB identity format" }
            val ivSize = input.readUnsignedByte()
            require(ivSize in 12..16) { "Invalid ADB identity IV" }
            val iv = ByteArray(ivSize).also(input::readFully)
            val encrypted = input.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
                updateAAD(AAD)
            }
            val payload = DataInputStream(ByteArrayInputStream(cipher.doFinal(encrypted)))
            return payload.use {
                val certificate = it.readSized(MAX_CERT_BYTES)
                val privateKey = it.readSized(MAX_KEY_BYTES)
                require(it.read() == -1) { "Trailing ADB identity data" }
                certificate to privateKey
            }
        }
    }

    private fun writeEncryptedIdentity(certificate: ByteArray, privateKey: ByteArray) {
        require(certificate.size in 1..MAX_CERT_BYTES)
        require(privateKey.size in 1..MAX_KEY_BYTES)
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(certificate.size)
                it.write(certificate)
                it.writeInt(privateKey.size)
                it.write(privateKey)
            }
            bytes.toByteArray()
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, encryptionKey())
            updateAAD(AAD)
        }
        val encrypted = cipher.doFinal(payload)
        val temporary = File(identityFile.parentFile, "$FILE_NAME.tmp")
        DataOutputStream(temporary.outputStream().buffered()).use {
            it.write(MAGIC)
            it.writeByte(cipher.iv.size)
            it.write(cipher.iv)
            it.write(encrypted)
            it.flush()
        }
        check(temporary.renameTo(identityFile) || run {
            identityFile.delete() && temporary.renameTo(identityFile)
        }) { "无法保存本机配对密钥" }
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun validateCertificate(certificate: ByteArray): Boolean = runCatching {
        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
        parsed.checkValidity()
        parsed.publicKey.algorithm == "RSA"
    }.getOrDefault(false)

    private fun DataInputStream.readSized(maximum: Int): ByteArray {
        val size = readInt()
        require(size in 1..maximum) { "Invalid ADB identity field size" }
        return ByteArray(size).also(::readFully)
    }

    private companion object {
        const val FILE_NAME = "adb_identity_v1.bin"
        const val KEY_ALIAS = "jingqi_adb_identity_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_CERT_BYTES = 32 * 1024
        const val MAX_KEY_BYTES = 32 * 1024
        val MAGIC = "JQADB1".encodeToByteArray()
        val AAD = "app.jingqi.guard/adb-identity/v1".encodeToByteArray()
    }
}
