package org.jenkinsci.plugins.openshift;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public class OpenShiftServer {
	private String name;
	private String brokerAddress;
	private String username;
	private String password;

	
	@DataBoundConstructor
	public OpenShiftServer(String name, String brokerAddress, String username,
			String password) {
		this.name = name;
		this.brokerAddress = brokerAddress;
		this.username = username;
		this.password = password;
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
		return password;
	}

}
