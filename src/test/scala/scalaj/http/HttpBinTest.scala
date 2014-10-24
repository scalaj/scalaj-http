package scalaj.http

import org.junit.Assert._
import org.junit.{Test}

class HttpBinTest {
  @Test
  def redirectShouldNotFollow {
    val response = DefaultHttp("http://httpbin.org/redirect-to?url=http://foo.org").asString
    assertEquals(302, response.code)
    assertEquals(Some("http://foo.org"), response.headers.get("Location"))
  }

  @Test
  def gzipDecode {
    val response = DefaultHttp("http://httpbin.org/gzip").asString
    assertEquals(200, response.code)
    assertEquals("{", response.body.substring(0,1))
  }

  @Test
  def deflateDecode {
    val response = DefaultHttp("http://httpbin.org/deflate").asString
    assertEquals(200, response.code)
    assertEquals("{", response.body.substring(0,1))
  }

  @Test
  def streamingResponse {
    val response = DefaultHttp("http://httpbin.org/stream/5").asString
    assertEquals("should have 5 lines", 5, response.body.split("\n").length)
  }

}