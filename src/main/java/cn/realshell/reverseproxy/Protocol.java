package cn.realshell.reverseproxy;

public class Protocol {

	/**
	 * client to server: <br />
	 * request {port} for create reverse proxy tunnel<br />
	 * {LEN|TYPE|VER|PORT|PASSWORD}
	 * 
	 * server to client:<br />
	 * result of request<br />
	 * {LEN|TYPE|PORT}<br />
	 * if "PORT == 0", INIT is faild<br />
	 */
	final static int INIT = 0;

	/**
	 * client to server and server to client <br />
	 * keep ping to keep alive
	 */
	final static int PING = 1;

	/**
	 * server to client: <br />
	 * create new tunnel <br />
	 * 
	 * client to server:<br />
	 * result of create new tunnel.
	 */
	final static int CONNECT = 2;

	/**
	 * relay data between client and server
	 */
	final static int RELAY = 3;

	/**
	 * tunnel broken
	 */
	final static int DISCONNECT = 4;

	/**
	 * server to client:<br />
	 * when error, say something to client.
	 */
	final static int ERROR = 5;

}
