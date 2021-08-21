package org.jenkinsci.plugins.openshift.util;

import java.io.OutputStream;

import org.apache.commons.io.output.NullOutputStream;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public interface Logger {
	void info(String msg);

	void error(String msg);
	
	OutputStream getOutputStream();
	
	static Logger NOOP = new Logger() {
		public void info(String msg) {
			// NOOP
		}

		public void error(String msg) {
			// NOOP
		}

		public OutputStream getOutputStream() {
			return new NullOutputStream();
		}
	};
}
