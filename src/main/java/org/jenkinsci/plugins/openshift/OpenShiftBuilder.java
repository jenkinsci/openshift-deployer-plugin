package org.jenkinsci.plugins.openshift;

import static org.apache.commons.io.FileUtils.copyFile;
import static org.apache.commons.io.FileUtils.copyFileToDirectory;
import static org.apache.commons.io.FileUtils.copyURLToFile;
import static org.apache.commons.io.FileUtils.forceDelete;
import static org.jenkinsci.plugins.openshift.Util.findServer;
import static org.jenkinsci.plugins.openshift.Util.getRootDeploymentFile;
import static org.jenkinsci.plugins.openshift.Util.isEmpty;
import static org.jenkinsci.plugins.openshift.Util.isURL;
import hudson.AbortException;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLSession;

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
import org.kohsuke.stapler.DataBoundConstructor;

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
        
        if (isEmpty(deploymentPath)) {
        	abort(listener, "Deployment path is not specified.");
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
	
	private String getBaseDir(AbstractBuild<?, ?> build) {
		return build.getWorkspace() + "/openshift";
	}

    private void deployToApp(IApplication app, AbstractBuild<?, ?> build, BuildListener listener) throws IOException, InterruptedException, InvalidRemoteException, TransportException, GitAPIException {
    	File baseDir = new File(getBaseDir(build));
    	if (baseDir.exists()) {
    		FileUtils.deleteDirectory(baseDir);
    	}
    	baseDir.mkdirs();
    	
    	// find deployment unit
		List<String> deployments = findDeployments(build, listener);
		if (deployments.isEmpty()) {
			abort(listener, "No deployment units found.");
		}

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
			log(listener, "Copying deployment to '" + destFile.getName() + "'");
			
			if (isURL(deployments.get(0))) {
				copyURLToFile(new URL(deployments.get(0)), destFile, 10000, 10000);
			} else {
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
			deployments.add(deploymentPath);
			
		} else {
			File dir = new File(build.getWorkspace() + "/" + deploymentPath);
			if (!dir.exists()) {
				abort(listener, "Directory 'target' doesn't exist. Don't know where else to look for a deployment!");
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

	private OpenShiftServer getServer(String selectedServer) {
        List<OpenShiftServer> servers = ((OpenShiftDescriptor) getDescriptor()).getServers();
        return findServer(selectedServer, servers);
    }
	
	private void abort(BuildListener listener, String msg) throws AbortException {
    	listener.getLogger().println("[OPENSHIFT] FAIL: " + msg);
    	throw new AbortException();
	}
	
	private void log(BuildListener listener, String msg) throws AbortException {
    	listener.getLogger().println("[OPENSHIFTÏ] " + msg);
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
}
