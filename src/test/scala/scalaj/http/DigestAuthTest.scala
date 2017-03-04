package scalaj.http

import org.junit.Test
import org.junit.Assert._


class DigestAuthTest {
  @Test
  def jettyDigestParsing: Unit = {
    val authDetails = DigestAuth.getAuthDetails("""Digest realm="Test Realm", domain="", nonce="J85e99PhPObD5sWM75R79iK1A2lI0DAJ", algorithm=MD5, qop="auth", stale=true""").get
    assertEquals("Digest", authDetails.authType)
    assertEquals(Some("Test Realm"), authDetails.params.get("realm"))
    assertEquals(Some("J85e99PhPObD5sWM75R79iK1A2lI0DAJ"), authDetails.params.get("nonce"))
    assertEquals(Some("MD5"), authDetails.params.get("algorithm"))
    assertEquals(Some("true"), authDetails.params.get("stale"))
  }

  @Test
  def httpbinDigestParsing: Unit = {
    val authDetails = DigestAuth.getAuthDetails("""Digest nonce="026ef3f7112f5c6da8f0e7beb1ebcc74", opaque="9777e826c52b062b54454106581f808f", realm="me@kennethreitz.com", qop="auth, auth-int"""").get
    assertEquals("Digest", authDetails.authType)
    assertEquals("got detalis " + authDetails, Some("026ef3f7112f5c6da8f0e7beb1ebcc74"), authDetails.params.get("nonce"))
    assertEquals(Some("auth, auth-int"), authDetails.params.get("qop"))
  }

  @Test
  def digestComparedToCurl: Unit = {
    val params = Map(
      "nonce" -> "6b1b1cc62a4fdc73aa7df7762263be43",
      "opaque" -> "ba8b9fda8a77aba832112b261769c172",
      "realm" -> "me@kennethreitz.com",
      "qop" -> "auth"
    )
    val expectedValue = """Digest username="hello", realm="me@kennethreitz.com", nonce="6b1b1cc62a4fdc73aa7df7762263be43", opaque="ba8b9fda8a77aba832112b261769c172", algorithm="MD5", uri="/digest-auth/auth/hello/hello", qop="auth", nc="00000001", cnonce="ZjE0YTc5YTg2YzU2MWI3ODhmMDc3MWNlMmFkYzI5NjQ=", response="cb974972a3a17f3a4435e3748dfc6da0""""
    assertEquals(Some(expectedValue),
      DigestAuth.createHeaderValue("hello", "hello", "GET", "/digest-auth/auth/hello/hello", Array(), params, Some("ZjE0YTc5YTg2YzU2MWI3ODhmMDc3MWNlMmFkYzI5NjQ=")))
  }
}
