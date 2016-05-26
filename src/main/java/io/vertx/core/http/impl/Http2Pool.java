/*
 * Copyright (c) 2011-2013 The original author or authors
 *  ------------------------------------------------------
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.core.http.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.timeout.IdleStateHandler;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.impl.ContextImpl;
import io.vertx.core.spi.metrics.HttpClientMetrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
class Http2Pool implements ConnectionManager.Pool<Http2ClientConnection> {

  // Pools must locks on the queue object to keep a single lock
  private final ConnectionManager.ConnQueue queue;
  private Queue<Http2ClientConnection> availableConnections = new ArrayDeque<>();
  private final Set<Http2ClientConnection> allConnections = new HashSet<>();
  private final Map<Channel, ? super Http2ClientConnection> connectionMap;
  final HttpClientImpl client;
  final HttpClientMetrics metrics;
  final int maxConcurrency;
  final boolean logEnabled;

  public Http2Pool(ConnectionManager.ConnQueue queue, HttpClientImpl client, HttpClientMetrics metrics,
                   Map<Channel, ? super Http2ClientConnection> connectionMap, int maxSockets,
                   int maxConcurrency, boolean logEnabled) {
    this.queue = queue;
    this.client = client;
    this.metrics = metrics;
    this.connectionMap = connectionMap;
    this.maxConcurrency = maxConcurrency;
    this.logEnabled = logEnabled;
  }

  @Override
  public HttpVersion version() {
    return HttpVersion.HTTP_2;
  }

  @Override
  public Http2ClientConnection pollConnection() {
    Http2ClientConnection conn = availableConnections.peek();
    if (conn != null) {
      conn.streamCount++;
      if (!canReserveStream(conn)) {
        availableConnections.remove();
      }
    }
    return conn;
  }

  void createConn(ContextImpl context, Channel ch, Waiter waiter, boolean upgrade) throws Http2Exception {
    ChannelPipeline p = ch.pipeline();
    synchronized (queue) {
      VertxHttp2ConnectionHandler<Http2ClientConnection> handler = new VertxHttp2ConnectionHandlerBuilder<Http2ClientConnection>()
          .connectionMap(connectionMap)
          .server(false)
          .useCompression(client.getOptions().isTryUseCompression())
          .initialSettings(client.getOptions().getInitialSettings())
          .connectionFactory(connHandler -> new Http2ClientConnection(Http2Pool.this, queue.metric, context, ch, connHandler, metrics))
          .logEnabled(logEnabled)
          .build();
      if (upgrade) {
        handler.onHttpClientUpgrade();
      }
      Http2ClientConnection conn = handler.connection;
      metrics.endpointConnected(queue.metric, conn.metric());
      int idleTimeout = client.getOptions().getIdleTimeout();
      if (idleTimeout > 0) {
        p.addLast("idle", new IdleStateHandler(0, 0, idleTimeout));
      }
      p.addLast(handler);
      allConnections.add(conn);
      conn.streamCount++;
      waiter.handleConnection(conn); // Should make same tests than in deliverRequest
      queue.deliverStream(conn, waiter);
      checkPending(conn);
      if (canReserveStream(conn)) {
        availableConnections.add(conn);
      }
    }
  }

  private boolean canReserveStream(Http2ClientConnection handler) {
    int maxConcurrentStreams = Math.min(handler.handler.connection().local().maxActiveStreams(), maxConcurrency);
    return handler.streamCount < maxConcurrentStreams;
  }

  void checkPending(Http2ClientConnection conn) {
    synchronized (queue) {
      Waiter waiter;
      while (canReserveStream(conn) && (waiter = queue.getNextWaiter()) != null) {
        conn.streamCount++;
        queue.deliverStream(conn, waiter);
      }
    }
  }

  void discard(Http2ClientConnection conn) {
    synchronized (queue) {
      if (allConnections.remove(conn)) {
        queue.connectionClosed();
      }
    }
    metrics.endpointDisconnected(queue.metric, conn.metric());
  }

  @Override
  public void recycle(Http2ClientConnection conn) {
    synchronized (queue) {
      conn.streamCount--;
      checkPending(conn);
      if (canReserveStream(conn)) {
        availableConnections.add(conn);
      }
    }
  }

  @Override
  public HttpClientStream createStream(Http2ClientConnection conn) throws Exception {
    return conn.createStream();
  }

  @Override
  public void closeAllConnections() {
    List<Http2ClientConnection> toClose;
    synchronized (queue) {
      toClose = new ArrayList<>(allConnections);
    }
    // Close outside sync block to avoid deadlock
    toClose.forEach(Http2ConnectionBase::close);
  }
}