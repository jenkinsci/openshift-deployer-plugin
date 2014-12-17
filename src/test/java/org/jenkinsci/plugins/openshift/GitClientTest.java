package org.jenkinsci.plugins.openshift;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import com.openshift.client.IApplication;

@RunWith(MockitoJUnitRunner.class)
public class GitClientTest {
	@Mock
	private IApplication app;

	@Test
	public void deploy() throws Exception {

		Repository repository = createNewRepository();
		// mock
		Mockito.when(app.getName()).thenReturn("testapp");
		Mockito.when(app.getGitUrl()).thenReturn(repository.getDirectory().getAbsolutePath());

		// deploy
		String deployment = ClassLoader.getSystemResource("deployment/app.war").getFile();
		GitClient gitClient = new GitClient(app);
		gitClient.deploy(Arrays.asList(deployment), createPath("TestWorkingCopy"), "/deployments");
		
		// verify
		TestUtils.gitRepoContainsFile(repository, "deployment/ROOT.war");
	}

	private Repository createNewRepository() throws IOException {
		// prepare a new folder
		File localPath = createPath("TestGitRepository");

		// create the directory
		Repository repository = FileRepositoryBuilder.create(new File(localPath, ".git"));
		repository.create();

		return repository;
	}

	private File createPath(String path) throws IOException {
		File file = File.createTempFile(path, "");
		file.delete();
		return file;
	}
}
