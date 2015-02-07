package org.jenkinsci.plugins.openshift;

import static java.util.Collections.singletonList;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.jenkinsci.plugins.openshift.util.Utils.abort;
import static org.jenkinsci.plugins.openshift.util.Utils.copyDeploymenstToMaster;
import static org.jenkinsci.plugins.openshift.util.Utils.copyFileFromSlaveToMaster;
import static org.jenkinsci.plugins.openshift.util.Utils.findServer;
import static org.jenkinsci.plugins.openshift.util.Utils.isURL;
import static org.jenkinsci.plugins.openshift.util.Utils.log;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.net.ssl.SSLSession;

import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jenkinsci.plugins.openshift.OpenShiftV2Client.DeploymentType;
import org.jenkinsci.plugins.openshift.OpenShiftV2Client.ValidationResult;
import org.jenkinsci.plugins.openshift.util.JenkinsLogger;
import org.jenkinsci.plugins.openshift.util.Utils;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.openshift.client.IApplication;
import com.openshift.client.IHttpClient.ISSLCertificateCallback;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public class DeployApplication extends Builder implements BuildStep {
	private static final String WORK_DIR = "/openshift-deployer-workdir";

	private static final Logger LOG = Logger.getLogger(DeployApplication.class.getName());

	private String serverName;
	private String cartridges;
	private String domain;
	private String gearProfile;
	private String appName;
	private String deploymentPackage;
	private String environmentVariables;
	private Boolean autoScale;
	private DeploymentType deploymentType = DeploymentType.GIT;
	private String openshiftDirectory;

	@DataBoundConstructor
	public DeployApplication(String serverName, String appName, String cartridges, String domain, String gearProfile, String deploymentPackage,
			String environmentVariables, Boolean autoScale, DeploymentType deploymentType, String openshiftDirectory) {
		this.serverName = serverName;
		this.appName = appName;
		this.cartridges = cartridges;
		this.domain = domain;
		this.gearProfile = gearProfile;
		this.deploymentPackage = deploymentPackage;
		this.environmentVariables = environmentVariables;
		this.autoScale = autoScale;
		this.deploymentType = deploymentType;
		this.openshiftDirectory = openshiftDirectory;
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

		if (isEmpty(deploymentPackage)) {
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
					abort(listener, "Specify the user domain. " + domains.size() + " domains found on the account.");
				} else if (domains.isEmpty()) {
					abort(listener, "No domains exist. Create a domain first.");
				}

				targetDomain = domains.get(0);
			}

			IApplication app;
			if (isEmpty(environmentVariables)) {
				app = client.getOrCreateApp(appName, targetDomain, Arrays.asList(cartridges.split(" ")), gearProfile, null, autoScale);
			} else {
				Map<String, String> mapOfEnvironmentVariables = new HashMap<String, String>();
				for (String environmentVariable : Arrays.asList(environmentVariables.split(" "))) {
					if (environmentVariable.contains("=")) {
						String[] parts = environmentVariable.split("=");
						mapOfEnvironmentVariables.put(parts[0], parts[1]);
					} else {
						abort(listener, "Invalid environment variable: " + environmentVariable);
					}
				}
				app = client.getOrCreateApp(appName, targetDomain, Arrays.asList(cartridges.split(" ")), gearProfile, mapOfEnvironmentVariables, autoScale);
			}

			deploy(deployments, app, build, listener);

		} catch (Exception e) {
			abort(listener, e);
		}

		return true;
	}

	private void deploy(List<String> deployments, IApplication app, AbstractBuild<?, ?> build, BuildListener listener)
			throws GitAPIException, IOException {
		if (deployments == null || deployments.isEmpty()) {
			abort(listener, "Deployment package list is empty.");
		}

		if (deploymentType == DeploymentType.BINARY) {
			doBinaryDeploy(deployments.get(0), app, build, listener);
		} else {
			doGitDeploy(deployments, app, build, listener);
		}

		log(listener, "Application deployed to " + app.getApplicationUrl());
	}

	private void doBinaryDeploy(String deployment, IApplication app, AbstractBuild<?, ?> build, final BuildListener listener) throws IOException {
		// reconfigure app for binary deploy
		if (!app.getDeploymentType().equalsIgnoreCase(DeploymentType.BINARY.name())) {
			app.setDeploymentType(DeploymentType.BINARY.toString().toLowerCase());
		}
		
		// copy deployments to master from the slave node or URLs
		File baseDir = createBaseDirOnMaster(build);
		List<String> localDeployments = Utils.copyDeploymenstToMaster(build, listener, singletonList(deployment), baseDir, deploymentType);

		// deploy
		SSHClient sshClient = new SSHClient(app);
		sshClient.setLogger(new JenkinsLogger(listener));
		sshClient.setSSHPrivateKey(Utils.getSSHPrivateKey());
		sshClient.deploy(new File(localDeployments.get(0)));
	}

	private void doGitDeploy(List<String> deployments, IApplication app, AbstractBuild<?, ?> build, BuildListener listener)
			throws GitAPIException, IOException {
		File baseDir = createBaseDirOnMaster(build);
		String commitMsg = "deployment added for Jenkins build " + build.getDisplayName() + "#" + build.getNumber();
		
		// set deployment dir based on cartridge type
		String relativeDeployPath;
		if (cartridges.contains("jbossews")) {
			relativeDeployPath = "/webapps"; // tomcat
		} else {
			relativeDeployPath = "/deployments"; // jboss/wildfly
		}

		// set .openshift dir
		String dotOpenshiftDir = null;
		if(!isEmpty(openshiftDirectory)) {
			if (new File(openshiftDirectory).isAbsolute()) {
				dotOpenshiftDir = openshiftDirectory;
			} else {
				dotOpenshiftDir = build.getWorkspace() + File.separator + openshiftDirectory;
			}
			
			if (!Utils.runingOnMaster()) {
				String localDotOpenShiftDir = baseDir + File.separator + ".openshift";
				copyFileFromSlaveToMaster(build, dotOpenshiftDir, localDotOpenShiftDir);
				dotOpenshiftDir = localDotOpenShiftDir;
			}
		}
		
		// copy deployments to master from the slave node or URL
		List<String> localDeployments = copyDeploymenstToMaster(build, listener, deployments, baseDir, deploymentType);
		
		// set git base dir
		File gitBaseDir = new File(baseDir, "git");
		
		// git deploy
		GitClient gitClient = new GitClient(app);
		gitClient.setLogger(new JenkinsLogger(listener));
		gitClient.deploy(localDeployments, gitBaseDir, relativeDeployPath, commitMsg, dotOpenshiftDir);
	}

	private File createBaseDirOnMaster(AbstractBuild<?, ?> build) throws IOException {
		String baseDirPath = Utils.getBuildWorkspaceOnMaster(build) + WORK_DIR;
		File baseDir = new File(baseDirPath);
		if (baseDir.exists()) {
			FileUtils.deleteDirectory(baseDir);
		}
		
		baseDir.mkdirs();
		return baseDir;
	}

	private List<String> findDeployments(AbstractBuild<?, ?> build, BuildListener listener) throws AbortException {
		List<String> deployments = new ArrayList<String>();

		if (isURL(deploymentPackage)) {
			try {
				deployments.add(TokenMacro.expand(build, listener, deploymentPackage));
			} catch (Exception e) {
				throw new AbortException(e.getMessage());
			}

		} else {
			VirtualChannel channel = build.getWorkspace().getChannel();
			String filePath = null;
			if (new File(deploymentPackage).isAbsolute()) {
				filePath = deploymentPackage;
			} else {
				filePath = build.getWorkspace() + File.separator + deploymentPackage;
			}
			
			FilePath dir = new FilePath(channel, filePath);

			LOG.fine("Using hudson.FilePath for resolving content for deploy:\n    Channel: " + channel + " \n    FilePath: " + filePath);

			try {
				if (!dir.exists()) {
					abort(listener, "Directory '" + dir + "' doesn't exist. No deployments found!");
				}
			} catch (Exception e) {
				throw new AbortException(e.getMessage());
			}

			try {
				if (dir.isDirectory()) {
					String includes = null;
					if (deploymentType == DeploymentType.BINARY) {
						includes = "*.tar.gz";
					} else {
						includes = "*.ear,*.war";
					}

					FilePath[] deploymentFiles = dir.list(includes);
					for (FilePath file : deploymentFiles) {
						deployments.add(file.getRemote());
						
						LOG.fine("Adding " + file.getRemote() + " to deployment list");
					}
				} else if (!dir.isDirectory() 
						&& (dir.getRemote().toLowerCase().endsWith(".ear") 
								|| dir.getRemote().toLowerCase().endsWith(".war"))) { // Handle single Files
					deployments.add(dir.getRemote());
					
					LOG.fine("Adding " + dir.getRemote() + " to the deployment list");
				}
				
			} catch (Exception e) {
				throw new AbortException(e.getMessage());
			}
		}

		// If we cannot find any deployments we should abort to avoid NullPointers
		if(deployments.isEmpty())
		{
			abort(listener, "No Deployments found! (configuredValue: " + deploymentPackage + ")");
		}

		return deployments;
	}

	public boolean isBinaryDeploy() {
		return deploymentType ==  DeploymentType.BINARY;
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

	public String getDeploymentPackage() {
		return deploymentPackage;
	}

	public String getEnvironmentVariables() {
		return environmentVariables;
	}

	public Boolean autoScale() {
		return autoScale;
	}

	public DeploymentType getDeploymentType() {
		return deploymentType;
	}

	public static class TrustingISSLCertificateCallback implements ISSLCertificateCallback {
		public boolean allowCertificate(java.security.cert.X509Certificate[] certs) {
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

		public String publicKeyPath;

		public DeployApplicationDescriptor() {
			super(DeployApplication.class);
			load();
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
			Object s = json.get("servers");
			if (!JSONNull.getInstance().equals(s)) {
				servers = req.bindJSONToList(Server.class, s);
			} else {
				servers = null;
			}

			publicKeyPath = json.getString("publicKeyPath");
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
			return isEmpty(publicKeyPath) ? DEFAULT_PUBLICKEY_PATH : publicKeyPath;
		}

		public FormValidation doCheckLogin(@QueryParameter("brokerAddress") final String brokerAddress, @QueryParameter("username") final String username,
				@QueryParameter("password") final String password) {
			OpenShiftV2Client client = new OpenShiftV2Client(brokerAddress, username, password);
			ValidationResult result = client.validate();
			if (result.isValid()) {
				return FormValidation.ok("Success");
			} else {
				return FormValidation.error(result.getMessage());
			}
		}

		public FormValidation doUploadSSHKeys(@QueryParameter("brokerAddress") final String brokerAddress, @QueryParameter("username") final String username,
				@QueryParameter("password") final String password, @QueryParameter("publicKeyPath") final String publicKeyPath) {
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

		public FormValidation doCheckPublicKeyPath(@QueryParameter("publicKeyPath") String path) {
			File file = new File(path);

			if (!file.exists()) {
				return FormValidation.error("Public key doesn't exist at " + path);
			}

			return FormValidation.ok();
		}
	}
}
