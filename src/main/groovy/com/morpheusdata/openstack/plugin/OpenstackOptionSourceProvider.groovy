package com.morpheusdata.openstack.plugin

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import groovy.util.logging.Slf4j

@Slf4j
class OpenstackOptionSourceProvider extends AbstractOptionSourceProvider {

	OpenstackPlugin plugin
	MorpheusContext morpheusContext

	OpenstackOptionSourceProvider(OpenstackPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	String getCode() {
		return 'openstack-option-source-plugin'
	}

	@Override
	String getName() {
		return 'Openstack Option Source Plugin'
	}

	@Override
	List<String> getMethodNames() {
		return new ArrayList<String>()
	}
}
