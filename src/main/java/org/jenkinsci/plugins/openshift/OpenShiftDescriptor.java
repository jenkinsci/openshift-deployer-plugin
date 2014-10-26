package org.jenkinsci.plugins.openshift;

import static org.jenkinsci.plugins.openshift.Util.findServer;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.File;
import java.io.IOException;
import java.util.List;

import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import org.jenkinsci.plugins.openshift.OpenShiftV2Client.ValidationResult;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */

@Extension
public class OpenShiftDescriptor extends BuildStepDescriptor<Builder> {
    private List<OpenShiftServer> servers;
    private final String DEFAULT_PUBLICKEY_PATH = System.getProperty("user.home") + "/.ssh/id_rsa.pub";
    
    public OpenShiftDescriptor() {
        super(OpenShiftBuilder.class);
        load();
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json)
            throws FormException {
        Object s = json.get("servers");
        if (!JSONNull.getInstance().equals(s)) {
            servers = req.bindJSONToList(OpenShiftServer.class, s);
        } else {
        	servers = null;
        }
        save();
        return super.configure(req, json);
    }

    @Override
    public String getDisplayName() {
        return "Deploy to OpenShift";
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
        return true;
    }

	public List<OpenShiftServer> getServers() {
		return servers;
	}
	
	public String getPublicKeyPath() {
		return DEFAULT_PUBLICKEY_PATH;
	}

	public FormValidation doCheckLogin(
			@QueryParameter("brokerAddress") final String brokerAddress,
	        @QueryParameter("username") final String username, 
	        @QueryParameter("password") final String password) {
		OpenShiftV2Client client = new OpenShiftV2Client(brokerAddress, username, password);
		ValidationResult result = client.validate();
		if (result.isValid()) {
			return FormValidation.ok("Success");
		} else {
			return FormValidation.error(result.getMessage());
		}
	}
	
	public FormValidation doUploadSSHKeys(
			@QueryParameter("brokerAddress") final String brokerAddress,
	        @QueryParameter("username") final String username, 
	        @QueryParameter("password") final String password,
	        @QueryParameter("publicKeyPath") final String publicKeyPath) {
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
			return FormValidation.error(e.getMessage());
		}
	}

	public ListBoxModel doFillServerNameItems() {
		ListBoxModel items = new ListBoxModel();

		if (servers != null) {
			for (OpenShiftServer server : servers) {
				items.add(server.getName(), server.getName());
			}
		}

		return items;
	}
	
	public ListBoxModel doFillGearProfileItems(@QueryParameter("serverName") final String serverName) {
		ListBoxModel items = new ListBoxModel();
		OpenShiftServer server = findServer(serverName, servers);
		OpenShiftV2Client client = new OpenShiftV2Client(server.getBrokerAddress(), server.getUsername(), server.getPassword());
		for (String gearProfile : client.getGearProfiles()) {
			items.add(gearProfile, gearProfile);
		}
		
		return items;
	}
	
	public ListBoxModel doFillDomainItems(@QueryParameter("serverName") final String serverName) {
		ListBoxModel items = new ListBoxModel();
		OpenShiftServer server = findServer(serverName, servers);
		OpenShiftV2Client client = new OpenShiftV2Client(server.getBrokerAddress(), server.getUsername(), server.getPassword());
		for (String domain : client.getDomains()) {
			items.add(domain, domain);
		}
		
		return items;
	}
}
