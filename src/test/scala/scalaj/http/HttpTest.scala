package scalaj.http

import java.io.ByteArrayInputStream
import java.net.{HttpCookie, InetSocketAddress, Proxy}
import org.junit.Assert._
import org.junit.{After, Before, Test}
import scalaj.http.HttpConstants._
import com.github.kristofa.test.http._

class HttpTest {
  
  val port = 51234
  val url  = "http://localhost:" + port
  
  val rProvider = new SimpleHttpResponseProvider()
  val server    = new MockHttpServer(port, rProvider)
  
  val cType    = "text/html"
  val rCode    = 200

  @Before
  def setUp(): Unit = {
    server.start()
  }

  @After 
  def tearDown(): Unit = {
    server.stop()
  }
  
  @Test
  def basicRequest: Unit = {
    val expectedCode = 200
    val expectedBody = "ok"
    val expectedContentType = "text/text"
    rProvider.expect(Method.GET, "/").respondWith(expectedCode, expectedContentType, expectedBody)
    

    object MyHttp extends BaseHttp(options = Seq(HttpOptions.readTimeout(1234)))
    val request: HttpRequest = MyHttp(url)
    val response: HttpResponse[String] = request.execute()
    assertEquals(Some(expectedContentType), response.header("Content-Type"))
    assertEquals(expectedCode, response.code)
    assertEquals(expectedBody, response.body)
    assertEquals("HTTP/1.1 200 OK", response.statusLine)
    assertTrue(response.is2xx)
    assertTrue(response.isSuccess)
    assertTrue(response.isNotError)
  }

  @Test
  def serverError: Unit = {
    rProvider.expect(Method.GET, "/").respondWith(500, "text/text", "error")
    val response: HttpResponse[String] = Http(url).execute()
    assertEquals(500, response.code)
    assertEquals("error", response.body)
    assertTrue(response.is5xx)
    assertTrue(response.isServerError)
    assertTrue(response.isError)
  }

  @Test
  def redirectShouldNotFollowByDefault: Unit = {
    rProvider.expect(Method.GET, "/").respondWith(301, "text/text", "error")
    val response: HttpResponse[String] = Http(url).execute()
    assertEquals(301, response.code)
    assertEquals("error", response.body)
  }
  
  @Test
  def asParams: Unit = {
    rProvider.expect(Method.GET, "/").respondWith(rCode, cType, "foo=bar");
    
    val response = Http(url).asParams
    assertEquals(Seq("foo" -> "bar"), response.body)
  }

  @Test
  def asParamMap: Unit = {
    rProvider.expect(Method.GET, "/").respondWith(rCode, cType, "foo=bar");
    
    val response = Http(url).asParamMap
    assertEquals(Map("foo" -> "bar"), response.body)
  }

  @Test
  def asBytes: Unit = {
    rProvider.expect(Method.GET, "/").respondWith(rCode, cType, "hi");
    
    val response = Http(url).asBytes
    assertEquals("hi", new String(response.body, HttpConstants.utf8))
  }  

  @Test
  def shouldPrependOptions: Unit = {
    val http = Http(url)
    val origOptions = http.options
    val origOptionsLength = origOptions.length
    val newOptions: List[HttpOptions.HttpOption] = List(c => { }, c=> { }, c => {})
    val http2 = http.options(newOptions)
    
    assertEquals(http2.options.length, origOptionsLength + 3)
    assertEquals(http2.options.take(3), newOptions)
    assertEquals(origOptions.length, origOptionsLength)
  }

  @Test
  def lastTimeoutValueShouldWin: Unit = {
    rProvider.expect(Method.GET, "/").respondWith(rCode, cType, "hi");
    
    val getFunc: HttpExec = (req, c) => {
      assertEquals(c.getReadTimeout, 1234)
      assertEquals(c.getConnectTimeout, 1234)
    }

    val r = Http(url).option(HttpOptions.connTimeout(1234)).option(HttpOptions.readTimeout(1234))
      .copy(connectFunc = getFunc)
    r.execute()
  }

  @Test
  def readString: Unit = {
    val bais = new ByteArrayInputStream("hello there".getBytes(HttpConstants.utf8))
    assertEquals("hello there", HttpConstants.readString(bais))
  }

  @Test
  def overrideTheMethod: Unit = {
    rProvider.expect(Method.DELETE, "/").respondWith(rCode, cType, "")
    Http(url).method("DELETE").asString
    server.verify
  }

  @Test
  def unofficialOverrideTheMethod: Unit = {
    val fooFunc: HttpExec = (req, c) => {
      throw new RuntimeException(c.getRequestMethod)
    }
    try {
      Http(url).method("FOO").copy(connectFunc = fooFunc).execute()
      fail("expected throw")
    } catch {
      case e: RuntimeException if e.getMessage == "FOO" => // ok
    }
    
  }

  @Test
  def allModificationsAreAdditive: Unit = {
    val params = List("a" -> "b")
    val proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("host", 80))
    val headers = List("foo" -> "bar")
    val options = List(HttpOptions.readTimeout(1234))

    var req = Http(url).params(params)

    req = req.proxy("host", 80)

    assertEquals("params", params, req.params)

    val expectedNewOptions = options ++ req.options
    req = req.options(options)

    assertEquals("params", params, req.params)
    assertEquals("proxy", proxy, req.proxyConfig.get)
    assertEquals("options", expectedNewOptions, req.options)

    req = req.headers(headers)

    assertEquals("params", params, req.params)
    assertEquals("proxy", proxy, req.proxyConfig.get)
    assertEquals("options", expectedNewOptions, req.options)

  }

  @Test(expected = classOf[java.net.ConnectException])
  def serverDown: Unit = {
    server.stop
    val response = Http(url).execute()
    assertEquals("", response.body)
  }

  @Test
  def varargs: Unit = {
    val req = Http(url).params("a" -> "b", "b" -> "a")
                       .headers("a" -> "b", "b" -> "a")
                       .options(HttpOptions.connTimeout(100), HttpOptions.readTimeout(100))
    assertEquals(2, req.params.size)
  }

  @Test
  def parseCookies: Unit = {
    val httpResponse = HttpResponse("hi", 200, Map("Set-Cookie" -> IndexedSeq("foo=bar", "baz=biz")))
    assertEquals(IndexedSeq(new HttpCookie("foo", "bar"), new HttpCookie("baz", "biz")), httpResponse.cookies)
  }

  @Test(expected = classOf[scalaj.http.HttpStatusException])
  def throwErrorThrowsWith401: Unit = {
    HttpResponse("hi", 401, Map.empty).throwError
  }

  @Test(expected = classOf[scalaj.http.HttpStatusException])
  def throwServerErrorThrowsWith400: Unit = {
    HttpResponse("hi", 400, Map.empty).throwError
  }

  @Test
  def throwErrorOkWith200: Unit = {
    assertEquals(200, HttpResponse("hi", 200, Map.empty).throwError.code)
  }

  @Test
  def throwServerErrorOkWith400: Unit = {
    assertEquals(400, HttpResponse("hi", 400, Map.empty).throwServerError.code)
  }

  @Test
  def testGetEquals: Unit = {
    assertEquals(Http(url), Http(url))
  }

  @Test
  def testPostEquals: Unit = {
    assertEquals(Http(url).postData("hi"), Http(url).postData("hi"))
  }
}