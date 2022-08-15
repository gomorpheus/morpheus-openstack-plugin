package com.morpheusdata.openstack.plugin

import com.morpheusdata.core.backup.AbstractBackupProvider
import com.morpheusdata.core.CloudProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.ProvisioningProvider
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.openstack.plugin.sync.AvailabilityZonesSync
import com.morpheusdata.openstack.plugin.sync.EndpointsSync
import com.morpheusdata.openstack.plugin.sync.ProjectsSync
import com.morpheusdata.openstack.plugin.sync.StorageAvailabilityZonesSync
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

import java.security.MessageDigest

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

		OptionType endpointComputeApi = new OptionType(
				name: 'Compute Service',
				code: 'openstack-plugin-endpoint-compute-api',
				fieldName: 'computeApi',
				displayOrder: 200,
				fieldLabel: 'Compute Service',
				required: false,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				fieldGroup: 'Service Endpoints'
		)

		OptionType endpointImageApi = new OptionType(
				name: 'Image Service',
				code: 'openstack-plugin-endpoint-image-api',
				fieldName: 'imageApi',
				displayOrder: 210,
				fieldLabel: 'Image Service',
				required: false,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				fieldGroup: 'Service Endpoints'
		)

		OptionType endpointStorageApi = new OptionType(
				name: 'Storage Service',
				code: 'openstack-plugin-endpoint-storage-api',
				fieldName: 'storageApi',
				displayOrder: 220,
				fieldLabel: 'Image Service',
				required: false,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				fieldGroup: 'Service Endpoints'
		)

		OptionType endpointNetworkApi = new OptionType(
				name: 'Network Service',
				code: 'openstack-plugin-endpoint-network-api',
				fieldName: 'networkApi',
				displayOrder: 230,
				fieldLabel: 'Network Service',
				required: false,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				fieldGroup: 'Service Endpoints'
		)
		OptionType endpointLoadBalancerApi = new OptionType(
				name: 'Load Balancer Service',
				code: 'openstack-plugin-endpoint-load-balancer-api',
				fieldName: 'loadBalancerApi',
				displayOrder: 240,
				fieldLabel: 'Load Balancer Service',
				required: false,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				fieldGroup: 'Service Endpoints'
		)
		OptionType endpointObjectStorageApi = new OptionType(
				name: 'Object Storage Service',
				code: 'openstack-plugin-endpoint-object-storage-api',
				fieldName: 'objectStorageApi',
				displayOrder: 250,
				fieldLabel: 'Object Storage Service',
				required: false,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				fieldGroup: 'Service Endpoints'
		)
		OptionType endpointSharedFileSystemApi = new OptionType(
				name: 'Shared File System Service',
				code: 'openstack-plugin-endpoint-shared-file-system-api',
				fieldName: 'sharedFileSystemApi',
				displayOrder: 260,
				fieldLabel: 'Shared File System Service',
				required: false,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				fieldGroup: 'Service Endpoints'
		)
		[apiUrl, domain, credentials, username, password, project, region, diskMode, provisionMethod, importExisting,
		    endpointComputeApi, endpointImageApi, endpointStorageApi, endpointNetworkApi, endpointLoadBalancerApi, endpointObjectStorageApi, endpointSharedFileSystemApi]
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
				def username
				def password
				if(validateCloudRequest.credentialType?.toString().isNumber()) {
					AccountCredential accountCredential = morpheus.accountCredential.get(validateCloudRequest.credentialType.toLong()).blockingGet()
					password = accountCredential.data.password
					username = accountCredential.data.username
				} else if(validateCloudRequest.credentialType == 'username-password') {
					password = validateCloudRequest.credentialPassword
					username = validateCloudRequest.credentialUsername
				} else if(validateCloudRequest.credentialType == 'local') {
					password = validateCloudRequest.opts?.zone?.servicePassword
					if(password == '************' && cloudInfo.id) {
						password = cloudInfo.servicePassword
					}
					username = validateCloudRequest.opts?.zone?.serviceUsername
				}

				if(username?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter a username')
				} else if(password?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter a password')
				} else if(cloudInfo.serviceUrl?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter an api url')
				} else {
					def identityVersion = OpenStackComputeUtility.parseEndpointVersion(cloudInfo.serviceUrl) ?: 'v3'
					def parsedVersion = identityVersion.substring(1)?.toString()?.isNumber() ? identityVersion.substring(1).toFloat() : null
					if(parsedVersion < 3.0f) {
						return new ServiceResponse(success: false, msg: "Minimum Identity API version supported is v3", errors: ["serviceUrl": "Minimum Identity API version supported is v3"])
					}
					if(cloudInfo.enabled) {
						//test api call
						NetworkProxy proxySettings = cloudInfo.apiProxy
						HttpApiClient client = new HttpApiClient()
						client.networkProxy = proxySettings

						Map configMap = cloudInfo.getConfigMap()
						AuthConfig authConfig = new AuthConfig(projectName: configMap.projectName, expireToken: true,
								identityUrl: cloudInfo.serviceUrl,
								identityVersion: identityVersion, cloudConfig: configMap, domainId: configMap.domainId, username: username, password: password)
						def token = OpenStackComputeUtility.getToken(client, authConfig)
						if(token.token) {
							log.info("Openstack Token Acquired along with Project ID...Setting Endpoints.")

							def endpointResults = (new EndpointsSync(plugin, cloudInfo, client, authConfig)).execute()

							if(endpointResults.success == false)
								return new ServiceResponse(success: false, msg: 'Error connecting to Openstack provider')
							else
								return ServiceResponse.success()
						} else {
							log.error("Error verifying Openstack cloud. Unable to acquire access token.")
							return new ServiceResponse(success: false, msg: 'Unable to acquire access token')
						}
					} else {
						return ServiceResponse.success()
					}
				}
			} else {
				return new ServiceResponse(success: false, msg: 'No cloud found')
			}
		} catch(e) {
			log.error('Error validating cloud', e)
			return new ServiceResponse(success: false, msg: 'Error validating cloud')
		}
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
			initializeCloudServices(cloud)
			initializeCloudConfig(cloud)
			//sync
			initializeCloudCache(cloud)
			refreshDaily(cloud)
			rtn = refresh(cloud)
			
		} catch (e) {
			log.error("refresh cloud error: ${e}", e)
			rtn.msg = "Error in setting up cloud: ${e}"
			rtn.error = rtn.msg
		}

		return rtn
	}

	def initializeCloudServices(Cloud cloud) {
		// TODO : initialize various network and load balancer services
	}

	def initializeCloudConfig(Cloud cloud) {
		log.debug "initializeCloudConfig: ${cloud.id}"
		HttpApiClient client
		try {
			def configMap = cloud.getConfigMap()
			configMap.identityVersion = OpenStackComputeUtility.parseEndpointVersion(cloud.serviceUrl) ?: 'v3'
			cloud.setConfigMap(configMap)
			morpheus.cloud.save(cloud).blockingGet()
			cloud = morpheus.cloud.getCloudById(cloud.id).blockingGet()

			if (cloud.enabled == true) {
				NetworkProxy proxySettings = cloud.apiProxy
				client = new HttpApiClient()
				client.networkProxy = proxySettings

				AuthConfig authConfig = plugin.getAuthConfig(cloud, true)
				def endpointResults = (new EndpointsSync(plugin, cloud, client, authConfig)).execute()
				if (endpointResults.success) {
					morpheus.cloud.save(cloud).blockingGet()
				} else {
					log.error "Error connecting to Openstack provider: ${cloud.id}"
				}
			}
		} catch(e) {
			log.error "Exception in initializeCloudConfig ${cloud.id} : ${e}", e
		} finally {
			if(client) {
				client.shutdownClient()
			}
		}
	}

	def initializeCloudCache(Cloud cloud) {
		log.debug("initializeCloudCache: ${cloud}")
		HttpApiClient client
		try {
			def hostOnline = testHostConnection(cloud)
			if(hostOnline) {
				NetworkProxy proxySettings = cloud.apiProxy
				client = new HttpApiClient()
				client.networkProxy = proxySettings

				// need to seed in initial projects to support further data caching
				log.debug("projects started")
				Date now = new Date()

				def authConfig = plugin.getAuthConfig(cloud, true)
				(new ProjectsSync(plugin, cloud, client, authConfig)).execute()

				log.debug("projects completed in ${new Date().time - now.time} ms")
			}
		} catch(e) {
			log.error("initialZoneCache error: ${e}", e)
		}
	}
	
	@Override
	ServiceResponse refresh(Cloud cloud) {
		log.debug "refresh: ${cloud.id}"
		ServiceResponse rtn = new ServiceResponse(success: false)

		HttpApiClient client

		try {
			NetworkProxy proxySettings = cloud.apiProxy
			client = new HttpApiClient()
			client.networkProxy = proxySettings


			rtn = ServiceResponse.success()
		} catch(e) {
			log.error "Error on cloud refresh for ${cloudInfo.id}"
		} finally {
			if(client) {
				client.shutdownClient()
			}
		}
		rtn
	}

	@Override
	void refreshDaily(Cloud cloud) {
		log.debug("refreshDaily: ${cloud}")
		def rtn = [success: false]

		HttpApiClient client

		try {
			def syncDate = new Date()
			def hostOnline = testHostConnection(cloud)
			if(hostOnline) {
				cloud = checkCloudConfig(cloud)
				morpheus.cloud.updateZoneStatus(cloud, Cloud.Status.syncing, null, syncDate)
				cloud = cacheApiMicroVersions(cloud)
				def cloudPools = []
				morpheus.cloud.pool.listSyncProjections(cloud.id, '').blockingSubscribe { cloudPools << it }

				NetworkProxy proxySettings = cloud.apiProxy
				client = new HttpApiClient()
				client.networkProxy = proxySettings

				for(ComputeZonePoolIdentityProjection cloudPool in cloudPools) {
					AuthConfig authConfig = plugin.getAuthConfig(cloud)
					(new AvailabilityZonesSync(plugin, cloud, client, authConfig, cloudPool)).execute()
					(new StorageAvailabilityZonesSync(plugin, cloud, client, authConfig, cloudPool)).execute()
//					cacheFlavors([account: zone.account, zone: zone, zonePool: zonePool, projectId: zonePool.externalId, proxySettings: proxySettings]).get()
//					cacheVolumeTypes([account: zone.account, zone: zone, zonePool: zonePool, projectId: zonePool.externalId, proxySettings: proxySettings]).get()
				}
				morpheus.cloud.updateZoneStatus(cloud, Cloud.Status.ok, null, syncDate)
			} else {
				morpheus.cloud.updateZoneStatus(cloud, Cloud.Status.offline, 'Openstack not reachable', syncDate)
			}
			rtn.success = true
		} catch(e) {
			log.error("refreshDaily error: ${e}", e)
		} finally {
			if(client) {
				client.shutdownClient()
			}
		}
	}

	def testHostConnection(Cloud cloud) {
		def rtn = false
		try {
			AuthConfig authConfig = plugin.getAuthConfig(cloud, true)
			def apiUrl = OpenStackComputeUtility.getOpenstackIdentityUrl(authConfig)
			def apiUrlObj = new URL(apiUrl)
			def apiHost = apiUrlObj.getHost()
			def apiPort = apiUrlObj.getPort() > 0 ? apiUrlObj.getPort() : (apiUrlObj?.getProtocol()?.toLowerCase() == 'https' ? 443 : 80)
			//socket check..
			rtn = ConnectionUtils.testHostConnectivity(apiHost, apiPort, true, true, cloud.apiProxy)
		} catch(e) {
			log.error("error testing host connection for cloud ${cloud?.name}: ${e}")
		}
		return rtn
	}

	def Cloud checkCloudConfig(Cloud cloud) {
		def save = false
		def regionCode = getRegionCode(cloud)
		if(cloud.regionCode != regionCode) {
			cloud.regionCode = regionCode
			save = true
		}
		if(save) {
			cloud = saveAndGet(cloud)
		}
		cloud
	}

	String getRegionCode(Cloud cloud) {
		def identityApi = cloud?.getConfigProperty('identityApi')
		def domain = cloud.getConfigProperty('domainId')
		def regionString = "${identityApi}.${domain}"
		MessageDigest md = MessageDigest.getInstance("MD5")
		md.update(regionString.bytes)
		byte[] checksum = md.digest()
		return checksum.encodeHex().toString()
	}

	def Cloud cacheApiMicroVersions(Cloud cloud) {
		def account = cloud.account
		try {
			def doSave = false
			def cloudConfigMap = cloud.getConfigMap()
			def serviceNames = ['Compute', 'Image', 'Storage', 'Network', 'LoadBalancer', 'ObjectStorage', 'SharedFileSystem']
			for(serviceName in serviceNames) {
				try {
					// uses the override version in the zone config if it exists.
					def existingMicroVersion = cloudConfigMap["${serviceName}MicroVersion"]
					def apiUrl
					def apiVersion
					try {
						apiUrl = OpenStackComputeUtility."getOpenstack${serviceName}Url"(cloud)
						apiVersion = OpenStackComputeUtility."getOpenstack${serviceName}Version"(cloud)
					} catch(Exception e3) {
						// api url isn't set, ignore
					}
					if(apiUrl) {
						def currentMicroVersion =  OpenStackComputeUtility.fetchApiMicroVersion(apiUrl, apiVersion)
						if(existingMicroVersion != currentMicroVersion) {
							cloudConfigMap["${serviceName}MicroVersion"] = currentMicroVersion
							doSave = true
						}
						log.debug("Current ${serviceName} microversion: ${cloudConfigMap["${serviceName}MicroVersion"]}")
					}

				} catch (Exception e2) {
					log.error("cacheApiMicroVersions error caching microversion for ${serviceName}: ${e2}", e2)
				}
			}

			if(doSave) {
				cloud.setConfigMap(cloudConfigMap)
				cloud = saveAndGet(cloud)
			}
		} catch(e) {
			log.error("cacheApiMicroVersions error: ${e}", e)
		}

		cloud
	}

	private Cloud saveAndGet(Cloud cloud) {
		morpheus.cloud.save(cloud).blockingGet()
		return morpheus.cloud.getCloudById(cloud.id).blockingGet()
	}
}
