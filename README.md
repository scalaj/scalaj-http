[![Build Status](https://travis-ci.org/scalaj/scalaj-http.png)](https://travis-ci.org/scalaj/scalaj-http)

# Simple Http

This is a bare bones http client for scala which wraps HttpURLConnection

## Usage

### Simple Get

```scala
import scalaj.http.Http
  
Http("http://foo.com/search").param("q","monkeys").asString
```

### Immutable Request

```Http(url)``` is just shorthead for a ```Http.apply``` which returns an immutable instance of ```Http.Request```.  
You can create a ```Request``` and reuse it:

```scala
val request: Http.Request = Http("http://date.jsontest.com/")

val resultOne = request.asString
val resultTwo = request.asString
```

#### Additive Request

All the "modification" methods of a ```Request``` are actually returning a new instance. The param(s), option(s), header(s) 
methods always add to their respective sets. So calling ```.headers(newHeaders)``` will return a ```Request``` instance 
that has ```newHeaders``` appended to the previous ```req.headers```


### Simple Post

```scala
Http.post("http://foo.com/add").params("name" -> "jon", "age" -> "29").asString
```

### OAuth v1 Dance and Request

```scala
import scalaj.http.{Http, Token}

val consumer = Token("key", "secret")
val token = Http.post("https://api.twitter.com/oauth/request_token").param("oauth_callback","oob")
  .oauth(consumer).asToken

println("Go to https://api.twitter.com/oauth/authorize?oauth_token=" + token.key)

val verifier = Console.readLine("Enter verifier: ").trim

val accessToken = Http.post("https://api.twitter.com/oauth/access_token")
  .oauth(consumer, token, verifier).asToken

println(Http("https://api.twitter.com/1.1/account/settings.json").oauth(consumer, accessToken).asString)
```

### Parsing the response

```scala
Http("http://foo.com").{responseCode, asString, asXml, asBytes, asParams}
```

## Installation

### sbt

```scala
val scalaj_http = "org.scalaj" %% "scalaj-http" % "0.3.12"
```

### maven

```xml
<dependency>
  <groupId>org.scalaj</groupId>
  <artifactId>scalaj-http_${scala.version}</artifactId>
  <version>0.3.12</version>
</dependency>  
```

## Advanced Usage Examples

### Parse the response InputStream to JSON

```scala
import java.io.InputStreamReader
import net.liftweb.json.JsonParser

Http("http://foo.com"){inputStream => 
  JsonParser.parse(new InputStreamReader(inputStream))
}
```

### Post raw Array[Byte] or String data and get response code

```scala
Http.postData(url, data).header("content-type", "application/json").responseCode
```

### Post multipart/form-data

```scala
Http.multipart(url, MultiPart("photo", "headshot.png", "image/png", fileBytes)).responseCode
```

You can also stream uploads and get a callback on progress:

```scala
Http.multipart(url, MultiPart("photo", "headshot.png", "image/png", inputStream, bytesInStream, 
  lenWritten => {
    println("Wrote %d bytes out of %d total for headshot.png".format(lenWritten, bytesInStream))
  })).responseCode
```

### Send https request to site with self-signed or otherwise shady certificate

```scala
Http("https://localhost/").option(HttpOptions.allowUnsafeSSL).asString
```

### Do a HEAD request

```scala
Http(url).method("HEAD").asString
```

### Custom connect and read timeouts

_These are set to 100 and 500 milliseconds respectively by default_

```scala
Http(url).option(HttpOptions.connTimeout(1000)).option(HttpOptions.readTimeout(5000)).asString
```

### Get responseCode, responseHeaders and parsedResponse

```scala
val (responseCode, headersMap, resultString) = Http(url).asHeadersAndParse(Http.readString)
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
