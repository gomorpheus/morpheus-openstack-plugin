package com.morpheusdata.openstack.plugin

import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.cloud.MorpheusComputeZonePoolService
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject

class OpenstackOptionSourceProviderSpec extends Specification {

	@Subject
	OpenstackOptionSourceProvider service

	MorpheusContext context
	MorpheusCloudService cloudContext
	MorpheusComputeZonePoolService poolContext
	OpenstackPlugin plugin
	@Shared OpenstackCloudProvider openstackCloudProvider

	void setup() {
		context = Mock(MorpheusContext)
		cloudContext = Mock(MorpheusCloudService)
		poolContext = Mock(MorpheusComputeZonePoolService)
		context.getCloud() >> cloudContext
		cloudContext.getPool() >> poolContext
		plugin = Mock(OpenstackPlugin)
		openstackCloudProvider = new OpenstackCloudProvider(plugin, context)
		service = new OpenstackOptionSourceProvider(plugin, context)
	}
}
