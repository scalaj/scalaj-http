package scalaj.http

import org.junit.Assert._
import org.junit.Test

class HttpRealWorldTest {
  @Test
  def gzipDecodeTwitter {
    val response = Http("https://twitter.com").asString
    assertEquals(200, response.code)
    assertEquals(Some("gzip"), response.header("content-encoding"))
    assertEquals("<!DOCTYPE", response.body.substring(0, 9))
  }

  @Test
  def followRedirect {
    val response = Http("http://purl.org/linked-data/sdmx/2009/code#sex-M")
      .header("Accept", "text/turtle")
      .option(HttpOptions.followRedirects(true))
      .asString
    assertEquals(200, response.code)
    assertTrue(response.body.contains("sdmx-code:unitMult"))
  }
}