package org.jenkinsci.plugins.openshift;

import com.openshift.client.IApplication;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import java.io.File;
import java.io.IOException;
import java.util.List;

import jenkins.model.Jenkins.MasterComputer;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

/**
 * @author <a href="mailto:hoffmann@apache.org">Juergen Hoffmann</a>.
 *         Date: 21-Dez-2014
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({AbstractBuild.class, Computer.class})
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
        
        mockStatic(Computer.class);
        PowerMockito.when(Computer.currentComputer()).thenReturn(mock(MasterComputer.class));
        
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
        assertNotNull("The list of deployments should contain app.war", deployments);
        assertEquals("One deployment should be in the list", 1, deployments.size());
        assertTrue("Deployments list should contain app.war", deployments.get(0).endsWith(deploymentPackage));
    }

    @Test
    public void testOpenshiftDirectory() throws Exception {
        deploymentPackage = "deployment/app.war";
        dotOpenshiftDirectory = this.getClass().getResource("/").getPath() + File.separator + "openshift";

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

    @Test
    public void testNullOpenshiftDirectory() throws Exception {
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

    @Test(expected = IllegalArgumentException.class)
    public void testIllegalArgumentException() throws Exception {
        deploymentPackage = "deployment/app.war";
        dotOpenshiftDirectory = "openshift";

        GitClient client = new GitClient(app);
        client.deploy(null, null, null, "", dotOpenshiftDirectory);
    }
    
    @Test
    public void deployAbsolutePathToDeploymentUnit() throws Exception {
        deploymentPackage = ClassLoader.getSystemResource("deployment/app.tar.gz").getFile();
        
        OpenShiftV2Client.DeploymentType deploymentType = OpenShiftV2Client.DeploymentType.BINARY;
        DeployApplication deployer = new DeployApplication(serverName, appName, cartridges, domain, gearProfile, deploymentPackage, environmentVariables, autoScale, deploymentType, dotOpenshiftDirectory);

        List<String> deployments = null;
        try {
            deployments = Whitebox.invokeMethod(deployer, "findDeployments", build, listener);
        } catch (AbortException e) {
        	// NOOP
        }

        // Verify Results
        assertNotNull("The list of deployments should contain app.war", deployments);
        assertEquals("One deployment should be in the list", 1, deployments.size());
        assertEquals(deploymentPackage, deployments.get(0));
    }
}
