package org.jenkinsci.plugins.openshift;

import java.io.Serializable;

import org.kohsuke.stapler.DataBoundConstructor;


public class Profile implements Serializable {
    
    private String name;
    
    private String pulpadmin;
    
    private String rpmsign;

     private String repoId;

    private String username;

    private String password;
    
    private Boolean showStdOut;
    
    private Boolean showCommandLine;
    
    private String versionStrategy;
    
    private Boolean updateDependencies;

    
    @DataBoundConstructor
    public Profile(String name, String pulpadmin, String rpmsign, String server,
            String repoId, String username, String password, Boolean showStdOut, 
            Boolean showCommandLine, String versionStrategy, Boolean updateDependencies) {
        this.name = name;
        this.pulpadmin = pulpadmin;
        this.rpmsign = rpmsign;
        this.repoId = repoId;
        this.username = username;
        this.password = password;
        this.showStdOut = showStdOut;
        this.showCommandLine = showCommandLine;
        this.versionStrategy = versionStrategy;
        this.updateDependencies = updateDependencies;
    }

    public String getName() {
        return name;
    }

    public String getPulpadmin() {
        return pulpadmin;
    }

    public String getRpmsign() {
        return rpmsign;
    }

    public String getRepoId() {
        return repoId;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public Boolean getShowStdOut() {
        if(showStdOut == null) {
            return false;
        }
        return showStdOut;
    }
    
    public Boolean getShowCommandLine() {
        if(showCommandLine == null) {
            return false;
        }
        return showCommandLine;
    }

    public String getVersionStrategy() {
        if(versionStrategy == null) {
            return "default";
        }
        return versionStrategy;
    }  
    
    public Boolean getUpdateDependencies() {
        if(updateDependencies == null) {
            return false;
        }
        return updateDependencies;
    }

}
