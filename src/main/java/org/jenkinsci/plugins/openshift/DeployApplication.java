package org.jenkinsci.plugins.openshift;

import static org.apache.commons.io.FileUtils.copyFile;
import static org.apache.commons.io.FileUtils.copyFileToDirectory;
import static org.apache.commons.io.FileUtils.forceDelete;
import static org.jenkinsci.plugins.openshift.Utils.abort;
import static org.jenkinsci.plugins.openshift.Utils.copyURLToFile;
import static org.jenkinsci.plugins.openshift.Utils.findServer;
import static org.jenkinsci.plugins.openshift.Utils.getRootDeploymentFile;
import static org.jenkinsci.plugins.openshift.Utils.isEmpty;
import static org.jenkinsci.plugins.openshift.Utils.isURL;
import static org.jenkinsci.plugins.openshift.Utils.log;
import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLSession;

import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig.Host;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.jenkinsci.plugins.openshift.OpenShiftV2Client.DeploymentType;
import org.jenkinsci.plugins.openshift.OpenShiftV2Client.ValidationResult;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.openshift.client.IApplication;
import com.openshift.client.IHttpClient.ISSLCertificateCallback;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public class DeployApplication extends Builder implements BuildStep {
	private static final String WORK_DIR = "/openshift";

	private static final String BINARY_TAR_NAME = "app.tar.gz";

	private static final String BINARY_DEPLOY_CMD = "oo-binary-deploy";

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

	@DataBoundConstructor
	public DeployApplication(String serverName, String appName, String cartridges, String domain, String gearProfile, String deploymentPackage,
			String environmentVariables, Boolean autoScale, DeploymentType deploymentType) {
		this.serverName = serverName;
		this.appName = appName;
		this.cartridges = cartridges;
		this.domain = domain;
		this.gearProfile = gearProfile;
		this.deploymentPackage = deploymentPackage;
		this.environmentVariables = environmentVariables;
		this.autoScale = autoScale;
		this.deploymentType = deploymentType;
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

			deployToApp(deployments, app, build, listener);

		} catch (AbortException e) {
			throw e;

		} catch (Exception e) {
			abort(listener, e.getMessage());
		}

		return true;
	}

	private void deployToApp(List<String> deployments, IApplication app, AbstractBuild<?, ?> build, BuildListener listener) throws AbortException,
			GitAPIException, IOException {
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

	private void doBinaryDeploy(String deployment, IApplication app, AbstractBuild<?, ?> build, final BuildListener listener) throws IOException,
			AbortException {
		if (!app.getDeploymentType().equalsIgnoreCase(DeploymentType.BINARY.name())) {
			app.setDeploymentType(DeploymentType.BINARY.toString().toLowerCase());
		}

		try {
			log(listener, "Deployging " + deployment);
			log(listener, "Starting SSH connection to " + app.getSshUrl());
			URI uri = new URI(app.getSshUrl());

			JSch jsch = new JSch();

			// confgure logger
			JSch.setLogger(new com.jcraft.jsch.Logger() {
				public void log(int level, String message) {
					try {
						if (isEnabled(level)) {
							Utils.log(listener, "SSH: " + message);
						}
					} catch (AbortException e) {
					}
				}

				public boolean isEnabled(int level) {
					return level >= INFO;
				}
			});

			// add ssh keys
			String sshPrivateKey = Utils.getSSHPrivateKey();
			jsch.addIdentity(sshPrivateKey);
			LOG.log(Level.FINE, "Using private SSH keys " + sshPrivateKey);

			Session session = jsch.getSession(uri.getUserInfo(), uri.getHost());
			session.setConfig("StrictHostKeyChecking", "no");
			session.connect(10000);

			Channel channel = session.openChannel("exec");
			((ChannelExec) channel).setErrStream(listener.getLogger());
			((ChannelExec) channel).setOutputStream(listener.getLogger());
			((ChannelExec) channel).setInputStream(new FileInputStream(getDeploymentFile(build, deployment)));
			((ChannelExec) channel).setCommand(BINARY_DEPLOY_CMD);

			channel.connect();

			while (!channel.isEOF()) {
			}

			channel.disconnect();
			session.disconnect();
			
			log(listener, "Application deployed to " + app.getApplicationUrl());

		} catch (JSchException e) {
			throw new AbortException("Failed to deploy the binary. " + e.getMessage());
		} catch (URISyntaxException e) {
			throw new AbortException(e.getMessage());
		}
	}

	private File getDeploymentFile(AbstractBuild<?, ?> build, String deployment) throws IOException {
		if (isURL(deployment)) {
			File baseDir = createBaseDir(build);
			File dest = new File(baseDir.getAbsolutePath() + "/" + BINARY_TAR_NAME);
			LOG.fine("Downloading the deployment binary to '" +  dest.getAbsolutePath() + "'");
			copyURLToFile(new URL(deployment), dest, 10000, 10000);
			return dest;
			
		} else {
			return new File(deployment);
		}
	}

	private void doGitDeploy(List<String> deployments, IApplication app, AbstractBuild<?, ?> build, BuildListener listener)
			throws AbortException, GitAPIException, IOException {
		File baseDir = createBaseDir(build);

		// clone repo
		log(listener, "Cloning '" + app.getName() + "' [" + app.getGitUrl() + "] to " + baseDir.getAbsolutePath());
		SshSessionFactory.setInstance(new JschConfigSessionFactory() {
			@Override
			protected void configure(Host hc, Session session) {
				session.setConfig("StrictHostKeyChecking", "no");
			}
		});
		Git git = Git.cloneRepository().setURI(app.getGitUrl()).setDirectory(baseDir).call();

		// clean git repo
		File[] removeList = baseDir.listFiles();
		for (File fileToRemove : removeList) {
			if (!fileToRemove.getName().equals(".git") && !fileToRemove.getName().equals(".openshift")) {
				log(listener, "Deleting '" + fileToRemove.getName() + "'");
				forceDelete(fileToRemove);
			}
		}

		// copy deployment
		copyDeploymentPackages(listener, baseDir, deployments);

		// add directories
		git.add().addFilepattern("webapps").call();

		git.add().addFilepattern("deployments").call();

		// commit changes
		log(listener, "Commiting repo");
		git.commit().setAll(true).setMessage("deployment added for Jenkins build #" + build.getNumber()).call();

		log(listener, "Pushing to upstream");
		PushCommand pushCommand = git.push();
		pushCommand.setProgressMonitor(new TextProgressMonitor(new OutputStreamWriter(System.out)));
		pushCommand.call();
	}

	private File createBaseDir(AbstractBuild<?, ?> build) throws IOException {
		File baseDir = new File(build.getWorkspace() + WORK_DIR);
		if (baseDir.exists()) {
			FileUtils.deleteDirectory(baseDir);
		}
		
		baseDir.mkdirs();
		return baseDir;
	}

	private void copyDeploymentPackages(BuildListener listener, File baseDir, List<String> deployments) throws AbortException, IOException {
		File dest = null;
		if (cartridges.contains("jbossews")) {
			dest = new File(baseDir.getAbsoluteFile() + "/webapps"); // tomcat
		} else {
			dest = new File(baseDir.getAbsoluteFile() + "/deployments"); // jboss/wildfly
		}

		if (deployments.size() == 1) {
			File destFile = getRootDeploymentFile(dest, deployments.get(0));

			if (isURL(deployments.get(0))) {
				log(listener, "Downloading the deployment package to '" + destFile.getName() + "'");
				copyURLToFile(new URL(deployments.get(0)), destFile, 10000, 10000);
			} else {
				log(listener, "Copying the deployment package '" + FilenameUtils.getName(deployments.get(0)) + "' to '" + destFile.getName() + "'");
				copyFile(new File(deployments.get(0)), destFile);
			}
		} else {
			for (String deployment : deployments) {
				log(listener, "Copying '" + FilenameUtils.getName(deployment) + "' to '" + dest.getName() + "'");
				copyFileToDirectory(new File(deployment), dest);
			}
		}
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
			File dir = new File(build.getWorkspace() + "/" + deploymentPackage);
			if (!dir.exists()) {
				abort(listener, "Directory '" + dir.getAbsolutePath() + "' doesn't exist. No deployments found!");
			}

			File[] deploymentFiles = dir.listFiles(new FilenameFilter() {
				public boolean accept(File dir, String name) {
					if (deploymentType == DeploymentType.BINARY) {
						return name.toLowerCase().endsWith(".tar.gz");
					} else {
						return name.toLowerCase().endsWith(".ear") || name.toLowerCase().endsWith(".war");
					}
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

	public String getDeploymentPackage() {
		return deploymentPackage;
	}

	public String getEnvironmentVariables() {
		return environmentVariables;
	}

	public Boolean autoScale() {
		return autoScale;
	}

	public DeploymentType getDeployType() {
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

		private String publicKeyPath;

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
		
		public ListBoxModel doFillDeploymentTypeItems() {
			ListBoxModel items = new ListBoxModel();
			items.add(DeploymentType.GIT.name(), DeploymentType.GIT.name());
			items.add(DeploymentType.BINARY.name(), DeploymentType.BINARY.name());

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
