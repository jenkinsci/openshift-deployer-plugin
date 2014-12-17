package org.jenkinsci.plugins.openshift.util;

import static java.util.Collections.EMPTY_LIST;
import static org.apache.commons.io.FilenameUtils.getExtension;
import hudson.AbortException;
import hudson.model.BuildListener;
import hudson.model.Hudson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.openshift.DeployApplication;
import org.jenkinsci.plugins.openshift.Server;
import org.jenkinsci.plugins.openshift.DeployApplication.DeployApplicationDescriptor;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public final class Utils {
	private Utils() {
	}
	
	public static boolean isEmpty(String str) {
		return str == null || str.trim().length() == 0;
	}
	
	public static File createRootDeploymentFile(File dest, String deployment) {
		StringBuilder path = new StringBuilder();
		path.append(dest.getAbsolutePath());
		path.append("/ROOT.");
		
		String dep = deployment.toLowerCase();
		if (isURL(dep)) {
			if (dep.contains(".ear")) {
				path.append("ear");
			} else if (dep.contains(".war")) {
				path.append("war");
			} else if (dep.contains("ear")) { // TODO: fuzzy! might be just part of the url e.g. /bear
				path.append("ear");
			} else { 						  
				path.append("war");
			}
		} else {
			path.append(getExtension(dep));
		}
		
		return new File(path.toString());
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
				if (!parent.mkdirs() && !parent.isDirectory()) {
					throw new IOException("Directory '" + parent
							+ "' could not be created");
				}
			}
		}
		return new FileOutputStream(file, false);
	}
	
	public static void abort(BuildListener listener, String msg) throws AbortException {
    	listener.error("[OPENSHIFT] " + msg);
    	throw new AbortException();
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
}
