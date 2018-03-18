package scalaj.http

import java.net.HttpCookie
import org.junit.Assert._
import org.junit.Test

case class BinResponse(
  files: Map[String, String],
  form: Map[String, String],
  args: Map[String, String],
  headers: Map[String, String],
  cookies: Map[String, String]
)

class HttpBinTest {


  // temporary helper method to work around ssl everywhere cert missing from
  // local dev machine's cacerts file
  def sslLeniency(httpReq: HttpRequest):HttpRequest = {
    import java.security.cert.X509Certificate
    import javax.net.ssl.SSLContext
    import javax.net.ssl.TrustManager
    import javax.net.ssl.X509TrustManager
    import java.security.cert.X509Certificate
    import javax.net.ssl.SSLSession
    import javax.net.ssl.HostnameVerifier
    import javax.net.ssl.HttpsURLConnection

    // would like to do something here to merge or replace system certs with developer provided certs
    val trustAllCerts = Array[TrustManager](new X509TrustManager() {
      def getAcceptedIssuers: Array[X509Certificate] = null
      def checkClientTrusted(certs: Array[X509Certificate], authType: String): Unit = {}
      def checkServerTrusted(certs: Array[X509Certificate], authType: String): Unit = {}
    })

    val sc = SSLContext.getInstance("SSL")
    sc.init(null, trustAllCerts, new java.security.SecureRandom())
    httpReq.option(HttpOptions.sslSocketFactory(sc.getSocketFactory()))
  }


  @Test
  def headRequest: Unit = {
    val response = Http("http://httpbin.org/status/200").method("HEAD").asString
    assertEquals(200, response.code)
  }

  @Test
  def overrideAcceptHeader: Unit = {
    val response = Http("http://httpbin.org/get").header("Accept", "foo/bar").asString
    val binResponse = Json.parse[BinResponse](response.body)
    assertEquals(Some("foo/bar"), binResponse.headers.get("Accept"))
  }

  @Test
  def redirectShouldNotFollow: Unit = {
    val response = Http("http://httpbin.org/redirect-to?url=http://foo.org").asString
    assertEquals(302, response.code)
    assertEquals(Some("http://foo.org"), response.header("Location"))
  }

  @Test
  def shouldFollowHttpsRedirect: Unit = {
    val response = sslLeniency(Http("http://httpbin.org/redirect-to?url=https://httpbin.org/get"))
      .option(HttpOptions.followRedirects(true)).asString
    assertEquals(200, response.code)
  }

  @Test
  def shouldGetHttpsUrl: Unit = {
    val response = sslLeniency(Http("https://httpbin.org/get"))
      .asString
    assertEquals(200, response.code)
  }

  @Test
  def errorHasHeaders: Unit = {
    val response = Http("http://httpbin.org/status/500").asString
    assertEquals("HTTP/1.1 500 INTERNAL SERVER ERROR", response.statusLine)
    assertEquals(500, response.code)
    assertTrue("Should have some headers", response.headers.contains("Date"))
  }

  @Test 
  def gzipWithHead: Unit = {
    val response = Http("http://httpbin.org/gzip").method("HEAD").asString
    assertEquals(200, response.code)
  }

  @Test
  def gzipDecode: Unit = {
    val response = Http("http://httpbin.org/gzip").asString
    assertEquals(200, response.code)
    assertEquals("{", response.body.substring(0,1))
  }

  @Test
  def gzipDecodeNoCompress: Unit = {
    val response = Http("http://httpbin.org/gzip").compress(false).asString
    assertEquals(200, response.code)
    assertNotEquals("{", response.body.substring(0,1))
  }

  @Test 
  def deflateWithHead: Unit = {
    val response = Http("http://httpbin.org/deflate").method("HEAD").asString
    assertEquals(200, response.code)
  }

  @Test
  def deflateDecode: Unit = {
    val response = Http("http://httpbin.org/deflate").asString
    assertEquals(200, response.code)
    assertEquals("{", response.body.substring(0,1))
  }

  @Test
  def streamingResponse: Unit = {
    val response = Http("http://httpbin.org/stream/5").asString
    assertEquals("should have 5 lines", 5, response.body.split("\n").length)
  }

  @Test
  def postMulti: Unit = {
    val response = Http("http://httpbin.org/post")
      .postMulti(
        MultiPart(name="file1", filename="foo.txt", mime="text/text", data="Hello!"),
        MultiPart(name="file2", filename="bar.txt", mime="text/text", data="Goodbye!")
      )
      .param("param1", "a").param("param2", "b").asString
    val binResponse = Json.parse[BinResponse](response.body)
    assertEquals("should have two files", 2, binResponse.files.size)
    assertEquals(Some("Hello!"), binResponse.files.get("file1"))
    assertEquals(Some("Goodbye!"), binResponse.files.get("file2"))
    assertEquals(Some("a"), binResponse.form.get("param1"))
    assertEquals(Some("b"), binResponse.form.get("param2"))
  }

  @Test
  def postForm: Unit = {
    val response = Http("http://httpbin.org/post").postForm.param("param1", "a").param("param2", "b").asString
    val binResponse = Json.parse[BinResponse](response.body)
    assertEquals(Some("a"), binResponse.form.get("param1"))
    assertEquals(Some("b"), binResponse.form.get("param2"))
  }

  @Test
  def postData: Unit = {
    val response = Http("http://httpbin.org/post").param("param1", "a").param("param2", "b").postData("foo").asString
    val binResponse = Json.parse[BinResponse](response.body)
    assertEquals(Some("a"), binResponse.args.get("param1"))
    assertEquals(Some("b"), binResponse.args.get("param2"))
  }

  @Test
  def cookie: Unit = {
    val response = Http("http://httpbin.org/cookies").cookie("foo", "bar").asString
    val binResponse = Json.parse[BinResponse](response.body)
    assertEquals(Some("bar"), binResponse.cookies.get("foo"))
  }

  @Test
  def cookies: Unit = {
    val response = Http("http://httpbin.org/cookies").cookies(
      Seq(new HttpCookie("foo", "bar"), new HttpCookie("baz", "biz"))
    ).asString
    val binResponse = Json.parse[BinResponse](response.body)
    assertEquals(Some("bar"), binResponse.cookies.get("foo"))
    assertEquals(Some("biz"), binResponse.cookies.get("baz"))
  }
}