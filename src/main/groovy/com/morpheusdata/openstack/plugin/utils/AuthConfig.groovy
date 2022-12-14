package com.morpheusdata.openstack.plugin.utils

public class AuthConfig {
	public String identityUrl
	public String identityVersion
	public String username
	public String password
	public Long cloudId
	public Map cloudConfig
	public String regionCode
	public String projectId
	public String projectName
	public String domainId
	public String sharedFileSystemUrl
	public String sharedFileSystemVersion
	public String networkUrl
	public String networkVersion
	public String networkPath
	public String loadBalancerUrl
	public Object token
	public String apiProjectId
	public String apiUserId
	public String expires
	public Boolean expireToken // whether the token should be expired
	public TokenResult tokenResult // The token for the auth config specified in the other properties

	@Override
	public String toString(){
		"cloudId: ${cloudId}, projectId: ${projectId}, projectName; ${projectName}, identityUrl: ${identityUrl}, identityVersion: ${identityVersion}, domainId: ${domainId}, cloudConfig: ${cloudConfig}, apiUserId: ${apiUserId}, expires: ${expires}"
	}
}
