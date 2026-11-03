package io.github.julssvanderhaack.luna

import io.github.julssvanderhaack.luna.fs.Keyring
import io.github.julssvanderhaack.luna.fs.NeedsException
import io.github.julssvanderhaack.luna.fs.Pgp
import io.github.julssvanderhaack.luna.fs.WrongPasswordException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Luna's OpenPGP against itself and, when it's installed, against the real gpg. */
class PgpTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val text = "Hola, Luna. ñ ü 🌙\n".repeat(1000)

    private fun encrypt(pw: String?, keys: Keyring? = null, fp: String? = null): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        val recipients = listOfNotNull(fp?.let { keys!!.recipient(it) })
        Pgp.encrypt(out, "nota.txt", 1_700_000_000_000, pw?.toCharArray(), recipients, compress = true) { it.write(text.toByteArray()) }
        out.toByteArray()
    }

    private fun decrypt(data: ByteArray, pw: String?, keys: Keyring, keyPw: String? = null): Pair<String, String> = runBlocking {
        var got: Pair<String, String>? = null
        Pgp.decrypt(ByteArrayInputStream(data), pw?.toCharArray(), keys, keyPw?.toCharArray()) { name, _, input ->
            got = name to input.readBytes().decodeToString()
        }
        got!!
    }

    @Test
    fun passwordRoundTrip() {
        val keys = Keyring(tmp.newFolder("k"))
        val data = encrypt("secreto")
        assertEquals("nota.txt" to text, decrypt(data, "secreto", keys))
        try {
            decrypt(data, "otra", keys)
            error("wrong password accepted")
        } catch (_: WrongPasswordException) {
        }
        val needs = Pgp.needs(ByteArrayInputStream(data), keys)
        assertTrue(needs.password && needs.keys.isEmpty())
    }

    private fun gpg(home: File, vararg args: String, input: ByteArray? = null): ByteArray {
        val p = ProcessBuilder(listOf("gpg", "--homedir", home.path, "--batch", "--yes", "--pinentry-mode", "loopback") + args)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        input?.let { p.outputStream.use { o -> o.write(it) } } ?: p.outputStream.close()
        val out = p.inputStream.readBytes()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals("gpg ${args.toList()}", 0, p.exitValue())
        return out
    }

    private fun hasGpg() = runCatching { ProcessBuilder("gpg", "--version").start().waitFor() == 0 }.getOrDefault(false)

    @Test
    fun interoperatesWithGpg() {
        assumeTrue(hasGpg())
        // a throwaway home, never the user's ~/.gnupg
        val home = tmp.newFolder("gnupg").apply {
            setReadable(false, false); setWritable(false, false); setExecutable(false, false)
            setReadable(true, true); setWritable(true, true); setExecutable(true, true)
        }
        try {
            // Luna → gpg with a password
            val data = encrypt("secreto")
            assertEquals(text, gpg(home, "--passphrase", "secreto", "-d", input = data).decodeToString())
            // gpg → Luna with a password
            val fromGpg = gpg(home, "--passphrase", "clave", "-c", "--cipher-algo", "AES256", "-o", "-", input = text.toByteArray())
            val keys = Keyring(tmp.newFolder("k"))
            assertEquals(text, decrypt(fromGpg, "clave", keys).second)
            // a gpg key pair, imported into Luna
            gpg(home, "--passphrase", "frase", "--quick-gen-key", "Ana Prueba <ana@example.com>", "default", "default", "never")
            val pub = gpg(home, "--armor", "--export", "ana@example.com")
            val sec = gpg(home, "--passphrase", "frase", "--armor", "--export-secret-keys", "ana@example.com")
            keys.import(ByteArrayInputStream(pub))
            val info = keys.keys().single()
            assertEquals("Ana Prueba <ana@example.com>", info.userId)
            assertTrue(info.canEncrypt && !info.hasSecret)
            // Luna encrypts for Ana, gpg (which has Ana's secret key) decrypts
            val forAna = encrypt(null, keys, info.fingerprint)
            assertEquals(text, gpg(home, "--passphrase", "frase", "-d", input = forAna).decodeToString())
            // gpg encrypts for Ana; Luna can't open it until it has the secret key
            val gpgForAna = gpg(home, "-e", "-r", "ana@example.com", "--trust-model", "always", "-o", "-", input = text.toByteArray())
            val needs = Pgp.needs(ByteArrayInputStream(gpgForAna), keys)
            assertTrue(!needs.password && needs.keys.single().contains("Ana"))
            keys.import(ByteArrayInputStream(sec))
            assertTrue(keys.keys().single().hasSecret)
            assertEquals(text, decrypt(gpgForAna, null, keys, "frase").second)
            try {
                decrypt(gpgForAna, null, keys, "mal")
                error("wrong passphrase accepted")
            } catch (_: WrongPasswordException) {
            }
            keys.delete(info.fingerprint)
            assertTrue(keys.keys().isEmpty())
            try {
                decrypt(gpgForAna, null, keys, "frase")
                error("decrypted without the key")
            } catch (_: NeedsException) {
            }
        } finally {
            // its own agent, started for this home: stop it
            runCatching { ProcessBuilder("gpgconf", "--homedir", home.path, "--kill", "all").start().waitFor() }
        }
    }
}
