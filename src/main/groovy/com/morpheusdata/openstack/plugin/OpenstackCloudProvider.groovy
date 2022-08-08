package com.morpheusdata.openstack.plugin

import com.morpheusdata.core.backup.AbstractBackupProvider
import com.morpheusdata.core.CloudProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.ProvisioningProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class OpenstackCloudProvider implements CloudProvider {

	OpenstackPlugin plugin
	MorpheusContext morpheusContext

	OpenstackCloudProvider(OpenstackPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		[]
	}

	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		[]
	}

	@Override
	Collection<ProvisioningProvider> getAvailableProvisioningProviders() {
		return plugin.getProvidersByType(ProvisioningProvider) as Collection<ProvisioningProvider>
	}

	@Override
	Collection<AbstractBackupProvider> getAvailableBackupProviders() {
		return null
	}

	@Override
	ProvisioningProvider getProvisioningProvider(String providerCode) {
		return getAvailableProvisioningProviders().find { it.code == providerCode }
	}

	@Override
	Collection<NetworkType> getNetworkTypes() {
		[]
	}

	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		def volumeTypes = []
		volumeTypes
	}

	@Override
	Collection<StorageControllerType> getStorageControllerTypes() {
		return null
	}

	@Override
	ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
		log.info("validate: {}", cloudInfo)
		try {
			if(cloudInfo) {
			} else {
				return new ServiceResponse(success: false, msg: 'No cloud found')
			}
		} catch(e) {
			log.error('Error validating cloud', e)
			return new ServiceResponse(success: false, msg: 'Error validating cloud')
		}
	}

	@Override
	ServiceResponse refresh(Cloud cloudInfo) {
		initializeCloud(cloudInfo)
	}

	@Override
	void refreshDaily(Cloud cloudInfo) {
		//nothing daily
	}

	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		return new ServiceResponse(success: true)
	}

	@Override
	Boolean hasComputeZonePools() {
		return true
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean hasFolders() {
		return false
	}

	@Override
	Boolean hasDatastores() {
		return false
	}

	@Override
	Boolean hasBareMetal() {
		return false
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
		return 'openstack-plugin-cloud'
	}

	@Override
	Icon getIcon() {
		return new Icon(path:"openstack-plugin.svg", darkPath: "openstack-plugin-dark.svg")
	}

	@Override
	String getName() {
		return 'Openstack'
	}

	@Override
	String getDescription() {
		return 'Openstack plugin'
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
	}

	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
	}

	@Override
	Boolean hasCloudInit() {
		return true
	}

	@Override
	Boolean supportsDistributedWorker() {
		return false
	}

	@Override
	ServiceResponse initializeCloud(Cloud cloud) {
		ServiceResponse rtn = new ServiceResponse(success: false)
		log.info "Initializing Cloud: ${cloud.code}"
		log.info "config: ${cloud.configMap}"

		try {
		} catch (e) {
			log.error("refresh cloud error: ${e}", e)
		}

		return rtn
	}
}
