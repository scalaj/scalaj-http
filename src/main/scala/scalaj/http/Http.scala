package scalaj.http

import java.lang.reflect.Field
import java.net.{HttpURLConnection, InetSocketAddress, Proxy, URL, URLEncoder, URLDecoder}
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


object HttpOptions {
  type HttpOption = HttpURLConnection => Unit

  val officalHttpMethods = Set("GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE")
  
  private val methodField: Field = {
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

case class HttpException(val message: String, cause: Throwable) extends RuntimeException(message, cause)


case class HttpResponse[T](body: T, code: Int, headers: Map[String, String])

case class HttpRequest(
  url: String,
  method: String,
  exec: HttpConstants.HttpExec,
  params: Seq[(String,String)], 
  headers: Seq[(String,String)],
  options: Seq[HttpOptions.HttpOption],
  proxy: Proxy,
  charset: String,
  sendBufferSize: Int,
  urlBuilder: (HttpRequest => String)
) {

  def params(p: Map[String, String]): HttpRequest = params(p.toSeq)
  def params(p: Seq[(String,String)]): HttpRequest = copy(params = params ++ p)
  def headers(h: Map[String, String]): HttpRequest = headers(h.toSeq)
  def headers(h: Seq[(String,String)]): HttpRequest = copy(headers = headers ++ h)
  def param(key: String, value: String): HttpRequest = params(Seq(key -> value))
  def header(key: String, value: String): HttpRequest = headers(Seq(key -> value))
  def options(o: Seq[HttpOptions.HttpOption]): HttpRequest = copy(options = o ++ options)
  def option(o: HttpOptions.HttpOption): HttpRequest = copy(options = o +: options)
  
  def auth(user: String, password: String) = header("Authorization", "Basic " + HttpConstants.base64(user + ":" + password))
  
  def oauth(consumer: Token): HttpRequest = oauth(consumer, None, None)
  def oauth(consumer: Token, token: Token): HttpRequest = oauth(consumer, Some(token), None)
  def oauth(consumer: Token, token: Token, verifier: String): HttpRequest = oauth(consumer, Some(token), Some(verifier))
  def oauth(consumer: Token, token: Option[Token], verifier: Option[String]): HttpRequest = {
    OAuth.sign(this, consumer, token, verifier)
  }

  def method(m: String): HttpRequest = option(HttpOptions.method(m))

  def proxy(host: String, port: Int): HttpRequest = proxy(host, port, Proxy.Type.HTTP)
  def proxy(host: String, port: Int, proxyType: Proxy.Type): HttpRequest = {
    copy(proxy = new Proxy(proxyType, new InetSocketAddress(host, port)))
  }
  def proxy(proxy: Proxy): HttpRequest = {
    copy(proxy = proxy)
  }
  
  def charset(cs: String): HttpRequest = copy(charset = cs)

  def sendBufferSize(numBytes: Int): HttpRequest = copy(sendBufferSize = numBytes)
  
  def execute[T](
    parser: InputStream => T = ((is: InputStream) => HttpConstants.readString(is, charset)),
    stream: Boolean = false
  ): HttpResponse[T] = {
    new URL(urlBuilder(this)).openConnection(proxy) match {
      case conn: HttpURLConnection =>
        conn.setInstanceFollowRedirects(false)
        headers.reverse.foreach{ case (name, value) => 
          conn.setRequestProperty(name, value)
        }
        options.reverse.foreach(_(conn))

        exec(this, conn)
        try {
          toResponse(conn, parser, conn.getInputStream, stream)
        } catch {
          case e: java.io.IOException if conn.getResponseCode > 0 =>
            toResponse(conn, parser, conn.getErrorStream, stream)
        }
    }
  }

  private def toResponse[T](
    conn: HttpURLConnection,
    parser: InputStream => T,
    inputStream: InputStream,
    isStreaming: Boolean
  ): HttpResponse[T] = {
    val headers = getResponseHeaders(conn)
    val encoding = headers.get("Content-Encoding")
    val body = HttpConstants.tryParse(inputStream, parser, encoding)
    if (!isStreaming) {
      closeStreams(conn)  
    }
    HttpResponse[T](body, conn.getResponseCode, headers)
  }

  private def getResponseHeaders(conn: HttpURLConnection): Map[String, String] = {
    // combining duplicate header values with a comma: 
    // http://stackoverflow.com/questions/4371328/are-duplicate-http-response-headers-acceptable

    // according to javadoc, there can be a headerField value where the HeaderFieldKey is null
    // at the 0th row in some implementations.  In that case it's the http status line
    Stream.from(0).map(i => i -> conn.getHeaderField(i)).takeWhile(_._2 != null).map{ case (i, value) =>
      Option(conn.getHeaderFieldKey(i)).getOrElse("Status") -> value
    }.groupBy(_._1).mapValues(_.map(_._2).mkString(", "))
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

  def postForm: HttpRequest = postForm(Nil)

  def postForm(params: Seq[(String, String)]): HttpRequest = {
    val postFunc: HttpConstants.HttpExec = (req, conn) => {
      conn.setDoOutput(true)
      conn.connect
      conn.getOutputStream.write(HttpConstants.toQs(req.params, req.charset).getBytes(req.charset))
    }
    copy(method="POST", exec=postFunc, urlBuilder=(req => req.url))
      .header("content-type", "application/x-www-form-urlencoded").params(params)
  }

  def postData(data: String): HttpRequest = postData(data.getBytes(charset))

  def postData(data: Array[Byte]): HttpRequest = {
    val postFunc: HttpConstants.HttpExec = (req, conn) => {
      conn.setDoOutput(true)
      conn.connect
      conn.getOutputStream.write(data)
    }
    copy(method="POST", exec=postFunc, urlBuilder=(req => req.url))
  }

  val CrLf = "\r\n"
  val Pref = "--"
  val Boundary = "--gc0pMUlT1B0uNdArYc0p"
  val ContentDisposition = "Content-Disposition: form-data; name=\""
  val Filename = "\"; filename=\""
  val ContentType = "Content-Type: "

  def postMulti(parts: MultiPart*): HttpRequest = {
    val postFunc: HttpConstants.HttpExec = (req, conn) => {

      conn.setDoOutput(true)
      conn.setDoInput(true)
      conn.setUseCaches(false)
      conn.setRequestMethod("POST")
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

          var bytesWritten = 0
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
    copy(method="POST", exec=postFunc, urlBuilder=(req => req.url))
  }
  
  def asBytes: HttpResponse[Array[Byte]] = execute(HttpConstants.readBytes)
  def asString: HttpResponse[String] = execute(HttpConstants.readString(_, charset))
  def asParams: HttpResponse[Seq[(String, String)]] = execute(HttpConstants.readParams(_, charset))
  def asParamMap: HttpResponse[Map[String, String]] = execute(HttpConstants.readParamMap(_, charset))
  def asToken: HttpResponse[Token] = execute(HttpConstants.readToken)
}



object HttpConstants {
  type HttpExec = (HttpRequest, HttpURLConnection) => Unit

  def defaultOptions: Seq[HttpOptions.HttpOption] = Seq(
    HttpOptions.connTimeout(1000),
    HttpOptions.readTimeout(5000),
    HttpOptions.followRedirects(false)
  )

  def tryParse[E](is: InputStream, parser: InputStream => E, encoding: Option[String]): E = {
    val theStream = if (encoding.exists(_.contains("gzip"))) {
      new GZIPInputStream(is)
    } else if(encoding.exists(_.contains("deflate"))) {
      new InflaterInputStream(is)
    } else is
    try {
      parser(theStream)
    } finally {
      theStream.close
    }
  }

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
  
  
  /**
   * [lifted from lift]
   * Read all data from a stream into an Array[Byte]
   */
  def readBytes(in: InputStream): Array[Byte] = {
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

  val utf8 = "UTF-8"
}
object Http extends BaseHttp

class BaseHttp (
  proxy: Proxy = Proxy.NO_PROXY, 
  options: Seq[HttpOptions.HttpOption] = HttpConstants.defaultOptions,
  charset: String = HttpConstants.utf8,
  sendBufferSize: Int = 4096,
  userAgent: String = "scalaj-http/1.0"
) {

  def apply(url: String): HttpRequest =  HttpRequest(
    url = url,
    method = "GET",
    exec = (req,conn) => conn.connect,
    params = Nil,
    headers = Seq("User-Agent" -> userAgent, "Accept-Encoding" -> "gzip,deflate"),
    options = options,
    proxy = proxy,
    charset = charset,
    sendBufferSize = sendBufferSize,
    urlBuilder = (req) => HttpConstants.appendQs(req.url, req.params, req.charset)
  )  
}
