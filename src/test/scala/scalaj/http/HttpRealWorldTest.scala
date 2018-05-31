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
    val expectedVariants = Set("<!DOCTYPE htm", "<?xml version")
    val chunk = response.body.substring(0, 13)
    assertTrue(s"Received $chunk...", expectedVariants contains chunk)
  }
}