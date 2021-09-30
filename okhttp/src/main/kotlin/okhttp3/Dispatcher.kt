/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3

import java.util.ArrayDeque
import java.util.Collections
import java.util.Deque
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.internal.assertThreadDoesntHoldLock
import okhttp3.internal.connection.RealCall
import okhttp3.internal.connection.RealCall.AsyncCall
import okhttp3.internal.okHttpName
import okhttp3.internal.threadFactory

/**
 * Policy on when async requests are executed.
 *
 * Each dispatcher uses an [ExecutorService] to run calls internally. If you supply your own
 * executor, it should be able to run [the configured maximum][maxRequests] number of calls
 * concurrently.
 */
class Dispatcher constructor() {
  /**
   * The maximum number of requests to execute concurrently. Above this requests queue in memory,
   * waiting for the running calls to complete.
   *
   * If more than [maxRequests] requests are in flight when this is invoked, those requests will
   * remain in flight.
   */
  // 同一时间允许并发执行网络请求的最大线程数
  @get:Synchronized var maxRequests = 64
    set(maxRequests) {
      require(maxRequests >= 1) { "max < 1: $maxRequests" }
      synchronized(this) {
        field = maxRequests
      }
      promoteAndExecute()
    }

  /**
   * The maximum number of requests for each host to execute concurrently. This limits requests by
   * the URL's host name. Note that concurrent requests to a single IP address may still exceed this
   * limit: multiple hostnames may share an IP address or be routed through the same HTTP proxy.
   *
   * If more than [maxRequestsPerHost] requests are in flight when this is invoked, those requests
   * will remain in flight.
   *
   * WebSocket connections to hosts **do not** count against this limit.
   */
  // 同一 host 下的最大同时请求数
  @get:Synchronized var maxRequestsPerHost = 5
    set(maxRequestsPerHost) {
      require(maxRequestsPerHost >= 1) { "max < 1: $maxRequestsPerHost" }
      synchronized(this) {
        field = maxRequestsPerHost
      }
      promoteAndExecute()
    }

  /**
   * A callback to be invoked each time the dispatcher becomes idle (when the number of running
   * calls returns to zero).
   *
   * Note: The time at which a [call][Call] is considered idle is different depending on whether it
   * was run [asynchronously][Call.enqueue] or [synchronously][Call.execute]. Asynchronous calls
   * become idle after the [onResponse][Callback.onResponse] or [onFailure][Callback.onFailure]
   * callback has returned. Synchronous calls become idle once [execute()][Call.execute] returns.
   * This means that if you are doing synchronous calls the network layer will not truly be idle
   * until every returned [Response] has been closed.
   */
  @set:Synchronized
  @get:Synchronized
  var idleCallback: Runnable? = null

  private var executorServiceOrNull: ExecutorService? = null

  @get:Synchronized
  @get:JvmName("executorService") val executorService: ExecutorService
    get() {
      if (executorServiceOrNull == null) {
        executorServiceOrNull = ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS,
            SynchronousQueue(), threadFactory("$okHttpName Dispatcher", false))
      }
      return executorServiceOrNull!!
    }

  /** Ready async calls in the order they'll be run. */
  // 保存当前等待执行的异步任务
  private val readyAsyncCalls = ArrayDeque<AsyncCall>()

  /** Running asynchronous calls. Includes canceled calls that haven't finished yet. */
  // 保存当前正在执行的异步任务
  private val runningAsyncCalls = ArrayDeque<AsyncCall>()

  /** Running synchronous calls. Includes canceled calls that haven't finished yet. */
  // 保存当前正在执行的同步任务
  private val runningSyncCalls = ArrayDeque<RealCall>()

  constructor(executorService: ExecutorService) : this() {
    this.executorServiceOrNull = executorService
  }

  internal fun enqueue(call: AsyncCall) {
    synchronized(this) {
      // 存到 readyAsyncCalls 中
      readyAsyncCalls.add(call)

      // Mutate the AsyncCall so that it shares the AtomicInteger of an existing running call to
      // the same host.
      if (!call.call.forWebSocket) {
        // 查找当前是否有指向同一 Host 的异步请求
        val existingCall = findExistingCallWithHost(call.host)
        // 有则交换 callsPerHost 变量，该变量就用于标记当前指向同一 Host 的请求数量
        if (existingCall != null) call.reuseCallsPerHostFrom(existingCall)
      }
    }
    // 调用 promoteAndExecute 方法来判断当前是否允许发起请求
    promoteAndExecute()
  }

  private fun findExistingCallWithHost(host: String): AsyncCall? {
    for (existingCall in runningAsyncCalls) {
      if (existingCall.host == host) return existingCall
    }
    for (existingCall in readyAsyncCalls) {
      if (existingCall.host == host) return existingCall
    }
    return null
  }

  /**
   * Cancel all calls currently enqueued or executing. Includes calls executed both
   * [synchronously][Call.execute] and [asynchronously][Call.enqueue].
   */
  @Synchronized fun cancelAll() {
    for (call in readyAsyncCalls) {
      call.call.cancel()
    }
    for (call in runningAsyncCalls) {
      call.call.cancel()
    }
    for (call in runningSyncCalls) {
      call.cancel()
    }
  }

  /**
   * Promotes eligible calls from [readyAsyncCalls] to [runningAsyncCalls] and runs them on the
   * executor service. Must not be called with synchronization because executing calls can call
   * into user code.
   *
   * @return true if the dispatcher is currently running calls.
   */
  // 由于当前正在执行的网络请求总数可能已经达到限制，或者是指向同一 Host 的请求也达到限制了，
  // 所以 promoteAndExecute()方法就用于从待执行列表 readyAsyncCalls 中获取当前符合运行条件的所有请求，
  // 将请求存到 runningAsyncCalls 中，并调用线程池来执行
  private fun promoteAndExecute(): Boolean {
    this.assertThreadDoesntHoldLock()

    val executableCalls = mutableListOf<AsyncCall>()
    val isRunning: Boolean
    synchronized(this) {
      val i = readyAsyncCalls.iterator()
      while (i.hasNext()) {
        val asyncCall = i.next()

        // 如果当前正在执行的异步请求总数已经超出限制，则直接返回
        if (runningAsyncCalls.size >= this.maxRequests) break // Max capacity.
        // 如果指向同个 Host 的请求总数已经超出限制，则取下一个请求
        if (asyncCall.callsPerHost.get() >= this.maxRequestsPerHost) continue // Host max capacity.

        i.remove()
        // 将当前asyncCall的 callsPerHost 递增加一，表示指向该 Host 的链接数加一了
        asyncCall.callsPerHost.incrementAndGet()
        // 将当前asyncCall的 asyncCall 存到可执行列表中
        executableCalls.add(asyncCall)
        // 将当前asyncCall的 asyncCall 存到正在执行列表中
        runningAsyncCalls.add(asyncCall)
      }
      isRunning = runningCallsCount() > 0
    }

    // 执行所有符合条件的请求
    for (i in 0 until executableCalls.size) {
      val asyncCall = executableCalls[i]
      asyncCall.executeOn(executorService)
    }

    return isRunning
  }

  /** Used by [Call.execute] to signal it is in-flight. */
  @Synchronized internal fun executed(call: RealCall) {
    runningSyncCalls.add(call)
  }

  /** Used by [AsyncCall.run] to signal completion. */
  internal fun finished(call: AsyncCall) {
    call.callsPerHost.decrementAndGet()
    finished(runningAsyncCalls, call)
  }

  /** Used by [Call.execute] to signal completion. */
  internal fun finished(call: RealCall) {
    finished(runningSyncCalls, call)
  }

  private fun <T> finished(calls: Deque<T>, call: T) {
    val idleCallback: Runnable?
    synchronized(this) {
      if (!calls.remove(call)) throw AssertionError("Call wasn't in-flight!")
      idleCallback = this.idleCallback
    }
    // 判断是否有需要处理的网络请求，有的话则启动
    val isRunning = promoteAndExecute()

    if (!isRunning && idleCallback != null) {
      idleCallback.run()
    }
  }

  /** Returns a snapshot of the calls currently awaiting execution. */
  @Synchronized fun queuedCalls(): List<Call> {
    return Collections.unmodifiableList(readyAsyncCalls.map { it.call })
  }

  /** Returns a snapshot of the calls currently being executed. */
  @Synchronized fun runningCalls(): List<Call> {
    return Collections.unmodifiableList(runningSyncCalls + runningAsyncCalls.map { it.call })
  }

  @Synchronized fun queuedCallsCount(): Int = readyAsyncCalls.size

  @Synchronized fun runningCallsCount(): Int = runningAsyncCalls.size + runningSyncCalls.size

  @JvmName("-deprecated_executorService")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "executorService"),
      level = DeprecationLevel.ERROR)
  fun executorService(): ExecutorService = executorService
}
