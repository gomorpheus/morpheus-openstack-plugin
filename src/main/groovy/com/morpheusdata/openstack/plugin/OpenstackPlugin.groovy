package com.morpheusdata.openstack.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import groovy.util.logging.Slf4j

@Slf4j
class OpenstackPlugin extends Plugin {

	private String cloudProviderCode

	@Override
	String getCode() {
		return 'morpheus-openstack-plugin'
	}

	@Override
	void initialize() {
		this.setName('Openstack Plugin')
		def openstackProvision = new OpenstackProvisionProvider(this, this.morpheus)
		def openstackCloud = new OpenstackCloudProvider(this, this.morpheus)
		cloudProviderCode = openstackCloud.code
		def openstackOptionSourceProvider = new OpenstackOptionSourceProvider(this, morpheus)

		this.pluginProviders.put(openstackProvision.code, openstackProvision)
		this.pluginProviders.put(openstackCloud.code, openstackCloud)
		this.pluginProviders.put(openstackOptionSourceProvider.code, openstackOptionSourceProvider)
	}

	@Override
	void onDestroy() {

	}

	def MorpheusContext getMorpheusContext() {
		this.morpheus
	}

	def OpenstackCloudProvider getCloudProvider() {
		this.getProviderByCode(cloudProviderCode)
	}

	/**
	 * Builds up the AuthConfig object (excluding the TokenResults)
	 * @param cloud
	 * @return
	 */
	AuthConfig getAuthConfig(Cloud cloud, skipAdditionalConfig=false) {
		log.debug "getAuthConfig: ${cloud}"
		def identityUrl = OpenStackComputeUtility.parseEndpoint(cloud.serviceUrl)
		def identityVersion = OpenStackComputeUtility.parseEndpointVersion(cloud.serviceUrl, false, 'v3')

		AuthConfig rtn = new AuthConfig([
				identityUrl    : identityUrl,
				identityVersion: identityVersion,
				domainId       : cloud.getConfigProperty('domainId'),
				username       : null,
				password       : null,
				cloudId        : cloud.id,
				cloudConfig    : cloud.configMap,
				projectId      : cloud.getConfigProperty('projectId'),
				projectName    : cloud.getConfigProperty('projectName'),
				regionCode     : cloud.regionCode
		])

		if(!cloud.accountCredentialLoaded) {
			AccountCredential accountCredential
			try {
				accountCredential = this.morpheus.cloud.loadCredentials(cloud.id).blockingGet()
			} catch(e) {
				// If there is no credential on the cloud, then this will error
				// TODO: Change to using 'maybe' rather than 'blockingGet'?
			}
			cloud.accountCredentialLoaded = true
			cloud.accountCredentialData = accountCredential?.data
		}

		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('username')) {
			rtn.username = cloud.accountCredentialData['username']
		} else {
			rtn.username = cloud.serviceUsername
		}
		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
			rtn.password = cloud.accountCredentialData['password']
		} else {
			rtn.password = cloud.servicePassword
		}

		if(!skipAdditionalConfig) {
			//network
			setNetworkApiConfig(rtn)
			// Shared File System
			setSharedFileSystemApiConfig(rtn)
		}

		rtn
	}

	private setNetworkApiConfig(AuthConfig authConfig) {
		def networkApi = OpenStackComputeUtility.getOpenstackNetworkUrl(authConfig)
		if(networkApi) {
			authConfig.networkUrl = OpenStackComputeUtility.parseEndpoint(networkApi)
			authConfig.networkVersion = OpenStackComputeUtility.parseEndpointVersion(networkApi, false, 'v2.0')
			authConfig.networkPath = '/' + authConfig.networkVersion
		}
	}

	private setSharedFileSystemApiConfig(AuthConfig authConfig) {
		def sharedFileSystemApi
		try {
			sharedFileSystemApi = OpenStackComputeUtility.getOpenstackSharedFileSystemUrl(authConfig)
		} catch(e) {}

		if(!sharedFileSystemApi) {
			sharedFileSystemApi = OpenStackComputeUtility.getStaticSharedFileSystemUrl(authConfig)
		}
		if(sharedFileSystemApi) {
			authConfig.sharedFileSystemUrl = sharedFileSystemApi
			authConfig.sharedFileSystemVersion = OpenStackComputeUtility.getOpenstackSharedFileSystemVersion(authConfig) ?: 'v2'
		}
	}

}
