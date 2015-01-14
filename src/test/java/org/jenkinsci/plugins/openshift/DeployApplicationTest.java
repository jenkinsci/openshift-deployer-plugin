package org.jenkinsci.plugins.openshift;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import org.jenkinsci.plugins.openshift.OpenShiftV2Client.DeploymentType;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class DeployApplicationTest extends BaseJenkinsTest {
	private static final String BINARY_DEPLOYMENT = "deployment/app.tar.gz";

	@Test
	public void deployBinary() throws Exception {
		FreeStyleProject project = jenkins.createFreeStyleProject();
		project.getBuildersList().add(createDeployAppBuildStep());
		FreeStyleBuild build = project.scheduleBuild2(0).get();
	}

	private DeployApplication createDeployAppBuildStep() {
		String deployment = ClassLoader.getSystemResource(BINARY_DEPLOYMENT).getFile();
		return new DeployApplication(SERVER_NAME, APP_NAME, EAP_CARTRIDGE, deployment, SMALL_GEAR, "", null, false, DeploymentType.BINARY, "");
	}
}
