package org.jenkinsci.plugins.openshift;

import static org.apache.commons.io.FileUtils.copyFile;
import static org.apache.commons.io.FileUtils.copyFileToDirectory;
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
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig.Host;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.jenkinsci.plugins.openshift.OpenShiftV2Client.ValidationResult;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.jcraft.jsch.Session;
import com.openshift.client.IApplication;
import com.openshift.client.IHttpClient.ISSLCertificateCallback;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public class OpenShiftBuilder extends Builder implements BuildStep {
    private String serverName;
    private String cartridges;
    private String domain;
    private String gearProfile;
    private String appName;
    private String deploymentPath;
    
	@DataBoundConstructor
    public OpenShiftBuilder(String serverName, String appName, String cartridges,
			String domain, String gearProfile, String deploymentPath) {
		this.serverName = serverName;
		this.appName = appName;
		this.cartridges = cartridges;
		this.domain = domain;
		this.gearProfile = gearProfile;
		this.deploymentPath = deploymentPath;
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
        	log(listener, "Deploying to OpenShift at http://" + server.getBrokerAddress() + ". Be patient! It might take a minute...");
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
    	
    	// find deployment unit
		File targetDir = new File(build.getWorkspace() + "/" + deploymentPath);
		List<File> deployments = findDeployment(listener, targetDir);
		if (deployments.isEmpty()) {
			abort(listener, "No deployment units found.");
		}

    	// clone repo
    	log(listener, "Cloning '" + app.getName()  + "' [" + app.getGitUrl() + "] to " + cloneDir.getAbsolutePath());
    	SshSessionFactory.setInstance(new JschConfigSessionFactory() {
			@Override
			protected void configure(Host hc, Session session) {
				session.setConfig("StrictHostKeyChecking", "no");				
			}
		});
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
		copyDeploymentUnits(listener, cloneDir, deployments);
		
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


	private void copyDeploymentUnits(BuildListener listener, File cloneDir,
			List<File> deployments) throws AbortException, IOException {
		File dest = null;
		if (cartridges.contains("jbossews")) {
			dest = new File(cloneDir.getAbsoluteFile() + "/webapps"); // tomcat
		} else {
			dest = new File(cloneDir.getAbsoluteFile() + "/deployments"); // jboss/wildfly
		}
		
		if (deployments.size() == 1) {
			log(listener, "Copying '" + deployments.get(0).getName() + "' to '" + dest.getName() + "/ROOT.war'");
			copyFile(deployments.get(0), new File(dest.getAbsoluteFile() + "/ROOT.war"));
		} else {
			for (File deployment : deployments) {
				log(listener, "Copying '" + deployment.getName() + "' to '" + dest.getName() + "'");
				copyFileToDirectory(deployment, dest);	
			}
		}
	}
    
	private List<File> findDeployment(BuildListener listener, File dir) throws AbortException {
		if (!dir.exists()) {
			abort(listener, "Directory 'target' doesn't exist. Don't know where else to look for a deployment!");
		}
		
		File[] deployments = dir.listFiles(new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.endsWith(".ear") || name.endsWith(".war");
			}
		});
		
		return Arrays.asList(deployments);
	}

	public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }
   
    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        private List<OpenShiftServer> servers;
        private String publicKeyPath = System.getProperty("user.home") + "/.ssh/id_rsa.pub";
        
        public DescriptorImpl() {
            super(OpenShiftBuilder.class);
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
            return "Deploy to OpenShift";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

		public List<OpenShiftServer> getServers() {
			return servers;
		}
		
		public String getPublicKeyPath() {
			return publicKeyPath;
		}

		public FormValidation doCheckLogin(
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
		
		public FormValidation doUploadSSHKeys(
				@QueryParameter("brokerAddress") final String brokerAddress,
		        @QueryParameter("username") final String username, 
		        @QueryParameter("password") final String password,
		        @QueryParameter("publicKeyPath") final String publicKeyPath) {
			OpenShiftV2Client client = new OpenShiftV2Client(brokerAddress, username, password);
			try {
				if (publicKeyPath == null) {
					return FormValidation.error("Specify the path to SSH public key.");
				}
				File file = new File(publicKeyPath);
				
				if (!file.exists()) {
					return FormValidation.error("Specified SSH public key doesn't exist: " + publicKeyPath);	
				}
				
				if (client.sshKeyExists(file)) {
					return FormValidation.ok("SSH public key already exists.");
					
				} else {
					client.uploadSSHKey(file);
					return FormValidation.ok("SSH Public key uploaded successfully.");
				}
			} catch (IOException e) {
				return FormValidation.error(e.getMessage());
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
	
	public String getDeploymentPath() {
		return deploymentPath;
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
    	listener.getLogger().println("[OPENSHIFT] FAIL: " + msg);
    	throw new AbortException();
	}
	
	private void log(BuildListener listener, String msg) throws AbortException {
    	listener.getLogger().println("[OPENSHIFTÏ] " + msg);
	}
}
