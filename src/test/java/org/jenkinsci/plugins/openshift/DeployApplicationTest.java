package org.jenkinsci.plugins.openshift;

import static org.jenkinsci.plugins.openshift.TestUtils.assertDeploySucceeded;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;

import org.jenkinsci.plugins.openshift.OpenShiftV2Client.DeploymentType;
import org.jenkinsci.plugins.openshift.annotation.IntegrationTest;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * @author ssadeghi
 * 
 * TODO: add tests for plugin running on slave nodes
 */
@Category(IntegrationTest.class)
public class DeployApplicationTest extends BaseJenkinsTest {
	private static final String BINARY_DEPLOYMENT = "deployment/app.tar.gz";
	private static final String GIT_DEPLOYMENT = "deployment/app.war";
	
	protected static final String APP_NAME = "testapp";

	@Test
	public void deployBinary() throws Exception {
		String deployment = ClassLoader.getSystemResource(BINARY_DEPLOYMENT).getFile();
		
		FreeStyleProject project = jenkins.createFreeStyleProject();
		DeployApplication deployBuildStep = newDeployAppBuildStep(APP_NAME, deployment, DeploymentType.BINARY);
		project.getBuildersList().add(deployBuildStep);
		FreeStyleBuild build = project.scheduleBuild2(0).get();
		
		assertDeploySucceeded(build);
		
		removeApp(APP_NAME);
	}
	

	@Test
	// TODO: fixed the test
	@Ignore
	public void deployGit() throws Exception {
		String deployment = ClassLoader.getSystemResource(GIT_DEPLOYMENT).getFile();
		
		FreeStyleProject project = jenkins.createFreeStyleProject();
		DeployApplication deployBuildStep = newDeployAppBuildStep(APP_NAME, deployment, DeploymentType.GIT);
		project.getBuildersList().add(deployBuildStep);
		FreeStyleBuild build = project.scheduleBuild2(0).get();
		
		assertDeploySucceeded(build);
		
		removeApp(APP_NAME);
	}
}
