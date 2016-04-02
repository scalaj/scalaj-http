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

import collection.immutable.TreeMap
import java.lang.reflect.Field
import java.net.{HttpCookie, HttpURLConnection, InetSocketAddress, Proxy, URL, URLEncoder, URLDecoder}
import java.io.{DataOutputStream, InputStream, BufferedReader, InputStreamReader, ByteArrayInputStream, 
  ByteArrayOutputStream}
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import java.util.zip.{GZIPInputStream, InflaterInputStream}
import scala.collection.JavaConverters._
import scala.util.matching.Regex

/** Helper functions for modifying the underlying HttpURLConnection */
object HttpOptions {
  type HttpOption = HttpURLConnection => Unit

  val officalHttpMethods = Set("GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE")
  
  private lazy val methodField: Field = {
    val m = classOf[HttpURLConnection].getDeclaredField("method")
    m.setAccessible(true)
    m
  }
  
  def method(methodOrig: String): HttpOption = c => {
    val method = methodOrig.toUpperCase
    if (officalHttpMethods.contains(method)) {
      c.setRequestMethod(method)
    } else {
      // HttpURLConnection enforces a list of official http METHODs, but not everyone abides by the spec
      // this hack allows us set an unofficial http method
      c match {
        case cs: HttpsURLConnection =>
          cs.getClass.getDeclaredFields.find(_.getName == "delegate").foreach{ del =>
            del.setAccessible(true)
            methodField.set(del.get(cs), method)
          }
        case c => 
          methodField.set(c, method)
      }
    }
  }
  def connTimeout(timeout: Int): HttpOption = c => c.setConnectTimeout(timeout)
  
  def readTimeout(timeout: Int): HttpOption = c => c.setReadTimeout(timeout)
  
  def followRedirects(shouldFollow: Boolean): HttpOption = c => c.setInstanceFollowRedirects(shouldFollow)

  /** Ignore the cert chain */
  def allowUnsafeSSL: HttpOption = c => c match {
    case httpsConn: HttpsURLConnection => 
      val hv = new HostnameVerifier() {
        def verify(urlHostName: String, session: SSLSession) = true
      }
      httpsConn.setHostnameVerifier(hv)
      
      val trustAllCerts = Array[TrustManager](new X509TrustManager() {
        def getAcceptedIssuers: Array[X509Certificate] = null
        def checkClientTrusted(certs: Array[X509Certificate], authType: String){}
        def checkServerTrusted(certs: Array[X509Certificate], authType: String){}
      })

      val sc = SSLContext.getInstance("SSL")
      sc.init(null, trustAllCerts, new java.security.SecureRandom())
      httpsConn.setSSLSocketFactory(sc.getSocketFactory())
    case _ => // do nothing
  }

  /** Add your own SSLSocketFactory to do certificate authorization or pinning */
  def sslSocketFactory(sslSocketFactory: SSLSocketFactory): HttpOption = c => c match {
    case httpsConn: HttpsURLConnection =>
      httpsConn.setSSLSocketFactory(sslSocketFactory) 
    case _ => // do nothing
  }
}

object MultiPart {
  def apply(name: String, filename: String, mime: String, data: String): MultiPart = {
    apply(name, filename, mime, data.getBytes(HttpConstants.utf8))
  }
  def apply(name: String, filename: String, mime: String, data: Array[Byte]): MultiPart = {
    MultiPart(name, filename, mime, new ByteArrayInputStream(data), data.length, n => ())
  }
}

case class MultiPart(val name: String, val filename: String, val mime: String, val data: InputStream, val numBytes: Long,
  val writeCallBack: Long => Unit)

case class HttpStatusException(
  code: Int,
  statusLine: String,
  body: String
) extends RuntimeException(code + " Error: " + statusLine)

/** Result of executing a [[scalaj.http.HttpRequest]]
  * @tparam T the body response since it can be parsed directly to things other than String
  * @param body the Http response body
  * @param code the http response code from the status line
  * @param headers the response headers
 */
case class HttpResponse[T](body: T, code: Int, headers: Map[String, IndexedSeq[String]]) {
  /** test if code is in beteween lower and upper inclusive */
  def isCodeInRange(lower: Int, upper: Int): Boolean = lower <= code && code <= upper

  /** is response code 2xx */
  def is2xx: Boolean = isCodeInRange(200, 299)
  /** same as is2xx */
  def isSuccess: Boolean = is2xx

  /** is response code 3xx */
  def is3xx: Boolean = isCodeInRange(300, 399)
  /** same as is3xx */
  def isRedirect: Boolean = is3xx

  /** is response code 4xx */
  def is4xx: Boolean = isCodeInRange(400, 499)
  /** same as is4xx */
  def isClientError: Boolean = is4xx

  /** is response code 5xx */
  def is5xx: Boolean = isCodeInRange(500, 599)
  /** same as is5xx */
  def isServerError: Boolean = is5xx

  /** same as (is4xx || is5xx) */
  def isError: Boolean = is4xx || is5xx
  /** same as !isError */
  def isNotError: Boolean = !isError

  /** helper method for throwing status exceptions */
  private def throwIf(condition: Boolean): HttpResponse[T] = {
    if (condition) {
      throw HttpStatusException(code, header("Status").getOrElse("UNKNOWN"), body.toString)
    }
    this
  }

  /** Throw a {{{scalaj.http.HttpStatusException}} if {{{isError}}} is true. Otherwise returns reference to self
   *
   * Useful if you don't want to handle 4xx or 5xx error codes from the server and just want bubble up an Exception
   * instead. HttpException.body will just be body.toString.
   *
   * Allows for chaining like this: {{{val result: String = Http(url).asString.throwError.body}}}
   */
  def throwError: HttpResponse[T] = throwIf(isError)

  /** Throw a {{{scalaj.http.HttpStatusException}} if {{{isServerError}}} is true. Otherwise returns reference to self
   *
   * Useful if you don't want to 5xx error codes from the server and just want bubble up an Exception instead.
   * HttpException.body will just be body.toString.
   *
   * Allows for chaining like this: {{{val result: String = Http(url).asString.throwServerError.body}}}
   */
  def throwServerError: HttpResponse[T] = throwIf(isServerError)

  /** Get the response header value for a key */
  def header(key: String): Option[String] = headers.get(key).flatMap(_.headOption)
  /** Get all the response header values for a repeated key */
  def headerSeq(key: String): IndexedSeq[String] = headers.getOrElse(key, IndexedSeq.empty)

  /** The full status line. like "HTTP/1.1 200 OK"
    * throws a RuntimeException if "Status" is not in headers
    */
  def statusLine: String = header("Status").getOrElse(throw new RuntimeException("headers doesn't contain Status"))

  /** Location header value sent for redirects. By default, this library will not follow redirects. */
  def location: Option[String] = header("Location")

  /** Content-Type header value */
  def contentType: Option[String] = header("Content-Type")

  /** Get the parsed cookies from the "Set-Cookie" header **/
  def cookies: IndexedSeq[HttpCookie] = headerSeq("Set-Cookie").flatMap(HttpCookie.parse(_).asScala)
}

/** Immutable builder for creating an http request
  *
  * This is the workhorse of the scalaj-http library.
  *
  * You shouldn't need to construct this manually. Use [[scalaj.http.Http.apply]] to get an instance
  *
  * The params, headers and options methods are all additive. They will always add things to the request. If you want to 
  * replace those things completely, you can do something like {{{.copy(params=newparams)}}}
  *
  */
case class HttpRequest(
  url: String,
  method: String,
  connectFunc: HttpConstants.HttpExec,
  params: Seq[(String,String)], 
  headers: Seq[(String,String)],
  options: Seq[HttpOptions.HttpOption],
  proxyConfig: Option[Proxy],
  charset: String,
  sendBufferSize: Int,
  urlBuilder: (HttpRequest => String),
  compress: Boolean
) {
  /** Add params to the GET querystring or POST form request */
  def params(p: Map[String, String]): HttpRequest = params(p.toSeq)
  /** Add params to the GET querystring or POST form request */
  def params(p: Seq[(String,String)]): HttpRequest = copy(params = params ++ p)
  /** Add params to the GET querystring or POST form request */
  def params(p: (String,String), rest: (String, String)*): HttpRequest = params(p +: rest)
  /** Add a param to the GET querystring or POST form request */
  def param(key: String, value: String): HttpRequest = params(key -> value)

  /** Add http headers to the request */
  def headers(h: Map[String, String]): HttpRequest = headers(h.toSeq)
  /** Add http headers to the request */
  def headers(h: Seq[(String,String)]): HttpRequest = copy(headers = headers ++ h)
  /** Add http headers to the request */
  def headers(h: (String,String), rest: (String, String)*): HttpRequest = headers(h +: rest)
  /** Add a http header to the request */
  def header(key: String, value: String): HttpRequest = headers(key -> value)

  /** Add Cookie header to the request */
  def cookie(name: String, value: String): HttpRequest = header("Cookie", name + "=" + value + ";")
  /** Add Cookie header to the request */
  def cookie(ck: HttpCookie): HttpRequest = cookie(ck.getName, ck.getValue)
  /** Add multiple cookies to the request. Usefull for round tripping cookies from HttpResponse.cookies */
  def cookies(cks: Seq[HttpCookie]): HttpRequest = header(
    "Cookie",
    cks.map(ck => ck.getName + "=" + ck.getValue).mkString("; ")
  )

  /** Entry point fo modifying the [[java.net.HttpURLConnection]] before the request is executed */
  def options(o: Seq[HttpOptions.HttpOption]): HttpRequest = copy(options = o ++ options)
  /** Entry point fo modifying the [[java.net.HttpURLConnection]] before the request is executed */
  def options(o: HttpOptions.HttpOption, rest: HttpOptions.HttpOption*): HttpRequest = options(o +: rest)
  /** Entry point fo modifying the [[java.net.HttpURLConnection]] before the request is executed */
  def option(o: HttpOptions.HttpOption): HttpRequest = options(o)
  
  /** Add a standard basic authorization header */
  def auth(user: String, password: String) = header("Authorization", "Basic " + HttpConstants.base64(user + ":" + password))
  
  /** OAuth v1 sign the request with the consumer token */
  def oauth(consumer: Token): HttpRequest = oauth(consumer, None, None)
  /** OAuth v1 sign the request with with both the consumer and client token */
  def oauth(consumer: Token, token: Token): HttpRequest = oauth(consumer, Some(token), None)
  /** OAuth v1 sign the request with with both the consumer and client token and a verifier*/
  def oauth(consumer: Token, token: Token, verifier: String): HttpRequest = oauth(consumer, Some(token), Some(verifier))
  /** OAuth v1 sign the request with with both the consumer and client token and a verifier*/
  def oauth(consumer: Token, token: Option[Token], verifier: Option[String]): HttpRequest = {
    OAuth.sign(this, consumer, token, verifier)
  }

  /** Change the http request method. 
    * The library will allow you to set this to whatever you want. If you want to do a POST, just use the
    * postData, postForm, or postMulti methods. If you want to setup your request as a form, data or multi request, but 
    * want to change the method type, call this method after the post method:
    *
    * {{{Http(url).postData(dataBytes).method("PUT").asString}}}
    */
  def method(m: String): HttpRequest = copy(method=m)

  /** Should HTTP compression be used
    * If true, Accept-Encoding: gzip,deflate will be sent with request.
    * If the server response with Content-Encoding: (gzip|deflate) the client will automatically handle decompression
    *
    * This is on by default
    *
    * @param c should compress
    */
  def compress(c: Boolean): HttpRequest = copy(compress=c)

  /** Send request via a standard http proxy */
  def proxy(host: String, port: Int): HttpRequest = proxy(host, port, Proxy.Type.HTTP)
  /** Send request via a proxy. You choose the type (HTTP or SOCKS) */
  def proxy(host: String, port: Int, proxyType: Proxy.Type): HttpRequest = {
    copy(proxyConfig = Some(HttpConstants.proxy(host, port, proxyType)))
  }
  /** Send request via a proxy */
  def proxy(proxy: Proxy): HttpRequest = {
    copy(proxyConfig = Some(proxy))
  }
  
  /** Change the charset used to encode the request and decode the response. UTF-8 by default */
  def charset(cs: String): HttpRequest = copy(charset = cs)

  /** The buffer size to use when sending Multipart posts */
  def sendBufferSize(numBytes: Int): HttpRequest = copy(sendBufferSize = numBytes)

  /** The socket connection and read timeouts in milliseconds. Defaults are 1000 and 5000 respectively */
  def timeout(connTimeoutMs: Int, readTimeoutMs: Int): HttpRequest = options(
    Seq(HttpOptions.connTimeout(connTimeoutMs), HttpOptions.readTimeout(readTimeoutMs))
  )
  
  /** Executes this request
    *
    * Keep in mind that if you're parsing the response to something other than String, you may hit parsing error if
    * the server responds with a different content type for error cases.
    *
    * @tparam T the type returned by the input stream parser
    * @param parser function to process the response body InputStream. Will be used for all response codes
    */
  def execute[T](
    parser: InputStream => T = (is: InputStream) => HttpConstants.readString(is, charset)
  ): HttpResponse[T] = {
    exec((code: Int, headers: Map[String, IndexedSeq[String]], is: InputStream) => parser(is))
  }

  /** Executes this request
    *
    * This is a power user method for parsing the response body. The parser function will be passed the response code,
    * response headers and the InputStream
    *
    * @tparam T the type returned by the input stream parser
    * @param parser function to process the response body InputStream
    */
  def exec[T](parser: (Int, Map[String, IndexedSeq[String]], InputStream) => T): HttpResponse[T] = {
    doConnection(parser, new URL(urlBuilder(this)), connectFunc)
  }

  private def doConnection[T](
    parser: (Int, Map[String, IndexedSeq[String]], InputStream) => T,
    urlToFetch: URL,
    connectFunc: (HttpRequest, HttpURLConnection) => Unit
  ): HttpResponse[T] = {
    proxyConfig.map(urlToFetch.openConnection).getOrElse(urlToFetch.openConnection) match {
      case conn: HttpURLConnection =>
        conn.setInstanceFollowRedirects(false)
        HttpOptions.method(method)(conn)
        if (compress) {
          conn.setRequestProperty("Accept-Encoding", "gzip,deflate")
        }
        headers.reverse.foreach{ case (name, value) => 
          conn.setRequestProperty(name, value)
        }
        options.reverse.foreach(_(conn))

        connectFunc(this, conn)
        try {
          toResponse(conn, parser, conn.getInputStream)
        } catch {
          case e: java.io.IOException if conn.getResponseCode > 0 =>
            toResponse(conn, parser, conn.getErrorStream)
        } finally {
          closeStreams(conn)
        }
    }
  }

  private def toResponse[T](
    conn: HttpURLConnection,
    parser: (Int, Map[String, IndexedSeq[String]], InputStream) => T,
    inputStream: InputStream
  ): HttpResponse[T] = {
    val responseCode: Int = conn.getResponseCode
    val headers: Map[String, IndexedSeq[String]] = getResponseHeaders(conn)
    val encoding: Option[String] = headers.get("Content-Encoding").flatMap(_.headOption)
    // HttpURLConnection won't redirect from https <-> http, so we handle manually here
    (if (conn.getInstanceFollowRedirects && (responseCode == 301 || responseCode == 302)) {
      headers.get("Location").flatMap(_.headOption).map(location => {
        doConnection(parser, new URL(location), DefaultConnectFunc)
      })
    } else None).getOrElse{
      val body: T = {
        val shouldDecompress = compress && inputStream != null
        val theStream = if (shouldDecompress && encoding.exists(_.equalsIgnoreCase("gzip"))) {
          new GZIPInputStream(inputStream)
        } else if(shouldDecompress && encoding.exists(_.equalsIgnoreCase("deflate"))) {
          new InflaterInputStream(inputStream)
        } else inputStream
        parser(responseCode, headers, theStream)
      }
      HttpResponse[T](body, responseCode, headers)
    }
  }

  private def getResponseHeaders(conn: HttpURLConnection): Map[String, IndexedSeq[String]] = {
    // There can be multiple values for the same response header key (this is common with Set-Cookie)
    // http://stackoverflow.com/questions/4371328/are-duplicate-http-response-headers-acceptable

    // according to javadoc, there can be a headerField value where the HeaderFieldKey is null
    // at the 0th row in some implementations.  In that case it's the http status line
    new TreeMap[String, IndexedSeq[String]]()(Ordering.by(_.toLowerCase)) ++ {
      Stream.from(0).map(i => i -> conn.getHeaderField(i)).takeWhile(_._2 != null).map{ case (i, value) =>
        Option(conn.getHeaderFieldKey(i)).getOrElse("Status") -> value
      }.groupBy(_._1).mapValues(_.map(_._2).toIndexedSeq)
    }
  }
  
  private def closeStreams(conn: HttpURLConnection) {
    try {
      conn.getInputStream.close
    } catch {
      case e: Exception => //ignore
    }
    try {
      conn.getErrorStream.close
    } catch {
      case e: Exception => //ignore
    }
  }

  /** Standard form POST request */
  def postForm: HttpRequest = postForm(Nil)

  /** Standard form POST request and set some parameters. Same as .postForm.params(params) */
  def postForm(params: Seq[(String, String)]): HttpRequest = {
    copy(method="POST", connectFunc=FormBodyConnectFunc, urlBuilder=PlainUrlFunc)
      .header("content-type", "application/x-www-form-urlencoded").params(params)
  }

  /** Raw data POST request. String bytes written out using configured charset */
  def postData(data: String): HttpRequest = body(data).method("POST")

  /** Raw byte data POST request */
  def postData(data: Array[Byte]): HttpRequest = body(data).method("POST")

  /** Raw data PUT request. String bytes written out using configured charset */
  def put(data: String): HttpRequest = body(data).method("PUT")

  /** Raw byte data PUT request */
  def put(data: Array[Byte]): HttpRequest = body(data).method("PUT")

  private def body(data: String): HttpRequest = copy(connectFunc=StringBodyConnectFunc(data))
  private def body(data: Array[Byte]): HttpRequest = copy(connectFunc=ByteBodyConnectFunc(data))

  /** Multipart POST request.
    *
    * This is probably what you want if you need to upload a mix of form data and binary data (like a photo)
    */
  def postMulti(parts: MultiPart*): HttpRequest = {
    copy(method="POST", connectFunc=MultiPartConnectFunc(parts), urlBuilder=PlainUrlFunc)
  }
  
  /** Execute this request and parse http body as Array[Byte] */
  def asBytes: HttpResponse[Array[Byte]] = execute(HttpConstants.readBytes)
  /** Execute this request and parse http body as String using server charset or configured charset*/
  def asString: HttpResponse[String] = exec((code: Int, headers: Map[String, IndexedSeq[String]], is: InputStream) => {
    val reqCharset: String = headers.get("content-type").flatMap(_.headOption).flatMap(ct => {
      HttpConstants.CharsetRegex.findFirstMatchIn(ct).map(_.group(1))
    }).getOrElse(charset)
    HttpConstants.readString(is, reqCharset)
  })
  /** Execute this request and parse http body as query string key-value pairs */
  def asParams: HttpResponse[Seq[(String, String)]] = execute(HttpConstants.readParams(_, charset))
  /** Execute this request and parse http body as query string key-value pairs */
  def asParamMap: HttpResponse[Map[String, String]] = execute(HttpConstants.readParamMap(_, charset))
  /** Execute this request and parse http body as a querystring containing oauth_token and oauth_token_secret tupple */
  def asToken: HttpResponse[Token] = execute(HttpConstants.readToken)
}

case object DefaultConnectFunc extends Function2[HttpRequest, HttpURLConnection, Unit] {
  def apply(req: HttpRequest, conn: HttpURLConnection): Unit = {
    conn.connect
  }

  override def toString = "DefaultConnectFunc"
}

case object FormBodyConnectFunc extends Function2[HttpRequest, HttpURLConnection, Unit] {
  def apply(req: HttpRequest, conn: HttpURLConnection): Unit = {
    conn.setDoOutput(true)
    conn.connect
    conn.getOutputStream.write(HttpConstants.toQs(req.params, req.charset).getBytes(req.charset))
  }

  override def toString = "FormBodyConnectFunc"
}

case class ByteBodyConnectFunc(data: Array[Byte]) extends Function2[HttpRequest, HttpURLConnection, Unit] {
  def apply(req: HttpRequest, conn: HttpURLConnection): Unit = {
    conn.setDoOutput(true)
    conn.connect
    conn.getOutputStream.write(data)
  }

  override def toString = "ByteBodyConnectFunc(Array[Byte]{" + data.length + "})"
}

case class StringBodyConnectFunc(data: String) extends Function2[HttpRequest, HttpURLConnection, Unit] {
  def apply(req: HttpRequest, conn: HttpURLConnection): Unit = {
    conn.setDoOutput(true)
    conn.connect
    conn.getOutputStream.write(data.getBytes(req.charset))
  }

  override def toString = "StringBodyConnectFunc(" + data + ")"
}

case class MultiPartConnectFunc(parts: Seq[MultiPart]) extends Function2[HttpRequest, HttpURLConnection, Unit] {
  def apply(req: HttpRequest, conn: HttpURLConnection): Unit = {
    val CrLf = "\r\n"
    val Pref = "--"
    val Boundary = "--gc0pMUlT1B0uNdArYc0p"
    val ContentDisposition = "Content-Disposition: form-data; name=\""
    val Filename = "\"; filename=\""
    val ContentType = "Content-Type: "

    conn.setDoOutput(true)
    conn.setDoInput(true)
    conn.setUseCaches(false)
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + Boundary)
    conn.setRequestProperty("MIME-Version", "1.0")

    // encode params up front for the length calculation
    val paramBytes = req.params.map(p => (p._1.getBytes(req.charset) -> p._2.getBytes(req.charset)))

    val partBytes = parts.map(p => (p.name.getBytes(req.charset),
                                    p.filename.getBytes(req.charset),
                                    p))

    // we need to pre-calculate the Content-Length of this HttpRequest because most servers don't
    // support chunked transfer
    val totalBytesToSend: Long = {
      val paramOverhead = Pref.length + Boundary.length + ContentDisposition.length + 1 + (CrLf.length * 4)
      val paramsLength = paramBytes.map(p => p._1.length + p._2.length + paramOverhead).sum

      val fileOverhead = Pref.length + Boundary.length + ContentDisposition.length + Filename.length + 1 +
        (CrLf.length * 5) + ContentType.length

      val filesLength =
        partBytes.map(p => fileOverhead + p._1.length + p._2.length + p._3.mime.length + p._3.numBytes).sum

      val finaleBoundaryLength = (Pref.length * 2) + Boundary.length + CrLf.length

      paramsLength + filesLength + finaleBoundaryLength
    }

    HttpConstants.setFixedLengthStreamingMode(conn, totalBytesToSend)

    val out = conn.getOutputStream()

    def writeBytes(s: String) {
      // this is only used for the structural pieces, not user input, so should be plain old ascii
      out.write(s.getBytes(HttpConstants.utf8))
    }

    paramBytes.foreach {
     case (name, value) =>
       writeBytes(Pref + Boundary + CrLf)
       writeBytes(ContentDisposition)
       out.write(name)
       writeBytes("\"" + CrLf)
       writeBytes(CrLf)
       out.write(value)
       writeBytes(CrLf)
    }

    val buffer = new Array[Byte](req.sendBufferSize)

    partBytes.foreach {
      case(name, filename, part) =>
        writeBytes(Pref + Boundary + CrLf)
        writeBytes(ContentDisposition)
        out.write(name)
        writeBytes(Filename)
        out.write(filename)
        writeBytes("\"" + CrLf)
        writeBytes(ContentType + part.mime + CrLf + CrLf)

        var bytesWritten: Long = 0L
        def readOnce {
          val len = part.data.read(buffer)
          if (len > 0) {
            out.write(buffer, 0, len)
            bytesWritten += len
            part.writeCallBack(bytesWritten)
          }

          if (len >= 0) {
            readOnce
          }
        }

        readOnce

        writeBytes(CrLf)
    }

    writeBytes(Pref + Boundary + Pref + CrLf)

    out.flush()
    out.close()
  }

  override def toString = "MultiPartConnectFunc(" + parts + ")"
}

case object QueryStringUrlFunc extends Function1[HttpRequest, String] {
  def apply(req: HttpRequest): String = {
    HttpConstants.appendQs(req.url, req.params, req.charset)
  }

  override def toString = "QueryStringUrlFunc"
}

case object PlainUrlFunc extends Function1[HttpRequest, String] {
  def apply(req: HttpRequest): String = req.url

  override def toString = "QueryStringUrlFunc"
}

/**
  * Mostly helper methods
  */
object HttpConstants {
  val CharsetRegex = new Regex("(?i)\\bcharset=\\s*\"?([^\\s;\"]*)")

  type HttpExec = (HttpRequest, HttpURLConnection) => Unit

  def defaultOptions: Seq[HttpOptions.HttpOption] = Seq(
    HttpOptions.connTimeout(1000),
    HttpOptions.readTimeout(5000),
    HttpOptions.followRedirects(false)
  )

  val setFixedLengthStreamingMode: (HttpURLConnection, Long) => Unit = {
    val connClass = classOf[HttpURLConnection]
    val (isLong, theMethod) = try {
      true -> connClass.getDeclaredMethod("setFixedLengthStreamingMode", java.lang.Long.TYPE)
    } catch {
      case e: NoSuchMethodException =>
        false -> connClass.getDeclaredMethod("setFixedLengthStreamingMode", java.lang.Integer.TYPE)
    }
    (conn, length) => 
      if (isLong) {
        theMethod.invoke(conn, length: java.lang.Long)
      } else {
        if (length > Int.MaxValue) {
          throw new RuntimeException("Failing attempt to upload file greater than 2GB on java version < 1.7")
        }
        theMethod.invoke(conn, length.toInt: java.lang.Integer)
      }
  }

  def urlEncode(name: String, charset: String): String = URLEncoder.encode(name, charset)
  def urlDecode(name: String, charset: String): String = URLDecoder.decode(name, charset)
  def base64(bytes: Array[Byte]): String = new String(Base64.encode(bytes))
  def base64(in: String): String = base64(in.getBytes(utf8))
  
  def toQs(params: Seq[(String,String)], charset: String): String = {
    params.map(p => urlEncode(p._1, charset) + "=" + urlEncode(p._2, charset)).mkString("&")
  }

  def appendQs(url:String, params: Seq[(String,String)], charset: String): String = {
    url + (if(params.isEmpty) "" else {
      (if(url.contains("?")) "&" else "?") + toQs(params, charset)
    })
  }
  
  def readString(is: InputStream): String = readString(is, utf8)
  /**
   * [lifted from lift]
   */
  def readString(is: InputStream, charset: String): String = {
    if (is == null) {
      ""
    } else {
      val in = new InputStreamReader(is, charset)
      val bos = new StringBuilder
      val ba = new Array[Char](4096)

      def readOnce {
        val len = in.read(ba)
        if (len > 0) bos.appendAll(ba, 0, len)
        if (len >= 0) readOnce
      }

      readOnce
      bos.toString
    }
  }
  
  
  /**
   * [lifted from lift]
   * Read all data from a stream into an Array[Byte]
   */
  def readBytes(in: InputStream): Array[Byte] = {
    if (in == null) {
      Array[Byte]()
    } else {
      val bos = new ByteArrayOutputStream
      val ba = new Array[Byte](4096)

      def readOnce {
        val len = in.read(ba)
        if (len > 0) bos.write(ba, 0, len)
        if (len >= 0) readOnce
      }

      readOnce

      bos.toByteArray
    }
  }

  def readParams(in: InputStream, charset: String = utf8): Seq[(String,String)] = {
    readString(in, charset).split("&").flatMap(_.split("=") match {
      case Array(k,v) => Some(urlDecode(k, charset), urlDecode(v, charset))
      case _ => None
    }).toList
  }

  def readParamMap(in: InputStream, charset: String = utf8): Map[String, String] = Map(readParams(in, charset):_*)

  def readToken(in: InputStream): Token = {
    val params = readParamMap(in)
    Token(params("oauth_token"), params("oauth_token_secret"))
  }

  def proxy(host: String, port: Int, proxyType: Proxy.Type = Proxy.Type.HTTP): Proxy = {
    new Proxy(proxyType, new InetSocketAddress(host, port))
  }

  val utf8 = "UTF-8"

}

/** Default entry point to this library */
object Http extends BaseHttp

/**
  * Extends and override this class to setup your own defaults
  *
  * @param proxyConfig http proxy; defaults to the Java default proxy (see http://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html).
 *              You can use [[scalaj.http.HttpConstants.proxy]] to specify an alternate proxy, or specify
 *              [[java.net.Proxy.NO_PROXY]] to explicitly use not use a proxy.
  * @param options set things like timeouts, ssl handling, redirect following
  * @param charset charset to use for encoding request and decoding response
  * @param sendBufferSize buffer size for multipart posts
  * @param userAgent User-Agent request header
  * @param compress use HTTP Compression
  */
class BaseHttp (
  proxyConfig: Option[Proxy] = None,
  options: Seq[HttpOptions.HttpOption] = HttpConstants.defaultOptions,
  charset: String = HttpConstants.utf8,
  sendBufferSize: Int = 4096,
  userAgent: String = "scalaj-http/1.0",
  compress: Boolean = true
) {

  /** Create a new [[scalaj.http.HttpRequest]]
   *
   * @param url the full url of the request. Querystring params can be added to a get request with the .params methods
   */
  def apply(url: String): HttpRequest = HttpRequest(
    url = url,
    method = "GET",
    connectFunc = DefaultConnectFunc,
    params = Nil,
    headers = Seq("User-Agent" -> userAgent),
    options = options,
    proxyConfig = proxyConfig,
    charset = charset,
    sendBufferSize = sendBufferSize,
    urlBuilder = QueryStringUrlFunc,
    compress = compress
  )  
}
