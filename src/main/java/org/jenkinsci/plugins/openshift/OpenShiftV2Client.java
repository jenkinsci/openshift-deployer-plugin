package org.jenkinsci.plugins.openshift;

import static org.jenkinsci.plugins.openshift.Util.isEmpty;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import javax.net.ssl.SSLSession;

import com.openshift.client.IApplication;
import com.openshift.client.IDomain;
import com.openshift.client.IGearProfile;
import com.openshift.client.IOpenShiftSSHKey;
import com.openshift.client.SSHPublicKey;
import com.openshift.client.IHttpClient.ISSLCertificateCallback;
import com.openshift.client.IOpenShiftConnection;
import com.openshift.client.IUser;
import com.openshift.client.OpenShiftConnectionFactory;
import com.openshift.client.cartridge.ICartridge;
import com.openshift.client.cartridge.IEmbeddableCartridge;
import com.openshift.client.cartridge.IStandaloneCartridge;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public class OpenShiftV2Client {
	private String broker;
	private String username;
	private String password;
	private IOpenShiftConnection conn;
	
	
	public OpenShiftV2Client(String broker, String username, String password) {
		this.broker = broker;
		this.username = username;
		this.password = password;
		
		this.conn = createConnection();
	}
	
	private IOpenShiftConnection createConnection() {
		return new OpenShiftConnectionFactory().getConnection("jenkins-ci", username, password, broker, new TrustingISSLCertificateCallback());
	}
	
	
	public ValidationResult validate() {
		try {
			if (conn.getDomains().size() == 0) {
				return new ValidationResult(false, "User doesn't have any domains. Create a domain for the user in OpenShift");	
			}
		} catch (Exception e) {
			return new ValidationResult(false, e.getMessage());
		}
		
		return new ValidationResult(true, "ok");
	}
	
	
	public IApplication getOrCreateApp(String appName, String domainName, List<String> cartridges, String gearProfile) throws OpenShiftException {
		IUser user = conn.getUser();
		IDomain domain = user.getDomain(domainName);
		
		// create domain if doesn't exist
		if (domain == null) {
			throw new OpenShiftException("Domain '" + domainName + "' doesn't exist.");
		}
		
		IApplication app = domain.getApplicationByName(appName);
		// create app if doesn't exist
		if (app == null) {
			if (isEmpty(gearProfile)) {
				app = domain.createApplication(appName, getStandaloneCartridge(cartridges));
			} else {
				app = domain.createApplication(appName, getStandaloneCartridge(cartridges), getGearProfile(gearProfile, domainName));
			}
			
			
			
			app.addEmbeddableCartridges(getEmbeddedCartridge(cartridges));
			app.waitForAccessible(5*60*1000); // 5 min
		}
		
		return app;
	}
	
	public boolean sshKeyExists(File publicKey) throws IOException {
		SSHPublicKey newKey = new SSHPublicKey(publicKey);
		IUser user = conn.getUser();
		for (IOpenShiftSSHKey key : user.getSSHKeys()) {
			if (newKey.getPublicKey().equals(key.getPublicKey())) {
				return true;
			}
		}

		return false;
	}
	
	public void uploadSSHKey(File publicKey) throws IOException {
		SSHPublicKey newKey = new SSHPublicKey(publicKey);
		conn.getUser().addSSHKey("jenkins-ci-" + InetAddress.getLocalHost().getHostName(), newKey);
	}

	private IGearProfile getGearProfile(String gearProfile, String domainName) {
		for (IGearProfile profile : conn.getUser().getDomain(domainName).getAvailableGearProfiles()) {
			if (profile.getName().equals(gearProfile)) {
				return profile;
			}
		}
		
		return null;
	}

	private IStandaloneCartridge getStandaloneCartridge(List<String> cartridgeNames) {
		for (String cartridgeName : cartridgeNames) {
			for (IStandaloneCartridge cartridge : conn.getStandaloneCartridges()) {
				if (cartridge.getName().equals(cartridgeName)) {
					return cartridge;
				}
			}
		}
		
		return null;
	}
	
	private List<IEmbeddableCartridge> getEmbeddedCartridge(List<String> cartridgeNames) {
		List<IEmbeddableCartridge> embeddableCartridges = new LinkedList<IEmbeddableCartridge>();
		for (String cartridgeName : cartridgeNames) {
			for (IEmbeddableCartridge cartridge : conn.getEmbeddableCartridges()) {
				if (cartridge.getName().equals(cartridgeName)) {
					embeddableCartridges.add(cartridge);
				}
			}
		}
		
		return embeddableCartridges;
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
	
	public List<String> getCartridges() {
		List<String> cartridges = new LinkedList<String>();
		for (ICartridge cartridge : conn.getCartridges()) {
			cartridges.add(cartridge.getName());
		}
		
		return cartridges;
	}

	public List<String> getGearProfiles() {
		List<String> gearProfiles = new LinkedList<String>();
		IDomain domain = conn.getUser().getDefaultDomain();
		
		if (domain != null) {
			for (IGearProfile gearProfile : domain.getAvailableGearProfiles()) {
				gearProfiles.add(gearProfile.getName());
			}
		}
		
		return gearProfiles;
	}
	
	public List<String> getDomains() {
		List<String> domains = new LinkedList<String>();
		for (IDomain domain : conn.getUser().getDomains()) {
			domains.add(domain.getId());
		}
	
		return domains;
	}

	public static void main(String[] args) {
		OpenShiftV2Client client = new OpenShiftV2Client("broker.example.com", "demo", "demo");
		client.getOrCreateApp("app1", "smx", Arrays.asList("jbosseap-6"), "small");
	}
	
	static class ValidationResult {
		private boolean valid;
		private String message;
		
		public ValidationResult(boolean valid, String message) {
			super();
			this.valid = valid;
			this.message = message;
		}

		public boolean isValid() {
			return valid;
		}
		
		public String getMessage() {
			return message;
		}
	}
}
