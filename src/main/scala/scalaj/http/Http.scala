package scalaj.http

import java.net.{HttpURLConnection, URL, URLEncoder, URLDecoder}
import java.io.{DataOutputStream, InputStream, BufferedReader, InputStreamReader}
import org.apache.commons.codec.binary.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import java.security.cert.X509Certificate


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
}

class HttpException(val code: Int, message: String) extends RuntimeException("ResponseCode[" + code + "] " + message)

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
    def headers(h: List[(String,String)]):Request = Request(method,exec,url, params, h,options)
    def param(key: String, value: String):Request = Request(method,exec,url,(key,value)::params, headers,options)
    def header(key: String, value: String):Request = Request(method,exec,url,params, (key,value)::headers,options)
    def options(o: List[HttpOptions.HttpOption]):Request = Request(method,exec, url, params, headers, o)
    def option(o: HttpOptions.HttpOption):Request = Request(method,exec,url, params, headers,o::options)
    
    def auth(user: String, password: String) = header("Authorization", "Basic " + base64(user + ":" + password))
    
    def oauth(consumer: Token):Request = oauth(consumer, None, None)
    def oauth(consumer: Token, token: Token):Request = oauth(consumer, Some(token), None)
    def oauth(consumer: Token, token: Token, verifier: String):Request = oauth(consumer, Some(token), Some(verifier))
    def oauth(consumer: Token, token: Option[Token], verifier: Option[String]):Request = OAuth.sign(this, consumer, token, verifier)
    
    def tryParse[E](is: InputStream, parser: InputStream => E):E = try {
      parser(is)
    } finally {
      is.close
    }
    
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
              throw new HttpException(conn.getResponseCode, tryParse(conn.getErrorStream(), readString))
          }
      }
    }
    
    def responseCode = process((conn:HttpURLConnection) => conn.getResponseCode)
    
    def readString(is: InputStream) = {
      val sb = new StringBuilder()
      val reader = new BufferedReader(new InputStreamReader(is, charset))
      var ch = -1
      def read() = {
        ch = reader.read()
        (ch != -1)
      }
      while(read()){
        sb.append(ch.asInstanceOf[Char])
      }
      sb.toString
    }
    
    def asString = apply(readString)
    
    def asXml = apply(is => scala.xml.XML.load(is))
    
    def asParams: List[(String,String)] = {
      asString.split("&").flatMap(_.split("=") match {
        case Array(k,v) => Some(urlDecode(k), urlDecode(v))
        case _ => None
      }).toList
    }
    
    def asParamMap = Map(asParams:_*)
    
    def asToken = {
      val params = asParamMap
      Token(params("oauth_token"), params("oauth_token_secret"))
    }
  }

  val defaultOptions = List(HttpOptions.connTimeout(30000), HttpOptions.readTimeout(60000))
  
  def urlEncode(name: String): String = URLEncoder.encode(name, charset)
  def urlDecode(name: String): String = URLDecoder.decode(name, charset)
  def base64(bytes: Array[Byte]) = new String((new Base64).encode(bytes))
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