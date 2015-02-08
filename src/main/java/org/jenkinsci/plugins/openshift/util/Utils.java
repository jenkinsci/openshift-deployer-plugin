package org.jenkinsci.plugins.openshift.util;

import static java.util.Collections.EMPTY_LIST;
import static org.apache.commons.io.FilenameUtils.getName;
import hudson.AbortException;
import hudson.FilePath;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Hudson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import jenkins.model.Jenkins.MasterComputer;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jenkinsci.plugins.openshift.DeployApplication;
import org.jenkinsci.plugins.openshift.OpenShiftV2Client.DeploymentType;
import org.jenkinsci.plugins.openshift.Server;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public final class Utils {
	private static final Logger LOG = Logger.getLogger(Utils.class.getName());
	
	private Utils() {
	}
	
	public static boolean isURL(String str) {
		return str.startsWith("http://") || str.startsWith("https://");
	}
	
	public static Server findServer(String selectedServer) {
        for (Server server : getServers()) {
            if(server.getName().equals(selectedServer)) {
                return server;
            }
        }
        
        return null;
	}
	
	public static void copyURLToFile(final URL source, final File destination,
			final int connectionTimeout, final int readTimeout)
			throws IOException {
		final URLConnection connection = source.openConnection();
		connection.setConnectTimeout(connectionTimeout);
		connection.setReadTimeout(readTimeout);
		InputStream is = connection.getInputStream();
		FileOutputStream output = openOutputStream(destination);
		try {
			IOUtils.copy(is, output);
			output.close();
		} finally {
			IOUtils.closeQuietly(is);
		}
	}
	
	public static FileOutputStream openOutputStream(final File file) throws IOException {
		if (file.exists()) {
			if (file.isDirectory()) {
				throw new IOException("File '" + file
						+ "' exists but is a directory");
			}
			if (file.canWrite() == false) {
				throw new IOException("File '" + file
						+ "' cannot be written to");
			}
		} else {
			final File parent = file.getParentFile();
			if (parent != null) {
				// TEMP
				LOG.info("Parent exists: " + parent.exists());
				
				// TEMP
				if (!parent.mkdirs() && !parent.isDirectory()) {
					throw new IOException("Directory '" + parent + "' could not be created");
				}
			}
		}
		return new FileOutputStream(file, false);
	}
	
	public static void abort(BuildListener listener, String msg) throws AbortException {
    	listener.error("[OPENSHIFT] " + msg);
    	throw new AbortException();
	}

	public static void abort(BuildListener listener, Exception e) throws AbortException {
		abort(listener, ExceptionUtils.getStackTrace(e));
	}
	
	public static void log(BuildListener listener, String msg) throws AbortException {
    	listener.getLogger().println("[OPENSHIFT] " + msg);
	}
	
	public static String getBuildStepName(String name) {
		return "OpenShift: " + name;
	}
	
	
	private static DeployApplication.DeployApplicationDescriptor getDeployApplicationDescriptor() {
		return (DeployApplication.DeployApplicationDescriptor)Hudson.getInstance().getDescriptor(DeployApplication.class);
	}
	
	@SuppressWarnings("unchecked")
	public static List<Server> getServers() {
		DeployApplication.DeployApplicationDescriptor descriptor = getDeployApplicationDescriptor();
		List<Server> servers = descriptor.getServers();
		return servers == null ? (List<Server>) EMPTY_LIST : servers;
	}
	
	public static String getSSHPrivateKey() {
		DeployApplication.DeployApplicationDescriptor descriptor = getDeployApplicationDescriptor();
		return descriptor.getPublicKeyPath() == null ? null : descriptor.getPublicKeyPath().replaceAll("^(.*)\\.pub$", "$1");
	}
	
	public static File createDir(String path) {
		File dir = new File(path);
		
		if (!dir.exists()) {
			dir.mkdirs();
		}
		
		return dir;
	}

	public static Boolean validateOpenshiftDirectory(String openshiftDirectory) {
		List<String> validNames = new ArrayList<String>() {{
			add("openshift");
			add(".openshift");
			add("config");
			add("action_hooks");
			add("markers");
		}};

		File directory = null;
		if (openshiftDirectory.startsWith(File.separator))
			directory = new File(openshiftDirectory); // Absolute Path
		else
			directory = new File(Utils.class.getResource("/").getPath() + File.separator + openshiftDirectory); // Relative Path

		// Make sure the path exists and is a directory
		if(!directory.exists()) return false;
		if(!directory.isDirectory()) return false;

		// Try to determine whether the user has configured a directory containing
		//  a .openshift directory, or the .openshift directory itself  */
		String[] filesArray = directory.list(new FilenameFilter() {
			public boolean accept(File dir, String name) {
				if (name.equals(".") || name.equals("..")) return false;
				return true;
			}
		});
		if(filesArray == null) return false;
		List<String> files = Arrays.asList(filesArray);

		// Check whether there are valid entries in the files list
		if(CollectionUtils.containsAny(files,validNames))
		{
			// Looks like a valid directory
			return true;
		}

		// We did not find anything, meaning the directory is not valid
		return false;
	}
	
    public static String getBuildWorkspaceOnMaster(AbstractBuild<?, ?> build) {
    	if (runingOnMaster()) {
    		return build.getWorkspace().getRemote();
    	}
    	
    	// the current build dir on master + workspace:
    	// jenkins/jobs/[jobname]/builds/[buildnumber]/workspace
    	return build.getProject().getBuildDir() + File.separator + build.getNumber() + File.separator + "workspace";
    }
    
    public static boolean runingOnMaster() {
    	return Computer.currentComputer() instanceof MasterComputer;
    }
    
	public static void copyFileFromSlaveToMaster(AbstractBuild<?,?> build, String slavePath, String masterPath) throws IOException {
		FilePath slaveFile = new FilePath(build.getWorkspace().getChannel(), slavePath);
		File masterFile = new File(masterPath);
		
		try {
			if (!slaveFile.exists()) {
				return;
			}
		} catch (InterruptedException e) {
			throw new IOException(e);		
		}
		
		try {
			if (slaveFile.isDirectory()) {
				// remove if dir exists on master
				if (masterFile.exists()) {
					FileUtils.deleteDirectory(masterFile);
				}
				
				// create dir on master
				if (!masterFile.mkdirs()) {
					throw new IOException("Failed to create the directory on master node: " + masterPath);
				}
				
				slaveFile.copyRecursiveTo(new FilePath(masterFile));

				
			} else {
				slaveFile.copyTo(new FilePath(masterFile));
			}
		} catch (InterruptedException e) {
			throw new IOException("Failed to copy file from slave node to master.", e);
		}
	}
		
	public static List<String> copyDeploymenstToMaster(AbstractBuild<?,?> build, BuildListener listener, 
			List<String> deployments, File baseDir, DeploymentType deploymentType) throws IOException {
		List<String> localDeployments = new ArrayList<String>();
		for (String deployment : deployments) {
			if (isURL(deployment)) {
				File localDeployment = new File (baseDir, getURLDeploymentName(deployment, deploymentType));
				
				log(listener, "Downloading the deployment from '" + deployment + "' to '" +  localDeployment.getAbsolutePath() + "'");
				
				try {
					copyURLToFile(new URL(deployment), localDeployment, 10000, 10000);
				} catch (Exception e) {
					abort(listener, e);
				}
				
				localDeployments.add(localDeployment.getAbsolutePath());
				
			} else {
				if (Utils.runingOnMaster()) { // deployment is already local
					localDeployments.add(deployment);
					
				} else {
					String localFile = baseDir + File.separator + getName(deployment);
					log(listener, "Copying the deployment from slave node to '" +  localFile + "'");
					copyFileFromSlaveToMaster(build, deployment, localFile);
    				localDeployments.add(localFile);
				}
			}
		}
		
		return localDeployments;
	}

	private static String getURLDeploymentName(String deployment, DeploymentType deploymentType) {
		if (!isURL(deployment)) {
			throw new IllegalArgumentException("Deployment paht is not a url: " + deployment);
		}
		
		if (deploymentType == DeploymentType.BINARY) {
			return "app.tar.gz";
		} else {
			String dep = deployment.toLowerCase();
			if (dep.contains(".ear")) {
				return "ROOT.ear";
			} else if (dep.contains(".war")) {
				return "ROOT.war";
			} else if (dep.contains("ear")) { // TODO: fuzzy! make a better guess since it 
											  // might be just part of the url e.g. /bear
				return "ROOT.ear";
			} else {
				return "ROOT.war";
			}
		}
	} 
}
