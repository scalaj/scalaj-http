package scalaj.http

import java.io.ByteArrayInputStream
import java.net.{InetSocketAddress, Proxy}
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
    assertEquals(Some(expectedContentType), response.headers.get("Content-Type"))
    assertEquals(expectedCode, response.code)
    assertEquals(expectedBody, response.body)
  }

  @Test
  def serverError: Unit = {
    rProvider.expect(Method.GET, "/").respondWith(500, "text/text", "error")
    val response: HttpResponse[String] = DefaultHttp(url).execute()
    assertEquals(500, response.code)
    assertEquals("error", response.body)
  }

  @Test
  def redirectShouldNotFollowByDefault: Unit = {
    rProvider.expect(Method.GET, "/").respondWith(301, "text/text", "error")
    val response: HttpResponse[String] = DefaultHttp(url).execute()
    assertEquals(301, response.code)
    assertEquals("error", response.body)
  }
  
  @Test
  def asParams: Unit = {
    rProvider.expect(Method.GET, "/").respondWith(rCode, cType, "foo=bar");
    
    val response = DefaultHttp(url).asParams
    assertEquals(Seq("foo" -> "bar"), response.body)
  }

  @Test
  def asParamMap: Unit = {
    rProvider.expect(Method.GET, "/").respondWith(rCode, cType, "foo=bar");
    
    val response = DefaultHttp(url).asParamMap
    assertEquals(Map("foo" -> "bar"), response.body)
  }

  @Test
  def asBytes: Unit = {
    rProvider.expect(Method.GET, "/").respondWith(rCode, cType, "hi");
    
    val response = DefaultHttp(url).asBytes
    assertEquals("hi", new String(response.body, HttpConstants.utf8))
  }  

  @Test
  def shouldPrependOptions: Unit = {
    val http = DefaultHttp(url)
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

    val r = DefaultHttp(url).option(HttpOptions.connTimeout(1234)).option(HttpOptions.readTimeout(1234))
      .copy(exec = getFunc)
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
    DefaultHttp(url).method("DELETE").asString
    server.verify
  }

  @Test
  def unofficialOverrideTheMethod: Unit = {
    val fooFunc: HttpExec = (req, c) => {
      throw new RuntimeException(c.getRequestMethod)
    }
    try {
      DefaultHttp(url).method("FOO").copy(exec = fooFunc).execute()
      fail("expected throw")
    } catch {
      case e: RuntimeException if e.getMessage == "FOO" => // ok
    }
    
  }

  @Test
  def allModificationsAreAdditive() {
    val params = List("a" -> "b")
    val proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("host", 80))
    val headers = List("foo" -> "bar")
    val options = List(HttpOptions.readTimeout(1234))

    var req = DefaultHttp(url).params(params)

    req = req.proxy("host", 80)

    assertEquals("params", params, req.params)

    val expectedNewOptions = options ++ req.options
    req = req.options(options)

    assertEquals("params", params, req.params)
    assertEquals("proxy", proxy, req.proxy)
    assertEquals("options", expectedNewOptions, req.options)

    req = req.headers(headers)

    assertEquals("params", params, req.params)
    assertEquals("proxy", proxy, req.proxy)
    assertEquals("options", expectedNewOptions, req.options)

  }

  @Test(expected = classOf[java.net.ConnectException])
  def serverDown {
    server.stop
    val response = DefaultHttp(url).execute()
    assertEquals("", response.body)
  }
}