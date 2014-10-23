package org.jenkinsci.plugins.openshift;

public final class Util {
	private Util() {
	}
	
	public static boolean isEmpty(String str) {
		return str == null || str.trim().length() == 0;
	}
}
