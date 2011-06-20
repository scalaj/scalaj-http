package scalaj.http

import org.specs._
import scalaj.http.Http._

class HttpSpec extends Specification {
  "Http" should {
    "prepend options" in {
      val http = Http("http://localhost")
      val origOptions = http.options
      val origOptionsLength = origOptions.length
      val newOptions: List[HttpOptions.HttpOption] = List(c => { }, c=> { }, c => {})
      val http2 = http.options(newOptions)
      
      http2.options.length must_== origOptionsLength + 3
      http2.options.take(3) must_== newOptions
      origOptions.length must_== origOptionsLength
    }
    
    "last timeout value should win" in {
      val getFunc: HttpExec = (req,conn) => {
        
      }

      val r = Request(getFunc, Http.noopHttpUrl("http://localhost"), "GET").options(HttpOptions.connTimeout(1234)).options(HttpOptions.readTimeout(1234))
      r.process(c => {
        c.getReadTimeout must_== 1234
        c.getConnectTimeout must_== 1234
      })
      
    }
  }
  
  
}