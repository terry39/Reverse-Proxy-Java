package cn.realshell.reverseproxy;

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
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AttributeKey;

public class ReverseProxyMainServer extends Thread {
	public final static Logger Log = LoggerFactory
			.getLogger(ReverseProxyMainServer.class);
	public final static AttributeKey<Long> ATTR_KEY_ID = AttributeKey
			.valueOf("ATTR_KEY_ID");
	public final static AttributeKey<Boolean> ATTR_KEY_CLIENT_CONNECTED = AttributeKey
			.valueOf("ATTR_KEY_CLIENT_CONNECTED");

	EventLoopGroup bGroup = new NioEventLoopGroup(1);
	EventLoopGroup wGroup = new NioEventLoopGroup();
	HashMap<Long, ReverseProxyServer> serverList = new HashMap<>();

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
		try {
			f.sync().channel().closeFuture().sync();
		} catch (InterruptedException e) {
			Log.error("can not listen on port " + port + " ...");
			e.printStackTrace();
		} finally {
			serverChannel = null;
			bGroup.shutdownGracefully();
			wGroup.shutdownGracefully();
		}
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
				startProxyPort(ch, msg.readInt());
				break;

			case Protocol.PING:
				// TODO heart beat
				break;

			case Protocol.CONNECT:
				onConnected(ch, msg);
				break;

			case Protocol.RELAY:
				onRelay(ch, msg);
				break;

			case Protocol.DISCONNECT:
				onDisconnected(ch, msg);
				break;
			}
		}

		private void onConnected(Channel ch, ByteBuf msg) {
			long connectionId = msg.readLong();
			long clientId = ch.attr(ATTR_KEY_ID).get();
			serverList.get(clientId).onConnected(connectionId);
		}

		private void onDisconnected(Channel ch, ByteBuf msg) {
			long clientId = ch.attr(ATTR_KEY_ID).get();
			long connectionId = msg.readLong();
			serverList.get(clientId).onDisconnected(connectionId);
		}

		private void onRelay(Channel ch, ByteBuf msg) {
			long clientId = ch.attr(ATTR_KEY_ID).get();
			long connectionId = msg.readLong();
			serverList.get(clientId).writeAndFlush(connectionId, msg);
		}

		private void startProxyPort(Channel ch, int port) {
			long clientId = (long) port;
			synchronized (serverList) {

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
			Log.debug("[Main]channelInactive");
			Channel ch = ctx.channel();
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
