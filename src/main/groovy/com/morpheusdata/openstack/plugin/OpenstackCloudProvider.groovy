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
		OptionType apiUrl = new OptionType(
				name: 'Identity Api Url',
				code: 'openstack-plugin-api-url',
				fieldName: 'serviceUrl',
				displayOrder: 0,
				fieldLabel: 'Identity Api Url',
				required: true,
				inputType: OptionType.InputType.TEXT,
				placeHolder: 'https://0.0.0.0:5000',
				fieldContext: 'domain'
		)
		OptionType domain = new OptionType(
				name: 'Domain',
				code: 'openstack-plugin-domain',
				fieldName: 'domainId',
				displayOrder: 10,
				fieldLabel: 'Domain',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				helpText: 'Domain name or ID can be used. The Domain can be found via the CLI by typing "openstack domain list".'
		)
		OptionType credentials = new OptionType(
				code: 'openstack-plugin-credential',
				inputType: OptionType.InputType.CREDENTIAL,
				name: 'Credentials',
				fieldName: 'type',
				fieldLabel: 'Credentials',
				fieldContext: 'credential',
				required: true,
				defaultValue: 'local',
				displayOrder: 20,
				optionSource: 'credentials',
				config: '{"credentialTypes":["username-password"]}'
		)
		OptionType username = new OptionType(
				name: 'Username',
				code: 'openstack-plugin-username',
				fieldName: 'serviceUsername',
				displayOrder: 30,
				fieldLabel: 'Username',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'domain',
				localCredential: true
		)
		OptionType password = new OptionType(
				name: 'Password',
				code: 'openstack-plugin-password',
				fieldName: 'servicePassword',
				displayOrder: 40,
				fieldLabel: 'Password',
				required: true,
				inputType: OptionType.InputType.PASSWORD,
				fieldContext: 'domain',
				localCredential: true
		)
		OptionType project = new OptionType(
				name: 'Project',
				code: 'openstack-plugin-project',
				fieldName: 'projectName',
				displayOrder: 50,
				fieldLabel: 'Project',
				required: false,
				inputType: OptionType.InputType.SELECT,
				fieldContext: 'config',
				dependsOn: 'openstack-plugin-password,openstack-plugin-username,openstack-plugin-username,credential-type',
				optionSource: 'openstackPluginProjects'
		)
		OptionType region = new OptionType(
				name: 'Region',
				code: 'openstack-plugin-region',
				fieldName: 'region',
				displayOrder: 60,
				fieldLabel: 'Region',
				required: false,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config'
		)
		OptionType diskMode = new OptionType(
				name: 'Image Format',
				code: 'openstack-plugin-disk-mode',
				fieldName: 'diskMode',
				displayOrder: 70,
				fieldLabel: 'Image Format',
				required: true,
				inputType: OptionType.InputType.SELECT,
				fieldContext: 'config',
				optionSource: 'openstackPluginDiskMode'
		)
		OptionType provisionMethod = new OptionType(
				name: 'Provision Type',
				code: 'openstack-plugin-provision-method',
				fieldName: 'provisionMethod',
				displayOrder: 80,
				fieldLabel: 'Provision Type',
				required: false,
				inputType: OptionType.InputType.SELECT,
				fieldContext: 'config',
				optionSource: 'openstackPluginProvisionMethod'
		)

		OptionType importExisting = new OptionType(
				name: 'Inventory Existing Instances',
				code: 'openstack-plugin-import-existing',
				fieldName: 'importExisting',
				displayOrder: 90,
				fieldLabel: 'Inventory Existing Instances',
				required: false,
				inputType: OptionType.InputType.CHECKBOX,
				fieldContext: 'config'
		)
		[apiUrl, domain, credentials, username, password, project, region, diskMode, provisionMethod, importExisting]
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
				return ServiceResponse.success()
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
