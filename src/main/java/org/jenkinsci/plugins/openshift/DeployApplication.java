package org.jenkinsci.plugins.openshift;

import static org.apache.commons.io.FileUtils.copyFile;
import static org.apache.commons.io.FileUtils.copyFileToDirectory;
import static org.apache.commons.io.FileUtils.forceDelete;
import static org.jenkinsci.plugins.openshift.Utils.abort;
import static org.jenkinsci.plugins.openshift.Utils.copyURLToFile;
import static org.jenkinsci.plugins.openshift.Utils.findServer;
import static org.jenkinsci.plugins.openshift.Utils.getRootDeploymentFile;
import static org.jenkinsci.plugins.openshift.Utils.isEmpty;
import static org.jenkinsci.plugins.openshift.Utils.isURL;
import static org.jenkinsci.plugins.openshift.Utils.log;
import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLSession;

import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
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
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.jcraft.jsch.Session;
import com.openshift.client.IApplication;
import com.openshift.client.IHttpClient.ISSLCertificateCallback;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public class DeployApplication extends Builder implements BuildStep {
    private String serverName;
    private String cartridges;
    private String domain;
    private String gearProfile;
    private String appName;
    private String deploymentPath;

	@DataBoundConstructor
    public DeployApplication(String serverName, String appName, String cartridges,
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

        if (isEmpty(cartridges)) {
        	abort(listener, "Cartridges are not specified.");
        }
        
        if (isEmpty(deploymentPath)) {
        	abort(listener, "Deployment path is not specified.");
        }
        
        try {
        	// find deployment unit
    		List<String> deployments = findDeployments(build, listener);
    		
    		if (deployments.isEmpty()) {
    			abort(listener, "No packages found to deploy to OpenShift.");
    		} else {
    			log(listener, "Deployments found: " + deployments);
    		}
        	
    		Server server = findServer(serverName);
    		if (server == null) {
        		abort(listener, "No OpenShift server is selected or none are defined in Jenkins Configuration.");
        	}
        	
        	log(listener, "Deploying to OpenShift at http://" + server.getBrokerAddress() + ". Be patient! It might take a minute...");
        	
        	OpenShiftV2Client client = new OpenShiftV2Client(server.getBrokerAddress(), server.getUsername(), server.getPassword());
        	
        	String targetDomain = domain;
        	if (isEmpty(targetDomain)) { // pick the domain if only one exists
        		List<String> domains = client.getDomains();
        		
        		if (domains.size() > 1) {
        			abort(listener, "Specify the user doamin. " + domains.size() + " domains found on the account.");
        		} else if (domains.isEmpty()) {
        			abort(listener, "No domains exist. Create a domain first.");
        		}
        		
        		targetDomain = domains.get(0);
        	}
        	
        	IApplication app = client.getOrCreateApp(appName, targetDomain, Arrays.asList(cartridges.split(" ")), gearProfile);
        	deployToApp(deployments, app, build, listener);
    		
        } catch(AbortException e) {
        	throw e;
        	
        } catch(Exception e) {
        	abort(listener, e.getMessage());
        }

        return true;
    }
	
	private String getBaseDir(AbstractBuild<?, ?> build) {
		return build.getWorkspace() + "/openshift";
	}

    private void deployToApp(List<String> deployments, IApplication app, AbstractBuild<?, ?> build, BuildListener listener) throws IOException, InterruptedException, InvalidRemoteException, TransportException, GitAPIException {
    	if (deployments == null || deployments.isEmpty()) {
    		abort(listener, "Deployment package list is empty.");
    	}
    	
    	File baseDir = new File(getBaseDir(build));
    	if (baseDir.exists()) {
    		FileUtils.deleteDirectory(baseDir);
    	}
    	baseDir.mkdirs();
    	
    	// clone repo
    	log(listener, "Cloning '" + app.getName()  + "' [" + app.getGitUrl() + "] to " + baseDir.getAbsolutePath());
    	SshSessionFactory.setInstance(new JschConfigSessionFactory() {
			@Override
			protected void configure(Host hc, Session session) {
				session.setConfig("StrictHostKeyChecking", "no");				
			}
		});
    	Git git = Git.cloneRepository()
        		.setURI(app.getGitUrl())
        		.setDirectory(baseDir)
        		.call();
    	
		// clean git repo
    	File[] removeList = baseDir.listFiles();
		for(File fileToRemove : removeList) {
			if (!fileToRemove.getName().equals(".git") && !fileToRemove.getName().equals(".openshift")) {
				log(listener, "Deleting '" + fileToRemove.getName() + "'");
				forceDelete(fileToRemove);
			}
		}

		// copy deployment
		copyDeploymentPackages(listener, baseDir, deployments);
		
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


	private void copyDeploymentPackages(BuildListener listener, File baseDir,
			List<String> deployments) throws AbortException, IOException {
		File dest = null;
		if (cartridges.contains("jbossews")) {
			dest = new File(baseDir.getAbsoluteFile() + "/webapps"); // tomcat
		} else {
			dest = new File(baseDir.getAbsoluteFile() + "/deployments"); // jboss/wildfly
		}
		
		if (deployments.size() == 1) {
			File destFile = getRootDeploymentFile(dest, deployments.get(0));

			if (isURL(deployments.get(0))) {
				log(listener, "Downloading the deployment package to '" + destFile.getName() + "'");
				copyURLToFile(new URL(deployments.get(0)), destFile, 10000, 10000);
			} else {
				log(listener, "Copying the deployment package '" + FilenameUtils.getName(deployments.get(0)) + "' to '" + destFile.getName() + "'");
				copyFile(new File(deployments.get(0)), destFile);
			}
		} else {
			for (String deployment : deployments) {
				log(listener, "Copying '" + FilenameUtils.getName(deployment) + "' to '" + dest.getName() + "'");
				copyFileToDirectory(new File(deployment), dest);	
			}
		}
	}

	private List<String> findDeployments(AbstractBuild<?,?> build, BuildListener listener) throws AbortException {
		List<String> deployments = new ArrayList<String>();
		
		if (isURL(deploymentPath)) {
			try {
				deployments.add(TokenMacro.expand(build, listener, deploymentPath));
			} catch (Exception e) {
				throw new AbortException(e.getMessage());
			}
			
		} else {
			File dir = new File(build.getWorkspace() + "/" + deploymentPath);
			if (!dir.exists()) {
				abort(listener, "Directory 'target' doesn't exist. No deployments found!");
			}
			
			File[] deploymentFiles = dir.listFiles(new FilenameFilter() {
				public boolean accept(File dir, String name) {
					return name.toLowerCase().endsWith(".ear") || name.toLowerCase().endsWith(".war");
				}
			});
			
			for (File file : deploymentFiles) {
				deployments.add(file.getAbsolutePath());
			}
		}
		
		return deployments;
	}

	public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
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
	
	@Extension
	public static class DeployApplicationDescriptor extends AbstractDescriptor {
		private final String DEFAULT_PUBLICKEY_PATH = System.getProperty("user.home") + "/.ssh/id_rsa.pub";

		private List<Server> servers = new ArrayList<Server>();
	    
	    public DeployApplicationDescriptor() {
	        super(DeployApplication.class);
	        load();
	    }

	    @Override
	    public boolean configure(StaplerRequest req, JSONObject json)
	            throws FormException {
	        Object s = json.get("servers");
	        if (!JSONNull.getInstance().equals(s)) {
	            servers = req.bindJSONToList(Server.class, s);
	        } else {
	        	servers = null;
	        }
	        save();
	        return super.configure(req, json);
	    }

	    @Override
	    public String getDisplayName() {
	        return Utils.getBuildStepName("Deploy Application");
	    }

		public List<Server> getServers() {
			return servers;
		}
		
		public String getPublicKeyPath() {
			return DEFAULT_PUBLICKEY_PATH;
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
				e.printStackTrace();
				return FormValidation.error(e.getMessage());
			}
		}

		public ListBoxModel doFillGearProfileItems(@QueryParameter("serverName") final String serverName) {
			ListBoxModel items = new ListBoxModel();
			Server server = findServer(serverName);
			
			if (server == null) {
				return items;
			}
			
			OpenShiftV2Client client = new OpenShiftV2Client(server.getBrokerAddress(), server.getUsername(), server.getPassword());
			for (String gearProfile : client.getGearProfiles()) {
				items.add(gearProfile, gearProfile);
			}
			
			return items;
		}
	}
}
