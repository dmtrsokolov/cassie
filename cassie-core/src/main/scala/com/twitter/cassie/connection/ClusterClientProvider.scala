package com.twitter.cassie.connection

// Copyright 2012 Twitter, Inc.

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.{Service, ServiceFactory}
import com.twitter.finagle.service.{ Backoff, RetryPolicy => FinagleRetryPolicy }
import com.twitter.finagle.stats.{ StatsReceiver, NullStatsReceiver }
import com.twitter.finagle.thrift.{ ThriftClientRequest, ThriftClientFramedCodec }
import com.twitter.finagle.tracing.{ Tracer, NullTracer }
import com.twitter.finagle.{ ChannelException, CodecFactory, Codec, ClientCodecConfig, RequestTimeoutException, WriteException }
import com.twitter.util.Duration
import com.twitter.util.{ Duration, Future, Throw, Timer, TimerTask, Time, Try }
import com.twitter.util.{ Future, Throw, Timer, TimerTask, Time, Try }
import java.net.InetSocketAddress
import java.net.{ SocketAddress }
import java.util.concurrent.TimeUnit
import org.apache.cassandra.finagle.thrift.Cassandra.ServiceToClient
import org.apache.cassandra.finagle.thrift.{ UnavailableException, TimedOutException }
import org.apache.thrift.protocol.{ TBinaryProtocol, TProtocolFactory }

sealed case class RetryPolicy()

object RetryPolicy {
  val Idempotent = RetryPolicy()
  val NonIdempotent = RetryPolicy()
}

private[cassie] class ClusterClientProvider(val hosts: CCluster[SocketAddress],
  val keyspace: String,
  val retries: Int = 5,
  val timeout: Duration = Duration(5, TimeUnit.SECONDS),
  val requestTimeout: Duration = Duration(1, TimeUnit.SECONDS),
  val connectTimeout: Duration = Duration(1, TimeUnit.SECONDS),
  val minConnectionsPerHost: Int = 1,
  val maxConnectionsPerHost: Int = 5,
  val hostConnectionMaxWaiters: Int = 100,
  val statsReceiver: StatsReceiver = NullStatsReceiver,
  val tracerFactory: Tracer.Factory = NullTracer.factory,
  val retryPolicy: RetryPolicy = RetryPolicy.Idempotent) extends ClientProvider {

  implicit val fakeTimer = new Timer {
    def schedule(when: Time)(f: => Unit): TimerTask = throw new Exception("illegal use!")
    def schedule(when: Time, period: Duration)(f: => Unit): TimerTask = throw new Exception("illegal use!")
    def stop() { throw new Exception("illegal use!") }
  }

  /** Record the given exception, and return true. */
  private def recordRetryable(e: Exception): Boolean = {
    statsReceiver.counter(e.getClass.getSimpleName()).incr
    true
  }

  val finagleRetryPolicy: FinagleRetryPolicy[Try[Nothing]] = retryPolicy match {
    case RetryPolicy.Idempotent =>
      FinagleRetryPolicy.backoff(Backoff.const(Duration(0, TimeUnit.MILLISECONDS)) take (retries)) {
        case Throw(x: WriteException) => recordRetryable(x)
        case Throw(x: RequestTimeoutException) => recordRetryable(x)
        case Throw(x: ChannelException) => recordRetryable(x)
        case Throw(x: UnavailableException) => recordRetryable(x)
        // TODO: if this is a legit serverside timeout, then we should be careful about retrying, since the
        // serverside timeout is ideally set to just a smidgeon below our client timeout, and we would thus
        // wait a lot of extra time
        case Throw(x: TimedOutException) => recordRetryable(x)
        // TODO: do we need to retry IndividualRequestTimeoutException?
      }
    case RetryPolicy.NonIdempotent =>
      FinagleRetryPolicy.backoff(Backoff.const(Duration(0, TimeUnit.MILLISECONDS)) take (retries)) {
        case Throw(x: WriteException) => recordRetryable(x)
        case Throw(x: UnavailableException) => recordRetryable(x)
      }
  }

  private var service = ClientBuilder()
    .cluster(hosts)
    .name("cassie")
    .codec(CassandraThriftFramedCodec())
    .retryPolicy(finagleRetryPolicy)
    .timeout(timeout)
    .requestTimeout(requestTimeout)
    .connectTimeout(connectTimeout)
    .tcpConnectTimeout(connectTimeout)
    .hostConnectionCoresize(minConnectionsPerHost)
    .hostConnectionLimit(maxConnectionsPerHost)
    .reportTo(statsReceiver)
    .tracerFactory(tracerFactory)
    .hostConnectionMaxWaiters(hostConnectionMaxWaiters)
    .build()

  private val client = new ServiceToClient(service, new TBinaryProtocol.Factory())

  def map[A](f: ServiceToClient => Future[A]) = f(client)

  override def close(): Unit = {
    hosts.close
    service.release()
    ()
  }

  /**
   * Convenience methods for passing in a codec factory.
   */
  object CassandraThriftFramedCodec {
    def apply() = new CassandraThriftFramedCodecFactory
    def get() = apply()
  }

  /**
   * Create a CassandraThriftFramedCodec with a BinaryProtocol
   */
  class CassandraThriftFramedCodecFactory
    extends CodecFactory[ThriftClientRequest, Array[Byte]]#Client {
    def apply(config: ClientCodecConfig) = {
      new CassandraThriftFramedCodec(new TBinaryProtocol.Factory(), config)
    }
  }

  class CassandraThriftFramedCodec(protocolFactory: TProtocolFactory, config: ClientCodecConfig) extends ThriftClientFramedCodec(protocolFactory: TProtocolFactory, config: ClientCodecConfig) {
    override def prepareConnFactory(factory: ServiceFactory[ThriftClientRequest, Array[Byte]]) = {
      val keyspacedSetFactory = factory flatMap { service =>
        val client = new ServiceToClient(service, new TBinaryProtocol.Factory())
        client.set_keyspace(keyspace) map { _ => service }
      }
      // set up tracing
      super.prepareConnFactory(keyspacedSetFactory)
    }
  }
}
