package com.morpheusdata.openstack.plugin

import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.cloud.MorpheusComputeZonePoolService
import spock.lang.Specification
import spock.lang.Subject

class OpenstackCloudProviderSpec extends Specification {

	@Subject
	OpenstackCloudProvider service

	MorpheusContext context
	MorpheusCloudService cloudContext
	MorpheusComputeZonePoolService poolContext
	OpenstackPlugin plugin

	void setup() {
		context = Mock(MorpheusContext)
		cloudContext = Mock(MorpheusCloudService)
		poolContext = Mock(MorpheusComputeZonePoolService)
		context.getCloud() >> cloudContext
		cloudContext.getPool() >> poolContext
		plugin = Mock(OpenstackPlugin)

		service = new OpenstackCloudProvider(plugin, context)
	}

	void "DI works"() {
		expect:
		service.morpheus
	}

	void "getOptionTypes"() {
		when:
		def optionTypes = service.getOptionTypes()

		then:
		optionTypes.size() == 0
	}

	void "getComputeServerTypes"() {
		when:
		def serverTypes = service.getComputeServerTypes()

		then:
		serverTypes.size() == 0
	}
}
