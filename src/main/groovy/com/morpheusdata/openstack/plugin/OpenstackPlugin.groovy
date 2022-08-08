package com.morpheusdata.openstack.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
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

}
