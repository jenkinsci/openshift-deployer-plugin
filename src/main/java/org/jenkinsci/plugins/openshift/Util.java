package org.jenkinsci.plugins.openshift;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public final class Util {
	private Util() {
	}
	
	public static boolean isEmpty(String str) {
		return str == null || str.trim().length() == 0;
	}
}
