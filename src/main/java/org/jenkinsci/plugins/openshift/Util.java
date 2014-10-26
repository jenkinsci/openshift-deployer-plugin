package org.jenkinsci.plugins.openshift;

import static org.apache.commons.io.FilenameUtils.getExtension;

import java.io.File;
import java.util.List;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public final class Util {
	private Util() {
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
}
