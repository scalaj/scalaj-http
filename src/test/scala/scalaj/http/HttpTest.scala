package scalaj.http

import java.io.ByteArrayInputStream
import org.junit.Assert._
import org.junit.Test
import scalaj.http.Http._

class HttpTest {
  
  @Test
  def asCodeHeaders: Unit = {
    val (code, headers) = Http("http://www.google.com/").asCodeHeaders
    assertTrue(headers.contains("Date"))
  }
  
  @Test
  def shouldPrependOptions: Unit = {
    val http = Http("http://localhost")
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
    val getFunc: HttpExec = (req,conn) => {
      
    }
    val r = Request(getFunc, Http.noopHttpUrl("http://www.google.com/"), "GET").options(HttpOptions.connTimeout(1234)).options(HttpOptions.readTimeout(1234))
    r.process(c => {
      assertEquals(c.getReadTimeout, 1234)
      assertEquals(c.getConnectTimeout, 1234)
    })
  }
  
  @Test
  def readString: Unit = {
    val bais = new ByteArrayInputStream("hello there".getBytes(Http.defaultCharset))
    Http.setCharset(Http.defaultCharset)
    assertEquals("hello there", Http.readString(bais))
  }
  
  @Test
  def shouldUseDefaultCharset: Unit = {
    val charset = Http.defaultCharset
    val message = "ãéíôúç - Hello There!"
    val bais = new ByteArrayInputStream(message.getBytes(charset))
    
    Http.setCharset(charset)
    assertEquals(message, Http.readString(bais))
  }
  
  @Test
  def shouldUseCustomCharset: Unit = {
    val charset = "ISO-8859-1"
    val message = "ãéíôúç - Hello There!"
    val bais = new ByteArrayInputStream(message.getBytes(charset))
    
    Http.setCharset(charset)
    assertEquals(message, Http.readString(bais))
  }

}