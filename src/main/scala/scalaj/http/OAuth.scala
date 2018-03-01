package scalaj.http

/** scalaj.http
  Copyright 2010 Jonathan Hoffman

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

import java.net.{HttpURLConnection, URL}

case class Token(key: String, secret: String)

/** utility methods used by [[scalaj.http.HttpRequest]] */
object OAuth {
  import java.net.URI
  import javax.crypto.Mac
  import javax.crypto.SecretKey
  import javax.crypto.spec.SecretKeySpec
  val MAC = "HmacSHA1"

  def sign(req: HttpRequest, connection: HttpURLConnection, consumer: Token, token: Option[Token], verifier: Option[String]): Unit = {
    val baseParams: Seq[(String,String)] = Seq(
      ("oauth_timestamp", (System.currentTimeMillis / 1000).toString),
      ("oauth_nonce", System.currentTimeMillis.toString)
    )

    var (oauthParams, signature) = getSig(baseParams, req, consumer, token, verifier)

    oauthParams +:= ("oauth_signature", signature)
    connection.setRequestProperty("Authorization", "OAuth " + oauthParams.map(p => p._1 + "=\"" + percentEncode(p._2) +"\"").mkString(","))
  }
  
  def getSig(baseParams: Seq[(String,String)], req: HttpRequest, consumer: Token, token: Option[Token], verifier: Option[String]): (Seq[(String,String)], String) = {
    var oauthParams = ("oauth_version", "1.0") +: ("oauth_consumer_key", consumer.key) +: ("oauth_signature_method", "HMAC-SHA1") +: baseParams
    
    token.foreach{t =>
      oauthParams +:= ("oauth_token", t.key)
    }
    
    verifier.foreach{v =>
      oauthParams +:= ("oauth_verifier", v)
    }
    
    val baseString = Seq(req.method.toUpperCase,normalizeUrl(new URL(req.url)),normalizeParams(req.params ++ oauthParams)).map(percentEncode).mkString("&")
    
    val keyString = percentEncode(consumer.secret) + "&" + token.map(t => percentEncode(t.secret)).getOrElse("")
    val key = new SecretKeySpec(keyString.getBytes(HttpConstants.utf8), MAC)
    val mac = Mac.getInstance(MAC)
    mac.init(key)
    val text = baseString.getBytes(HttpConstants.utf8)
    (oauthParams, HttpConstants.base64(mac.doFinal(text)))
  }
  
  private def normalizeParams(params: Seq[(String,String)]) = {
    percentEncode(params).sortWith(_ < _).mkString("&")
  }
  
  private def normalizeUrl(url: URL) = {
    val uri = new URI(url.toString)
    val scheme = uri.getScheme().toLowerCase()
    var authority = uri.getAuthority().toLowerCase()
    val dropPort = (scheme.equals("http") && uri.getPort() == 80) || (scheme.equals("https") && uri.getPort() == 443)
    if (dropPort) {
      // find the last : in the authority
      val index = authority.lastIndexOf(":")
      if (index >= 0) {
        authority = authority.substring(0, index)
      }
    }
    var path = uri.getRawPath()
    if (path == null || path.length() <= 0) {
      path = "/" // conforms to RFC 2616 section 3.2.2
    }
    // we know that there is no query and no fragment here.
    scheme + "://" + authority + path
  }
  
  def percentEncode(params: Seq[(String,String)]): Seq[String] = {
    params.map(p => percentEncode(p._1) + "=" + percentEncode(p._2))
  }
  
  def percentEncode(s: String): String = {
    if (s == null) "" else {
       HttpConstants.urlEncode(s, HttpConstants.utf8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
     }
  }
}