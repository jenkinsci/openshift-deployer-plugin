package org.jenkinsci.plugins.openshift;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public class OpenShiftException extends RuntimeException {
	public OpenShiftException() {
		super();
	}

	public OpenShiftException(String message, Throwable cause,
			boolean enableSuppression, boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

	public OpenShiftException(String message, Throwable cause) {
		super(message, cause);
	}

	public OpenShiftException(String message) {
		super(message);
	}

	public OpenShiftException(Throwable cause) {
		super(cause);
	}
}
