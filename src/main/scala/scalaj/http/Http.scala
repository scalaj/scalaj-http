package scalaj.http

import java.net.{HttpURLConnection, URL, URLEncoder, URLDecoder}
import java.io.{DataOutputStream, InputStream, BufferedReader, InputStreamReader, ByteArrayOutputStream}
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
  def apply(name: String, filename: String, mime: String, data: String): MultiPart = MultiPart(name, filename, mime, data.getBytes(Http.charset))
}

case class MultiPart(val name: String, val filename: String, val mime: String, val data: Array[Byte])

case class HttpException(val code: Int, val message: String, val body: String) extends RuntimeException(code + ": " + message)

object Http {
  def apply(url: String):Request = get(url)
  
  type HttpExec = (Request, HttpURLConnection) => Unit
  type HttpUrl = Request => URL
  
  object Request {
    def apply(exec: HttpExec, url: HttpUrl, method:String):Request = Request(method, exec, url, Nil, Nil, HttpOptions.method(method)::defaultOptions)
  }
  case class Request(method: String, exec: HttpExec, url: HttpUrl, params: List[(String,String)], headers: List[(String,String)], options: List[HttpOptions.HttpOption]) {
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
    def oauth(consumer: Token, token: Option[Token], verifier: Option[String]):Request = OAuth.sign(this, consumer, token, verifier)
    
    def getUrl: URL = url(this)
    
    def apply[T](parser: InputStream => T): T = process((conn:HttpURLConnection) => tryParse(conn.getInputStream(), parser))
    
    def process[T](processor: HttpURLConnection => T): T = {

      url(this).openConnection match {
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
              throw new HttpException(conn.getResponseCode, conn.getResponseMessage, tryParse(conn.getErrorStream(), readString))
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
    
    def asString: String = apply(readString)
    
    def asXml: Elem = apply(is => XML.load(is))
    
    def asParams: List[(String,String)] = {
      asString.split("&").flatMap(_.split("=") match {
        case Array(k,v) => Some(urlDecode(k), urlDecode(v))
        case _ => None
      }).toList
    }
    
    def asParamMap: Map[String, String] = Map(asParams:_*)
    
    def asToken: Token = {
      val params = asParamMap
      Token(params("oauth_token"), params("oauth_token_secret"))
    }
  }
  
  def tryParse[E](is: InputStream, parser: InputStream => E):E = try {
    parser(is)
  } finally {
    is.close
  }
  
  /**
   * [lifted from lift]
   */
  def readString(is: InputStream): String = {
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

  val defaultOptions = List(HttpOptions.connTimeout(100), HttpOptions.readTimeout(500))
  
  def urlEncode(name: String): String = URLEncoder.encode(name, charset)
  def urlDecode(name: String): String = URLDecoder.decode(name, charset)
  def base64(bytes: Array[Byte]): String = new String(Base64.encode(bytes))
  def base64(in: String): String = base64(in.getBytes(charset))
  
  def toQs(params: List[(String,String)]) = params.map(p => urlEncode(p._1) + "=" + urlEncode(p._2)).mkString("&")
  def appendQs(url:String, params: List[(String,String)]) = url + (if(params.isEmpty) "" else {
    (if(url.contains("?")) "&" else "?") + toQs(params)
  })
  
  def appendQsHttpUrl(url: String): HttpUrl = r => new URL(appendQs(url, r.params))
  def noopHttpUrl(url :String): HttpUrl = r => new URL(url)
  
  def get(url: String): Request = {
    val getFunc: HttpExec = (req,conn) => conn.connect
    
    Request(getFunc, appendQsHttpUrl(url), "GET")
  }
  
  
  val CrLf = "\r\n"
  val Pref = "--"
  val Boundary = "gc0pMUlT1B0uNdArYc0p"
 
  def multipart(url: String, parts: MultiPart*): Request = {
     val postFunc: Http.HttpExec = (req,conn) => {

       conn.setDoOutput(true)
       conn.setDoInput(true)
       conn.setUseCaches(false)
       conn.setRequestMethod("POST")
       conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + Boundary)
       conn.setRequestProperty("MIME-Version", "1.0")

       val out = new DataOutputStream(conn.getOutputStream())

       req.params.foreach {
         case (name, value) =>
           out.writeBytes(Pref + Boundary + CrLf)
           out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"")
           out.writeBytes(CrLf + CrLf + value.toString + CrLf)
       }

       parts.foreach { part =>
         out.writeBytes(Pref + Boundary + CrLf)
         out.writeBytes("Content-Disposition: form-data; name=\"" + part.name + "\"; filename=\"" + part.filename + "\"" + CrLf)
         out.writeBytes("Content-Type: " + part.mime + CrLf + CrLf)

         out.write(part.data)

         out.writeBytes(CrLf + Pref + Boundary + Pref + CrLf)
       }


       out.flush()
       out.close()
     }
     Http.Request(postFunc, Http.noopHttpUrl(url), "POST")
  }
  
  def postData(url: String, data: String): Request = postData(url, data.getBytes(charset))
  def postData(url: String, data: Array[Byte]): Request = {
    val postFunc: HttpExec = (req,conn) => {
      conn.setDoOutput(true)
      conn.connect
      conn.getOutputStream.write(data)
    }
    Request(postFunc, noopHttpUrl(url), "POST")
  }
  
  def post(url: String): Request = {
    val postFunc: HttpExec = (req,conn) => {
      conn.setDoOutput(true)
      conn.connect
      conn.getOutputStream.write(toQs(req.params).getBytes(charset))
    }
    Request(postFunc, noopHttpUrl(url), "POST").header("content-type", "application/x-www-form-urlencoded")
  }
  val charset = "UTF-8"
}
