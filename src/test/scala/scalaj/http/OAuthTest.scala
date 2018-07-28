package scalaj.http

import org.junit.Assert._
import org.junit.Test

class OAuthTest {
  
  @Test
  def oauthShouldCorrectlySign: Unit = {
    // from example http://hueniverse.com/2008/10/beginners-guide-to-oauth-part-iv-signing-requests/
    val params = List(
      ("oauth_nonce", "kllo9940pd9333jh"),
      ("oauth_timestamp", "1191242096")
    )
    
    val url = "http://photos.example.net/photos"
    val req = Http(url).param("file", "vacation.jpg").param("size", "original")
    
    val (oauthParams, signature) = OAuth.getSig(params, req, Token("dpf43f3p2l4k3l03","kd94hf93k423kf44"), Some(Token("nnch734d00sl2jdk","pfkkdhi9sl3r4s00")),None)
    
    assertEquals(signature, "tR3+Ty81lMeYAr/Fid0kMTYa/WM=")
  }

  @Test
  def oauthShouldCorrectlySignMultiPart: Unit = {
    val oauthInputParams= List(
      ("oauth_nonce", "kllo9940pd9333jh"),
      ("oauth_timestamp", "1191242096")
    )

    val url = "http://photos.example.net/photos"
    // the params will be part of the multi-part body and should not be used as part of the oauth signature
    val req = Http(url).param("file", "vacation.jpg").param("size", "original").postMulti(MultiPart("file", "vacation.jpg", "image/jpeg", "byteshere"))

    val (oauthParams, signature) = OAuth.getSig(oauthInputParams, req, Token("dpf43f3p2l4k3l03","kd94hf93k423kf44"), Some(Token("nnch734d00sl2jdk","pfkkdhi9sl3r4s00")),None)

    assertEquals(signature, "9TY6LYA9cYoAs3ZzF2Kb4/A+fFQ=")
  }
}
