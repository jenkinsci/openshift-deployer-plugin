package org.jenkinsci.plugins.openshift;

import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public class Server {
	private String name;
	private String brokerAddress;
	private String username;
	private String password;
	private Secret secret;

	
	@DataBoundConstructor
	public Server(String name, String brokerAddress, String username,
			String password) {
		this.name = name;
		this.brokerAddress = brokerAddress;
		this.username = username;
		if (secret == null) secret = Secret.fromString(password);
		this.password = secret.getEncryptedValue();
	}

	public String getName() {
		return name;
	}

	public String getBrokerAddress() {
		return brokerAddress;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return Secret.toString(secret);
	}
}
