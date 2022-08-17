package com.morpheusdata.openstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.NetworkServer
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.ProvisionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.Network
import com.morpheusdata.openstack.plugin.OpenstackPlugin
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import com.morpheusdata.openstack.plugin.utils.OpenstackSyncUtils
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class NetworksSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private OpenstackPlugin plugin
	private HttpApiClient apiClient
	private AuthConfig authConfig
	private ComputeZonePoolIdentityProjection cloudPool

	public NetworksSync(OpenstackPlugin openstackPlugin, Cloud cloud, HttpApiClient apiClient, AuthConfig authConfig, ComputeZonePoolIdentityProjection cloudPool) {
		this.plugin = openstackPlugin
		this.cloud = cloud
		this.morpheusContext = openstackPlugin.morpheusContext
		this.apiClient = apiClient
		this.authConfig = authConfig
		this.cloudPool = cloudPool
	}

	def execute() {
		log.debug "BEGIN: execute NetworksSync: ${cloud.id}, ${cloudPool.externalId}"
		def rtn = [success:false]
		try {
			authConfig.projectId = cloudPool.externalId
			def token = OpenStackComputeUtility.getToken(apiClient, authConfig)
			if (token.token) {
				def processData = [:]
				processData.objCategory = OpenstackSyncUtils.getObjectCategory("network", cloud.id)
				processData.listResults = OpenStackComputeUtility.listNetworks(apiClient, authConfig, authConfig.projectId)
				processData.apiType = processData.listResults.api
				if(processData.listResults.success == true) {

					List<Long> networkIds = []
					morpheusContext.network.listSyncProjections(cloud.id).blockingSubscribe { networkIds << it.id }

					Observable<Network> domainRecords = morpheusContext.network.listById(networkIds).filter { Network it ->
						it.category == processData.objCategory && (!it.zonePoolId || it.zonePoolId == cloudPool.id)
					}
					SyncTask<Network, Map, Network> syncTask = new SyncTask<>(domainRecords, processData.listResults?.results?.networks)
					syncTask.addMatchFunction { Network domainObject, Map cloudItem ->
						domainObject.externalId == cloudItem.id
					}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<Network, Map>> updateItems ->
						List<SyncTask.UpdateItem<Network, Map>> list = updateItems.collect { it ->
							return new SyncTask.UpdateItem<Network, Map>(existingItem: it.existingItem, masterItem: it.masterItem)
						}
						Observable.fromIterable(list);
					}.onAdd { itemsToAdd ->
						addNetworks(itemsToAdd, processData)
					}.onUpdate { List<SyncTask.UpdateItem<Network, Map>> updateItems ->
						updateNetworks(updateItems, processData)
					}.onDelete { removeItems ->
						removeNetworks(removeItems)
					}.start()
				}
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute NetworksSync: ${cloud.id}"
		return ServiceResponse.create(rtn)
	}

	protected void addNetworks(List addList, Map config) {
		try {
			log.debug("addNetworks: ${addList?.size()}")

			NetworkServer networkServer = cloud.networkServer
			def openstackVlan = new NetworkType(code: "openstack-provision-provider-pluginPrivate")
			def objCategory = config.objCategory

			def adds = []
			for(remoteItem in addList) {
				def doSave = false
				def addConfig = [
						cloud: cloud, name: remoteItem.name, owner: new Account(id: cloud.owner.id), type: openstackVlan,
						code: "${objCategory}.${remoteItem.id}", category: objCategory, zonePoolId: cloudPool.id, assignedZonePools: [cloudPool],
						externalId: remoteItem.id, uniqueId: remoteItem.id, externalType: 'Network', refType: 'ComputeZone',
						refId: cloud.id, networkServer: networkServer
				]
				if(config.apiType == 'nova') {
					addConfig += [
							name: remoteItem.label, dhcpServer: remoteItem.enable_dhcp, cidr: remoteItem.cidr, dhcpIp: remoteItem.dhcp_server, dnsPrimary: remoteItem.dns1,
							dnsSecondary: remoteItem.dns2, gateway: remoteItem.gateway, netmask: remoteItem.netmask, vlanId: remoteItem.vlan?.toInteger(), broadcast: remoteItem.broadcast,
							interfaceName: remoteItem.bridge_interface, bridgeName: remoteItem.bridge, bridgeInterface: remoteItem.bridge_interface, networkRole: 'all'
					]
					doSave = true
				} else {
					if(remoteItem.subnets?.size() > 0) {
						def subnetResults = OpenStackComputeUtility.findBestSubnet(apiClient,authConfig, authConfig.projectId, remoteItem.id)
						if(subnetResults?.success == true) {
							def subnet = subnetResults.subnet
							if(subnet) {
								addConfig += [
										name: remoteItem.name, dhcpServer: subnet.enable_dhcp, cidr: subnet.cidr, networkType: remoteItem['provider:network_type'],
										internalId: subnet.id, gateway: subnet.gateway_ip, networkRole: (remoteItem['router:external'] == true ? 'external' : 'internal')
								]
								def networkCidr = NetworkUtility.getNetworkCidrConfig(addConfig.cidr)
								addConfig.netmask = networkCidr.config?.netmask
								if(subnet.dns_nameservers?.size() > 0)
									addConfig.dnsPrimary = subnet.dns_nameservers[0]
								if(subnet.dns_nameservers?.size() > 1)
									addConfig.dnsSecondary = subnet.dns_nameservers[1]
								if(addConfig.networkType == 'vlan' && remoteItem['provider:segmentation_id'])
									addConfig.vlanId = remoteItem['provider:segmentation_id']
								doSave = true
							}
						} else {
							addConfig += [
									name: remoteItem.name, networkType: remoteItem['provider:network_type'], networkRole: (remoteItem['router:external'] == true ? 'external' : 'internal')
							]
							if(addConfig.networkType == 'vlan' && remoteItem['provider:segmentation_id']) {
								addConfig.vlanId = remoteItem['provider:segmentation_id']
							}
							doSave = true
						}
					} else {
						addConfig += [name: remoteItem.name, networkType: remoteItem['provider:network_type'], networkRole: (remoteItem['router:external'] == true ? 'external' : 'internal')]
						doSave = true
					}
				}
				if(doSave == true) {
					adds << new Network(addConfig)
				}
			}

			if(adds) {
				morpheusContext.network.create(adds).blockingGet()
			}
		} catch(e) {
			log.error("addNetworks error: ${e}", e)
		}
	}

	protected updateNetworks( List<SyncTask.UpdateItem<Network, Map>>updateList, Map processData) {
		log.debug("updateNetworks: ${updateList?.size()}")

		def objCategory = processData.objCategory
		def networkServer = cloud.networkServer
		def openstackVlan = new NetworkType(code: "openstack-provision-provider-pluginPrivate")

		def updates = []
		for(updateMap in updateList) {
			def remoteItem = updateMap.masterItem
			Network localItem = updateMap.existingItem
			def doSave = false
			def updateConfig = [:]

			if(localItem.type?.code != openstackVlan?.code) {
				updateConfig.type = openstackVlan
				doSave = true
			}
			if(processData.apiType == 'nova') {
				updateConfig += [
						name:remoteItem.label, externalId:remoteItem.id, uniqueId:remoteItem.id,
						dhcpServer:remoteItem.enable_dhcp, cidr:remoteItem.cidr, dhcpIp:remoteItem.dhcp_server, dnsPrimary:remoteItem.dns1, dnsSecondary:remoteItem.dns2,
						gateway:remoteItem.gateway, netmask:remoteItem.netmask, vlanId:remoteItem.vlan?.toInteger(), broadcast:remoteItem.broadcast,
						interfaceName:remoteItem.bridge_interface, bridgeName:remoteItem.bridge, bridgeInterface:remoteItem.bridge_interface]
				doSave = true
			} else {
				updateConfig += [name:remoteItem.name]
				if(remoteItem.subnets?.size() > 0) {
					def subnetResults = OpenStackComputeUtility.findBestSubnet(apiClient, authConfig, authConfig.projectId, remoteItem.id)
					if(subnetResults?.success == true) {
						def subnet = subnetResults.subnet
						if(subnet) {
							updateConfig += [name:remoteItem.name, dhcpServer:subnet.enable_dhcp, cidr:subnet.cidr, networkType:remoteItem['provider:network_type'],
							                 internalId:subnet.id, gateway:subnet.gateway_ip, networkRole:(remoteItem['router:external'] == true ? 'external' : 'internal')]
							def networkCidr = NetworkUtility.getNetworkCidrConfig(updateConfig.cidr)
							updateConfig.netmask = networkCidr.config?.netmask
							if(subnet.dns_nameservers?.size() > 0)
								updateConfig.dnsPrimary = subnet.dns_nameservers[0]
							if(subnet.dns_nameservers?.size() > 1)
								updateConfig.dnsSecondary = subnet.dns_nameservers[1]
							if(updateConfig.networkType == 'vlan' && remoteItem['provider:segmentation_id'])
								updateConfig.vlanId = remoteItem['provider:segmentation_id']

							doSave = true
						}
					} else {
						def networkRole = remoteItem['router:external'] == true ? 'external' : 'internal'
						if(localItem.networkRole != networkRole) {
							localItem.networkRole = networkRole
							doSave = true
						}
					}
				}
			}

			if(doSave == true) {
				doSave = false
				updateConfig.each { entry ->
					if(localItem."${entry.key}" != entry.value) {
						localItem."${entry.key}" = entry.value
						doSave = true
					}
				}
			}

			if(cloudPool && !localItem.assignedZonePools?.find{it.id == cloudPool?.id}) {
				localItem.assignedZonePools << cloudPool
				doSave=true
			}
			if(cloudPool.id && !localItem.zonePoolId) {
				localItem.zonePoolId = cloudPool.id
				doSave=true
			}
			if(networkServer && localItem.networkServer == null) {
				// update network with missing network server
				localItem.networkServer = networkServer
				doSave = true
			}
			if(localItem.category != objCategory) {
				localItem.category = objCategory
				doSave = true
			}
			def networkRole = (remoteItem['router:external'] == true ? 'external' : 'internal')
			if(localItem.networkRole != networkRole) {
				localItem.networkRole = networkRole
				doSave = true
			}

			if(doSave == true) {
				updates << localItem
			}
		}
		if(updates) {
			log.debug "Have ${updates.size()} networks to update"
			morpheusContext.network.save(updates).blockingGet()
		}
	}

	protected removeNetworks(List<Network> removeItems) {
		try {
			for(Network localItem in removeItems) {
				ComputeZonePool foundPool = localItem.assignedZonePools?.find{it.id == cloudPool.id}
				if(foundPool) {
					localItem.assignedZonePools.remove(foundPool)
				}
				if(localItem.assignedZonePools.size() == 0) {
					morpheusContext.network.remove([localItem]).blockingGet()
				} else {
					morpheusContext.network.save(localItem).blockingGet()
				}
			}
		} catch(e) {
			log.error "Error in removeNetworks ${e}", e
		}
	}

}
