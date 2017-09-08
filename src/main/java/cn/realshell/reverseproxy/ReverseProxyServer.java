package cn.realshell.reverseproxy;

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AttributeKey;

public class ReverseProxyServer extends Thread {
	private final static Logger Log = LoggerFactory
			.getLogger(ReverseProxyServer.class);

	private final static AttributeKey<Long> ATTR_KEY_ID = ReverseProxyMainServer.ATTR_KEY_ID;

	EventLoopGroup bGroup = new NioEventLoopGroup(1);
	EventLoopGroup wGroup = new NioEventLoopGroup();
	private int port = 0;
	private Channel serverChannel = null;
	private Channel proxyChannel = null;
	private HashMap<Long, Channel> clientList = new HashMap<>();
	private long serializeId = 1;

	public ReverseProxyServer(Channel ch, int port) {
		this.port = port;
		this.proxyChannel = ch;
	}

	@Override
	public void run() {
		startListen();
	}

	private void startListen() {
		ServerBootstrap b = new ServerBootstrap().group(bGroup, wGroup)
				.channel(NioServerSocketChannel.class) //
				.childHandler(new ReverseProxyServerInitializer()) //
				.option(ChannelOption.SO_BACKLOG, 16) //
				.childOption(ChannelOption.SO_KEEPALIVE, false) //
				.childOption(ChannelOption.AUTO_READ, false) //
				.option(ChannelOption.SO_RCVBUF, 1024) //
				.option(ChannelOption.SO_SNDBUF, 1024) //
				.option(ChannelOption.TCP_NODELAY, false) //
				.option(ChannelOption.SO_REUSEADDR, true);

		if (Log.isDebugEnabled())
			b.handler(new LoggingHandler(LogLevel.DEBUG));

		ChannelFuture f = b.bind(port);
		serverChannel = f.channel();
		Log.debug("proxy listen on:" + port);
		saySuccOrFaildToProxyClient(true);
		try {
			f.sync().channel().closeFuture().sync();
		} catch (InterruptedException e) {
			saySuccOrFaildToProxyClient(false);
			Log.error("can not listen on port " + port + " ...");
			e.printStackTrace();
		} finally {
			serverChannel = null;
			bGroup.shutdownGracefully();
			wGroup.shutdownGracefully();
		}
	}

	private void saySuccOrFaildToProxyClient(boolean succ) {
		int len = Long.BYTES + Short.BYTES + Integer.BYTES;
		ByteBuf buf = Unpooled.buffer(len);
		buf.writeLong(len);
		buf.writeShort(Protocol.INIT);
		buf.writeInt(succ ? port : 0);
		proxyChannel.writeAndFlush(buf);
		if (!succ) {
			ReverseProxyMainServer.closeOnFlush(proxyChannel);
		}
	}

	public synchronized long getSerializeId() {
		return this.serializeId++;
	}

	private class ReverseProxyServerInitializer extends
			ChannelInitializer<SocketChannel> {

		@Override
		protected void initChannel(SocketChannel ch) throws Exception {
			ChannelPipeline pipeline = ch.pipeline();
			if (Log.isDebugEnabled())
				pipeline.addLast(new LoggingHandler(LogLevel.DEBUG));
			pipeline.addLast(new ReverseProxyServerHandler());
		}

	}

	private class ReverseProxyServerHandler extends
			SimpleChannelInboundHandler<ByteBuf> {

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			Channel ch = ctx.channel();
			if (proxyChannel == null) {
				ReverseProxyMainServer.closeOnFlush(ch);
				return;
			}

			long connectionId = getSerializeId();
			ch.attr(ATTR_KEY_ID).set(connectionId);
			clientList.put(connectionId, ctx.channel());

			ByteBuf buf = Unpooled.buffer();
			buf.writeLong(Long.BYTES + Short.BYTES + Long.BYTES);
			buf.writeShort(Protocol.CONNECT);// create connection
			buf.writeLong(connectionId);
			proxyChannel.writeAndFlush(buf);

			super.channelActive(ctx);
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg)
				throws Exception {
			Channel ch = ctx.channel();
			long connectionId = ch.attr(ATTR_KEY_ID).get();

			ByteBuf buf = Unpooled.buffer();

			buf.writeLong(Long.BYTES + Short.BYTES + Long.BYTES
					+ msg.readableBytes());
			buf.writeShort(Protocol.RELAY);
			buf.writeLong(connectionId);
			buf.writeBytes(msg);
			proxyChannel.writeAndFlush(buf);
			ch.read();
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			Channel ch = ctx.channel();
			long connectionId = ch.attr(ATTR_KEY_ID).get();
			clientList.remove(connectionId);

			int len = Long.BYTES + Short.BYTES + Long.BYTES;
			ByteBuf buf = Unpooled.buffer(len);
			buf.writeLong(len);
			buf.writeShort(Protocol.DISCONNECT);
			buf.writeLong(connectionId);
			proxyChannel.writeAndFlush(buf);

			super.channelInactive(ctx);
		}

	}

	public void shutdown() {
		Log.info("shutdown server on port " + port);
		try {
			if (proxyChannel != null) {
				ReverseProxyMainServer.closeOnFlush(proxyChannel);
				proxyChannel = null;
			}
			if (serverChannel != null) {
				try {
					serverChannel.close();
				} catch (Exception e) {
				}
			}
		} catch (Exception e) {
		}
	}

	public void onConnected(long connectionId) {
		Channel ch = clientList.get(connectionId);
		if (ch != null) {
			ch.read();
		}
	}

	public void writeAndFlush(long connectionId, ByteBuf msg) {
		Channel ch = null;
		ch = clientList.get(connectionId);
		if (ch != null) {
			ch.writeAndFlush(msg.copy());
			ch.read();
		}
	}

	public void onDisconnected(long connectionId) {
		Channel ch = null;
		ch = clientList.get(connectionId);
		if (ch != null) {
			clientList.remove(connectionId);
			ReverseProxyMainServer.closeOnFlush(ch);
		}
	}
}
