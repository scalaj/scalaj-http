package com.foursquare.sh

import java.net.{HttpURLConnection, URL, URLEncoder}
import java.io.{DataOutputStream, InputStream, BufferedReader, InputStreamReader}
import org.apache.commons.codec.binary.Base64


object ShOptions {
  type ShOption = HttpURLConnection => Unit
  
  def method(method: String):ShOption = c => c.setRequestMethod(method)
  def connTimeout(timeout: Int):ShOption = c => c.setConnectTimeout(timeout)
  def readTimeout(timeout: Int):ShOption = c => c.setReadTimeout(timeout)
  
}



object Sh {
  def apply(url: String):Request = get(url)
  
  type ShExec = (Request, HttpURLConnection) => Unit
  type ShUrl = Request => URL
  
  object Request {
    def apply(exec: ShExec, url: ShUrl, method:String):Request = Request(exec, url, Nil, Nil, ShOptions.method(method)::defaultOptions)
  }
  case class Request(exec: ShExec, url: ShUrl, params: List[(String,String)], headers: List[(String,String)], options: List[ShOptions.ShOption]) {
    def params(p: List[(String,String)]):Request = Request(exec,url, p, headers,options)
    def headers(h: List[(String,String)]):Request = Request(exec,url, params, h,options)
    def param(key: String, value: String):Request = Request(exec,url,(key,value)::params, headers,options)
    def header(key: String, value: String):Request = Request(exec,url,params, (key,value)::headers,options)
    def options(o: List[ShOptions.ShOption]):Request = Request(exec, url, params, headers, o)
    def option(o: ShOptions.ShOption):Request = Request(exec,url, params, headers,o::options)
    
    def auth(user: String, password: String) = header("Authorization", "Basic " + base64(user + ":" + password))
    
    def apply[T](parser: InputStream => T): T = {
      url(this).openConnection match {
        case conn:HttpURLConnection =>
          conn.setInstanceFollowRedirects(true)
          headers.reverse.foreach{case (name, value) => 
            conn.setRequestProperty(name, value)
          }
          options.reverse.foreach(_(conn))

          exec(this, conn)
          val is = conn.getInputStream()
          try {
            parser(is)
          } finally {
            is.close
          }
      }
    }
    
    def asString = apply(is => {
      
      val sb = new StringBuilder()
      val reader = new BufferedReader(new InputStreamReader(is, charset))
      var ch = -1
      def read = {
        ch = reader.read()
        (ch != -1)
      }
      while(read())
        sb.append(ch.asInstanceOf[Char])
      }
      sb.toString
    })
    
    def asXml = apply(is => scala.xml.XML.load(is))
  }

  val defaultOptions = List(ShOptions.connTimeout(30000), ShOptions.readTimeout(60000))
  
  def urlEncode(name: String): String = URLEncoder.encode(name, charset)
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
    Request(postFunc, noopShUrl(url), "POST")
  }
  val charset = "UTF-8"
}