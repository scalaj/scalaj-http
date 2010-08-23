package hoffrocket.sh

import java.net.{HttpURLConnection, URL, URLEncoder, URLDecoder}
import java.io.{DataOutputStream, InputStream, BufferedReader, InputStreamReader}
import org.apache.commons.codec.binary.Base64


object ShOptions {
  type ShOption = HttpURLConnection => Unit
  
  def method(method: String):ShOption = c => c.setRequestMethod(method)
  def connTimeout(timeout: Int):ShOption = c => c.setConnectTimeout(timeout)
  def readTimeout(timeout: Int):ShOption = c => c.setReadTimeout(timeout)
  
}

class ShException(val code: Int, message: String) extends RuntimeException("ResponseCode[" + code + "] " + message)

case class Token(key: String, secret: String)
object OAuth {
  import java.net.URI
  import javax.crypto.Mac
  import javax.crypto.SecretKey
  import javax.crypto.spec.SecretKeySpec
  val MAC = "HmacSHA1"
      
  def sign(req: Sh.Request, consumer: Token, token: Option[Token], verifier: Option[String]): Sh.Request = {

    
    val baseParams:List[(String,String)] = List(
      ("oauth_timestamp", (System.currentTimeMillis/1000).toString),
      ("oauth_nonce", System.currentTimeMillis.toString)
    )
    
    var (oauthParams, signature) = getSig(baseParams, req, consumer, token, verifier)
    
    oauthParams ::= ("oauth_signature", signature)
    
    req.header("Authorization", "OAuth " + oauthParams.map(p => p._1 + "=\"" + percentEncode(p._2) +"\"").mkString(","))
  }
  
  def getSig(baseParams: List[(String,String)], req: Sh.Request, consumer: Token, token: Option[Token], verifier: Option[String]): (List[(String,String)], String) = {
    var oauthParams = ("oauth_version", "1.0")::("oauth_consumer_key", consumer.key)::("oauth_signature_method", "HMAC-SHA1") :: baseParams
    
    token.foreach{t =>
      oauthParams ::= ("oauth_token", t.key)
    }
    
    verifier.foreach{v =>
      oauthParams ::= ("oauth_verifier", v)
    }
    
    val baseString = List(req.method.toUpperCase,normalizeUrl(req.url(req)),normalizeParams(req.params ++ oauthParams)).map(percentEncode).mkString("&")
    
    val keyString = percentEncode(consumer.secret) + "&" + token.map(t => percentEncode(t.secret)).getOrElse("")
    val key = new SecretKeySpec(keyString.getBytes(Sh.charset), MAC)
    val mac = Mac.getInstance(MAC)
    mac.init(key)
    val text = baseString.getBytes(Sh.charset)
    (oauthParams, Sh.base64(mac.doFinal(text)))
  }
  
  private def normalizeParams(params: List[(String,String)]) = {
    percentEncode(params).sort(_ < _).mkString("&")
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
  
  def percentEncode(params: List[(String,String)]):List[String] = params.map(p => percentEncode(p._1) + "=" + percentEncode(p._2))
  
  def percentEncode(s: String): String = {
    if (s == null) "" else {
       Sh.urlEncode(s).replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
     }
  }
}

object Sh {
  def apply(url: String):Request = get(url)
  
  type ShExec = (Request, HttpURLConnection) => Unit
  type ShUrl = Request => URL
  
  object Request {
    def apply(exec: ShExec, url: ShUrl, method:String):Request = Request(method, exec, url, Nil, Nil, ShOptions.method(method)::defaultOptions)
  }
  case class Request(method: String, exec: ShExec, url: ShUrl, params: List[(String,String)], headers: List[(String,String)], options: List[ShOptions.ShOption]) {
    def params(p: (String, String)*):Request = params(p.toList)
    def params(p: List[(String,String)]):Request = Request(method, exec,url, p, headers,options)
    def headers(h: List[(String,String)]):Request = Request(method,exec,url, params, h,options)
    def param(key: String, value: String):Request = Request(method,exec,url,(key,value)::params, headers,options)
    def header(key: String, value: String):Request = Request(method,exec,url,params, (key,value)::headers,options)
    def options(o: List[ShOptions.ShOption]):Request = Request(method,exec, url, params, headers, o)
    def option(o: ShOptions.ShOption):Request = Request(method,exec,url, params, headers,o::options)
    
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
              throw new ShException(conn.getResponseCode, tryParse(conn.getErrorStream(), readString))
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

  val defaultOptions = List(ShOptions.connTimeout(30000), ShOptions.readTimeout(60000))
  
  def urlEncode(name: String): String = URLEncoder.encode(name, charset)
  def urlDecode(name: String): String = URLDecoder.decode(name, charset)
  def base64(bytes: Array[Byte]) = new String((new Base64).encode(bytes))
  def base64(in: String): String = base64(in.getBytes(charset))
  
  def toQs(params: List[(String,String)]) = params.map(p => urlEncode(p._1) + "=" + urlEncode(p._2)).mkString("&")
  def appendQs(url:String, params: List[(String,String)]) = url + (if(params.isEmpty) "" else {
    (if(url.contains("?")) "&" else "?") + toQs(params)
  })
  
  def appendQsShUrl(url: String): ShUrl = r => new URL(appendQs(url, r.params))
  def noopShUrl(url :String): ShUrl = r => new URL(url)
  
  def get(url: String): Request = {
    val getFunc: ShExec = (req,conn) => conn.connect
    
    Request(getFunc, appendQsShUrl(url), "GET")
  }
  def post(url: String): Request = {
    val postFunc: ShExec = (req,conn) => {
      conn.setDoOutput(true)
      conn.connect
      conn.getOutputStream.write(toQs(req.params).getBytes(charset))
    }
    Request(postFunc, noopShUrl(url), "POST").header("content-type", "application/x-www-form-urlencoded")
  }
  val charset = "UTF-8"
}