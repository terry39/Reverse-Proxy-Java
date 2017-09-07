package cn.realshell.reverseproxy;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;

import org.apache.log4j.PropertyConfigurator;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReverseProxy {

	static {
		File file = new File(System.getProperty("user.dir") + File.separator
				+ "log4j.properties");
		if (!file.exists())
			file = new File(file.getParentFile(), file.getName());
		PropertyConfigurator.configure(file.getAbsolutePath());

		BufferedInputStream in = null;
		try {
			file = new File(System.getProperty("user.dir") + File.separator
					+ "log4j2.xml");
			if (!file.exists())
				file = new File(file.getParentFile(), file.getName());
			in = new BufferedInputStream(new FileInputStream(file));
			final ConfigurationSource source = new ConfigurationSource(in);
			Configurator.initialize(null, source);
		} catch (Exception e) {
		} finally {
			try {
				if (in != null)
					in.close();
			} catch (Exception e) {
			}
		}
	}

	private static Logger Log = LoggerFactory.getLogger(ReverseProxy.class);
	public static int verCode = 1;
	public static String verName = "0.0.1";

	private ReverseProxy() {
	}

	public static void main(String[] args) {
		if (null == args || args.length < 1 || !Config.init(args[0])) {
			showHelp();
			return;
		}

		if (Config.INSTANCE.local != null) {
			new ReverseProxyClient().start();
		} else {
			new ReverseProxyMainServer(Config.INSTANCE.port).start();
		}
	}

	private static void showHelp() {
		System.out.println(String.format("Nothing for help, ver = %s, %d",
				verName, verCode));
	}
}
