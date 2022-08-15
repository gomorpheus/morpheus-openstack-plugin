package com.morpheusdata.openstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Cloud
import com.morpheusdata.openstack.plugin.OpenstackPlugin
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class EndpointsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private OpenstackPlugin plugin
	private HttpApiClient apiClient
	private AuthConfig authConfig

	public EndpointsSync(OpenstackPlugin openstackPlugin, Cloud cloud, HttpApiClient apiClient, AuthConfig authConfig) {
		this.plugin = openstackPlugin
		this.cloud = cloud
		this.morpheusContext = openstackPlugin.morpheusContext
		this.apiClient = apiClient
		this.authConfig = authConfig
	}

	def execute() {
		log.debug "BEGIN: execute EndpointsSync: ${cloud.id}"
		def rtn = [success:false]
		try {
			def token = OpenStackComputeUtility.getToken(apiClient, authConfig)
			if (token.token) {
				rtn = OpenStackComputeUtility.setEndpoints(cloud, apiClient, authConfig, token.osVersion, token.results)
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute EndpointsSync: ${cloud.id}"
		return ServiceResponse.create(rtn)
	}
}
