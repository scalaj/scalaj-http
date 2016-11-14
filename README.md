[![Build Status](https://travis-ci.org/scalaj/scalaj-http.png)](https://travis-ci.org/scalaj/scalaj-http)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.scalaj/scalaj-http_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.scalaj/scalaj-http_2.11)

# Simplified Http

This is a fully featured http client for Scala which wraps [java.net.HttpURLConnection](https://docs.oracle.com/javase/8/docs/api/java/net/HttpURLConnection.html)

Features:
* Zero dependencies
* Cross compiled for Scala 2.9.3, 2.10, 2.11, and 2.12
* OAuth v1 request signing
* Automatic support of gzip and deflate encodings from server
* Easy to add querystring or form params. URL encoding is handled for you.
* Multipart file uploads

Non-Features:
* Async execution
  * The library is thread safe. HttpRequest and HttpResponse are immutable. So it should be easy to wrap in an execution framework of your choice.

Works in Google AppEngine and Android environments.

**_Note:_ 2.x.x is a new major version which is both syntactically and behaviorally different than the 0.x.x version.**

Previous version is branched here: https://github.com/scalaj/scalaj-http/tree/0.3.x

Big differences:
* Executing the request always returns a HttpResponse[T] instance that contains the response-code, headers, and body
* Exceptions are no longer thrown for 4xx and 5xx response codes. Yay!
* Http(url) is the starting point for every type of request (post, get, multi, etc)
* You can easily create your own singleton instance to set your own defaults (timeouts, proxies, etc)
* Sends "Accept-Encoding: gzip,deflate" request header and decompresses based on Content-Encoding (configurable)
* Redirects are no longer followed by default. Use .option(HttpOptions.followRedirects(true)) to change.

## Installation

### in your build.sbt

```scala
libraryDependencies +=  "org.scalaj" %% "scalaj-http" % "2.3.0"
```

### maven

```xml
<dependency>
  <groupId>org.scalaj</groupId>
  <artifactId>scalaj-http_${scala.version}</artifactId>
  <version>2.3.0</version>
</dependency>  
```

If you're including this in some other public library. Do your users a favor and change the fully qualified name
so they don't have version conflicts if they're using a different version of this library. 
The easiest way to do that is just to copy the source into your project :)

## Usage

### Simple Get

```scala
import scalaj.http._
  
val response: HttpResponse[String] = Http("http://foo.com/search").param("q","monkeys").asString
response.body
response.code
response.headers
response.cookies
```

### Immutable Request

```Http(url)``` is just shorthand for a ```Http.apply``` which returns an immutable instance of ```HttpRequest```.  
You can create a ```HttpRequest``` and reuse it:

```scala
val request: HttpRequest = Http("http://date.jsontest.com/")

val responseOne = request.asString
val responseTwo = request.asString
```

#### Additive Request

All the "modification" methods of a ```HttpRequest``` are actually returning a new instance. The param(s), option(s), header(s) 
methods always add to their respective sets. So calling ```.headers(newHeaders)``` will return a ```HttpRequest``` instance 
that has ```newHeaders``` appended to the previous ```req.headers```


### Simple form encoded POST

```scala
Http("http://foo.com/add").postForm(Seq("name" -> "jon", "age" -> "29")).asString
```

### OAuth v1 Dance and Request

*Note: the `.oauth(...)` call must be the last method called in the request construction*

```scala
import scalaj.http.{Http, Token}

val consumer = Token("key", "secret")
val response = Http("https://api.twitter.com/oauth/request_token").postForm(Seq("oauth_callback" -> "oob"))
  .oauth(consumer).asToken

println("Go to https://api.twitter.com/oauth/authorize?oauth_token=" + response.body.key)

val verifier = Console.readLine("Enter verifier: ").trim

val accessToken = Http("https://api.twitter.com/oauth/access_token").postForm.
  .oauth(consumer, token, verifier).asToken

println(Http("https://api.twitter.com/1.1/account/settings.json").oauth(consumer, accessToken.body).asString)
```

### Parsing the response

```scala
Http("http://foo.com").{asString, asBytes, asParams}
```
Those methods will return an ```HttpResponse[String | Array[Byte] | Seq[(String, String)]]``` respectively

## Advanced Usage Examples

### Parse the response InputStream directly

```scala
val response: HttpResponse[Map[String,String]] = Http("http://foo.com").execute(parser = {inputStream =>
  Json.parse[Map[String,String]](inputStream)
})
```

### Post raw Array[Byte] or String data and get response code

```scala
Http(url).postData(data).header("content-type", "application/json").asString.code
```

### Post multipart/form-data

```scala
Http(url).postMulti(MultiPart("photo", "headshot.png", "image/png", fileBytes)).asString
```

You can also stream uploads and get a callback on progress:

```scala
Http(url).postMulti(MultiPart("photo", "headshot.png", "image/png", inputStream, bytesInStream, 
  lenWritten => {
    println(s"Wrote $lenWritten bytes out of $bytesInStream total for headshot.png")
  })).asString
```

### Stream a chunked transfer response (like an event stream)

```scala
Http("http://httpbin.org/stream/20").execute(is => {
  scala.io.Source.fromInputStream(is).getLines().foreach(println)
})
```

_note that you may have to wrap in a while loop and set a long readTimeout to stay connected_

### Send https request to site with self-signed or otherwise shady certificate

```scala
Http("https://localhost/").option(HttpOptions.allowUnsafeSSL).asString
```

### Do a HEAD request

```scala
Http(url).method("HEAD").asString
```

### Custom connect and read timeouts

_These are set to 1000 and 5000 milliseconds respectively by default_

```scala
Http(url).timeout(connTimeoutMs = 1000, readTimeoutMs = 5000).asString
```

### Get request via a proxy

```scala
val response = Http(url).proxy(proxyHost, proxyPort).asString
```

### Other custom options

The ```.option()``` method takes a function of type ```HttpURLConnection => Unit``` so 
you can manipulate the connection in whatever way you want before the request executes.

### Change the Charset

By default, the charset for all param encoding and string response parsing is UTF-8. You 
can override with charset of your choice:

```scala
Http(url).charset("ISO-8859-1").asString
```

### Create your own HttpRequest builder

You don't have to use the default Http singleton. Create your own:

```scala
object MyHttp extends BaseHttp (
  proxyConfig: Option[Proxy] = None,
  options: Seq[HttpOptions.HttpOption] = HttpConstants.defaultOptions,
  charset: String = HttpConstants.utf8,
  sendBufferSize: Int = 4096,
  userAgent: String = "scalaj-http/1.0",
  compress: Boolean = true
)
```

### Full API documentation

[scaladocs here](http://scalaj.github.io/scalaj-http/2.0.0)

## Dealing with annoying java library issues
#### Overriding the `Access-Control, Content-Length, Content-Transfer-Encoding, Host, Keep-Alive, Origin, Trailer, Transfer-Encoding, Upgrade, Via` headers
Some of the headers are locked by the java library for "security" reasons and the behavior is that the library will just silently fail to set them. You can workaround by doing one of the following:
   * Start your JVM with this command line parameter: `-Dsun.net.http.allowRestrictedHeaders=true`
   * or, do this first thing at runtime: `System.setProperty("sun.net.http.allowRestrictedHeaders", "true")`
