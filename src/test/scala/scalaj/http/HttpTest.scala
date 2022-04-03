package scalaj.http

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import java.net.{HttpCookie, InetSocketAddress, Proxy}
import java.util.zip.GZIPOutputStream
import javax.servlet.{ServletRequest, ServletResponse}
import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

import org.eclipse.jetty.security.{ConstraintMapping, ConstraintSecurityHandler, HashLoginService}
import org.eclipse.jetty.security.authentication.{BasicAuthenticator, DigestAuthenticator, LoginAuthenticator}
import org.eclipse.jetty.server.{Request, Server}
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHandler, ServletHolder}
import org.eclipse.jetty.servlets.ProxyServlet
import org.eclipse.jetty.util.security.{Constraint, Credential}
import org.junit.Assert._
import org.junit.Test

import scalaj.http.HttpConstants._
import java.util.concurrent.atomic.AtomicReference


class HttpTest {

  def makeRequest(
    reqHandler: (HttpServletRequest, HttpServletResponse) => Unit
  )(requestF: String => Unit): Unit = {
    val server = new Server(0)
    val refHolder = new AtomicReference[Throwable]()
    server.setHandler(new AbstractHandler(){
      def handle(
        target: String,
        baseRequest: Request,
        request: HttpServletRequest,
        response: HttpServletResponse
      ): Unit = {
        try {
          reqHandler(request, response)
        } catch {
          case e: Throwable =>
            refHolder.set(e)
        }
        baseRequest.setHandled(true)
      }
    })
    try {
      server.start()
      val port = server.getConnectors.head.getLocalPort
      requestF("http://localhost:" + port + "/")
    } finally {
      server.stop()
    }
    if (refHolder.get() != null) {
      throw refHolder.get()
    }
  }

  def makeAuthenticatedRequest
  (
    authenticator: LoginAuthenticator,
    username: String,
    password: String,
    response:String
  )(requestF: String => Unit): Unit = {
    val server = new Server(0)
    val context = new ServletContextHandler(ServletContextHandler.SESSIONS)
    val loginService = new HashLoginService()
    val roles = Array("user")
    loginService.putUser(username, Credential.getCredential(password), roles)
    loginService.setName("Test Realm")
    val constraint = new Constraint()
    constraint.setName(loginService.getName)
    constraint.setRoles(roles)
    constraint.setAuthenticate(true)
    val cm = new ConstraintMapping()
    cm.setConstraint(constraint)
    cm.setPathSpec("/*")
    val csh = new ConstraintSecurityHandler()
    csh.setAuthenticator(authenticator)
    csh.setRealmName(loginService.getName)
    csh.addConstraintMapping(cm)
    csh.setLoginService(loginService)
    context.setSecurityHandler(csh)
    context.setContextPath("/")
    server.setHandler(context)
    context.addServlet(new ServletHolder(new HttpServlet(){
      override def doGet(reg: HttpServletRequest, resp: HttpServletResponse): Unit = {
        resp.getWriter.print(response)
      }
    }), "/*")
    try {
      server.start()
      val port = server.getConnectors.head.getLocalPort
      requestF("http://localhost:" + port + "/")
    } finally {
      server.stop()
    }
  }

  def makeProxiedRequest(proxyF: (String, Int) => Unit): Unit = {
    val server = new Server(0)
    val servletHandler = new ServletHandler()
    servletHandler.addServletWithMapping(classOf[AuthProxyServlet], "/*")
    server.setHandler(servletHandler)
    try {
      server.start()
      val port = server.getConnectors.head.getLocalPort
      proxyF("localhost", port)
    } finally {
      server.stop()
    }
  }

  @Test
  def basicAuthRequest: Unit = {
    val expectedBody = "Hello from authed servlet"
    makeAuthenticatedRequest(new BasicAuthenticator(), "test", "test", expectedBody){url =>
      val result = Http(url).auth("test", "test").asString
      assertEquals(200, result.code)
      assertEquals(expectedBody, result.body)
    }
  }

  @Test
  def digestAuthRequest: Unit = {
    val expectedBody = "Hello from authed servlet"
    makeAuthenticatedRequest(new DigestAuthenticator(), "test", "test", expectedBody){url =>
      val result = Http(url).digestAuth("test", "test").asString
      assertEquals("expecting success, but got " + result, 200, result.code)
      assertEquals(expectedBody, result.body)
    }
  }

  @Test
  def digestAuthRequestBadCreds: Unit = {
    // verify that we don't loop infinitely on bad creds
    makeAuthenticatedRequest(new DigestAuthenticator(), "test", "test", "hi"){url =>
      val result = Http(url).digestAuth("test", "wrong").asString
      assertEquals("expecting failure, but got " + result, 401, result.code)
    }
  }

  @Test
  def basicRequest: Unit = {
    val expectedCode = HttpServletResponse.SC_OK
    val expectedBody = "ok"
    val expectedContentType = "text/text;charset=utf-8"

    object MyHttp extends BaseHttp(options = Seq(HttpOptions.readTimeout(1234)))
    makeRequest((req, resp) => {
      resp.setContentType(expectedContentType)
      resp.setStatus(expectedCode)
      resp.getWriter.print(expectedBody)
    })(url => {
      val request: HttpRequest = MyHttp(url)
      val response: HttpResponse[String] = request.execute()
      assertEquals(Some(expectedContentType), response.header("Content-Type"))
      assertEquals(expectedCode, response.code)
      assertEquals(expectedBody, response.body)
      assertEquals("HTTP/1.1 200 OK", response.statusLine)
      assertTrue(response.is2xx)
      assertTrue(response.isSuccess)
      assertTrue(response.isNotError)
    })
  }


  // TODO: .oauth currently must be the last method to be called because it captures the state of the request.
  // see https://github.com/scalaj/scalaj-http/pull/156
  @Test
  def oauthRequestShouldHaveCorrectAuthHeader: Unit = {
    val consumerToken = Token("dpf43f3p2l4k3l03","kd94hf93k423kf44")
    val userToken = Token("nnch734d00sl2jdk","pfkkdhi9sl3r4s00")

    object MyHttp extends BaseHttp(options = Seq(HttpOptions.readTimeout(1234)))
    makeRequest((req, resp) => {
      resp.setContentType("text/text;charset=utf-8")
      resp.setStatus(HttpServletResponse.SC_OK)
      resp.getWriter.print(req.getHeader("Authorization"))
    })(url => {
      val request: HttpRequest = MyHttp(url).param("file", "vacation.jpg").oauth(
        consumerToken,
        userToken
      )
      val response: HttpResponse[String] = request.execute()
      // Authorizaiton header is prefixed with "OAuth ", comma separated, quoted values
      val oauthHeaderParams: Map[String, String] = response.body.substring(6).split(",").flatMap(_.split("=") match {
        case Array(k,v) => Some(
          HttpConstants.urlDecode(k, "utf-8") ->
          HttpConstants.urlDecode(v.substring(1, v.length -1 ), "utf-8")
        )
        case _ => None
      }).toMap

      val (_, expectedSignature) = OAuth.getSig(
        oauthHeaderParams.filterKeys(Set("oauth_timestamp", "oauth_nonce")).toSeq,
        request,
        consumerToken,
        Some(userToken),
        None
      )
      assertEquals(Some(expectedSignature), oauthHeaderParams.get("oauth_signature"))
    })
  }

  @Test
  def serverError: Unit = {
    makeRequest((req, resp) => {
      resp.setStatus(500)
      resp.getWriter.print("error")
    })(url => {
      val response: HttpResponse[String] = Http(url).execute()
      assertEquals(500, response.code)
      assertEquals("error", response.body)
      assertTrue(response.is5xx)
      assertTrue(response.isServerError)
      assertTrue(response.isError)
    })
  }

  @Test
  def redirectShouldNotFollowByDefault: Unit = {
    makeRequest((req, resp) => {
      resp.setStatus(301)
      resp.setHeader("Location", "http://www.google.com/")
      resp.getWriter.print("moved")
    })(url => {
      val response: HttpResponse[String] = Http(url).execute()
      assertEquals(301, response.code)
      assertEquals("moved", response.body)
    })
  }

  @Test
  def shouldFollowRedirectOnProtocolSwitch: Unit = {
    List(301, 302, 307).foreach(status => {
      makeRequest((_, resp) => {
        resp.setStatus(status)
        resp.setHeader("Location", "https://foobar.foobar")
      })(url => {
        try {
          val response = Http(url).option(HttpOptions.followRedirects(true)).execute()
          fail(s"Expecting redirect to throw IOException for $status status, but got $response")
        } catch {
          case _: IOException =>
        }
      })
    })
  }

  @Test
  def headersShouldBeCaseInsensitive: Unit = {
    makeRequest((req, resp) => {
      // special check for content-encoding header, though it seems like jetty normalizes it.
      resp.setHeader("content-ENCODING", "gzip")
      val byteStream = new ByteArrayOutputStream()
      val gzipStream = new GZIPOutputStream(byteStream)
      resp.setHeader("X-FOO", "foobar")
      gzipStream.write("hello".getBytes("UTF-8"))
      gzipStream.close()
      resp.getOutputStream.write(byteStream.toByteArray)
    })(url => {
      val response: HttpResponse[String] = Http(url).execute()
      assertEquals(200, response.code)
      assertEquals("hello", response.body)
      assertEquals(Some("foobar"), response.header("x-foo"))
      assertEquals(Some("foobar"), response.header("x-FOO"))
    })
  }

  @Test
  def asParams: Unit = {
    makeRequest((req, resp) => {
      resp.setStatus(200)
      resp.getWriter.print("foo=bar")
    })(url => {
      val response = Http(url).asParams
      assertEquals(Seq("foo" -> "bar"), response.body)
    })
  }

  @Test
  def asParamMap: Unit = {
    makeRequest((req, resp) => {
      resp.setStatus(200)
      resp.getWriter.print("foo=bar")
    })(url => {
      val response = Http(url).asParamMap
      assertEquals(Map("foo" -> "bar"), response.body)
    })
  }

  @Test
  def asBytes: Unit = {
    makeRequest((req, resp) => {
      resp.setStatus(200)
      resp.getWriter.print("hi")
    })(url => {
      val response = Http(url).asBytes
      assertEquals("hi", new String(response.body, HttpConstants.utf8))
    })
  }

  @Test
  def shouldPrependOptions: Unit = {
    val http = Http("http://foo.com/")
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
    makeRequest((req, resp) => {
      resp.setStatus(200)
      resp.getWriter.print("hi")
    })(url => {
      val getFunc: HttpExec = (req, c) => {
        assertEquals(c.getReadTimeout, 1234)
        assertEquals(c.getConnectTimeout, 1234)
      }

      val r = Http(url).option(HttpOptions.connTimeout(1234)).option(HttpOptions.readTimeout(1234))
        .copy(connectFunc = getFunc)
      r.execute()
    })
  }

  @Test
  def readString: Unit = {
    val bais = new ByteArrayInputStream("hello there".getBytes(HttpConstants.utf8))
    assertEquals("hello there", HttpConstants.readString(bais))
  }

  @Test
  def overrideTheMethod: Unit = {
    makeRequest((req, resp) => {
      assertEquals("DELETE", req.getMethod)
      resp.setStatus(200)
      resp.getWriter.print("")
    })(url => {
      Http(url).method("DELETE").asString
    })
  }

  @Test
  def unofficialOverrideTheMethod: Unit = {
    makeRequest((req, resp) => {
      resp.setStatus(200)
      resp.getWriter.print("")
    })(url => {
      val fooFunc: HttpExec = (req, c) => {
        throw new RuntimeException(c.getRequestMethod)
      }
      try {
        Http(url).method("FOO").copy(connectFunc = fooFunc).execute()
        fail("expected throw")
      } catch {
        case e: RuntimeException if e.getMessage == "FOO" => // ok
      }
    })
  }

  @Test
  def postDataOverrideMethod: Unit = {
    makeRequest((req, resp) => {
      assertEquals("GET", req.getMethod)
      assertEquals("hi", req.getReader().readLine())
      resp.setStatus(200)
      resp.getWriter.print("")
    })(url => {
      Http(url).postData("hi").method("GET").asString
    })
  }

  @Test
  def shouldUseCharsetFromServerContentType: Unit = {
    val diverseString = "£ÇÜfÿ"
    Seq("UTF-8", "ISO-8859-1").foreach { charset =>
      makeRequest((req, resp) => {
        resp.setStatus(200)
        resp.setContentType("text/plain; charset=" + charset)
        resp.getOutputStream.write(diverseString.getBytes(charset))
      })(url => {
        assertEquals("Should properly decode " + charset + " responses", diverseString, Http(url).asString.body)
      })
    }
  }

  @Test
  def proxyNoAuthTest: Unit = {
    val theExpectedBody = "hello hello"
    makeProxiedRequest((proxyHost, proxyPort) => {
      makeRequest((req, resp) => {
        resp.setStatus(200)
        resp.getWriter.print(theExpectedBody)
      })(url => {
        val result = Http(url).proxy(proxyHost, proxyPort).asString
        assertEquals(theExpectedBody, result.body)
        assertEquals(200, result.code)
      })
    })
  }

  @Test
  def proxyBadAuthTest: Unit = {
    makeProxiedRequest((proxyHost, proxyPort) => {
      makeRequest((req, resp) => {
        resp.setStatus(200)
      })(url => {
        val result = Http(url).proxy(proxyHost, proxyPort).proxyAuth("test", "bad").asString
        assertEquals(407, result.code)
      })
    })
  }

  @Test
  def proxyCorrectAuthTest: Unit = {
    val theExpectedBody = "hello hello"
    makeProxiedRequest((proxyHost, proxyPort) => {
      makeRequest((req, resp) => {
        resp.setStatus(200)
        resp.getWriter.print(theExpectedBody)
      })(url => {
        val result = Http(url).proxy(proxyHost, proxyPort).proxyAuth("test", "test").asString
        assertEquals(theExpectedBody, result.body)
        assertEquals(200, result.code)
      })
    })
  }

  @Test
  def allModificationsAreAdditive: Unit = {
    val params = List("a" -> "b")
    val proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("host", 80))
    val headers = List("foo" -> "bar")
    val options = List(HttpOptions.readTimeout(1234))

    var req = Http("http://foo.com/").params(params)

    req = req.proxy("host", 80)

    assertEquals("params", params, req.params)

    val expectedNewOptions = options ++ req.options
    req = req.options(options)

    assertEquals("params", params, req.params)
    assertEquals("proxy", proxy, req.proxyConfig.get)
    assertEquals("options", expectedNewOptions, req.options)

    req = req.headers(headers)

    assertEquals("params", params, req.params)
    assertEquals("proxy", proxy, req.proxyConfig.get)
    assertEquals("options", expectedNewOptions, req.options)

  }

  @Test(expected = classOf[java.net.ConnectException])
  def serverDown: Unit = {
    val response = Http("http://localhost:9999/").execute()
    assertEquals("", response.body)
  }

  @Test
  def varargs: Unit = {
    val req = Http("http://foo.com/").params("a" -> "b", "b" -> "a")
                       .headers("a" -> "b", "b" -> "a")
                       .options(HttpOptions.connTimeout(100), HttpOptions.readTimeout(100))
    assertEquals(2, req.params.size)
  }

  @Test
  def parseCookies: Unit = {
    val httpResponse = HttpResponse("hi", 200, Map("Set-Cookie" -> IndexedSeq("foo=bar", "baz=biz")))
    assertEquals(IndexedSeq(new HttpCookie("foo", "bar"), new HttpCookie("baz", "biz")), httpResponse.cookies)
  }

  @Test(expected = classOf[scalaj.http.HttpStatusException])
  def throwErrorThrowsWith401: Unit = {
    HttpResponse("hi", 401, Map.empty).throwError
  }

  @Test(expected = classOf[scalaj.http.HttpStatusException])
  def throwServerErrorThrowsWith400: Unit = {
    HttpResponse("hi", 400, Map.empty).throwError
  }

  @Test
  def throwErrorOkWith200: Unit = {
    assertEquals(200, HttpResponse("hi", 200, Map.empty).throwError.code)
  }

  @Test
  def throwServerErrorOkWith400: Unit = {
    assertEquals(400, HttpResponse("hi", 400, Map.empty).throwServerError.code)
  }

  @Test
  def testGetEquals: Unit = {
    assertEquals(Http("http://foo.com/"), Http("http://foo.com/"))
  }

  @Test
  def testPostEquals: Unit = {
    assertEquals(Http("http://foo.com/").postData("hi"), Http("http://foo.com/").postData("hi"))
  }


}

class AuthProxyServlet extends ProxyServlet {
  override def service(req: ServletRequest, res: ServletResponse): Unit = {
    val httpReq = req.asInstanceOf[HttpServletRequest]
    val httpRes = res.asInstanceOf[HttpServletResponse]
    val proxyAuth = httpReq.getHeader("proxy-authorization")
    if(proxyAuth == null || proxyAuth == HttpConstants.basicAuthValue("test", "test")){
      super.service(req, res)
    }
    else {
      httpRes.sendError(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED, "invalid proxy auth")
    }
  }
}
