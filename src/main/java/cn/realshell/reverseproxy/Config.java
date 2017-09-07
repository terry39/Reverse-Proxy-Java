package cn.realshell.reverseproxy;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import com.google.gson.Gson;

public class Config {
	/**
	 * client config: local target "ip:port"
	 */
	public String local = null;
	/**
	 * client config: connect to main server "ip:port"
	 */
	public String server = null;

	/**
	 * server config: server listen on this port<br />
	 * client config: request server to bind port for new proxy tunnel
	 */
	public int port = 0;

	/**
	 * single instance
	 */
	public static Config INSTANCE = null;

	/**
	 * init config with config.json
	 * 
	 * @param path
	 * @return init succ for fail
	 */
	public static boolean init(String path) {
		if (path.trim().length() < 1)
			return false;

		File f = new File(path);
		if (!f.exists())
			return false;

		Gson g = new Gson();
		Config config = null;
		try {
			config = g.fromJson(readFileToString(f), Config.class);
			INSTANCE = config;
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null != config;
	}

	/**
	 * 
	 *
	 * @param path
	 * @return
	 */
	public static String readFileToString(String path) {
		return readFileToString(new File(path));
	}

	/**
	 * 
	 *
	 * @param path
	 * @return
	 */
	public static String readFileToString(File file) {
		String str = "";
		FileInputStream in = null;
		ByteArrayOutputStream out = null;
		try {
			in = new FileInputStream(file);
			byte[] buffer = new byte[1024];
			out = new ByteArrayOutputStream();

			int len = in.read(buffer);
			while (len > 0) {
				out.write(buffer, 0, len);
				len = in.read(buffer);
			}
			str = out.toString();
			out.close();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		} finally {
			close(in);
			close(out);
		}

		return str;

	}

	public static void close(Closeable f) {
		if (f != null) {
			try {
				f.close();
			} catch (Exception e) {
			}
		}
	}
}
