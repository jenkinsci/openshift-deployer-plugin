package org.jenkinsci.plugins.openshift;

import hudson.model.Hudson;
import hudson.util.ReflectionUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Properties;

import org.jenkinsci.plugins.openshift.DeployApplication.DeployApplicationDescriptor;
import org.jenkinsci.plugins.openshift.OpenShiftV2Client.DeploymentType;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;

public abstract class BaseJenkinsTest {
	private static final String SYS_PROPS_FILE = "dev.properties";
	protected static final String SSH_PUBLIC_KEY = "keys/id_rsa.pub";
	protected static final String SSH_PRIVATE_KEY = "keys/id_rsa";
	protected static final String SMALL_GEAR = "small";
	protected static final String EAP_CARTRIDGE = "jbosseap-6";
	protected static final String SERVER_NAME = "openshift";
	
	protected OpenShiftV2Client client;
	
	@Rule 
	public JenkinsRule jenkins = new JenkinsRule();
	
	@BeforeClass
	public static void initSysProps() throws IOException {
		// load system properties from a file
		Properties props = new Properties();
		props.load(new FileReader(ClassLoader.getSystemResource(SYS_PROPS_FILE).getFile()));
		
		// set system properties
		System.getProperties().putAll(props);
		
		// clean all apps if any
		removeAllApps();
	}
	
	protected static void removeAllApps() throws IOException {
		String brokerAddress = TestUtils.getProp("openshift.brokerAddress");
		String username = TestUtils.getProp("openshift.username");
		String password = TestUtils.getProp("openshift.password");
		
		OpenShiftV2Client client = new OpenShiftV2Client(brokerAddress, username, password);
		
		for (String domain : client.getDomains()) {
			for (String app : client.getApps(domain)) {
				client.deleteApp(app, domain);
			}
		}
	}
	
	@Before
	public void setup() throws Exception {
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
	}
	
	protected void removeApp(String appName) {
		for (String domain : client.getDomains()) {
			client.deleteApp(appName, domain);
		}
	}
	
	protected DeployApplication newDeployAppBuildStep(String appName, String deployment, DeploymentType type) {
		return newDeployAppBuildStep(appName, deployment, type, null, null);
	}
	
	protected DeployApplication newDeployAppBuildStep(String appName, String deployment, DeploymentType type, String env, String dotOpenShiftDir) {
		return new DeployApplication(SERVER_NAME, 
									appName, 
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
