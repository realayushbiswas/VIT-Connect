package com.offgridmesh.vitconnect.mesh

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for direct (1:1) messages: EC Diffie-Hellman (P-256) key agreement +
 * AES-256-GCM. Every device generates its own key pair on first launch and never transmits its
 * private key anywhere. Public keys are gossiped across the mesh as KEY_ANNOUNCE messages (see
 * [MeshManager]) using the same flood/store-and-forward path as chat, so any two devices can
 * derive a shared secret and talk privately even across multiple relay hops — a relay only ever
 * sees the AES-GCM ciphertext, never the plaintext or any key material that could decrypt it.
 *
 * Uses only Android's built-in JCA/javax.crypto providers — no external crypto dependency.
 */
class CryptoManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyPair: KeyPair = loadOrCreateKeyPair()

    /** X.509-encoded EC public key — safe to broadcast to the whole mesh. */
    val publicKeyBytes: ByteArray = keyPair.public.encoded

    private fun loadOrCreateKeyPair(): KeyPair {
        val storedPriv = prefs.getString(KEY_PRIVATE, null)
        val storedPub = prefs.getString(KEY_PUBLIC, null)
        if (storedPriv != null && storedPub != null) {
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(storedPriv, Base64.NO_WRAP)))
            val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(storedPub, Base64.NO_WRAP)))
            return KeyPair(pub, priv)
        }
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val fresh = generator.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(fresh.private.encoded, Base64.NO_WRAP))
            .putString(KEY_PUBLIC, Base64.encodeToString(fresh.public.encoded, Base64.NO_WRAP))
            .apply()
        return fresh
    }

    private fun deriveAesKey(theirPublicKeyBytes: ByteArray): SecretKeySpec {
        val kf = KeyFactory.getInstance("EC")
        val theirPublicKey = kf.generatePublic(X509EncodedKeySpec(theirPublicKeyBytes))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair.private)
        agreement.doPhase(theirPublicKey, true)
        val sharedSecret = agreement.generateSecret()
        // SHA-256 of the raw ECDH secret as a simple, standard KDF down to an AES-256 key.
        val aesKeyBytes = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        return SecretKeySpec(aesKeyBytes, "AES")
    }

    /** Encrypts [plaintext] so only the holder of the matching private key for [theirPublicKeyBytes] can read it. Returns IV(12 bytes) + ciphertext+tag. */
    fun encrypt(plaintext: String, theirPublicKeyBytes: ByteArray): ByteArray {
        val aesKey = deriveAesKey(theirPublicKeyBytes)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + ciphertext
    }

    /** Reverses [encrypt]. Throws if [theirPublicKeyBytes] doesn't match the original sender (wrong key = auth tag failure). */
    fun decrypt(blob: ByteArray, theirPublicKeyBytes: ByteArray): String {
        val aesKey = deriveAesKey(theirPublicKeyBytes)
        val iv = blob.copyOfRange(0, 12)
        val ciphertext = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * The WhatsApp/Signal "safety number" equivalent: a fingerprint of both participants'
     * public keys that each side computes independently (order-independent, so both land on
     * the same value) and can compare out-of-band — in person, over a call, whatever — to
     * confirm no one is sitting in the middle of the key exchange.
     */
    fun safetyNumber(myDeviceId: String, theirDeviceId: String, theirPublicKeyBytes: ByteArray): String {
        val entries = listOf(
            myDeviceId to publicKeyBytes,
            theirDeviceId to theirPublicKeyBytes,
        ).sortedBy { it.first }
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { (id, key) ->
            digest.update(id.toByteArray(Charsets.UTF_8))
            digest.update(key)
        }
        val hex = digest.digest().joinToString("") { "%02X".format(it) }.take(24)
        return hex.chunked(4).joinToString(" ")
    }

    companion object {
        private const val PREFS = "offgrid_mesh_crypto"
        private const val KEY_PRIVATE = "ec_private_key"
        private const val KEY_PUBLIC = "ec_public_key"
    }
}
