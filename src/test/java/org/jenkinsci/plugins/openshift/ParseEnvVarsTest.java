package org.jenkinsci.plugins.openshift;

import hudson.model.BuildListener;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(PowerMockRunner.class)
public class ParseEnvVarsTest {

    @Mock
    private BuildListener listener;

    @Test
    public void simpleEnvVarsOk() throws Exception {
        Map<String, String> result = Whitebox.invokeMethod(newDeployApplication("FOO=BAR"), "parseEnvironmentVariables", listener);
        assertNotNull(result);
        assertEquals("Should contain 1 element", 1, result.size());
        assertEquals("Lookup of FOO should result in BAR",
                "BAR", result.get("FOO"));
    }

    @Test
    public void quotedEnvVarsOk() throws Exception {
        Map<String, String> result = Whitebox.invokeMethod(newDeployApplication("\"_JAVA_OPTIONS=-Djava.net.preferIPv6Addresses=true -Djava.net.preferIPv4Stack=false\""), "parseEnvironmentVariables", listener);
        assertNotNull(result);
        assertEquals("Should contain 1 element", 1, result.size());
        assertEquals("Lookup of _JAVA_OPTIONS should result in complete property",
                "-Djava.net.preferIPv6Addresses=true -Djava.net.preferIPv4Stack=false", result.get("_JAVA_OPTIONS"));

    }

    @Test
    public void singleQuotedEnvVarsOk() throws Exception {
        Map<String, String> result = Whitebox.invokeMethod(newDeployApplication("'_JAVA_OPTIONS=-Djava.net.preferIPv6Addresses=true -Djava.net.preferIPv4Stack=false'"), "parseEnvironmentVariables", listener);
        assertNotNull(result);
        assertEquals("Should contain 1 element", 1, result.size());
        assertEquals("Lookup of _JAVA_OPTIONS should result in complete property",
                "-Djava.net.preferIPv6Addresses=true -Djava.net.preferIPv4Stack=false", result.get("_JAVA_OPTIONS"));
    }

    @Test
    public void multipleEnvVarsOk() throws Exception {
        Map<String, String> result = Whitebox.invokeMethod(newDeployApplication("FOO=BAR \"_JAVA_OPTIONS=-Djava.net.preferIPv6Addresses=true -Djava.net.preferIPv4Stack=false\" BAZ=QUX"), "parseEnvironmentVariables", listener);
        assertNotNull(result);
        assertEquals("Should contain 1 element", 3, result.size());

        assertEquals("Lookup of FOO should result in BAR",
                "BAR", result.get("FOO"));

        assertEquals("Lookup of _JAVA_OPTIONS should result in complete property",
                "-Djava.net.preferIPv6Addresses=true -Djava.net.preferIPv4Stack=false", result.get("_JAVA_OPTIONS"));

        assertEquals("Lookup of FOO should result in BAR",
                "QUX", result.get("BAZ"));
    }

    private DeployApplication newDeployApplication(final String envVars) {
        return new DeployApplication(
                "", "", "", "", "", "", envVars, false, OpenShiftV2Client.DeploymentType.GIT, "");
    }

}
