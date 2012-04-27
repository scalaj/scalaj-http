package scalaj.http

import org.junit.Assert._
import org.junit.Test
import scalaj.http.Http._

class HttpTest {
  
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
    val r = Request(getFunc, Http.noopHttpUrl("http://localhost"), "GET").options(HttpOptions.connTimeout(1234)).options(HttpOptions.readTimeout(1234))
    r.process(c => {
      assertEquals(c.getReadTimeout, 1234)
      assertEquals(c.getConnectTimeout, 1234)
    })
  }
  
  
}