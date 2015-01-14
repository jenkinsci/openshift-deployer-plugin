package org.jenkinsci.plugins.openshift;

import com.openshift.client.IApplication;
import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author <a href="mailto:hoffmann@apache.org">Juergen Hoffmann</a>.
 *         Date: 21-Dez-2014
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(AbstractBuild.class)
public class DeploymentPackageTest {

    @Mock
    private AbstractBuild build;

    @Mock
    private Launcher launcher;

    @Mock
    private BuildListener listener;

    @Mock
    private IApplication app;

    private Repository repository;

    private String serverName = "broker.example.com";
    private String appName = "junit-testapp";
    private String cartridges = "jbosseap-6";
    private String domain = "test";
    private String gearProfile = "small";
    private String deploymentPackage = "";
    private String environmentVariables = "";
    private Boolean autoScale = false;
    private String dotOpenshiftDirectory = "";

    @Before
    public void setup() throws IOException {
        // Define a Test Directory
        File localPath = File.createTempFile("TestGitRepository", "");

        // prepare a new folder
        if(localPath.exists()) localPath.delete();

        // create the repository
        repository = FileRepositoryBuilder.create(new File(localPath, ".git"));
        repository.create();

        // Setup Mocks
        when(build.getWorkspace()).thenReturn(new FilePath(new File(getClass().getResource("/").getPath())));
        when(listener.getLogger()).thenReturn(System.out);

        when(app.getName()).thenReturn("testapp");
        when(app.getGitUrl()).thenReturn(repository.getDirectory().getAbsolutePath());
    }


    @Test
    public void deployNonExistingFile() throws Exception {
        deploymentPackage = "non-existing-directory/deployment.war";

        OpenShiftV2Client.DeploymentType deploymentType = OpenShiftV2Client.DeploymentType.GIT;
        DeployApplication deployer = new DeployApplication(serverName, appName, cartridges, domain, gearProfile, deploymentPackage, environmentVariables, autoScale, deploymentType, dotOpenshiftDirectory);

        List<String> deployments = null;
        try {
            deployments = Whitebox.invokeMethod(deployer, "findDeployments", build, listener);
        } catch (AbortException e) {
            // expected
        }
        verify(listener, times(1)).error("[OPENSHIFT] Directory '" + getClass().getResource("/").getPath() + "non-existing-directory/deployment.war' doesn't exist. No deployments found!");
        assertNull("The build should NOT have been performed", deployments);
    }

    @Test
    public void deployFromDirectory() throws Exception {
        deploymentPackage = "deployment";

        OpenShiftV2Client.DeploymentType deploymentType = OpenShiftV2Client.DeploymentType.GIT;
        DeployApplication deployer = new DeployApplication(serverName, appName, cartridges, domain, gearProfile, deploymentPackage, environmentVariables, autoScale, deploymentType, dotOpenshiftDirectory);

        List<String> deployments = null;
        try {
            deployments = Whitebox.invokeMethod(deployer, "findDeployments", build, listener);
        } catch (AbortException e) {
            // expected
        }

        //Evaluate Results
        verify(listener, times(1)).getLogger();
        assertNotNull("The list of deployments should not be null", deployments);
        assertTrue("The List of deployments should contain app.war", deployments.contains(getClass().getResource("/deployment/app.war").getPath()));
    }

    @Test
    public void deploySingleDeploymentUnit() throws Exception {
        deploymentPackage = "deployment/app.war";

        OpenShiftV2Client.DeploymentType deploymentType = OpenShiftV2Client.DeploymentType.GIT;
        DeployApplication deployer = new DeployApplication(serverName, appName, cartridges, domain, gearProfile, deploymentPackage, environmentVariables, autoScale, deploymentType, dotOpenshiftDirectory);

        List<String> deployments = null;
        try {
            deployments = Whitebox.invokeMethod(deployer, "findDeployments", build, listener);
        } catch (AbortException e) {
            // expected
        }

        // Verify Results
        verify(listener, times(1)).getLogger();
        assertNotNull("The list of deployments should contain app.war", deployments);
    }

    @Test
    public void testOpenshiftDirectory() throws Exception {
        deploymentPackage = "deployment/app.war";
        dotOpenshiftDirectory = "openshift";

        OpenShiftV2Client.DeploymentType deploymentType = OpenShiftV2Client.DeploymentType.GIT;
        DeployApplication deployer = new DeployApplication(serverName, appName, cartridges, domain, gearProfile, deploymentPackage, environmentVariables, autoScale, deploymentType, dotOpenshiftDirectory);

        List<String> deployments = null;
        try {
            deployments = Whitebox.invokeMethod(deployer, "findDeployments", build, listener);
        } catch (AbortException e) {
            // expected
        }
        Whitebox.invokeMethod(deployer, "doGitDeploy", deployments, app, build, listener);

        //verify
        assertTrue(
                "Repository must contain the JPDA marker file .openshift/markers/enable_jpda",
                TestUtils.gitRepoContainsFile(repository, ".openshift/markers/enable_jpda")
        );
        assertTrue(
                "Repository must contain the java7 marker file .openshift/markers/java7",
                TestUtils.gitRepoContainsFile(repository, ".openshift/markers/java7")
        );
    }

    @Test
    public void testNoOpenshiftDirectory() throws Exception {
        deploymentPackage = "deployment/app.war";
        dotOpenshiftDirectory = "";

        OpenShiftV2Client.DeploymentType deploymentType = OpenShiftV2Client.DeploymentType.GIT;
        DeployApplication deployer = new DeployApplication(serverName, appName, cartridges, domain, gearProfile, deploymentPackage, environmentVariables, autoScale, deploymentType, dotOpenshiftDirectory);

        List<String> deployments = null;
        try {
            deployments = Whitebox.invokeMethod(deployer, "findDeployments", build, listener);
        } catch (AbortException e) {
            // expected
        }
        Whitebox.invokeMethod(deployer, "doGitDeploy", deployments, app, build, listener);

        //verify
        assertFalse(
                "Repository must not contain the JPDA marker file .openshift/markers/enable_jpda",
                TestUtils.gitRepoContainsFile(repository, ".openshift/markers/enable_jpda")
        );
        assertFalse(
                "Repository must not contain the java7 marker file .openshift/markers/java7",
                TestUtils.gitRepoContainsFile(repository, ".openshift/markers/java7")
        );
    }
}
