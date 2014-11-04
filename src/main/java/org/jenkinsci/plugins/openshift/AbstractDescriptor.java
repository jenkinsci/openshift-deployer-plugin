package org.jenkinsci.plugins.openshift;

import static org.jenkinsci.plugins.openshift.Utils.findServer;

import org.kohsuke.stapler.QueryParameter;

import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;

public abstract class AbstractDescriptor extends BuildStepDescriptor<Builder> {
	public AbstractDescriptor() {
		super();
	}

	public AbstractDescriptor(Class<? extends Builder> clazz) {
		super(clazz);
	}

	@Override
	public boolean isApplicable(Class<? extends AbstractProject> jobType) {
		return true;
	}
	
	public ListBoxModel doFillDomainItems(@QueryParameter("serverName") final String serverName) {
		ListBoxModel items = new ListBoxModel();
		Server server = findServer(serverName);
		OpenShiftV2Client client = new OpenShiftV2Client(server.getBrokerAddress(), server.getUsername(), server.getPassword());
		for (String domain : client.getDomains()) {
			items.add(domain, domain);
		}
		
		return items;
	}
	
	public ListBoxModel doFillServerNameItems() {
		ListBoxModel items = new ListBoxModel();

		for (Server server : Utils.getServers()) {
			items.add(server.getName(), server.getName());
		}

		return items;
	}
}
