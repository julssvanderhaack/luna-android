package io.github.julssvanderhaack.luna.fs

import io.github.julssvanderhaack.luna.tr
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPMarker
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPBEEncryptedData
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBEDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPBEKeyEncryptionMethodGenerator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Date

/** A key in Luna's keyring, for the "Para estas personas" list and Ajustes → Claves. */
data class KeyInfo(val fingerprint: String, val userId: String, val created: Long, val hasSecret: Boolean, val canEncrypt: Boolean)

class WrongPasswordException : IOException(tr("Contraseña incorrecta"))

/** The file is for a key Luna doesn't have (or the file wants a password and none was given). */
class NeedsException(val password: Boolean, val keys: List<String>) :
    IOException(if (keys.isNotEmpty()) tr("Está cifrado para una clave que no tienes en Luna") else tr("Hace falta la contraseña"))

/**
 * Public keys of the people you encrypt for, and your own secret keys to
 * decrypt with, imported from .asc/.gpg files (gpg --export / --export-secret-keys).
 * Kept as binary keyrings in the app's private storage.
 */
class Keyring(dir: File) {
    private val pubFile = File(dir, "pgp/pubring.pgp")
    private val secFile = File(dir, "pgp/secring.pgp")
    private val fp = BcKeyFingerprintCalculator()

    @Synchronized
    fun publicRings(): PGPPublicKeyRingCollection =
        if (pubFile.exists()) pubFile.inputStream().use { PGPPublicKeyRingCollection(it, fp) } else PGPPublicKeyRingCollection(emptyList())

    @Synchronized
    fun secretRings(): PGPSecretKeyRingCollection =
        if (secFile.exists()) secFile.inputStream().use { PGPSecretKeyRingCollection(it, fp) } else PGPSecretKeyRingCollection(emptyList())

    fun keys(): List<KeyInfo> {
        val secret = secretRings()
        return publicRings().keyRings.asSequence().map { ring ->
            val master = ring.publicKey
            KeyInfo(
                fingerprint = hex(master.fingerprint),
                userId = master.userIDs.asSequence().firstOrNull() ?: tr("(sin nombre)"),
                created = master.creationTime.time,
                hasSecret = secret.getSecretKeyRing(master.keyID) != null,
                canEncrypt = encryptionKey(ring) != null,
            )
        }.sortedBy { it.userId.lowercase() }.toList()
    }

    /** Reads every key in [input]; returns how many rings were added or updated. */
    @Synchronized
    fun import(input: InputStream): Int {
        var pub = publicRings()
        var sec = secretRings()
        var n = 0
        val f = PGPObjectFactory(PGPUtil.getDecoderStream(input), fp)
        while (true) {
            val o = runCatching { f.nextObject() }.getOrElse { throw IOException(tr("No es un archivo de claves OpenPGP")) } ?: break
            when (o) {
                is PGPPublicKeyRing -> {
                    pub.getPublicKeyRing(o.publicKey.keyID)?.let { pub = PGPPublicKeyRingCollection.removePublicKeyRing(pub, it) }
                    pub = PGPPublicKeyRingCollection.addPublicKeyRing(pub, o)
                    n++
                }
                is PGPSecretKeyRing -> {
                    sec.getSecretKeyRing(o.secretKey.keyID)?.let { sec = PGPSecretKeyRingCollection.removeSecretKeyRing(sec, it) }
                    sec = PGPSecretKeyRingCollection.addSecretKeyRing(sec, o)
                    // the public half too, so it can be encrypted for and listed
                    val ring = PGPPublicKeyRing(o.publicKeys.asSequence().toList())
                    if (pub.getPublicKeyRing(ring.publicKey.keyID) == null) pub = PGPPublicKeyRingCollection.addPublicKeyRing(pub, ring)
                    n++
                }
            }
        }
        if (n == 0) throw IOException(tr("No hay claves OpenPGP en ese archivo"))
        write(pub, sec)
        return n
    }

    @Synchronized
    fun delete(fingerprint: String) {
        var pub = publicRings()
        var sec = secretRings()
        pub.keyRings.asSequence().firstOrNull { hex(it.publicKey.fingerprint) == fingerprint }?.let { ring ->
            pub = PGPPublicKeyRingCollection.removePublicKeyRing(pub, ring)
            sec.getSecretKeyRing(ring.publicKey.keyID)?.let { sec = PGPSecretKeyRingCollection.removeSecretKeyRing(sec, it) }
        }
        write(pub, sec)
    }

    private fun write(pub: PGPPublicKeyRingCollection, sec: PGPSecretKeyRingCollection) {
        pubFile.parentFile?.mkdirs()
        pubFile.outputStream().use { pub.encode(it) }
        secFile.outputStream().use { sec.encode(it) }
    }

    fun recipient(fingerprint: String): PGPPublicKey? =
        publicRings().keyRings.asSequence().firstOrNull { hex(it.publicKey.fingerprint) == fingerprint }?.let(::encryptionKey)

    fun secretKey(keyId: Long): PGPSecretKey? = secretRings().getSecretKey(keyId)

    fun describe(keyId: Long): String =
        publicRings().getPublicKey(keyId)?.let { k ->
            publicRings().getPublicKeyRing(keyId)?.publicKey?.userIDs?.asSequence()?.firstOrNull() ?: hex(k.fingerprint).takeLast(16)
        } ?: "%016X".format(keyId)

    companion object {
        fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

        /** A subkey that may encrypt (the usual), else the master key if it may. */
        private fun encryptionKey(ring: PGPPublicKeyRing): PGPPublicKey? =
            ring.publicKeys.asSequence().filter { it.isEncryptionKey && !it.hasRevocation() && !expired(it) }
                .sortedBy { if (it.isMasterKey) 1 else 0 }.firstOrNull()

        private fun expired(k: PGPPublicKey) = k.validSeconds > 0 && k.creationTime.time + k.validSeconds * 1000 < System.currentTimeMillis()
    }
}

/**
 * OpenPGP files the same way desktop Luna does it with gpg: AES-256 with a
 * password, or for other people's public keys; file.gpg, carpeta.tar.gpg.
 * gpg -d and Kleopatra open them, and Luna opens theirs.
 */
object Pgp {
    private val digests = BcPGPDigestCalculatorProvider()

    /** Writes to [out] what [body] writes into the plaintext stream, encrypted. */
    suspend fun encrypt(
        out: OutputStream,
        name: String,
        modified: Long,
        password: CharArray?,
        recipients: List<PGPPublicKey>,
        compress: Boolean,
        body: suspend (OutputStream) -> Unit,
    ) {
        require(password != null || recipients.isNotEmpty())
        val gen = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true).setSecureRandom(SecureRandom()),
        )
        if (password != null) gen.addMethod(BcPBEKeyEncryptionMethodGenerator(password, digests.get(HashAlgorithmTags.SHA256)))
        recipients.forEach { gen.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(it)) }
        val enc = gen.open(out, ByteArray(1 shl 16))
        // photos, videos and zips don't get smaller: skip the work
        val comp = if (compress) PGPCompressedDataGenerator(CompressionAlgorithmTags.ZLIB) else null
        val inner = comp?.open(enc) ?: enc
        val lit = PGPLiteralDataGenerator()
        val plain = lit.open(inner, PGPLiteralData.BINARY, name, Date(modified), ByteArray(1 shl 16))
        body(plain)
        plain.close()
        lit.close()
        comp?.close()
        enc.close()
        gen.close()
    }

    /** What decrypting [input] will need: a password, a key, or both possible. */
    fun needs(input: InputStream, keyring: Keyring): NeedsException {
        val list = encryptedList(input)
        var pw = false
        val keys = ArrayList<String>()
        var haveKey = false
        for (ed in list.encryptedDataObjects) when (ed) {
            is PGPPBEEncryptedData -> pw = true
            is PGPPublicKeyEncryptedData -> if (keyring.secretKey(ed.keyID) != null) haveKey = true else keys += keyring.describe(ed.keyID)
        }
        return NeedsException(pw, if (haveKey) emptyList() else keys)
    }

    private fun encryptedList(input: InputStream): PGPEncryptedDataList {
        val f = PGPObjectFactory(PGPUtil.getDecoderStream(input), BcKeyFingerprintCalculator())
        while (true) {
            when (val o = runCatching { f.nextObject() }.getOrNull()) {
                is PGPEncryptedDataList -> return o
                is PGPMarker -> continue
                else -> throw IOException(tr("No es un archivo cifrado con OpenPGP"))
            }
        }
    }

    /**
     * Decrypts [input] with [password] or, if it was encrypted for one, the secret
     * key in [keyring] (unlocked with [keyPassphrase]), and hands [body] the
     * plaintext and the name stored inside. Throws [WrongPasswordException] and
     * [NeedsException] for the UI to ask again.
     */
    suspend fun decrypt(
        input: InputStream,
        password: CharArray?,
        keyring: Keyring,
        keyPassphrase: CharArray?,
        body: suspend (name: String, modified: Long, InputStream) -> Unit,
    ) {
        val list = encryptedList(input)
        var data: PGPEncryptedData? = null
        var clear: InputStream? = null
        var wrong = false
        for (ed in list.encryptedDataObjects) {
            try {
                when (ed) {
                    is PGPPublicKeyEncryptedData -> {
                        val sk = keyring.secretKey(ed.keyID) ?: continue
                        val priv = try {
                            sk.extractPrivateKey(BcPBESecretKeyDecryptorBuilder(digests).build(keyPassphrase ?: CharArray(0)))
                        } catch (_: PGPException) {
                            wrong = true
                            continue
                        }
                        clear = ed.getDataStream(BcPublicKeyDataDecryptorFactory(priv))
                    }
                    is PGPPBEEncryptedData -> {
                        if (password == null) continue
                        clear = ed.getDataStream(BcPBEDataDecryptorFactory(password, digests))
                    }
                }
            } catch (_: PGPException) {
                wrong = true
                continue
            }
            if (clear != null) {
                data = ed
                break
            }
        }
        if (clear == null || data == null) {
            if (wrong) throw WrongPasswordException()
            input.close()
            throw NeedsException(list.encryptedDataObjects.asSequence().any { it is PGPPBEEncryptedData }, emptyList())
        }
        var f = PGPObjectFactory(clear, BcKeyFingerprintCalculator())
        while (true) {
            when (val o = f.nextObject() ?: throw IOException(tr("El archivo cifrado está vacío"))) {
                is PGPCompressedData -> f = PGPObjectFactory(o.dataStream, BcKeyFingerprintCalculator())
                is PGPOnePassSignatureList, is PGPSignatureList, is PGPMarker -> continue
                is PGPLiteralData -> {
                    body(o.fileName, o.modificationTime.time, o.inputStream)
                    // drain what body left, or the integrity check can't run
                    val rest = o.inputStream
                    val sink = ByteArray(8192)
                    while (rest.read(sink) >= 0) Unit
                    break
                }
                else -> throw IOException(tr("Contenido OpenPGP desconocido"))
            }
        }
        if (data.isIntegrityProtected && !data.verify()) throw IOException(tr("El archivo cifrado está dañado o ha sido modificado"))
    }
}
