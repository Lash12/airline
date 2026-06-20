package push

import com.patson.model.PushSubscription

import java.math.BigInteger
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security._
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.{ECGenParameterSpec, ECParameterSpec, ECPoint, ECPrivateKeySpec, ECPublicKeySpec}
import java.time.Instant
import java.util.Base64
import javax.crypto.{Cipher, KeyAgreement, Mac}
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

case class PushDeliveryResult(status: Int, permanentFailure: Boolean, body: String)

class WebPushClient {
  private val http = HttpClient.newBuilder().build()
  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder
  private val recordSize = 4096

  def send(subscription: PushSubscription, config: PushConfig, payload: String): PushDeliveryResult = {
    val endpoint = URI.create(subscription.endpoint)
    val jwt = vapidJwt(endpoint, config)
    val encryptedPayload = encryptPayload(subscription, payload)
    val request = HttpRequest.newBuilder(endpoint)
      .POST(HttpRequest.BodyPublishers.ofByteArray(encryptedPayload))
      .header("TTL", "2419200")
      .header("Urgency", "normal")
      .header("Content-Encoding", "aes128gcm")
      .header("Content-Type", "application/octet-stream")
      .header("Authorization", s"vapid t=$jwt, k=${config.vapidPublicKey}")
      .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    PushDeliveryResult(response.statusCode(), response.statusCode() == 404 || response.statusCode() == 410, response.body())
  }

  private def vapidJwt(endpoint: URI, config: PushConfig): String = {
    val audience = s"${endpoint.getScheme}://${endpoint.getHost}"
    val header = encoder.encodeToString("""{"typ":"JWT","alg":"ES256"}""".getBytes(StandardCharsets.UTF_8))
    val claims = encoder.encodeToString(
      s"""{"aud":"$audience","exp":${Instant.now().getEpochSecond + 12 * 60 * 60},"sub":"${config.vapidSubject}"}"""
        .getBytes(StandardCharsets.UTF_8)
    )
    val unsigned = s"$header.$claims"
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(privateKey(config.vapidPrivateKey))
    signer.update(unsigned.getBytes(StandardCharsets.US_ASCII))
    s"$unsigned.${encoder.encodeToString(derToJose(signer.sign()))}"
  }

  private def privateKey(rawPrivateKey: String): PrivateKey = {
    val d = new BigInteger(1, decoder.decode(rawPrivateKey))
    val parameters = AlgorithmParameters.getInstance("EC")
    parameters.init(new ECGenParameterSpec("secp256r1"))
    val ecSpec = parameters.getParameterSpec(classOf[ECParameterSpec])
    KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, ecSpec)).asInstanceOf[ECPrivateKey]
  }

  private def encryptPayload(subscription: PushSubscription, payload: String): Array[Byte] = {
    val userPublicKey = decodePublicKey(subscription.p256dhKey)
    val authSecret = decoder.decode(subscription.authKey)
    val keyPair = ephemeralKeyPair()
    val senderPublicKey = encodePublicKey(keyPair.getPublic.asInstanceOf[ECPublicKey])
    val salt = randomBytes(16)

    val agreement = KeyAgreement.getInstance("ECDH")
    agreement.init(keyPair.getPrivate)
    agreement.doPhase(userPublicKey, true)
    val ecdhSecret = agreement.generateSecret()

    val keyInfo = concat(
      "WebPush: info".getBytes(StandardCharsets.US_ASCII),
      Array[Byte](0),
      decoder.decode(subscription.p256dhKey),
      senderPublicKey
    )
    val prkKey = hmac(authSecret, ecdhSecret)
    val ikm = hkdfExpand(prkKey, keyInfo, 32)
    val prk = hmac(salt, ikm)
    val cek = hkdfExpand(prk, "Content-Encoding: aes128gcm\u0000".getBytes(StandardCharsets.US_ASCII), 16)
    val nonce = hkdfExpand(prk, "Content-Encoding: nonce\u0000".getBytes(StandardCharsets.US_ASCII), 12)

    val plaintext = payload.getBytes(StandardCharsets.UTF_8) ++ Array[Byte](2)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce))
    val ciphertext = cipher.doFinal(plaintext)

    concat(
      salt,
      ByteBuffer.allocate(4).putInt(recordSize).array(),
      Array(senderPublicKey.length.toByte),
      senderPublicKey,
      ciphertext
    )
  }

  private def ephemeralKeyPair(): KeyPair = {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(new ECGenParameterSpec("secp256r1"))
    generator.generateKeyPair()
  }

  private def decodePublicKey(rawPublicKey: String): PublicKey = {
    val bytes = decoder.decode(rawPublicKey)
    val x = new BigInteger(1, bytes.slice(1, 33))
    val y = new BigInteger(1, bytes.slice(33, 65))
    val parameters = AlgorithmParameters.getInstance("EC")
    parameters.init(new ECGenParameterSpec("secp256r1"))
    val ecSpec = parameters.getParameterSpec(classOf[ECParameterSpec])
    KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), ecSpec))
  }

  private def encodePublicKey(publicKey: ECPublicKey): Array[Byte] =
    Array[Byte](4) ++ fixedLength(publicKey.getW.getAffineX, 32) ++ fixedLength(publicKey.getW.getAffineY, 32)

  private def fixedLength(value: BigInteger, length: Int): Array[Byte] = {
    val raw = value.toByteArray.dropWhile(_ == 0)
    Array.fill[Byte](Math.max(0, length - raw.length))(0) ++ raw.takeRight(length)
  }

  private def hmac(key: Array[Byte], data: Array[Byte]): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data)
  }

  private def hkdfExpand(key: Array[Byte], info: Array[Byte], length: Int): Array[Byte] =
    hmac(key, info ++ Array[Byte](1)).take(length)

  private def randomBytes(length: Int): Array[Byte] = {
    val bytes = Array.ofDim[Byte](length)
    new SecureRandom().nextBytes(bytes)
    bytes
  }

  private def concat(parts: Array[Byte]*): Array[Byte] = parts.flatten.toArray

  private def derToJose(der: Array[Byte]): Array[Byte] = {
    var offset = 0
    if (der(offset) != 0x30.toByte) throw new IllegalArgumentException("ECDSA signature is not a DER sequence")
    offset += 1
    val sequenceLength = der(offset) & 0xff
    offset += 1
    if ((sequenceLength & 0x80) != 0) {
      offset += sequenceLength & 0x7f
    }

    def readInteger(): Array[Byte] = {
      if (der(offset) != 0x02.toByte) throw new IllegalArgumentException("ECDSA signature integer is missing")
      offset += 1
      val length = der(offset) & 0xff
      offset += 1
      val raw = der.slice(offset, offset + length)
      offset += length
      val trimmed = raw.dropWhile(_ == 0)
      Array.fill[Byte](Math.max(0, 32 - trimmed.length))(0) ++ trimmed.takeRight(32)
    }

    readInteger() ++ readInteger()
  }
}
