package com.morpheusdata.openstack.plugin

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
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
		return new ArrayList<String>(['openstackPluginProjects',
		                              'openstackPluginDiskMode',
		                              'openstackPluginProvisionMethod'])
	}

	def openstackPluginProjects(args) {
		log.debug "openstackPluginProjects: ${args}"

		HttpApiClient client

		def rtn = []
		try {
			Cloud cloud = loadLookupZone(args)
			def cloudArgs = args?.size() > 0 ? args.getAt(0) : null
			NetworkProxy proxySettings = cloud.apiProxy
			client = new HttpApiClient()
			client.networkProxy = proxySettings

			if(cloud.serviceUrl && cloud.serviceUsername && cloud.servicePassword) {
				def authConfig = plugin.getAuthConfig(cloud, true)
				def projectListResults = OpenStackComputeUtility.listProjects(client, authConfig)
				if(projectListResults.success && !projectListResults.error?.size()) {
					def tmpProjects = []
					for (project in projectListResults.results.projects) {
						def match = cloudArgs.phrase ? (project.name =~ ".*?(${cloudArgs.phrase}).*?") : true
						if(match) {
							tmpProjects << [id: project.name, value: project.name, name: project.name]
						}
					}
					tmpProjects = tmpProjects.sort { a, b -> a.name?.toLowerCase() <=> b.name?.toLowerCase() }
					rtn += tmpProjects
				}
			}
		} catch (Exception e) {
			log.error("Error loading projects for openstack cloud create form: {}", e)
		} finally {
			if(client) {
				client.shutdownClient()
			}
		}

		return rtn

	}

	def openstackPluginDiskMode(args) {
		log.debug "openstackPluginDiskMode: ${args}"
		List options = [[value: 'qcow2', name: 'QCOW2'], [value: 'raw', name: 'RAW'],[value: 'vmdk', name: 'VMDK']]
		options
	}

	def openstackPluginProvisionMethod(args) {
		log.debug "openstackPluginProvisionMethod: ${args}"
		return [
			[name:'Image', value:"image"],
			[name:'Volume', value:"volume"]
		]
	}

	/**
	 * Load/create a cloud with credentials and auth info set on it.. overlay any arg config
	 * @param args
	 * @return
	 */
	private Cloud loadLookupZone(args) {
		log.debug "loadLookupZone: $args"
		Cloud tmpCloud = new Cloud()
		try {
			def cloudArgs = args?.size() > 0 ? args.getAt(0) : null
			if (cloudArgs?.zone) {
				// Case when changes are made in the config dialog
				tmpCloud.serviceUrl = cloudArgs.zone.serviceUrl
				tmpCloud.serviceUsername = cloudArgs.zone.serviceUsername
				tmpCloud.servicePassword = cloudArgs.zone.servicePassword
				if(tmpCloud.servicePassword == '************' && cloudArgs?.zoneId?.toLong()) {
					def cloud = morpheusContext.cloud.getCloudById(cloudArgs?.zoneId?.toLong()).blockingGet()
					tmpCloud.servicePassword = cloud.servicePassword
				}
				tmpCloud.setConfigProperty('domainId', cloudArgs.config?.domainId)

				Map credentialConfig = morpheusContext.accountCredential.loadCredentialConfig(cloudArgs?.credential, cloudArgs.zone).blockingGet()
				tmpCloud.accountCredentialLoaded = true
				tmpCloud.accountCredentialData = credentialConfig?.data
			} else {
				// Case when the config dialog opens without any changes
				def zoneId = cloudArgs?.zoneId?.toLong()
				if (zoneId) {
					log.debug "using zoneId: ${zoneId}"
					tmpCloud = morpheusContext.cloud.getCloudById(zoneId).blockingGet()

					// Load the credential for the cloud
					def authData = plugin.getAuthConfig(tmpCloud, true)
					def username = authData.username
					def password = authData.password
					tmpCloud.accountCredentialData = null // force the user of serviceUsername / servicePassword
					tmpCloud.serviceUsername = username
					tmpCloud.servicePassword = password

					// Overlay any settings passed in
					if (cloudArgs.zone?.serviceUrl)
						tmpCloud.serviceUrl = cloudArgs.zone?.serviceUrl

					if (cloudArgs.zone?.serviceUsername)
						tmpCloud.serviceUsername = cloudArgs.zone?.serviceUsername

					if (cloudArgs.zone?.password && cloudArgs.zone.password != MorpheusUtils.passwordHidden)
						tmpCloud.servicePassword = cloudArgs.zone.servicePassword

					if (cloudArgs.config?.domainId)
						tmpCloud.setConfigProperty('domainId', cloudArgs.config?.domainId)
				}
			}
		} catch(e) {
			log.error "Error in loadLookupZone: ${e}", e
		}
		tmpCloud
	}
}
