package org.jenkinsci.plugins.openshift;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import hudson.model.Build;

import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.junit.Assert;

public class TestUtils {
	public static String getProp(String key) {
        if (System.getProperties().containsKey(key)) {
            return  System.getProperty(key);
        } else if (System.getenv().containsKey(key)) {
            return System.getenv(key);
        } 
        
        return null;
	}
	
	public static boolean gitRepoContainsFile(Repository repository, String file) throws IllegalStateException, IOException {
		ObjectId lastCommitId = repository.resolve(Constants.HEAD);

        // a RevWalk allows to walk over commits based on some filtering
        RevWalk revWalk = new RevWalk(repository);
        RevCommit commit = revWalk.parseCommit(lastCommitId);

        // and using commit's tree find the path
        RevTree tree = commit.getTree();
        
        TreeWalk treeWalk = new TreeWalk(repository);
        treeWalk.addTree(tree);
        treeWalk.setRecursive(true);
        treeWalk.setFilter(PathFilter.create(file));
        return treeWalk.next(); 
	}
	
	protected static void assertBuildLogContains(Build<?,?> build, String str) throws IOException {
		assertNotNull("Build shouldn't be null", build);
		assertNotNull("Pattern shouldn't be null", str);
		
		String log = FileUtils.readFileToString(build.getLogFile());
		System.out.println(log);
		assertNotNull("Build log shouldn't be null", log);
		assertTrue("Log should contain the pattern", log.contains(str));
	}
}
