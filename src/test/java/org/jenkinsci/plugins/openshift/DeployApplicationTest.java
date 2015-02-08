package org.jenkinsci.plugins.openshift;

import static org.jenkinsci.plugins.openshift.TestUtils.assertBuildLogContains;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;

import org.jenkinsci.plugins.openshift.OpenShiftV2Client.DeploymentType;
import org.jenkinsci.plugins.openshift.annotation.IntegrationTest;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(IntegrationTest.class)
@Ignore
public class DeployApplicationTest extends BaseJenkinsTest {
	private static final String BINARY_DEPLOYMENT = "deployment/app.tar.gz";

	@Test
	public void deployBinary() throws Exception {
		// binary deployment package
		String deployment = ClassLoader.getSystemResource(BINARY_DEPLOYMENT).getFile();
		
		FreeStyleProject project = jenkins.createFreeStyleProject();
		DeployApplication deployBuildStep = newDeployAppBuildStep(deployment, DeploymentType.BINARY);
		project.getBuildersList().add(deployBuildStep);
		FreeStyleBuild build = project.scheduleBuild2(0).get();
		
		assertBuildLogContains(build, SUCCESS_LOG);
		
		removeApp(APP_NAME);
	}
}
