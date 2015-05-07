package org.jenkinsci.plugins.openshift;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.jenkinsci.plugins.openshift.util.Utils.abort;
import static org.jenkinsci.plugins.openshift.util.Utils.findServer;
import static org.jenkinsci.plugins.openshift.util.Utils.log;
import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;

import java.io.IOException;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.openshift.util.Utils;
import org.kohsuke.stapler.DataBoundConstructor;

import com.openshift.client.IApplication;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public class DeleteApplication extends Builder implements BuildStep {
    private String serverName;
    private String domain;
    private String appName;

	@DataBoundConstructor
    public DeleteApplication(String serverName, String appName, String domain) {
		this.serverName = serverName;
		this.appName = appName;
		this.domain = domain;
	}


	@Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        if (build != null && build.getResult() != null && build.getResult().isWorseThan(Result.SUCCESS)) {
        	abort(listener, "Build is not success.");
        }

        if (isEmpty(appName)) {
        	abort(listener, "Application name is not specified.");
        }

        try {
        	Server server = findServer(serverName);
    		if (server == null) {
        		abort(listener, "No OpenShift server is selected or none are defined in Jenkins Configuration.");
        	}

        	OpenShiftV2Client client = new OpenShiftV2Client(server.getBrokerAddress(), server.getUsername(), server.getPassword());
        	
        	String targetDomain = domain;
        	if (isEmpty(targetDomain)) { // pick the domain if only one exists
        		List<String> domains = client.getDomains();
        		
        		if (domains.size() > 1) {
        			abort(listener, "Specify the user domain. " + domains.size() + " domains found on the account.");
        		} else if (domains.isEmpty()) {
        			abort(listener, "No domains exist. Cannot delete the gear.");
        		}
        		
        		targetDomain = domains.get(0);
        	}
        	String expandedAppName = expandedAppName(build, listener);
			if(!appName.equalsIgnoreCase(expandedAppName))
			{
				log(listener, "Expanded application name to be used (non alphanum chars sanitized): " + expandedAppName);
			}
        	IApplication deletedApp = client.deleteApp(expandedAppName, targetDomain);
        	if (deletedApp != null) {
			log(listener, "Application '" + appName + "' [" + deletedApp.getApplicationUrl() + "] is deleted.");
		}
		else {
			log(listener, "Application '" + appName + "' is not found.");
		}
        	
        } catch(Exception e) {
        	abort(listener, e.getMessage());
        }

        return true;
    }
	private String expandedAppName(final AbstractBuild<?, ?> build, final BuildListener listener) throws AbortException {
		return Utils.removeNonAlpha(Utils.expandAll(build, listener, appName));
	}

	public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }
   
	public String getServerName() {
		return serverName;
	}

	public String getDomain() {
		return domain;
	}

	public String getAppName() {
		return appName;
	}
	
	@Extension
	public static class DeleteApplicationDescriptor extends AbstractDescriptor {
	    public DeleteApplicationDescriptor() {
	        super(DeleteApplication.class);
	        load();
	    }

	    @Override
	    public String getDisplayName() {
	        return Utils.getBuildStepName("Delete Application");
	    }

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}
	}
}
