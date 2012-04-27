package scalaj.http

import org.junit.Assert._
import org.junit.Test

class OAuthTest {
  
  @Test
  def oauthShoulCorrectlySign: Unit = {
    // from example http://hueniverse.com/2008/10/beginners-guide-to-oauth-part-iv-signing-requests/
    val params = List(
        ("oauth_nonce","kllo9940pd9333jh"),
        ("oauth_timestamp","1191242096")
    )
    
    val url = "http://photos.example.net/photos"
    val req = Http(url).param("file", "vacation.jpg").param("size", "original")
    
    val (oauthParams, signature) = OAuth.getSig(params, req, Token("dpf43f3p2l4k3l03","kd94hf93k423kf44"), Some(Token("nnch734d00sl2jdk","pfkkdhi9sl3r4s00")),None)
    
    assertEquals(signature, "tR3+Ty81lMeYAr/Fid0kMTYa/WM=")
  }
}