package scalaj.http

import org.junit.Assert._
import org.junit.Test

class HttpRealWorldTest {
  @Test
  def gzipDecodeTwitter: Unit = {
    val response = Http("https://twitter.com").
      option(HttpOptions.followRedirects(true)).
      asString
    assertEquals(200, response.code)
    assertEquals(Some("gzip"), response.header("content-encoding"))
    assertEquals("<?xml version=", response.body.substring(0, 14))
  }
}