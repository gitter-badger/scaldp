/**
   Copyright 2013 Typesafe (http://www.typesafe.com).

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

//taken from Play 2.2 because WS is not published in its own library
//appended org.w3 original package to avoid name clashes when using play

package org.w3.play.api.libs.ws

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent._
import scala.util.Try

//import play.api.http.{ContentTypeOf, Writeable}
import com.ning.http.client.{AsyncHttpClient, AsyncHttpClientConfig, FluentCaseInsensitiveStringsMap, HttpResponseBodyPart, HttpResponseHeaders, HttpResponseStatus, PerRequestConfig, RequestBuilderBase, Response => AHCResponse}

import scala.collection.immutable.TreeMap
//import play.core.utils.CaseInsensitiveOrdered
import java.io.File

import com.ning.http.client.Realm.AuthScheme
import com.ning.http.util.AsyncHttpProviderUtils
import org.w3.banana.io.{Syntax, Writer}
import play.api.libs.iteratee.Input.El
import play.api.libs.iteratee._

import scala.collection.JavaConverters._

//import play.core.Execution.Implicits.internalContext
//import play.api.libs.json.{Json, JsValue}

/**
 * Asynchronous API to to query web services, as an http client.
 *
 * Usage example:
 * {{{
 * WS.url("http://example.com/feed").get()
 * WS.url("http://example.com/item").post("content")
 * }}}
 *
 * The value returned is a Future[Response],
 * and you should use Play's asynchronous mechanisms to use this response.
 *
 */
object WS extends WSTrait {

  import com.ning.http.client.Realm.{AuthScheme, RealmBuilder}
  implicit val ec = scala.concurrent.ExecutionContext.Implicits.global

  private val clientHolder: AtomicReference[Option[AsyncHttpClient]] = new AtomicReference(None)

  /**
   * resets the underlying AsyncHttpClient
   */
  private[play] def resetClient(): Unit = {
    val oldClient = clientHolder.getAndSet(None)
    oldClient.map { clientRef =>
      clientRef.close()
    }
  }

  /**
   * retrieves or creates underlying HTTP client.
   */
  def client = {
    clientHolder.get.getOrElse {
      val innerClient = new AsyncHttpClient(asyncBuilder.build())
      clientHolder.compareAndSet(None, Some(innerClient))
      clientHolder.get.get
    }
  }

  /**
   * The  builder AsncBuilder for default play app
   **/
  def asyncBuilder: AsyncHttpClientConfig.Builder = {
    val playConfig = None //play.api.Play.maybeApplication.map(_.configuration)

    //todo: use RDF graph to set the options

    val asyncHttpConfig = new AsyncHttpClientConfig.Builder()
      .setConnectionTimeoutInMs(120000)
      .setRequestTimeoutInMs(120000)
      .setFollowRedirects(true)
      .setUseProxyProperties(true)
      .setUserAgent("W3C LDP Scala UserAgent")

    asyncHttpConfig
  }

  def ningHeadersToMap(headers: java.util.Map[String, java.util.Collection[String]]) =
    mapAsScalaMapConverter(headers).asScala.map(e => e._1 -> e._2.asScala.toSeq).toMap

  def ningHeadersToMap(headers: FluentCaseInsensitiveStringsMap) = {
    val res = mapAsScalaMapConverter(headers).asScala.map(e => e._1 -> e._2.asScala.toSeq).toMap
    //todo: wrap the case insensitive ning map instead of creating a new one (unless perhaps immutabilty is important)
    TreeMap(res.toSeq: _*)(CaseInsensitiveOrdered)
  }

  /**
   * A WS Request.
   */
  class WSRequest(_method: String, _auth: Option[Tuple3[String, String, AuthScheme]], _calc: Option[SignatureCalculator])
                 (implicit client: AsyncHttpClient)
    extends RequestBuilderBase[WSRequest](classOf[WSRequest], _method, false) {

    import scala.collection.JavaConverters._

    def getStringData = body.getOrElse("")
    protected var body: Option[String] = None
    override def setBody(s: String) = { this.body = Some(s); super.setBody(s) }

    protected var calculator: Option[SignatureCalculator] = _calc

    protected var headers: Map[String, Seq[String]] = Map()

    protected var _url: String = null

    //this will do a java mutable set hence the {} response
    _auth.map(data => auth(data._1, data._2, data._3)).getOrElse({})

    /**
     * Add http auth headers. Defaults to HTTP Basic.
     */
    private def auth(username: String, password: String, scheme: AuthScheme = AuthScheme.BASIC): WSRequest = {
      this.setRealm((new RealmBuilder())
        .setScheme(scheme)
        .setPrincipal(username)
        .setPassword(password)
        .setUsePreemptiveAuth(true)
        .build())
    }

    /**
     * Return the current headers of the request being constructed
     */
    def allHeaders: Map[String, Seq[String]] = {
      mapAsScalaMapConverter(request.asInstanceOf[com.ning.http.client.Request].getHeaders()).asScala.map(e => e._1 -> e._2.asScala.toSeq).toMap
    }

    /**
     * Return the current query string parameters
     */
    def queryString: Map[String, Seq[String]] = {
      mapAsScalaMapConverter(request.asInstanceOf[com.ning.http.client.Request].getParams()).asScala.map(e => e._1 -> e._2.asScala.toSeq).toMap
    }

    /**
     * Retrieve an HTTP header.
     */
    def header(name: String): Option[String] = headers.get(name).flatMap(_.headOption)

    /**
     * The HTTP method.
     */
    def method: String = _method

    /**
     * The URL
     */
    def url: String = _url


    private[libs] def execute: Future[Response] = {
      import com.ning.http.client.AsyncCompletionHandler
      var result = Promise[Response]()
      calculator.map(_.sign(this))
      WS.client.executeRequest(this.build(), new AsyncCompletionHandler[AHCResponse]() {
        override def onCompleted(response: AHCResponse) = {
          result.success(Response(response))
          response
        }
        override def onThrowable(t: Throwable) = {
          result.failure(t)
        }
      })
      result.future
    }

    /**
     * Set an HTTP header.
     */
    override def setHeader(name: String, value: String) = {
      headers = headers + (name -> List(value))
      super.setHeader(name, value)
    }

    /**
     * Add an HTTP header (used for headers with multiple values).
     */
    override def addHeader(name: String, value: String) = {
      headers = headers + (name -> (headers.get(name).getOrElse(List()) :+ value))
      super.addHeader(name, value)
    }

    /**
     * Defines the request headers.
     */
    override def setHeaders(hdrs: FluentCaseInsensitiveStringsMap) = {
      headers = ningHeadersToMap(hdrs)
      super.setHeaders(hdrs)
    }

    /**
     * Defines the request headers.
     */
    override def setHeaders(hdrs: java.util.Map[String, java.util.Collection[String]]) = {
      headers = ningHeadersToMap(hdrs)
      super.setHeaders(hdrs)
    }

    /**
     * Defines the request headers.
     */
    def setHeaders(hdrs: Map[String, Seq[String]]) = {
      headers = hdrs
      hdrs.foreach(header => header._2.foreach(value =>
        super.addHeader(header._1, value)
      ))
      this
    }

    /**
     * Defines the query string.
     */
    def setQueryString(queryString: Map[String, Seq[String]]) = {
      for ((key, values) <- queryString; value <- values) {
        this.addQueryParameter(key, value)
      }
      this
    }

    /**
     * Defines the URL.
     */
    override def setUrl(url: String) = {
      _url = url
      super.setUrl(url)
    }

    private[libs] def executeStream[A](consumer: ResponseHeaders => Iteratee[Array[Byte], A]): Future[Iteratee[Array[Byte], A]] = {
      import com.ning.http.client.AsyncHandler
      var doneOrError = false
      calculator.map(_.sign(this))

      var statusCode = 0
      val iterateeP = Promise[Iteratee[Array[Byte], A]]()
      var iteratee: Iteratee[Array[Byte], A] = null

      WS.client.executeRequest(this.build(), new AsyncHandler[Unit]() {
        import com.ning.http.client.AsyncHandler.STATE

        override def onStatusReceived(status: HttpResponseStatus) = {
          statusCode = status.getStatusCode()
          STATE.CONTINUE
        }

        override def onHeadersReceived(h: HttpResponseHeaders) = {
          val headers = h.getHeaders()
          iteratee = consumer(ResponseHeaders(statusCode, ningHeadersToMap(headers)))
          STATE.CONTINUE
        }

        override def onBodyPartReceived(bodyPart: HttpResponseBodyPart) = {
          if (!doneOrError) {
            iteratee = iteratee.pureFlatFold {
              case Step.Done(a, e) => {
                doneOrError = true
                val it = Done(a, e)
                iterateeP.success(it)
                it
              }

              case Step.Cont(k) => {
                k(El(bodyPart.getBodyPartBytes()))
              }

              case Step.Error(e, input) => {
                doneOrError = true
                val it = Error(e, input)
                iterateeP.success(it)
                it
              }
            }
            STATE.CONTINUE
          } else {
            iteratee = null
            // Must close underlying connection, otherwise async http client will drain the stream
            bodyPart.markUnderlyingConnectionAsClosed()
            STATE.ABORT
          }
        }

        override def onCompleted() = {
          Option(iteratee).map(iterateeP.success(_))
        }

        override def onThrowable(t: Throwable) = {
          iterateeP.failure(t)
        }
      })
      iterateeP.future
    }

  }

}

/**
 * A WS for fine tuning.
 * Eg: if one wants to make requests to different servers with different client certificates...
 * @param client
 */
case class WSx(client: AsyncHttpClient)(implicit val ec: ExecutionContext) extends WSTrait


trait  WSTrait {

  implicit def client: AsyncHttpClient
  implicit def ec: ExecutionContext

  /**
   * Prepare a new request. You can then construct it by chaining calls.
   *
   * @param url the URL to request
   */
  def url(url: String): WSRequestHolder = WSRequestHolder(url, Map(), Map(), None, None, None, None, None)


  /**
   * A WS Request builder.
   */
  case class WSRequestHolder(url: String,
                             headers: Map[String, Seq[String]],
                             queryString: Map[String, Seq[String]],
                             calc: Option[SignatureCalculator],
                             auth: Option[Tuple3[String, String, AuthScheme]],
                             followRedirects: Option[Boolean],
                             timeout: Option[Int],
                             virtualHost: Option[String]) {

    import org.w3.play.api.libs.ws.WS.WSRequest

    /**
     * sets the signature calculator for the request
     * @param calc
     */
    def sign(calc: SignatureCalculator): WSRequestHolder = this.copy(calc = Some(calc))

    /**
     * sets the authentication realm
     * @param username
     * @param password
     * @param scheme
     */
    def withAuth(username: String, password: String, scheme: AuthScheme): WSRequestHolder =
      this.copy(auth = Some((username, password, scheme)))

    /**
     * adds any number of HTTP headers
     * @param hdrs
     */
    def withHeaders(hdrs: (String, String)*): WSRequestHolder = {
      val headers = hdrs.foldLeft(this.headers)((m, hdr) =>
        if (m.contains(hdr._1)) m.updated(hdr._1, m(hdr._1) :+ hdr._2)
        else (m + (hdr._1 -> Seq(hdr._2)))
      )
      this.copy(headers = headers)
    }

    /**
     * adds any number of query string parameters to the
     */
    def withQueryString(parameters: (String, String)*): WSRequestHolder =
      this.copy(queryString = parameters.foldLeft(queryString) {
        case (m, (k, v)) => m + (k -> (v +: m.get(k).getOrElse(Nil)))
      })

    /**
     * Sets whether redirects (301, 302) should be followed automatically
     */
    def withFollowRedirects(follow: Boolean): WSRequestHolder =
      this.copy(followRedirects = Some(follow))

    /**
     * Sets the request timeout in milliseconds
     */
    def withTimeout(timeout: Int): WSRequestHolder =
      this.copy(timeout = Some(timeout))

    def withVirtualHost(vh: String): WSRequestHolder = {
      this.copy(virtualHost = Some(vh))
    }

    /**
     * performs a get with supplied body
     */

    def get(): Future[Response] = prepare("GET").execute

    /**
     * performs a get with supplied body
     * @param consumer that's handling the response
     */
    def get[A](consumer: ResponseHeaders => Iteratee[Array[Byte], A]): Future[Iteratee[Array[Byte], A]] =
      prepare("GET").executeStream(consumer)

    /**
     * Perform a POST on the request asynchronously.
     */
    def post[T,S](body: T, syntax: Syntax[S])(implicit wrt: Writer[T,Try, S]): Future[Response] =
      prepare("POST", body, syntax).execute

    /**
     * Perform a POST on the request asynchronously.
     * Request body won't be chunked
     */
    def post(body: File): Future[Response] = prepare("POST", body).execute

    /**
     * performs a POST with supplied body
     * @param consumer that's handling the response
     */
    def postAndRetrieveStream[A, T, S](body: T, syntax: Syntax[S])
                                      (consumer: ResponseHeaders => Iteratee[Array[Byte], A])
                                      (implicit wrt: Writer[T,Try,S]): Future[Iteratee[Array[Byte], A]] =
      prepare("POST", body, syntax).executeStream(consumer)

    /**
     * Perform a PUT on the request asynchronously.
     */
    def put[T,S](body: T, syntax: Syntax[S])(implicit wrt: Writer[T,Try,S]): Future[Response] = prepare("PUT", body,syntax).execute

    /**
     * Perform a PUT on the request asynchronously.
     * Request body won't be chunked
     */
    def put(body: File): Future[Response] = prepare("PUT", body).execute

    /**
     * performs a PUT with supplied body
     * @param consumer that's handling the response
     */
    def putAndRetrieveStream[A, T, S](body: T, syntax: Syntax[S])
                                                (consumer: ResponseHeaders => Iteratee[Array[Byte], A])
                                                (implicit wrt: Writer[T,Try,S]): Future[Iteratee[Array[Byte], A]] =
      prepare("PUT", body, syntax).executeStream(consumer)

    /**
     * Perform a DELETE on the request asynchronously.
     */
    def delete(): Future[Response] = prepare("DELETE").execute

    /**
     * Perform a HEAD on the request asynchronously.
     */
    def head(): Future[Response] = prepare("HEAD").execute

    /**
     * Perform a OPTIONS on the request asynchronously.
     */
    def options(): Future[Response] = prepare("OPTIONS").execute

    /**
     * Execute an arbitrary method on the request asynchronously.
     *
     * @param method The method to execute
     */
    def execute(method: String): Future[Response] = prepare(method).execute

    private[play] def prepare(method: String) = {
      val request = new WSRequest(method, auth, calc).setUrl(url)
        .setHeaders(headers)
        .setQueryString(queryString)
      followRedirects.map(request.setFollowRedirects(_))
      timeout.map { t: Int =>
        val config = new PerRequestConfig()
        config.setRequestTimeoutInMs(t)
        request.setPerRequestConfig(config)
      }
      virtualHost.map { v =>
        request.setVirtualHost(v)
      }
      request
    }

    private[play] def prepare(method: String, body: File) = {
      import com.ning.http.client.generators.FileBodyGenerator

      val bodyGenerator = new FileBodyGenerator(body);

      val request = new WSRequest(method, auth, calc).setUrl(url)
        .setHeaders(headers)
        .setQueryString(queryString)
        .setBody(bodyGenerator)
      followRedirects.map(request.setFollowRedirects(_))
      timeout.map { t: Int =>
        val config = new PerRequestConfig()
        config.setRequestTimeoutInMs(t)
        request.setPerRequestConfig(config)
      }
      virtualHost.map { v =>
        request.setVirtualHost(v)
      }

      request
    }

    private[play] def prepare[T,S](method: String, body: T, syntax: Syntax[S])(implicit wrt: Writer[T,Try,S]) = {
      val request = new WSRequest(method, auth, calc).setUrl(url)
        .setHeaders(Map("Content-Type" -> Seq(syntax.defaultMimeType.mime)) ++ headers)
        .setQueryString(queryString)
        .setBody(wrt.asString(body,"").get) //todo: this only works for strings. Adapt Writer for binaries. Also deal better with Try
      followRedirects.map(request.setFollowRedirects(_))
      timeout.map { t: Int =>
        val config = new PerRequestConfig()
        config.setRequestTimeoutInMs(t)
        request.setPerRequestConfig(config)
      }
      virtualHost.map { v =>
        request.setVirtualHost(v)
      }
      request
    }
  }
}

/**
 * A WS HTTP response.
 */
case class Response(ahcResponse: AHCResponse) {

  import scala.xml._

  /**
   * Get the underlying response object.
   */
  def getAHCResponse = ahcResponse

  /**
   * The response status code.
   */
  def status: Int = ahcResponse.getStatusCode()

  /**
   * The response status message.
   */
  def statusText: String = ahcResponse.getStatusText()

  /**
   * Get a response header.
   */
  def header(key: String): Option[String] = Option(ahcResponse.getHeader(key))

  /**
   * The response body as String.
   */
  lazy val body: String = {
    // RFC-2616#3.7.1 states that any text/* mime type should default to ISO-8859-1 charset if not
    // explicitly set, while Plays default encoding is UTF-8.  So, use UTF-8 if charset is not explicitly
    // set and content type is not text/*, otherwise default to ISO-8859-1
    val contentType = Option(ahcResponse.getContentType).getOrElse("application/octet-stream")
    val charset = Option(AsyncHttpProviderUtils.parseCharset(contentType)).getOrElse {
      if (contentType.startsWith("text/"))
        AsyncHttpProviderUtils.DEFAULT_CHARSET
      else
        "utf-8"
    }
    ahcResponse.getResponseBody(charset)
  }

  /**
   * The response body as Xml.
   */
  lazy val xml: Elem = XML.loadString(body)


}

/**
 * An HTTP response header (the body has not been retrieved yet)
 */
case class ResponseHeaders(status: Int, headers: Map[String, Seq[String]])

/**
 * Sign a WS call.
 */
trait SignatureCalculator {

  /**
   * Sign it.
   */
  def sign(request: WS.WSRequest)

}

object CaseInsensitiveOrdered extends Ordering[String] {
  def compare(x: String, y: String): Int = x.compareToIgnoreCase(y)
}

