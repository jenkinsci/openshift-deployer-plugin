package org.jenkinsci.plugins.openshift;

import static org.apache.commons.io.FileUtils.copyFile;
import static org.apache.commons.io.FileUtils.forceDelete;
import static org.jenkinsci.plugins.openshift.Util.isEmpty;
import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLSession;

import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.jenkinsci.plugins.openshift.OpenShiftV2Client.ValidationResult;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.openshift.client.IApplication;
import com.openshift.client.IHttpClient.ISSLCertificateCallback;

public class OpenShiftDeployerBuilder extends Builder implements BuildStep {
    private String serverName;
    private String cartridges;
    private String domain;
    private String gearProfile;
    private String appName;
    
	@DataBoundConstructor
    public OpenShiftDeployerBuilder(String serverName, String appName, String cartridges,
			String domain, String gearProfile) {
		this.serverName = serverName;
		this.appName = appName;
		this.cartridges = cartridges;
		this.domain = domain;
		this.gearProfile = gearProfile;
	}


	@Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        if (build != null && build.getResult() != null && build.getResult().isWorseThan(Result.SUCCESS)) {
        	abort(listener, "Build is not success : will not try to deploy.");
        }

        if (isEmpty(appName)) {
        	abort(listener, "Application name is not specified.");
        }

        if (isEmpty(domain)) {
        	abort(listener, "Domain name is not specified.");
        }

        if (isEmpty(cartridges)) {
        	abort(listener, "Cartridges are not specified.");
        }
        
        try {
        	OpenShiftServer server = getServer(serverName);
        	OpenShiftV2Client client = new OpenShiftV2Client(server.getBrokerAddress(), server.getUsername(), server.getPassword());
        	IApplication app = client.getOrCreateApp(appName, domain, Arrays.asList(cartridges.split(" ")), gearProfile);
        	deployToApp(app, build, listener);
    		
        } catch(Exception e) {
        	abort(listener, e.getMessage());
        }

        return true;
    }

    private void deployToApp(IApplication app, AbstractBuild<?, ?> build, BuildListener listener) throws IOException, InterruptedException, InvalidRemoteException, TransportException, GitAPIException {
    	File cloneDir = new File(build.getWorkspace() + "/openshift");
    	if (cloneDir.exists()) {
    		FileUtils.deleteDirectory(cloneDir);
    	}

    	// clone repo
    	log(listener, "Cloning '" + app.getName()  + "' [" + app.getGitUrl() + "] to " + cloneDir.getAbsolutePath());
    	Git git = Git.cloneRepository()
        		.setURI(app.getGitUrl())
        		.setDirectory(cloneDir)
        		.call();
    	
		// clean git repo
    	File[] removeList = cloneDir.listFiles();
		for(File fileToRemove : removeList) {
			if (!fileToRemove.getName().equals(".git") && !fileToRemove.getName().equals(".openshift")) {
				log(listener, "Deleting '" + fileToRemove.getName() + "'");
				forceDelete(fileToRemove);
			}
		}

		// copy deployment
		File targetDir = new File(build.getWorkspace() + "/target");
		File deployment = findDeployment(listener, targetDir);
		if (deployment == null) {
			abort(listener, "No deployments found in 'target' directory");
		}
		
		log(listener, "Copying target/" + deployment.getName() + " to deployments/ROOT.war");
		copyFile(deployment, new File(cloneDir.getAbsoluteFile() + "/deployments/ROOT.war"));
		log(listener, "Copying target/" + deployment.getName() + " to webapps/ROOT.war");
		copyFile(deployment, new File(cloneDir.getAbsoluteFile() + "/webapps/ROOT.war"));
		
		// add directories
		git.add()
			.addFilepattern("webapps")
			.call();
		
		git.add()
			.addFilepattern("deployments")
			.call();
		
		// commit changes
		log(listener, "Commiting repo");
		git.commit()
			.setAll(true)
			.setMessage("deployment added for Jenkins build #" + build.getNumber())
			.call();

		log(listener, "Pushing to upstream");
		PushCommand pushCommand = git.push();
		pushCommand.setProgressMonitor(new TextProgressMonitor(new OutputStreamWriter(System.out)));
		pushCommand.call();
		
		log(listener, "Application deployed to " + app.getApplicationUrl());
	}
    
	private File findDeployment(BuildListener listener, File dir) throws AbortException {
		if (!dir.exists()) {
			abort(listener, "Directory 'target' doesn't exist. Don't know where else to look for a deployment!");
		}
		
		File[] deployments = dir.listFiles(new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.endsWith(".ear") || name.endsWith(".war");
			}
		});
		
		return deployments.length == 0 ? null : deployments[0];
	}

	public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }
   
    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        private List<OpenShiftServer> servers;
        
        public DescriptorImpl() {
            super(OpenShiftDeployerBuilder.class);
            load();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json)
                throws FormException {
            Object s = json.get("servers");
            if (!JSONNull.getInstance().equals(s)) {
                servers = req.bindJSONToList(OpenShiftServer.class, s);
            } else {
            	servers = null;
            }
            save();
            return super.configure(req, json);
        }

        @Override
        public String getDisplayName() {
            return "OpenShift Deployer";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

		public List<OpenShiftServer> getServers() {
			return servers;
		}
				
		public FormValidation doTestConnection(
				@QueryParameter("brokerAddress") final String brokerAddress,
		        @QueryParameter("username") final String username, 
		        @QueryParameter("password") final String password) {
			OpenShiftV2Client client = new OpenShiftV2Client(brokerAddress, username, password);
			ValidationResult result = client.validate();
			if (result.isValid()) {
				return FormValidation.ok("Success");
			} else {
				return FormValidation.error(result.getMessage());
			}
		}

		public ListBoxModel doFillServerNameItems() {
			ListBoxModel items = new ListBoxModel();

			if (servers != null) {
				for (OpenShiftServer server : servers) {
					items.add(server.getName(), server.getName());
				}
			}

			return items;
		}
		
//		public ListBoxModel doFillCartridgesItems(@QueryParameter("serverName") final String serverName) {
//			ListBoxModel items = new ListBoxModel();
//			OpenShiftServer server = findServer(serverName, servers);
//			OpenShiftV2Client client = new OpenShiftV2Client(server.getBrokerAddress(), server.getUsername(), server.getPassword());
//			List<String> cartridgeNames = client.getCartridges();
//			Collections.sort(cartridgeNames);
//			for (String cartridge : cartridgeNames) {
//				items.add(cartridge, cartridge);
//			}
//			
//			return items;
//		}
		
		public ListBoxModel doFillGearProfileItems(@QueryParameter("serverName") final String serverName) {
			ListBoxModel items = new ListBoxModel();
			OpenShiftServer server = findServer(serverName, servers);
			OpenShiftV2Client client = new OpenShiftV2Client(server.getBrokerAddress(), server.getUsername(), server.getPassword());
			for (String gearProfile : client.getGearProfiles()) {
				items.add(gearProfile, gearProfile);
			}
			
			return items;
		}
		
		public ListBoxModel doFillDomainItems(@QueryParameter("serverName") final String serverName) {
			ListBoxModel items = new ListBoxModel();
			OpenShiftServer server = findServer(serverName, servers);
			OpenShiftV2Client client = new OpenShiftV2Client(server.getBrokerAddress(), server.getUsername(), server.getPassword());
			for (String domain : client.getDomains()) {
				items.add(domain, domain);
			}
			
			return items;
		}
    }

	public String getServerName() {
		return serverName;
	}
	
	public String getCartridges() {
		return cartridges;
	}

	public String getDomain() {
		return domain;
	}

	public String getGearProfile() {
		return gearProfile;
	}

	public String getAppName() {
		return appName;
	}

	public static class TrustingISSLCertificateCallback implements ISSLCertificateCallback {
		public boolean allowCertificate(
				java.security.cert.X509Certificate[] certs) {
			return true;
		}

		public boolean allowHostname(String hostname, SSLSession session) {
			return true;
		}
	}
	
	private OpenShiftServer getServer(String selectedServer) {
        List<OpenShiftServer> servers = ((DescriptorImpl) getDescriptor()).getServers();
        return findServer(selectedServer, servers);
    }
	
	private static OpenShiftServer findServer(String selectedServer, List<OpenShiftServer> servers) {
        for (OpenShiftServer server : servers) {
            if(server.getName().equals(selectedServer)) {
                return server;
            }
        }
        
        return null;
	}
	
	private void abort(BuildListener listener, String msg) throws AbortException {
    	listener.getLogger().println("[OpenShift Deployer] FAIL: " + msg);
    	throw new AbortException();
	}
	
	private void log(BuildListener listener, String msg) throws AbortException {
    	listener.getLogger().println("[OpenShift Deployer] " + msg);
	}
}
