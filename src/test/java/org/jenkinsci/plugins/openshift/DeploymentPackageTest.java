package org.jenkinsci.plugins.openshift;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

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

    @Test
    public void deployNonExistingFile() throws Exception {
        String serverName = "broker.example.com";
        String appName = "junit-testapp";
        String cartridges = "jbosseap-6";
        String domain = "test";
        String gearProfile = "small";
        String deploymentPackage = "non-existing-directory/deployment.war";
        String environmentVariables = "";
        Boolean autoScale = false;
        OpenShiftV2Client.DeploymentType deploymentType = OpenShiftV2Client.DeploymentType.GIT;
        DeployApplication deployer = new DeployApplication(serverName, appName, cartridges, domain, gearProfile, deploymentPackage, environmentVariables, autoScale, deploymentType);

        when(build.getWorkspace()).thenReturn(new FilePath(new File("/tmp")));


        List<String> deployments = null;
        try {
            deployments = Whitebox.invokeMethod(deployer, "findDeployments", build, listener);
        } catch (AbortException e) {
            // expected
        }
        verify(listener, times(1)).error("[OPENSHIFT] Directory '/tmp/non-existing-directory/deployment.war' doesn't exist. No deployments found!");
        assertNull("The build should NOT have been performed", deployments);
    }

    @Test
    public void deployFromDirectory() throws Exception {
        //Setup the deployer Plugin
        String serverName = "broker.example.com";
        String appName = "junit-testapp";
        String cartridges = "jbosseap-6";
        String domain = "test";
        String gearProfile = "small";
        String deploymentPackage = "deployment";
        String environmentVariables = "";
        Boolean autoScale = false;
        OpenShiftV2Client.DeploymentType deploymentType = OpenShiftV2Client.DeploymentType.GIT;
        DeployApplication deployer = new DeployApplication(serverName, appName, cartridges, domain, gearProfile, deploymentPackage, environmentVariables, autoScale, deploymentType);

        // Setup Mocks
        when(build.getWorkspace()).thenReturn(new FilePath(new File(getClass().getResource("/").getPath())));
        when(listener.getLogger()).thenReturn(System.out);

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
        // Setup deployer
        String serverName = "broker.example.com";
        String appName = "junit-testapp";
        String cartridges = "jbosseap-6";
        String domain = "test";
        String gearProfile = "small";
        String deploymentPackage = "deployment/app.war";
        String environmentVariables = "";
        Boolean autoScale = false;
        OpenShiftV2Client.DeploymentType deploymentType = OpenShiftV2Client.DeploymentType.GIT;
        DeployApplication deployer = new DeployApplication(serverName, appName, cartridges, domain, gearProfile, deploymentPackage, environmentVariables, autoScale, deploymentType);

        // Setup Mocks
        when(build.getWorkspace()).thenReturn(new FilePath(new File(getClass().getResource("/").getPath())));
        when(listener.getLogger()).thenReturn(System.out);

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

}
