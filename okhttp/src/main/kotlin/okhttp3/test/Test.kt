package okhttp3.test

/**
 * 简易版，拦截器请求
 */
class Request

class Response

interface Chain {

  fun request(): Request

  fun proceed(request: Request): Response

}

interface Interceptor {

  fun intercept(chain: Chain): Response

}

class RealInterceptorChain(
  private val request: Request,
  private val interceptors: List<Interceptor>,
  private val index: Int
) : Chain {

  private fun copy(index: Int): RealInterceptorChain {
    return RealInterceptorChain(request, interceptors, index)
  }

  override fun request(): Request {
    // 请求对象
    return request
  }

  override fun proceed(request: Request): Response {
    // 下一个RealInterceptorChain，用
    val next = copy(index = index + 1)
    // index为当前的
    val interceptor = interceptors[index]
    val response = interceptor.intercept(next)
    return response
  }

}

class LogInterceptor : Interceptor {
  override fun intercept(chain: Chain): Response {
    val request = chain.request()
    println("LogInterceptor -- getRequest")
    val response = chain.proceed(request)
    println("LogInterceptor ---- getResponse")
    return response
  }
}

class HeaderInterceptor : Interceptor {
  override fun intercept(chain: Chain): Response {
    val request = chain.request()
    println("HeaderInterceptor -- getRequest")
    val response = chain.proceed(request)
    println("HeaderInterceptor ---- getResponse")
    return response
  }
}

class CallServerInterceptor : Interceptor {
  override fun intercept(chain: Chain): Response {
    val request = chain.request()
    println("CallServerInterceptor -- getRequest")
    val response = Response()
    println("CallServerInterceptor ---- getResponse")
    return response
  }
}

fun main() {
  val interceptorList = mutableListOf<Interceptor>()
  interceptorList.add(LogInterceptor())
  interceptorList.add(HeaderInterceptor())
  interceptorList.add(CallServerInterceptor())
  val request = Request()
  val realInterceptorChain = RealInterceptorChain(request, interceptorList, 0)
  val response = realInterceptorChain.proceed(request)
  println("main response")
}
