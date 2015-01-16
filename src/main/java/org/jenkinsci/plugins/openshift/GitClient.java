package org.jenkinsci.plugins.openshift;

import com.jcraft.jsch.Session;
import com.openshift.client.IApplication;
import hudson.AbortException;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig.Host;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.jenkinsci.plugins.openshift.util.Logger;
import org.jenkinsci.plugins.openshift.util.Utils;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.util.List;

import static org.apache.commons.io.FileUtils.*;
import static org.jenkinsci.plugins.openshift.util.Utils.copyURLToFile;
import static org.jenkinsci.plugins.openshift.util.Utils.*;

/**
 * @author Siamak Sadeghianfar <ssadeghi@redhat.com>
 */
public class GitClient {
	private Logger log = Logger.NOOP;
	
	private IApplication app;
	
	public GitClient(IApplication app) {
		super();
		this.app = app;
	}
	
	public void setLogger(Logger log) {
		this.log = log;
	}

	
	public void deploy(List<String> deployments, File workingCopyDir, String relativeDeployDir) 
			throws IOException, GitAPIException {
		deploy(deployments, workingCopyDir, relativeDeployDir, "", "");
	}

	/**
	 * Deploy the deployment units through Git.
	 *
	 * @param deployments list of packages to be deployed
	 * @param workingCopyDir where git repo will be cloned
	 * @param relativeDeployDir the relative path in the working copy where the deployment packages should be copied
	 * @param commitMsg git commit message
	 * @param openshiftDirectory The configured value that contains an openshift directory structure.
	 *                           This value should be the Absolute Path to the directory. The Parameter can
	 *                           also be null or empty
	 *
	 * @throws IOException
	 * @throws GitAPIException
	 * @throws TransportException
	 * @throws InvalidRemoteException
	 */
	public void deploy(List<String> deployments, File workingCopyDir, String relativeDeployDir, String commitMsg, String openshiftDirectory) throws IOException, GitAPIException {

		// Sanity Check
		if(openshiftDirectory != null && !StringUtils.isEmpty(openshiftDirectory))
		{
			if(!openshiftDirectory.startsWith(File.separator))
				throw new IllegalArgumentException("openshiftDirectory is not null or empty and is not an absolute path.");
		}


		// clone repo
		log.info("Cloning '" + app.getName() + "' [" + app.getGitUrl() + "] to " + workingCopyDir);
		SshSessionFactory.setInstance(new JschConfigSessionFactory() {
			@Override
			protected void configure(Host hc, Session session) {
				session.setConfig("StrictHostKeyChecking", "no");
			}
		});
		Git git = Git.cloneRepository().setURI(app.getGitUrl()).setDirectory(workingCopyDir).call();

		// clean git repo
		File[] removeList = workingCopyDir.listFiles();
		for (File fileToRemove : removeList) {
			if (!fileToRemove.getName().equals(".git") && !fileToRemove.getName().equals(".openshift")) {
				log.info("Deleting '" + fileToRemove.getName() + "'");
				forceDelete(fileToRemove);
			}
		}

		// copy deployment
		File dest = new File(workingCopyDir.getAbsoluteFile() + relativeDeployDir);
		copyDeploymentPackages(deployments, dest);

		// Handle OpenShift Directory
		File dotOpenshiftSource = null;

		if(!StringUtils.isEmpty(openshiftDirectory))
		{
			Boolean correctLayout = Utils.validateOpenshiftDirectory(openshiftDirectory);
			if (!openshiftDirectory.endsWith("openshift")) {
                // Examine the current directory if it contains an openshift or .openshift directory
                File directory = new File(openshiftDirectory);

                File[] dirContents = directory.listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        if (name.equals(".") || name.equals(".."))
                            return false;
                        else
                            return true;
                    }
                });

                for (File file : dirContents)
                {
                    if(file.getName().endsWith("openshift")) {
                        dotOpenshiftSource = file;
                        break;
                    }

                }

            }
            else
            {
				dotOpenshiftSource = new File(openshiftDirectory); // Absolute Path
			}

			for (File source : dotOpenshiftSource.listFiles())
                copyDirectoryToDirectory(source, new File(workingCopyDir + File.separator + ".openshift"));
		}

		// add directories
		git.add().addFilepattern(".").call();

		// commit changes
		log.info("Committing repo");
		git.commit().setAll(true).setMessage(commitMsg).call();

		log.info("Pushing to upstream");
		PushCommand pushCommand = git.push();
		pushCommand.setProgressMonitor(new TextProgressMonitor(new OutputStreamWriter(System.out)));
		Iterable<PushResult> pushResults = pushCommand.call();
		for(PushResult result : pushResults)
			System.out.println(result.toString());
	}

	private void copyDeploymentPackages(List<String> deployments, File dest) throws AbortException, IOException {
		if (deployments.size() == 1) {
			File destFile = createRootDeploymentFile(dest, deployments.get(0));

			if (isURL(deployments.get(0))) {
				log.info("Downloading the deployment package to '" + destFile.getName() + "'");
				copyURLToFile(new URL(deployments.get(0)), destFile, 10000, 10000);
			} else {
				log.info("Copying the deployment package '" + FilenameUtils.getName(deployments.get(0)) + "' to '" + destFile.getName() + "'");
				copyFile(new File(deployments.get(0)), destFile);
			}
		} else {
			for (String deployment : deployments) {
				log.info("Copying '" + FilenameUtils.getName(deployment) + "' to '" + dest.getName() + "'");
				copyFileToDirectory(new File(deployment), dest);
			}
		}
	}

}
