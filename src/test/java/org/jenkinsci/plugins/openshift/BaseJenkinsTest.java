package org.jenkinsci.plugins.openshift;

import hudson.model.Hudson;
import hudson.util.ReflectionUtils;

import java.io.File;
import java.lang.reflect.Field;

import org.jenkinsci.plugins.openshift.DeployApplication.DeployApplicationDescriptor;
import org.jenkinsci.plugins.openshift.OpenShiftV2Client.DeploymentType;
import org.junit.Before;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;

public abstract class BaseJenkinsTest {
	protected static final String SSH_PUBLIC_KEY = "keys/id_rsa.pub";
	protected static final String SSH_PRIVATE_KEY = "keys/id_rsa";
	protected static final String SMALL_GEAR = "small";
	protected static final String EAP_CARTRIDGE = "jbosseap-6";
	protected static final String APP_NAME = "testapp";
	protected static final String SERVER_NAME = "openshift";
	protected static final String SUCCESS_LOG = "Application deployed to http://testapp";
	
	protected OpenShiftV2Client client;
	
	@Rule 
	public JenkinsRule jenkins = new JenkinsRule();
	
	@Before
	public void setup() throws Exception {
		@SuppressWarnings("rawtypes")
		DeployApplicationDescriptor descriptor = (DeployApplicationDescriptor)Hudson.getInstance().getDescriptor(DeployApplication.class);
		
		// openshift credentials
		String brokerAddress = TestUtils.getProp("openshift.brokerAddress");
		String username = TestUtils.getProp("openshift.username");
		String password = TestUtils.getProp("openshift.password");
		
		// set openshift server
		Server server = new Server(SERVER_NAME, brokerAddress, username, password);
		descriptor.getServers().add(server);

		// set ssh keys
		String privateKey = ClassLoader.getSystemResource(SSH_PRIVATE_KEY).getFile();
		Field sshPrivateKeyField = DeployApplication.DeployApplicationDescriptor.class.getField("publicKeyPath");
		ReflectionUtils.setField(sshPrivateKeyField, descriptor, privateKey);

		// upload ssh keys
		client = new OpenShiftV2Client(brokerAddress, username, password);
		String publicKey = ClassLoader.getSystemResource(SSH_PUBLIC_KEY).getFile();
		File publicKeyFile = new File(publicKey);
		if (!client.sshKeyExists(publicKeyFile)) {
			client.uploadSSHKey(publicKeyFile);
		}
		
		// clean up
		removeApp(APP_NAME);
	}
	
	protected void removeApp(String appName) {
		for (String domain : client.getDomains()) {
			client.deleteApp(appName, domain);
		}
	}
	
	protected DeployApplication newDeployAppBuildStep(String deployment, DeploymentType type) {
		return newDeployAppBuildStep(deployment, type, null, null);
	}
	
	protected DeployApplication newDeployAppBuildStep(String deployment, DeploymentType type, String env, String dotOpenShiftDir) {
		return new DeployApplication(SERVER_NAME, 
									APP_NAME, 
									EAP_CARTRIDGE, 
									"", /* domain name, falls back to the only define domain */ 
									SMALL_GEAR, 
									deployment, 
									env, /* environment variables */
									false, /* auto-scale */
									type, 
									dotOpenShiftDir);
	}
}
