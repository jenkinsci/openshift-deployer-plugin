package org.jenkinsci.plugins.openshift;

import com.openshift.client.IApplication;
import hudson.EnvVars;
import hudson.ExtensionList;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import jenkins.model.Jenkins;
import jenkins.model.Jenkins.MasterComputer;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@RunWith(PowerMockRunner.class)
@PrepareForTest({AbstractBuild.class, Jenkins.class, Computer.class})
public class ExpandedJenkinsVarsTest {

    public static final String ENVVAR_KEY_GIT_BRANCH = "GIT_BRANCH";
    public static final String ENVVAR_VALUE_GIT_BRANCH = "someFeature";
    @Mock
    private AbstractBuild build;

    @Mock
    private BuildListener listener;

    @Mock
    private IApplication app;

    @Mock
    private Jenkins jenkins;


    @Before
    public void setup() throws Exception {

        // Setup Mocks

        PowerMockito.mockStatic(Jenkins.class);
        PowerMockito.when(Jenkins.getInstance()).thenReturn(jenkins);

        final ExtensionList extensionList = mock(ExtensionList.class);
        when(extensionList.toArray()).thenReturn(new Object[]{});
        //noinspection unchecked
        when(jenkins.getExtensionList(TokenMacro.class)).thenReturn(extensionList);

        when(build.getEnvironment(listener)).thenReturn(new EnvVars(ENVVAR_KEY_GIT_BRANCH, ENVVAR_VALUE_GIT_BRANCH));


        mockStatic(Computer.class);
        PowerMockito.when(Computer.currentComputer()).thenReturn(mock(MasterComputer.class));
    }

    @Test
    public void appNameWithoutPlaceHolderShouldNotBeUpdated() throws Exception {

        final String configuredAppName = "junit-testapp";
        final DeployApplication deployer = newDeployApplication(configuredAppName, "", "");

        final String expandedAppName = Whitebox.invokeMethod(deployer, "expandedAppName", build, listener);

        assertEquals("appName should not be updated", configuredAppName, expandedAppName);
    }

    @Test
    public void appNameWithPlaceHolderShouldBeChanged() throws Exception {

        final String configuredAppName = "junit-testapp";
        final DeployApplication deployer =
                newDeployApplication(format("%s-${%s}", configuredAppName, ENVVAR_KEY_GIT_BRANCH), "", "");

        final String expandedAppName = Whitebox.invokeMethod(deployer, "expandedAppName", build, listener);

        assertEquals("appName should  be updated",
                format("%s-%s", configuredAppName,ENVVAR_VALUE_GIT_BRANCH), expandedAppName);
    }

    @Test
    public void cartridgesWithPlaceHolderShouldBeChanged() throws Exception {

        final String configuredCartridges = "jbosseap-6";
        final DeployApplication deployer =
                newDeployApplication("", format("${%s}-%s", ENVVAR_KEY_GIT_BRANCH,configuredCartridges), "");

        final String expandedCartridges = Whitebox.invokeMethod(deployer, "expandedCartridges", build, listener);

        assertEquals("Cartridges should  be updated",
                format("%s-%s", ENVVAR_VALUE_GIT_BRANCH, configuredCartridges), expandedCartridges);
    }

    @Test
    public void deploymentPackageWithPlaceHolderShouldBeChanged() throws Exception {

        final String configuredDeploymentPackage = "test.war";
        final DeployApplication deployer =
                newDeployApplication("", "", format("${%s}-%s", ENVVAR_KEY_GIT_BRANCH,configuredDeploymentPackage));

        final String expandedDeploymentPackage = Whitebox.invokeMethod(deployer, "expandedDeploymentPackage", build, listener);

        assertEquals("DeploymentPackage should  be updated",
                format("%s-%s", ENVVAR_VALUE_GIT_BRANCH, configuredDeploymentPackage), expandedDeploymentPackage);
    }

    private DeployApplication newDeployApplication(final String appName, final String cartridges, final String deploymentPackage) {
        return new DeployApplication(
                "", appName, cartridges, "", "", deploymentPackage, "", false, OpenShiftV2Client.DeploymentType.GIT, "");
    }


}
