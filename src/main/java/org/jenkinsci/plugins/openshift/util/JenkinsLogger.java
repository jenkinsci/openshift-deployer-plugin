package org.jenkinsci.plugins.openshift.util;

import java.io.OutputStream;

import hudson.model.BuildListener;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public class JenkinsLogger implements Logger {
	private static final String DEFAULT_PREFIX = "OPENSHIFT";

	private BuildListener listener;
	
	private String prefix;

	public JenkinsLogger(BuildListener listener) {
		this(listener, DEFAULT_PREFIX);
	}
	
	public JenkinsLogger(BuildListener listener, String prefix) {
		super();
		this.listener = listener;
		this.prefix = prefix;
	}

	public void info(String msg) {
		listener.getLogger().println(wrap(msg));
	}

	public void error(String msg) {
		listener.error(wrap(msg));
	}
	
	private String wrap(String msg) {
		return "[" + prefix + "] " + msg; 
	}

	public OutputStream getOutputStream() {
		return listener.getLogger();
	}
}
