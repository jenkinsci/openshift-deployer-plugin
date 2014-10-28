package org.jenkinsci.plugins.openshift;

import static org.apache.commons.io.FilenameUtils.getExtension;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

import org.apache.commons.io.IOUtils;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public final class Utils {
	private Utils() {
	}
	
	public static boolean isEmpty(String str) {
		return str == null || str.trim().length() == 0;
	}
	
	public static File getRootDeploymentFile(File dest, String deployment) {
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
	
	public static OpenShiftServer findServer(String selectedServer, List<OpenShiftServer> servers) {
        for (OpenShiftServer server : servers) {
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
}
