package scalaj.http

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

import scala.collection.immutable.VectorBuilder
import scala.util.Random


case class WwwAuthenticate(authType: String, params: Map[String, String])
object DigestAuth {

  def trimQuotes(str: String): String = {
    if (str.length >= 2 && str.charAt(0) == '"' && str.charAt(str.length - 1) == '"') {
      str.substring(1, str.length - 1)
    } else {
      str
    }
  }

  // need to parse one char at a time rather than split on comma because values can be
  // quoted comma separated strings
  def splitParams(params: String): IndexedSeq[String] = {
    var builder = new VectorBuilder[String]()
    var start = 0
    var i = 0
    var quotes = 0
    while (i < params.length) {
      params.charAt(i) match {
        case '\\' => i += 1
        case '"' => quotes += 1
        case ',' =>
          if (quotes % 2 == 0) {
            val item = params.substring(start, i).trim()
            if (item.length > 0) {
              builder += item
            }
            start = i + 1
          }
        case _ => // nada
      }
      i += 1
    }
    builder += params.substring(start).trim()
    builder.result()
  }

  def getAuthDetails(headerValue: String): Option[WwwAuthenticate] = {
    headerValue.indexOf(' ') match {
      case indexOfSpace if indexOfSpace > 0 =>
        val authType = headerValue.substring(0, indexOfSpace)
        val params: Map[String, String] = splitParams(headerValue.substring(indexOfSpace + 1)).flatMap(param => {
          param.split('=') match {
            case Array(key, value) => Some(key.trim.toLowerCase(Locale.ENGLISH) -> trimQuotes(value.trim))
            case _ => None
          }
        }).toMap
        Some(WwwAuthenticate(authType, params))
      case _ => None
    }
  }

  val HexArray = "0123456789abcdef".toCharArray()

  def hex(bytes: Array[Byte]): String = {
    var hexChars = new Array[Char](bytes.length * 2)
    var j = 0
    while (j < bytes.length) {
      val v = bytes(j) & 0xFF
      hexChars(j * 2) = HexArray(v >>> 4)
      hexChars(j * 2 + 1) = HexArray(v & 0x0F)
      j += 1
    }
    new String(hexChars)
  }

  def createHeaderValue
  (
    username: String,
    password: String,
    method: String,
    uri: String,
    content: Array[Byte],
    serverParams: Map[String, String],
    testClientNonce: Option[String] = None
  ): Option[String] = {
    val algorithm = serverParams.getOrElse("algorithm", "MD5")
    val digester = Option(MessageDigest.getInstance(algorithm)).getOrElse(
      throw new Exception("unsupported digest algorithm" + algorithm)
    )
    def hexDigest(str: String): String = hex(digester.digest(str.getBytes(StandardCharsets.ISO_8859_1)))
    for {
      realm <- serverParams.get("realm")
      nonce <- serverParams.get("nonce")
    } yield {
      val qopOpt: Option[String] = serverParams.get("qop").flatMap(serverQop => {
        val serverQopValues = serverQop.split(',').map(_.trim)
        if(serverQopValues.contains("auth")) Some("auth")
        else if (serverQopValues.contains("auth-int")) Some("auth-int")
        else None
      })
      val a1 = username + ":" + realm + ":" + password
      val hashA1: String = hexDigest(a1)
      val a2 = method + ":" + uri + {
        if (qopOpt.exists(_ == "auth-int")) ":" + hex(digester.digest(content)) else ""
      }
      val hashA2: String = hexDigest(a2)

      val (nonceCountOpt, clientNonceOpt, a3) = qopOpt match {
        case Some(qop) =>
          val nc = "00000001"
          val clientNonce = testClientNonce.getOrElse({
            val bytes = new Array[Byte](16)
            Random.nextBytes(bytes)
            hex(bytes)
          })
          val a3 = hashA1 + ":" + nonce + ":" + nc + ":" + clientNonce + ":" + qop + ":" + hashA2
          (Some(nc), Some(clientNonce), a3)
        case _ =>
          (None, None, hashA1 + ":" + nonce + ":" + hashA2)
      }
      val hashA3: String = hexDigest(a3)
      val sb = new StringBuilder("Digest ")
      def appendQuoted(key: String, value: String): StringBuilder = {
        sb.append(key + "=\"").append(value).append("\"")
      }
      appendQuoted("username", username).append(", ")
      appendQuoted("realm", realm).append(", ")
      appendQuoted("nonce", nonce).append(", ")
      serverParams.get("opaque").foreach(opaque => {
        appendQuoted("opaque", opaque).append(", ")
      })
      appendQuoted("algorithm", algorithm).append(", ")
      appendQuoted("uri", uri).append(", ")
      for {
        qop <- qopOpt
        nonceCount <- nonceCountOpt
        clientNonce <- clientNonceOpt
      } {
        appendQuoted("qop", qop).append(", ")
        appendQuoted("nc", nonceCount).append(", ")
        appendQuoted("cnonce", clientNonce).append(", ")
      }
      appendQuoted("response", hashA3)
      sb.toString()
    }
  }
}
