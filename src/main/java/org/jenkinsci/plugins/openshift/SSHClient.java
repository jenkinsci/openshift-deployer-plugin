package org.jenkinsci.plugins.openshift;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.io.output.CloseShieldOutputStream;
import org.jenkinsci.plugins.openshift.util.Logger;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.openshift.client.IApplication;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public class SSHClient {
	private static final String BINARY_DEPLOY_CMD = "oo-binary-deploy";
	
	private Logger log = Logger.NOOP;
	
	private IApplication app;
	
	private String sshPrivateKey;
	
	public SSHClient(IApplication app) {
		super();
		this.app = app;
	}
	
	public void setLogger(Logger log) {
		this.log = log;
	}
	
	public void setSSHPrivateKey(String sshPrivateKey) {
		this.sshPrivateKey = sshPrivateKey;
	}

	public void deploy(File deployment) throws IOException {
		try {
			log.info("Deployging " + deployment.getAbsolutePath());
			log.info("Starting SSH connection to " + app.getSshUrl());
			URI uri = new URI(app.getSshUrl());

			JSch jsch = new JSch();

			// confgure logger
			JSch.setLogger(new com.jcraft.jsch.Logger() {
				private final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(JSch.class.getName());
				
				public void log(int level, String message) {
					LOG.fine(message);
					
					if (isEnabled(level)) {
						try {
							log.info(message);
						} catch (Exception e) {}
					}
				}

				public boolean isEnabled(int level) {
					return level >= WARN;
				}
			});

			// add ssh keys
			jsch.addIdentity(sshPrivateKey);
			log.info("Using SSH private key " + sshPrivateKey);

			Session session = jsch.getSession(uri.getUserInfo(), uri.getHost());
			session.setConfig("StrictHostKeyChecking", "no");
			session.connect(10000);

			FileInputStream in = new FileInputStream(deployment);
			Channel channel = session.openChannel("exec");
			((ChannelExec) channel).setErrStream(new CloseShieldOutputStream(log.getOutputStream()));
			((ChannelExec) channel).setOutputStream(new CloseShieldOutputStream(log.getOutputStream()));
			((ChannelExec) channel).setInputStream(in);
			((ChannelExec) channel).setCommand(BINARY_DEPLOY_CMD);

			channel.connect();
			try {
				while (!channel.isEOF()) {
				}

				in.close();
				channel.disconnect();
				session.disconnect();
			} catch (Throwable t) {
				t.printStackTrace();
			}
			
		} catch (JSchException e) {
			throw new IOException("Failed to deploy the binary. " + e.getMessage(), e);
		} catch (URISyntaxException e) {
			throw new IOException(e.getMessage(), e);
		}
	}
}
