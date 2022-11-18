package org.jenkinsci.plugins.openshift;

import com.openshift.client.IApplication;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

@RunWith(MockitoJUnitRunner.class)
public class GitClientTest {
	@Mock
	private IApplication app;

	private static Repository repository;


	@BeforeClass
	public static void globalTestSetup() throws IOException {
		// prepare a new folder
		File localPath = createPath("TestGitRepository");

		// create the directory
		repository = FileRepositoryBuilder.create(new File(localPath, ".git"));
		repository.create();
	}

	@Before
	public void generalMockSetup() throws Exception {
		// mock
		Mockito.when(app.getName()).thenReturn("testapp");
		Mockito.when(app.getGitUrl()).thenReturn(repository.getDirectory().getAbsolutePath());
	}

	@Test
	public void deploy() throws Exception {
		// deploy
		String deployment = ClassLoader.getSystemResource("deployment/app.war").getFile();
		GitClient gitClient = new GitClient(app);
		gitClient.deploy(Arrays.asList(deployment), createPath("TestWorkingCopy"), "/deployments");

		// verify
		TestUtils.gitRepoContainsFile(repository, "deployment/ROOT.war");
	}

	@Test
	public void testJava7Marker() throws Exception {

		// deploy
		String deployment = ClassLoader.getSystemResource("deployment/app.war").getFile();
		GitClient gitClient = new GitClient(app);
		gitClient.deploy(Arrays.asList(deployment), createPath("TestWorkingCopy"), "/deployments");

		// verify
		TestUtils.gitRepoContainsFile(repository, "deployment/ROOT.war");
	}

	private static File createPath(String path) throws IOException {
		File file = Files.createTempFile(path, "").toFile();
		file.delete();
		return file;
	}
}
