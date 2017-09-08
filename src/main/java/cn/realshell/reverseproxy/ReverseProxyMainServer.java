package cn.realshell.reverseproxy;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelMatcher;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;

public class ReverseProxyMainServer extends Thread {
	public final static Logger Log = LoggerFactory
			.getLogger(ReverseProxyMainServer.class);
	public final static AttributeKey<Long> ATTR_KEY_ID = AttributeKey
			.valueOf("ATTR_KEY_ID");
	public final static AttributeKey<Long> ATTR_LAST_PING = AttributeKey
			.valueOf("ATTR_LAST_PING");
	public final static AttributeKey<Integer> ATTR_VER = AttributeKey
			.valueOf("ATTR_VER");
	public final static AttributeKey<Boolean> ATTR_KEY_CLIENT_CONNECTED = AttributeKey
			.valueOf("ATTR_KEY_CLIENT_CONNECTED");

	EventLoopGroup bGroup = new NioEventLoopGroup(1);
	EventLoopGroup wGroup = new NioEventLoopGroup();
	HashMap<Long, ReverseProxyServer> serverList = new HashMap<>();
	public final static DefaultChannelGroup clientChannelList = new DefaultChannelGroup(
			GlobalEventExecutor.INSTANCE);

	private int port = 0;
	private Channel serverChannel = null;

	public ReverseProxyMainServer(int port) {
		this.port = port;
	}

	@Override
	public void run() {
		startListen();
	}

	private void startListen() {
		ServerBootstrap b = new ServerBootstrap().group(bGroup, wGroup)
				.channel(NioServerSocketChannel.class)
				.option(ChannelOption.SO_BACKLOG, 16)
				.childOption(ChannelOption.SO_KEEPALIVE, false)
				.childOption(ChannelOption.AUTO_READ, true)
				.option(ChannelOption.SO_RCVBUF, 1024)
				.option(ChannelOption.SO_SNDBUF, 1024)
				.option(ChannelOption.TCP_NODELAY, false)
				.option(ChannelOption.SO_REUSEADDR, true)
				.childHandler(new ReverseProxyMainServerInitializer());

		if (Log.isDebugEnabled())
			b.handler(new LoggingHandler(LogLevel.DEBUG));

		ChannelFuture f = b.bind(port);
		serverChannel = f.channel();
		Log.debug("listen on:" + port);
		startPingThread();
		try {
			f.sync().channel().closeFuture().sync();
		} catch (InterruptedException e) {
			Log.error("can not listen on port " + port + " ...");
			e.printStackTrace();
		} finally {
			clientChannelList.close();
			serverChannel = null;
			bGroup.shutdownGracefully();
			wGroup.shutdownGracefully();
		}
	}

	private void startPingThread() {
		ChannelMatcher closeMatcher = new ChannelMatcher() {

			@Override
			public boolean matches(Channel ch) {
				long lastPing = ch.hasAttr(ATTR_LAST_PING) ? ch.attr(
						ATTR_LAST_PING).get() : 0L;
				long NOW = System.currentTimeMillis();

				return lastPing != 0L
						&& (NOW - lastPing) > Config.INSTANCE.ping_timeout * 1000;
			}
		};

		new Thread(() -> {
			while (serverChannel != null) {
				try {
					Thread.sleep(1000);
				} catch (Exception e) {
				}
				if (clientChannelList.size() < 1)
					continue;

				clientChannelList.close(closeMatcher);
			}
		}).start();
	}

	public static void closeOnFlush(Channel ch) {
		if (ch != null && ch.isActive()) {
			ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(
					ChannelFutureListener.CLOSE);
		}
	}

	private class ReverseProxyMainServerInitializer extends
			ChannelInitializer<SocketChannel> {

		@Override
		protected void initChannel(SocketChannel ch) throws Exception {
			ChannelPipeline pipeline = ch.pipeline();
			if (Log.isDebugEnabled())
				pipeline.addLast(new LoggingHandler(LogLevel.DEBUG));
			pipeline.addLast(new LengthFieldBasedFrameDecoder(65535, 0, 8, -8,
					0));
			pipeline.addLast(new ReverseProxyMainServerHandler());
		}

	}

	private class ReverseProxyMainServerHandler extends
			SimpleChannelInboundHandler<ByteBuf> {
		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			Channel ch = ctx.channel();
			ch.attr(ATTR_LAST_PING).set(System.currentTimeMillis());
			clientChannelList.add(ch);
			super.channelActive(ctx);
		}

		/**
		 * protocol: {length:long}{type:short}{value:byte[]} <br />
		 * type=0: reverse proxy client ask for open port on server <br />
		 * type=1: heart beat, keep alive <br />
		 * type=2: create/succ tunnel with id(Long) <br />
		 * type=3: {id:long}{byte[]} <br />
		 * type=4: disconnect connection with id(long) <br />
		 * type=5: on error<br />
		 */
		@Override
		protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg)
				throws Exception {

			Channel ch = ctx.channel();

			long len = msg.readLong();
			short type = msg.readShort();

			switch (type) {
			case Protocol.INIT:
				if (msg.readableBytes() > Integer.BYTES * 2) {
					createTunnel(ch, msg.readInt(), msg.readInt(),
							msg.toString(Charset.forName("UTF-8")));
				} else
					closeOnFlush(ch);
				break;

			case Protocol.PING:
				if (msg.readableBytes() >= Long.BYTES) {
					onPing(ch, msg.readLong());
				} else
					closeOnFlush(ch);
				break;

			case Protocol.CONNECT:
				if (msg.readableBytes() >= Long.BYTES) {
					onConnected(ch, msg.readLong());
				} else
					closeOnFlush(ch);
				break;

			case Protocol.RELAY:
				if (msg.readableBytes() >= Long.BYTES) {
					onRelay(ch, msg.readLong(), msg);
				} else
					closeOnFlush(ch);
				break;

			case Protocol.DISCONNECT:
				if (msg.readableBytes() >= Long.BYTES) {
					onDisconnected(ch, msg.readLong());
				} else
					closeOnFlush(ch);
				break;

			default:
				closeOnFlush(ch);
				break;
			}
		}

		private void onPing(Channel ch, long time) {
			ch.attr(ATTR_LAST_PING).set(System.currentTimeMillis());

			int len = Long.BYTES + Short.BYTES + Long.BYTES;
			ByteBuf buf = Unpooled.buffer();
			buf.writeLong(len);
			buf.writeShort(Protocol.PING);
			buf.writeLong(time);

			ch.writeAndFlush(buf);
		}

		private void onConnected(Channel ch, long connectionId) {
			long clientId = ch.attr(ATTR_KEY_ID).get();
			serverList.get(clientId).onConnected(connectionId);
		}

		private void onDisconnected(Channel ch, long connectionId) {
			long clientId = ch.attr(ATTR_KEY_ID).get();
			serverList.get(clientId).onDisconnected(connectionId);
		}

		private void onRelay(Channel ch, long connectionId, ByteBuf msg) {
			long clientId = ch.attr(ATTR_KEY_ID).get();
			serverList.get(clientId).writeAndFlush(connectionId, msg);
		}

		private void createTunnel(Channel ch, int ver, int port, String password) {
			if (!Config.INSTANCE.password.equals(password)) {
				byte[] message = String.format("Password \"%s\" is not match",
						password).getBytes();
				int len = Long.BYTES + Short.BYTES + message.length;
				ByteBuf buf = Unpooled.buffer(len);
				buf.writeLong(len);
				buf.writeShort(Protocol.ERROR);
				buf.writeBytes(message);
				ch.writeAndFlush(buf);

				closeOnFlush(ch);

				return;
			}
			ch.attr(ATTR_VER).set(ver);
			long clientId = (long) port;

			if (serverList.containsKey(clientId)) {
				sendError(ch, "proxy server port exists.");
				closeOnFlush(ch);
				return;
			}

			ch.attr(ATTR_KEY_ID).set(clientId);

			ReverseProxyServer server = new ReverseProxyServer(ch, port);
			serverList.put(clientId, server);
			server.start();
		}

		private void sendError(Channel ch, String str) {
			byte[] bytes = str.getBytes();
			int len = Long.BYTES + Short.BYTES + bytes.length;
			ByteBuf buf = Unpooled.buffer();
			buf.writeLong(len);
			buf.writeShort(5); // error type
			buf.writeBytes(bytes);
			ch.writeAndFlush(buf);
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			Channel ch = ctx.channel();
			clientChannelList.remove(ch);
			long clientId = ch.hasAttr(ATTR_KEY_ID) ? ch.attr(ATTR_KEY_ID)
					.get() : 0L;
			if (clientId != 0L) {
				ReverseProxyServer server = serverList.get(clientId);
				serverList.remove(clientId);
				server.shutdown();
			}

		}

	}

}
