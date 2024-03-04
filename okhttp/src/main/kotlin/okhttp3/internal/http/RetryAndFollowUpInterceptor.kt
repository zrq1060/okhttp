/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.http

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.canReuseConnectionFor
import okhttp3.internal.closeQuietly
import okhttp3.internal.connection.Exchange
import okhttp3.internal.connection.RealCall
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.stripBody
import okhttp3.internal.withSuppressed

/**
 * This interceptor recovers from failures and follows redirects as necessary. It may throw an
 * [IOException] if the call was canceled.
 */
class RetryAndFollowUpInterceptor(private val client: OkHttpClient) : Interceptor {
  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    val realChain = chain as RealInterceptorChain
    var request = chain.request
    val call = realChain.call
    var followUpCount = 0
    var priorResponse: Response? = null
    var newRoutePlanner = true
    var recoveredFailures = listOf<IOException>()
    while (true) {
      call.enterNetworkInterceptorExchange(request, newRoutePlanner, chain)

      var response: Response
      var closeActiveExchange = true
      try {
        if (call.isCanceled()) {
          throw IOException("Canceled")
        }

        try {
          // 2. 开始执行，进入后续拦截器，真正进行网络请求；
          response = realChain.proceed(request)
          newRoutePlanner = true
        } catch (e: IOException) {
          // 4. 发生IOException异常，是否可恢复判断逻辑参照上述RouteException判断逻辑
          // An attempt to communicate with a server failed. The request may have been sent.
          if (!recover(e, call, request, requestSendStarted = e !is ConnectionShutdownException)) {
            throw e.withSuppressed(recoveredFailures)
          } else {
            recoveredFailures += e
          }
          newRoutePlanner = false
          continue
        }

        // Clear out downstream interceptor's additional request headers, cookies, etc.
        // 5. 根据上一个Response结果构建一个新的response对象，且这个对象的body为空
        response =
          response.newBuilder()
            .request(request)
            .priorResponse(priorResponse?.stripBody())
            .build()

        // 6. 根据请求码创建一个新的请求，以供下一次重试请求使用
        val exchange = call.interceptorScopedExchange
        val followUp = followUpRequest(response, exchange)

        // 7. 如果第六步构建的出来的Request为空，则不再进行，直接返回Response
        if (followUp == null) {
          if (exchange != null && exchange.isDuplex) {
            call.timeoutEarlyExit()
          }
          closeActiveExchange = false
          return response
        }

        // 8. 构建的Request对象存在请求body，且为一次性请求，则直接返回Response，也不进行重试。
        val followUpBody = followUp.body
        if (followUpBody != null && followUpBody.isOneShot()) {
          closeActiveExchange = false
          return response
        }

        response.body.closeQuietly()

        // 9. 判断当前重试次数是否已经到达最大次数（默认20），如果到达，则直接抛出异常
        if (++followUpCount > MAX_FOLLOW_UPS) {
          throw ProtocolException("Too many follow-up requests: $followUpCount")
        }

        // 10. 如果上述没有抛出异常或者中断循环，则进入while循环，开始下一次重试过程
        request = followUp
        priorResponse = response
      } finally {
        call.exitNetworkInterceptorExchange(closeActiveExchange)
      }
    }
  }

  /**
   * Report and attempt to recover from a failure to communicate with a server. Returns true if
   * `e` is recoverable, or false if the failure is permanent. Requests with a body can only
   * be recovered if the body is buffered or if the failure occurred before the request has been
   * sent.
   */
  // 是否可恢复
  private fun recover(
    e: IOException,
    call: RealCall,
    userRequest: Request,
    requestSendStarted: Boolean,
  ): Boolean {
    // The application layer has forbidden retries.
    // 1. 首先判断是否允许重试，根据我们创建请求的时候配置的重试开关，如果配置为false，则不允许重试;
    if (!client.retryOnConnectionFailure) return false

    // We can't send the request body again.
    // 2. 第二层判断如果请求已经开始，且当前请求最多只能被发送一次的情况下，则不允许重试；
    if (requestSendStarted && requestIsOneShot(e, userRequest)) return false

    // This exception is fatal.
    // 3. 判断当前请求是否可恢复的，以下异常场景不可恢复：
    //    a. ProtocolException，协议异常
    //    b. SocketTimeoutException，Socket链接超时且请求没有开始
    //    c. SSLHandshakeException && CertificateException ：
    //        表示和服务端约定的安全级别不匹配异常，引起基本为证书引起的，这种链接是不可用的。
    //    d. SSLPeerUnverifiedException
    //        对等实体认证异常，也就是说对等个体没有被验证，类似没有证书，或者在握手期间没有建立对等个体验证；
    if (!isRecoverable(e, requestSendStarted)) return false

    // No more routes to attempt.
    // 4. 判断是否存在其他可重试的路由，如果不存在，不允许重试；
    if (!call.retryAfterFailure()) return false

    // For failure recovery, use the same route selector with a new connection.
    // 5. 不属于上述情况判断可以重试；
    return true
  }

  private fun requestIsOneShot(
    e: IOException,
    userRequest: Request,
  ): Boolean {
    val requestBody = userRequest.body
    return (requestBody != null && requestBody.isOneShot()) ||
      e is FileNotFoundException
  }

  private fun isRecoverable(
    e: IOException,
    requestSendStarted: Boolean,
  ): Boolean {
    // If there was a protocol problem, don't recover.
    if (e is ProtocolException) {
      return false
    }

    // If there was an interruption don't recover, but if there was a timeout connecting to a route
    // we should try the next route (if there is one).
    if (e is InterruptedIOException) {
      return e is SocketTimeoutException && !requestSendStarted
    }

    // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
    // again with a different route.
    if (e is SSLHandshakeException) {
      // If the problem was a CertificateException from the X509TrustManager,
      // do not retry.
      if (e.cause is CertificateException) {
        return false
      }
    }
    if (e is SSLPeerUnverifiedException) {
      // e.g. a certificate pinning error.
      return false
    }
    // An example of one we might want to retry with a different route is a problem connecting to a
    // proxy and would manifest as a standard IOException. Unless it is one we know we should not
    // retry, we return true and try a new route.
    return true
  }

  /**
   * Figures out the HTTP request to make in response to receiving [userResponse]. This will
   * either add authentication headers, follow redirects or handle a client request timeout. If a
   * follow-up is either unnecessary or not applicable, this returns null.
   */
  @Throws(IOException::class)
  private fun followUpRequest(
    userResponse: Response,
    exchange: Exchange?,
  ): Request? {
    val route = exchange?.connection?.route()
    val responseCode = userResponse.code

    val method = userResponse.request.method
    when (responseCode) {
      HTTP_PROXY_AUTH -> {
        // HTTP_PROXY_AUTH（407）：代理认证，需要进行代理认证（默认实现返回null，可以进行重写覆盖实现）；
        val selectedProxy = route!!.proxy
        if (selectedProxy.type() != Proxy.Type.HTTP) {
          throw ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy")
        }
        return client.proxyAuthenticator.authenticate(route, userResponse)
      }

      // HTTP_UNAUTHORIZED（401）：未授权，需要进行授权认证（默认实现返回null，可以进行重写覆盖实现）；
      HTTP_UNAUTHORIZED -> return client.authenticator.authenticate(route, userResponse)

      HTTP_PERM_REDIRECT, HTTP_TEMP_REDIRECT, HTTP_MULT_CHOICE, HTTP_MOVED_PERM, HTTP_MOVED_TEMP, HTTP_SEE_OTHER -> {
        // HTTP_PERM_REDIRECT（307）临时重定向
        // HTTP_TEMP_REDIRECT（308）永久重定向
        // HTTP_MULT_CHOICE（300） 多选项
        // HTTP_MOVED_PERM（301） 永久重定向，表示请求的资源已经分配了新的URI，以后应该使用新的URI
        // HTTP_MOVED_TEMP（302）临时性重定向
        // HTTP_SEE_OTHER（303）表示由于请求对应的资源存在着另外一个URI，应该使用GET方法定向获取请求的资源
        return buildRedirectRequest(userResponse, method)
      }

      HTTP_CLIENT_TIMEOUT -> {
        // HTTP_CLIENT_TIMEOUT（408）：请求超时，逻辑如下
        // 408's are rare in practice, but some servers like HAProxy use this response code. The
        // spec says that we may repeat the request without modifications. Modern browsers also
        // repeat the request (even non-idempotent ones.)
        if (!client.retryOnConnectionFailure) {
          // The application layer has directed us not to retry the request.
          return null
        }

        val requestBody = userResponse.request.body
        if (requestBody != null && requestBody.isOneShot()) {
          return null
        }
        val priorResponse = userResponse.priorResponse
        if (priorResponse != null && priorResponse.code == HTTP_CLIENT_TIMEOUT) {
          // We attempted to retry and got another timeout. Give up.
          return null
        }

        if (retryAfter(userResponse, 0) > 0) {
          return null
        }

        return userResponse.request
      }

      HTTP_UNAVAILABLE -> {
        // HTTP_UNAVAILABLE（503）：表明服务器暂时处于超负载或正在进行停机维护，现无法处理请求。
        val priorResponse = userResponse.priorResponse
        if (priorResponse != null && priorResponse.code == HTTP_UNAVAILABLE) {
          // We attempted to retry and got another timeout. Give up.
          return null
        }

        if (retryAfter(userResponse, Integer.MAX_VALUE) == 0) {
          // specifically received an instruction to retry without delay
          return userResponse.request
        }

        return null
      }

      HTTP_MISDIRECTED_REQUEST -> {
        // OkHttp can coalesce HTTP/2 connections even if the domain names are different. See
        // RealConnection.isEligible(). If we attempted this and the server returned HTTP 421, then
        // we can retry on a different connection.
        val requestBody = userResponse.request.body
        if (requestBody != null && requestBody.isOneShot()) {
          return null
        }

        if (exchange == null || !exchange.isCoalescedConnection) {
          return null
        }

        exchange.connection.noCoalescedConnections()
        return userResponse.request
      }

      else -> return null
    }
  }

  private fun buildRedirectRequest(
    userResponse: Response,
    method: String,
  ): Request? {
    // Does the client allow redirects?
    if (!client.followRedirects) return null

    val location = userResponse.header("Location") ?: return null
    // Don't follow redirects to unsupported protocols.
    val url = userResponse.request.url.resolve(location) ?: return null

    // If configured, don't follow redirects between SSL and non-SSL.
    val sameScheme = url.scheme == userResponse.request.url.scheme
    if (!sameScheme && !client.followSslRedirects) return null

    // Most redirects don't include a request body.
    val requestBuilder = userResponse.request.newBuilder()
    if (HttpMethod.permitsRequestBody(method)) {
      val responseCode = userResponse.code
      val maintainBody =
        HttpMethod.redirectsWithBody(method) ||
          responseCode == HTTP_PERM_REDIRECT ||
          responseCode == HTTP_TEMP_REDIRECT
      if (HttpMethod.redirectsToGet(method) && responseCode != HTTP_PERM_REDIRECT && responseCode != HTTP_TEMP_REDIRECT) {
        requestBuilder.method("GET", null)
      } else {
        val requestBody = if (maintainBody) userResponse.request.body else null
        requestBuilder.method(method, requestBody)
      }
      if (!maintainBody) {
        requestBuilder.removeHeader("Transfer-Encoding")
        requestBuilder.removeHeader("Content-Length")
        requestBuilder.removeHeader("Content-Type")
      }
    }

    // When redirecting across hosts, drop all authentication headers. This
    // is potentially annoying to the application layer since they have no
    // way to retain them.
    if (!userResponse.request.url.canReuseConnectionFor(url)) {
      requestBuilder.removeHeader("Authorization")
    }

    return requestBuilder.url(url).build()
  }

  private fun retryAfter(
    userResponse: Response,
    defaultDelay: Int,
  ): Int {
    val header = userResponse.header("Retry-After") ?: return defaultDelay

    // https://tools.ietf.org/html/rfc7231#section-7.1.3
    // currently ignores a HTTP-date, and assumes any non int 0 is a delay
    if (header.matches("\\d+".toRegex())) {
      return Integer.valueOf(header)
    }
    return Integer.MAX_VALUE
  }

  companion object {
    /**
     * How many redirects and auth challenges should we attempt? Chrome follows 21 redirects; Firefox,
     * curl, and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
     */
    private const val MAX_FOLLOW_UPS = 20
  }
}
