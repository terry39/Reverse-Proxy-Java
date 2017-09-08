package cn.realshell.reverseproxy;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
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
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;

public class ReverseProxyClient extends Thread {
	private final static Logger Log = LoggerFactory
			.getLogger(ReverseProxyClient.class);

	private final static AttributeKey<Long> ATTR_LAST_PING = ReverseProxyMainServer.ATTR_LAST_PING;
	private final static AttributeKey<Long> ATTR_KEY_ID = ReverseProxyMainServer.ATTR_KEY_ID;

	Bootstrap b = null;
	Channel proxyChannel = null;
	EventLoopGroup g = new NioEventLoopGroup();
	HashMap<Long, Channel> clientList = new HashMap<>();

	String host = null;
	int port = 0;

	public ReverseProxyClient() {
		b = new Bootstrap().group(g).channel(NioSocketChannel.class)
				.handler(new ChannelMainInit())
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000 * 3)
				.option(ChannelOption.SO_KEEPALIVE, true)
				.option(ChannelOption.AUTO_READ, true)
				.option(ChannelOption.SO_RCVBUF, 1024) //
				.option(ChannelOption.SO_SNDBUF, 1024) //
				.option(ChannelOption.TCP_NODELAY, true) //
				.option(ChannelOption.SO_REUSEADDR, true) //
				.option(ChannelOption.SO_KEEPALIVE, true);

		// init local server ip
		String[] ss = Config.INSTANCE.local.split(":");
		host = ss[0];
		port = Integer.valueOf(ss[1]);

	}

	@Override
	public void run() {
		connectToMainServer();
	}

	private void connectToMainServer() {
		String[] ss = Config.INSTANCE.server.split(":");
		String _host = ss[0];
		int _port = Integer.valueOf(ss[1]);

		b.connect(_host, _port).addListener(new ChannelFutureListener() {

			@Override
			public void operationComplete(ChannelFuture f) throws Exception {
				if (!f.isSuccess()) {
					Log.warn("reconnect to server");
					f.channel()
							.eventLoop()
							.schedule(() -> connectToMainServer(), 1,
									TimeUnit.SECONDS);
				} else {
					proxyChannel = f.channel();
					Log.info("connected to main server");
				}
			}
		});
	}

	private void connectToLocalServer(long connectionId) {
		Bootstrap bs = new Bootstrap().group(g).channel(NioSocketChannel.class)
				.handler(new ChannelInit())
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000 * 3)
				.option(ChannelOption.SO_KEEPALIVE, true)
				.option(ChannelOption.AUTO_READ, true)
				.option(ChannelOption.SO_RCVBUF, 1024) //
				.option(ChannelOption.SO_SNDBUF, 1024) //
				.option(ChannelOption.TCP_NODELAY, true) //
				.option(ChannelOption.SO_REUSEADDR, true) //
				.option(ChannelOption.SO_KEEPALIVE, true);
		bs.connect(host, port).addListener(new ChannelFutureListener() {

			@Override
			public void operationComplete(ChannelFuture f) throws Exception {
				int len = Long.BYTES + Short.BYTES + Long.BYTES;
				ByteBuf buf = Unpooled.buffer(len);
				buf.writeLong(len);
				if (f.isSuccess()) {
					buf.writeShort(Protocol.CONNECT); // type = connection succ
					buf.writeLong(connectionId);
					proxyChannel.writeAndFlush(buf);

					Channel ch = f.channel();
					ch.attr(ATTR_KEY_ID).set(connectionId);
					clientList.put(connectionId, ch);
				} else {
					buf.writeShort(Protocol.DISCONNECT); // type = disconnection
					buf.writeLong(connectionId);
					proxyChannel.writeAndFlush(buf);
				}
			}
		});
	}

	private Thread pingThread = null;

	private void startPingThread() {
		pingThread = new Thread(
				() -> {
					while (!this.isInterrupted()) {
						try {
							Thread.sleep(Config.INSTANCE.ping_interval * 1000);
						} catch (Exception e) {
							break;
						}

						if (proxyChannel != null) {
							long lastPing = proxyChannel
									.hasAttr(ATTR_LAST_PING) ? proxyChannel
									.attr(ATTR_LAST_PING).get() : 0L;
							long NOW = System.currentTimeMillis();

							if (lastPing != 0L
									&& NOW - lastPing > Config.INSTANCE.ping_timeout * 1000) {
								ReverseProxyMainServer
										.closeOnFlush(proxyChannel);
								break;
							}

							int len = Long.BYTES + Short.BYTES + Long.BYTES;
							ByteBuf buf = Unpooled.buffer();
							buf.writeLong(len);
							buf.writeShort(Protocol.PING);
							buf.writeLong(System.currentTimeMillis());

							proxyChannel.writeAndFlush(buf);
						}
					}
				});
		pingThread.start();
	}

	private class ChannelMainInit extends ChannelInitializer<Channel> {

		@Override
		protected void initChannel(Channel ch) throws Exception {
			ChannelPipeline pipline = ch.pipeline();
			if (Log.isDebugEnabled())
				pipline.addLast(new LoggingHandler(LogLevel.DEBUG));
			pipline.addLast(new LengthFieldBasedFrameDecoder(65535, 0, 8, -8, 0));
			pipline.addLast(new ReverseProxyMainClientHandler());
		}

	}

	private class ReverseProxyMainClientHandler extends
			SimpleChannelInboundHandler<ByteBuf> {

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			// ask for open proxy server on port
			byte[] password = Config.INSTANCE.password.getBytes();
			int len = Long.BYTES + Short.BYTES + Integer.BYTES + Integer.BYTES
					+ password.length;
			ByteBuf buf = Unpooled.buffer(len);
			buf.writeLong(len);
			buf.writeShort(Protocol.INIT);
			buf.writeInt(ReverseProxy.verCode);
			buf.writeInt(Config.INSTANCE.port);
			buf.writeBytes(password);
			proxyChannel.writeAndFlush(buf);

			super.channelActive(ctx);
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {

			localChannelList.close();

			if (pingThread != null && !pingThread.isInterrupted()) {
				pingThread.interrupt();
				pingThread = null;
			}

			// reconnect
			ctx.channel().eventLoop()
					.schedule(() -> connectToMainServer(), 1, TimeUnit.SECONDS);

			super.channelInactive(ctx);
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg)
				throws Exception {
			Channel ch = ctx.channel();
			long len = msg.readLong();
			short type = msg.readShort();
			long connectionId = 0L;
			Channel _ch = null;

			switch (type) {
			case 0:
				if (msg.readableBytes() >= Integer.BYTES) {
					boolean succ = msg.readInt() > 0;
					if (!succ) {
						Log.error("can not open port on server");
						ReverseProxyMainServer.closeOnFlush(_ch);
					} else {
						startPingThread();
					}
				} else
					ReverseProxyMainServer.closeOnFlush(ch);

				break;

			case 1:
				if (Log.isDebugEnabled() && msg.readableBytes() >= Long.BYTES) {
					long time = msg.readLong();
					long NOW = System.currentTimeMillis();
					Log.debug("delay: " + (NOW - time) + " ms");
				}
				ch.attr(ATTR_LAST_PING).set(System.currentTimeMillis());
				break;
			case 2:
				if (msg.readableBytes() >= Long.BYTES) {
					connectionId = msg.readLong();
					connectToLocalServer(connectionId);
				} else
					ReverseProxyMainServer.closeOnFlush(ch);
				break;
			case 3:
				if (msg.readableBytes() >= Long.BYTES) {
					connectionId = msg.readLong();
					_ch = clientList.get(connectionId);
					if (_ch != null) {
						_ch.writeAndFlush(msg.copy());
					}
				} else
					ReverseProxyMainServer.closeOnFlush(ch);
				break;

			case 4:
				if (msg.readableBytes() >= Long.BYTES) {
					connectionId = msg.readLong();
					_ch = clientList.get(connectionId);
					if (_ch != null) {
						clientList.remove(connectionId);
						ReverseProxyMainServer.closeOnFlush(_ch);
					}
				} else
					ReverseProxyMainServer.closeOnFlush(ch);
				break;

			case 5:
				onError(msg.toString(Charset.forName("UTF-8")));
				break;

			}

		}

		private void onError(String str) {
			Log.error(str);
			System.exit(0);
		}

	}

	private class ChannelInit extends ChannelInitializer<Channel> {

		@Override
		protected void initChannel(Channel ch) throws Exception {
			ChannelPipeline pipline = ch.pipeline();
			if (Log.isDebugEnabled())
				pipline.addLast(new LoggingHandler(LogLevel.DEBUG));
			pipline.addLast(new ReverseProxyClientHandler());
		}
	}

	public final static DefaultChannelGroup localChannelList = new DefaultChannelGroup(
			GlobalEventExecutor.INSTANCE);

	private class ReverseProxyClientHandler extends
			SimpleChannelInboundHandler<ByteBuf> {

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			localChannelList.add(ctx.channel());
			super.channelActive(ctx);
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			Channel ch = ctx.channel();
			localChannelList.remove(ch);
			long connectionId = ch.attr(ATTR_KEY_ID).get();
			if (clientList.containsKey(connectionId)) {
				clientList.remove(connectionId);
				int len = Long.BYTES + Short.BYTES + Long.BYTES;
				ByteBuf buf = Unpooled.buffer(len);
				buf.writeLong(len);
				buf.writeShort(Protocol.DISCONNECT);
				buf.writeLong(connectionId);
				proxyChannel.writeAndFlush(buf);

			}
			super.channelInactive(ctx);
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg)
				throws Exception {
			Channel ch = ctx.channel();
			long connectionId = ch.attr(ATTR_KEY_ID).get();

			int len = Long.BYTES + Short.BYTES + Long.BYTES
					+ msg.readableBytes();

			ByteBuf buf = Unpooled.buffer(len);
			buf.writeLong(len);
			buf.writeShort(Protocol.RELAY);
			buf.writeLong(connectionId);
			buf.writeBytes(msg);
			proxyChannel.writeAndFlush(buf);

			ch.read();
		}

	}

}
