package org.jenkinsci.plugins.openshift;

import java.io.IOException;

import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

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
}
