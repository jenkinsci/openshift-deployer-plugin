package org.jenkinsci.plugins.openshift;

import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.util.ReflectionUtils;

import java.io.File;
import java.lang.reflect.Field;

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
	
	protected String brokerAddress = TestUtils.getProp("openshift.brokerAddress");
	protected String username = TestUtils.getProp("openshift.username");
	protected String password = TestUtils.getProp("openshift.password");
	
	@Rule 
	public JenkinsRule jenkins = new JenkinsRule();
	
	@Before
	public void setup() throws Exception {
		@SuppressWarnings("rawtypes")
		Descriptor descriptor = Hudson.getInstance().getDescriptor(DeployApplication.class);
		
		// set openshift server
		Server server = new Server(SERVER_NAME, brokerAddress, username, password);
		((DeployApplication.DeployApplicationDescriptor)descriptor).getServers().add(server);

		// set ssh keys
		String sshPrivateKey = ClassLoader.getSystemResource(SSH_PRIVATE_KEY).getFile();
		Field sshPrivateKeyField = DeployApplication.DeployApplicationDescriptor.class.getField("publicKeyPath");
		ReflectionUtils.setField(sshPrivateKeyField, descriptor, sshPrivateKey);

		
		// upload ssh keys
		OpenShiftV2Client client = new OpenShiftV2Client(brokerAddress, username, password);
		String sshPublicKey = ClassLoader.getSystemResource(SSH_PUBLIC_KEY).getFile();
		client.uploadSSHKey(new File(sshPublicKey));
	}
}
