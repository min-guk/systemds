/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.controlprogram.federated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseCompletion.Result;
import org.apache.sysds.runtime.controlprogram.federated.FederatedPhaseCompletion.Scope;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.junit.After;
import org.junit.Test;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

public class FederatedPhaseCompletionNettyTest {
	private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

	private enum ServerMode {
		SUCCESS,
		ERROR,
		CLOSE_WITHOUT_RESPONSE
	}

	@After
	public void clearClientTransport() {
		FederatedData.clearWorkGroup();
	}

	@Test
	public void pooledConnectionTracksSuccessfulNettyResponse() throws Exception {
		Result result = executeAgainstServer(ServerMode.SUCCESS);
		assertEquals(1, result.getRegistered());
		assertEquals(1, result.getSuccessful());
		assertTrue(result.isClean());
	}

	@Test
	public void pooledConnectionTracksUnsuccessfulNettyResponse() throws Exception {
		Result result = executeAgainstServer(ServerMode.ERROR);
		assertEquals(1, result.getRegistered());
		assertEquals(1, result.getUnsuccessful());
		assertEquals(1, result.getFailures().size());
		assertFalse(result.isClean());
	}

	@Test
	public void pooledConnectionTracksChannelClose() throws Exception {
		Result result = executeAgainstServer(ServerMode.CLOSE_WITHOUT_RESPONSE);
		assertEquals(1, result.getRegistered());
		assertEquals(1, result.getExceptional());
		assertEquals(0, result.getOutstanding());
		assertFalse(result.isClean());
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void synchronousWriteFailureRemovesPendingAndCompletesTracker() throws Exception {
		InetSocketAddress address = InetSocketAddress.createUnresolved("write-failure.invalid", 18400);
		Channel channel = mock(Channel.class);
		EventLoop eventLoop = mock(EventLoop.class);
		Promise<FederatedResponse> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
		when(channel.isActive()).thenReturn(true);
		when(channel.eventLoop()).thenReturn(eventLoop);
		when(eventLoop.newPromise()).thenReturn((Promise) promise);
		doThrow(new IllegalStateException("synchronous write failure"))
			.when(channel).writeAndFlush(any());

		Class<?> connectionClass = Class.forName(FederatedData.class.getName() + "$PooledConnection");
		Constructor<?> constructor = connectionClass.getDeclaredConstructor(ImmutablePair.class,
			InetSocketAddress.class);
		constructor.setAccessible(true);
		Object connection = constructor.newInstance(ImmutablePair.of(address, 0L), address);
		Field channelField = connectionClass.getDeclaredField("_channel");
		channelField.setAccessible(true);
		channelField.set(connection, channel);
		Method send = connectionClass.getDeclaredMethod("send", FederatedRequest[].class);
		send.setAccessible(true);

		try(Scope scope = FederatedPhaseCompletion.begin("sync-write-failure")) {
			try {
				send.invoke(connection, (Object) new FederatedRequest[] {request(91)});
				fail("Expected the synchronous channel write failure");
			}
			catch(InvocationTargetException ex) {
				assertTrue(ex.getCause() instanceof IllegalStateException);
			}
			scope.sealAfterProducersComplete(CompletableFuture.completedFuture(null));
			Result result = scope.await(DRAIN_TIMEOUT);
			assertEquals(1, result.getExceptional());
			assertEquals(0, result.getOutstanding());

			Field pendingField = connectionClass.getDeclaredField("_pending");
			pendingField.setAccessible(true);
			assertTrue(((Queue<?>) pendingField.get(connection)).isEmpty());
			scope.abort();
		}
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void postAwaitDispatchIsRejectedBeforePooledChannelWrite() throws Exception {
		InetSocketAddress address = InetSocketAddress.createUnresolved("late-write.invalid", 18401);
		Channel channel = mock(Channel.class);
		EventLoop eventLoop = mock(EventLoop.class);
		Promise<FederatedResponse> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
		when(channel.isActive()).thenReturn(true);
		when(channel.eventLoop()).thenReturn(eventLoop);
		when(eventLoop.newPromise()).thenReturn((Promise) promise);
		Object connection = pooledConnection(address, channel);
		Method send = connection.getClass().getDeclaredMethod("send", FederatedRequest[].class);
		send.setAccessible(true);

		try(Scope scope = FederatedPhaseCompletion.begin("late-pooled-write")) {
			scope.seal();
			assertFalse(scope.await(DRAIN_TIMEOUT).isClean());
			try {
				send.invoke(connection, (Object) new FederatedRequest[] {request(92)});
				fail("Expected sealed phase to reject the pooled dispatch");
			}
			catch(InvocationTargetException ex) {
				assertTrue(ex.getCause() instanceof FederatedPhaseCompletion.LateDispatchException);
			}
			verify(channel, never()).writeAndFlush(any());
			assertFalse(promise.isDone());
			scope.attestProducerQuiescence(CompletableFuture.completedFuture(null));
			assertEquals(1, scope.await(DRAIN_TIMEOUT).getRejectedDispatches());
			scope.abort();
		}
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void terminalResultIsInvalidatedByLatePooledDispatch() throws Exception {
		InetSocketAddress address = InetSocketAddress.createUnresolved("terminal-late.invalid", 18402);
		Channel channel = mock(Channel.class);
		EventLoop eventLoop = mock(EventLoop.class);
		Promise<FederatedResponse> promise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
		when(channel.isActive()).thenReturn(true);
		when(channel.eventLoop()).thenReturn(eventLoop);
		when(eventLoop.newPromise()).thenReturn((Promise) promise);
		Object connection = pooledConnection(address, channel);
		Method send = connection.getClass().getDeclaredMethod("send", FederatedRequest[].class);
		send.setAccessible(true);

		Result terminal;
		Scope finishedScope = FederatedPhaseCompletion.begin("terminal-live-verdict");
		try(finishedScope) {
			finishedScope.sealAfterProducersComplete(CompletableFuture.completedFuture(null));
			finishedScope.await(DRAIN_TIMEOUT);
			terminal = finishedScope.finish();
			assertTrue(terminal.isClean());
		}
		try {
			send.invoke(connection, (Object) new FederatedRequest[] {request(93)});
			fail("Expected terminal admission gate to reject detached late dispatch");
		}
		catch(InvocationTargetException ex) {
			assertTrue(ex.getCause() instanceof FederatedPhaseCompletion.LateDispatchException);
		}
		verify(channel, never()).writeAndFlush(any());
		assertFalse(terminal.isClean());
		assertEquals(1, terminal.getLateDispatches());
		assertEquals(1, terminal.getRejectedDispatches());
		assertEquals(FederatedPhaseCompletion.FailureKind.LATE_DISPATCH_REJECTED,
			terminal.getFailures().get(0).getKind());
		assertTrue(terminal.isProducerQuiescenceAttested());
		assertTrue(terminal.isSealed());
		assertTrue(terminal.getOutstanding() == 0);
		assertTrue(terminal.getRegistered() == 0);
		assertTrue(terminal.getCompleted() == 0);
		assertTrue(terminal.getUnsuccessful() == 0);
		assertTrue(terminal.getExceptional() == 0);
		assertTrue(terminal.getSuccessful() == 0);
		assertTrue(terminal.getProducerEvidenceCount() == 1);
		assertFalse(terminal.hadDrainTimeout());
		assertFalse(terminal.isAborted());
		assertTrue(terminal.getEndpoints().isEmpty());
		assertTrue(terminal.getTids().isEmpty());
		finishedScope.abort();
		assertTrue(terminal.isAborted());

		try(Scope cleanup = FederatedPhaseCompletion.begin("terminal-live-verdict-cleanup")) {
			cleanup.sealAfterProducersComplete(CompletableFuture.completedFuture(null));
			cleanup.await(DRAIN_TIMEOUT);
			cleanup.finish();
		}
	}

	private static Result executeAgainstServer(ServerMode mode) throws Exception {
		try(TestServer server = new TestServer(mode);
			Scope scope = FederatedPhaseCompletion.begin("netty-" + mode.name())) {
			Future<FederatedResponse> response = FederatedData.executeFederatedOperation(server.address(), request(77));
			scope.sealAfterProducersComplete(CompletableFuture.completedFuture(null));
			if(mode == ServerMode.CLOSE_WITHOUT_RESPONSE) {
				try {
					response.get(5, TimeUnit.SECONDS);
					fail("Expected channel close to fail the response future");
				}
				catch(ExecutionException expected) {
					// expected
				}
			}
			else {
				FederatedResponse value = response.get(5, TimeUnit.SECONDS);
				assertEquals(mode == ServerMode.SUCCESS, value.isSuccessful());
			}
			scope.await(DRAIN_TIMEOUT);
			return mode == ServerMode.SUCCESS ? scope.finish() : scope.abort();
		}
	}

	private static FederatedRequest request(long tid) {
		FederatedRequest request = new FederatedRequest(RequestType.NOOP, 1);
		request.setTID(tid);
		return request;
	}

	private static Object pooledConnection(InetSocketAddress address, Channel channel) throws Exception {
		Class<?> connectionClass = Class.forName(FederatedData.class.getName() + "$PooledConnection");
		Constructor<?> constructor = connectionClass.getDeclaredConstructor(ImmutablePair.class,
			InetSocketAddress.class);
		constructor.setAccessible(true);
		Object connection = constructor.newInstance(ImmutablePair.of(address, 0L), address);
		Field channelField = connectionClass.getDeclaredField("_channel");
		channelField.setAccessible(true);
		channelField.set(connection, channel);
		return connection;
	}

	private static final class TestServer implements AutoCloseable {
		private final EventLoopGroup _boss = new NioEventLoopGroup(1);
		private final EventLoopGroup _workers = new NioEventLoopGroup(1);
		private final Channel _channel;

		private TestServer(ServerMode mode) throws InterruptedException {
			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap.group(_boss, _workers).channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel channel) {
						channel.pipeline().addLast(new ObjectDecoder(Integer.MAX_VALUE,
							ClassResolvers.weakCachingResolver(ClassLoader.getSystemClassLoader())));
						channel.pipeline().addLast(new ObjectEncoder());
						channel.pipeline().addLast(new SimpleChannelInboundHandler<Object>() {
							@Override
							protected void channelRead0(ChannelHandlerContext context, Object message) {
								if(mode == ServerMode.CLOSE_WITHOUT_RESPONSE) {
									context.close();
									return;
								}
								FederatedResponse.ResponseType type = mode == ServerMode.SUCCESS
									? FederatedResponse.ResponseType.SUCCESS_EMPTY
									: FederatedResponse.ResponseType.ERROR;
								context.writeAndFlush(mode == ServerMode.SUCCESS
									? new FederatedResponse(type)
									: new FederatedResponse(type, "test error"));
							}
						});
					}
				});
			_channel = bootstrap.bind("127.0.0.1", 0).sync().channel();
		}

		private InetSocketAddress address() {
			return (InetSocketAddress) _channel.localAddress();
		}

		@Override
		public void close() throws InterruptedException {
			_channel.close().sync();
			_workers.shutdownGracefully().sync();
			_boss.shutdownGracefully().sync();
		}
	}
}
