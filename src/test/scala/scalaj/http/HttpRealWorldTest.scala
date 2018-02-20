package scalaj.http

import org.junit.Assert._
import org.junit.Test

class HttpRealWorldTest {
  @Test
  def gzipDecodeTwitter: Unit = {
    val response = Http("https://twitter.com").asString
    assertEquals(200, response.code)
    assertEquals(Some("gzip"), response.header("content-encoding"))
    assertEquals("<!DOCTYPE", response.body.substring(0,9))
  }
}