package scalaj.http

import java.net.{HttpURLConnection, InetSocketAddress, Proxy, URL, URLEncoder, URLDecoder}
import java.io.{DataOutputStream, InputStream, BufferedReader, InputStreamReader, ByteArrayInputStream, 
  ByteArrayOutputStream}
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import java.security.cert.X509Certificate
import scala.xml.{Elem, XML}


object HttpOptions {
  type HttpOption = HttpURLConnection => Unit
  
  def method(method: String):HttpOption = c => c.setRequestMethod(method)
  def connTimeout(timeout: Int):HttpOption = c => c.setConnectTimeout(timeout)
  def readTimeout(timeout: Int):HttpOption = c => c.setReadTimeout(timeout)
  def allowUnsafeSSL:HttpOption = c => c match {
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
    apply(name, filename, mime, data.getBytes(Http.utf8))
  }
  def apply(name: String, filename: String, mime: String, data: Array[Byte]): MultiPart = {
    MultiPart(name, filename, mime, new ByteArrayInputStream(data), data.length)
  }
}

case class MultiPart(val name: String, val filename: String, val mime: String, val data: InputStream, val numBytes: Int)

case class HttpException(val code: Int, val message: String, val body: String, cause: Throwable) extends 
  RuntimeException(code + ": " + message, cause)

object Http {
  def apply(url: String):Request = get(url)
  
  type HttpExec = (Request, HttpURLConnection) => Unit
  type HttpUrl = Request => URL
  
  object Request {
    def apply(exec: HttpExec, url: HttpUrl, method:String):Request = Request(method, exec, url, Nil, Nil, 
      HttpOptions.method(method)::defaultOptions)
  }

  case class Request(method: String, exec: HttpExec, url: HttpUrl, params: List[(String,String)], 
      headers: List[(String,String)], options: List[HttpOptions.HttpOption], proxy: Proxy = Proxy.NO_PROXY,
      charset: String = Http.utf8) {

    def params(p: (String, String)*):Request = params(p.toList)
    def params(p: List[(String,String)]):Request = Request(method, exec,url, p, headers,options)
    def headers(h: (String,String)*):Request = headers(h.toList)
    def headers(h: List[(String,String)]):Request = Request(method,exec,url, params, h ++ headers,options)
    def param(key: String, value: String):Request = Request(method,exec,url,(key,value)::params, headers,options)
    def header(key: String, value: String):Request = Request(method,exec,url,params, (key,value)::headers,options)
    def options(o: HttpOptions.HttpOption*):Request = options(o.toList)
    def options(o: List[HttpOptions.HttpOption]):Request = Request(method,exec, url, params, headers, o ++ options)
    def option(o: HttpOptions.HttpOption):Request = Request(method,exec,url, params, headers,o::options)
    
    def auth(user: String, password: String) = header("Authorization", "Basic " + base64(user + ":" + password))
    
    def oauth(consumer: Token):Request = oauth(consumer, None, None)
    def oauth(consumer: Token, token: Token):Request = oauth(consumer, Some(token), None)
    def oauth(consumer: Token, token: Token, verifier: String):Request = oauth(consumer, Some(token), Some(verifier))
    def oauth(consumer: Token, token: Option[Token], verifier: Option[String]):Request = {
      OAuth.sign(this, consumer, token, verifier)
    }

    def proxy(host: String, port: Int) = copy(proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)))

    def charset(cs: String): Request = copy(charset = cs)
    
    def getUrl: URL = url(this)
    
    def apply[T](parser: InputStream => T): T = process((conn:HttpURLConnection) => tryParse(conn.getInputStream(), parser))
    
    def process[T](processor: HttpURLConnection => T): T = {

      def getErrorBody(errorStream: InputStream): String = {
        if (errorStream != null) {
          tryParse(errorStream, readString(_, charset))  
        } else ""
      }

      url(this).openConnection(proxy) match {
        case conn:HttpURLConnection =>
          conn.setInstanceFollowRedirects(true)
          headers.reverse.foreach{case (name, value) => 
            conn.setRequestProperty(name, value)
          }
          options.reverse.foreach(_(conn))

          exec(this, conn)
          try {
            processor(conn)
          } catch {
            case e: java.io.IOException =>
              throw new HttpException(conn.getResponseCode, conn.getResponseMessage, 
                getErrorBody(conn.getErrorStream), e)
          }
      }
    }
    
    def getResponseHeaders(conn: HttpURLConnection): Map[String, List[String]] = {
      // according to javadoc, there can be a headerField value where the HeaderFieldKey is null
      // at the 0th row in some implementations.  In that case it's the http status line
      Stream.from(0).map(i => i -> conn.getHeaderField(i)).takeWhile(_._2 != null).map{ case (i, value) =>
        Option(conn.getHeaderFieldKey(i)).getOrElse("Status") -> value
      }.groupBy(_._1).mapValues(_.map(_._2).toList)
    }
    
    def responseCode: Int = process((conn:HttpURLConnection) => conn.getResponseCode)
    
    def asCodeHeaders: (Int, Map[String, List[String]]) = process { conn: HttpURLConnection => 
      (conn.getResponseCode, getResponseHeaders(conn))
    }
    
    def asHeadersAndParse[T](parser: InputStream => T): (Int, Map[String, List[String]], T) = process { conn: HttpURLConnection =>
      (conn.getResponseCode, getResponseHeaders(conn), tryParse(conn.getInputStream(), parser))
    }
    
    def asBytes: Array[Byte] = apply(readBytes)
    
    def asString: String = apply(readString(_, charset))
    def asXml: Elem = apply(readXml)
    def asParams: List[(String, String)] = apply(readParams(_, charset))
    def asParamMap: Map[String, String] = apply(readParamMap(_, charset))
    def asToken: Token = apply(readToken)
  }
  
  def tryParse[E](is: InputStream, parser: InputStream => E): E = try {
    parser(is)
  } finally {
    is.close
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

  def readXml(in: InputStream): Elem = XML.load(in)

  def readParams(in: InputStream, charset: String = utf8): List[(String,String)] = {
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

  val defaultOptions = List(HttpOptions.connTimeout(100), HttpOptions.readTimeout(500))
  
  def urlEncode(name: String, charset: String): String = URLEncoder.encode(name, charset)
  def urlDecode(name: String, charset: String): String = URLDecoder.decode(name, charset)
  def base64(bytes: Array[Byte]): String = new String(Base64.encode(bytes))
  def base64(in: String): String = base64(in.getBytes(utf8))
  
  def toQs(params: List[(String,String)], charset: String) = {
    params.map(p => urlEncode(p._1, charset) + "=" + urlEncode(p._2, charset)).mkString("&")
  }

  def appendQs(url:String, params: List[(String,String)], charset: String) = {
    url + (if(params.isEmpty) "" else {
      (if(url.contains("?")) "&" else "?") + toQs(params, charset)
    })
  }
  
  def appendQsHttpUrl(url: String): HttpUrl = r => new URL(appendQs(url, r.params, r.charset))
  def noopHttpUrl(url :String): HttpUrl = r => new URL(url)
  
  def get(url: String): Request = {
    val getFunc: HttpExec = (req,conn) => conn.connect
    
    Request(getFunc, appendQsHttpUrl(url), "GET")
  }
  
  
  val CrLf = "\r\n"
  val Pref = "--"
  val Boundary = "--gc0pMUlT1B0uNdArYc0p"
  val ContentDisposition = "Content-Disposition: form-data; name=\""
  val Filename = "\"; filename=\""
  val ContentType = "Content-Type: "
 
  def multipart(url: String, parts: MultiPart*): Request = {
    val postFunc: Http.HttpExec = (req, conn) => {

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

      // we need to pre-calculate the Content-Length of this request because most servers don't
      // support chunked transfer
      val totalBytesToSend = {
        val paramOverhead = Pref.length + Boundary.length + ContentDisposition.length + 1 + (CrLf.length * 4)
        val paramsLength = paramBytes.map(p => p._1.length + p._2.length + paramOverhead).sum

        val fileOverhead = Pref.length + Boundary.length + ContentDisposition.length + Filename.length + 1 +
          (CrLf.length * 5) + ContentType.length

        val filesLength =
          partBytes.map(p => fileOverhead + p._1.length + p._2.length + p._3.mime.length + p._3.numBytes).sum

        val finaleBoundaryLength = (Pref.length * 2) + Boundary.length + CrLf.length
        
        paramsLength + filesLength + finaleBoundaryLength
      }

      conn.setFixedLengthStreamingMode(totalBytesToSend)

      val out = conn.getOutputStream()

      def writeBytes(s: String) {
        // this is only used for the structural pieces, not user input, so should be plain old ascii
        out.write(s.getBytes(Http.utf8))
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

      val buffer = new Array[Byte](4096)

      partBytes.foreach { 
        case(name, filename, part) =>
          writeBytes(Pref + Boundary + CrLf)
          writeBytes(ContentDisposition)
          out.write(name)
          writeBytes(Filename)
          out.write(filename)
          writeBytes("\"" + CrLf)
          writeBytes(ContentType + part.mime + CrLf + CrLf)

          def readOnce {
            val len = part.data.read(buffer)
            if (len > 0) out.write(buffer, 0, len)
            if (len >= 0) readOnce
          }

          readOnce

          writeBytes(CrLf)
      }

      writeBytes(Pref + Boundary + Pref + CrLf)

      out.flush()
      out.close()
    }
    Http.Request(postFunc, Http.noopHttpUrl(url), "POST")
  }
  
  def postData(url: String, data: String): Request = postData(url, data.getBytes(utf8))
  def postData(url: String, data: Array[Byte]): Request = {
    val postFunc: HttpExec = (req, conn) => {
      conn.setDoOutput(true)
      conn.connect
      conn.getOutputStream.write(data)
    }
    Request(postFunc, noopHttpUrl(url), "POST")
  }
  
  def post(url: String): Request = {
    val postFunc: HttpExec = (req, conn) => {
      conn.setDoOutput(true)
      conn.connect
      conn.getOutputStream.write(toQs(req.params, req.charset).getBytes(req.charset))
    }
    Request(postFunc, noopHttpUrl(url), "POST").header("content-type", "application/x-www-form-urlencoded")
  }
  val utf8 = "UTF-8"
}
