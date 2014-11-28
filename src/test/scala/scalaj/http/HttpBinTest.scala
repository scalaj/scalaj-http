package scalaj.http

import org.junit.Assert._
import org.junit.Test

case class BinResponse(files: Map[String, String], form: Map[String, String])

class HttpBinTest {

  @Test
  def redirectShouldNotFollow {
    val response = Http("http://httpbin.org/redirect-to?url=http://foo.org").asString
    assertEquals(302, response.code)
    assertEquals(Some("http://foo.org"), response.headers.get("Location"))
  }

  @Test
  def gzipDecode {
    val response = Http("http://httpbin.org/gzip").asString
    assertEquals(200, response.code)
    assertEquals("{", response.body.substring(0,1))
  }

  @Test
  def deflateDecode {
    val response = Http("http://httpbin.org/deflate").asString
    assertEquals(200, response.code)
    assertEquals("{", response.body.substring(0,1))
  }

  @Test
  def streamingResponse {
    val response = Http("http://httpbin.org/stream/5").asString
    assertEquals("should have 5 lines", 5, response.body.split("\n").length)
  }

  @Test
  def postMulti {
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
}