package com.morpheusdata.openstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.model.projection.NetworkSubnetIdentityProjection
import com.morpheusdata.openstack.plugin.OpenstackPlugin
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import com.morpheusdata.openstack.plugin.utils.OpenstackSyncUtils
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class SubnetsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private OpenstackPlugin plugin
	private HttpApiClient apiClient
	private AuthConfig authConfig
	private ComputeZonePoolIdentityProjection cloudPool
	private Date syncDate
	static SYNC_INTERVAL = 1000l * 60l * 5l

	public SubnetsSync(OpenstackPlugin openstackPlugin, Cloud cloud, HttpApiClient apiClient, AuthConfig authConfig, ComputeZonePoolIdentityProjection cloudPool, Date syncDate) {
		this.plugin = openstackPlugin
		this.cloud = cloud
		this.morpheusContext = openstackPlugin.morpheusContext
		this.apiClient = apiClient
		this.authConfig = authConfig
		this.cloudPool = cloudPool
		this.syncDate = syncDate
	}

	def execute() {
		log.debug "BEGIN: execute SubnetsSync: ${cloud.id}, ${cloudPool.externalId}"
		def rtn = [success:false]
		try {
			authConfig.projectId = cloudPool.externalId
			def token = OpenStackComputeUtility.getToken(apiClient, authConfig)
			if (token.token) {
				def processData = [:]
				processData.networkObjCategory = OpenstackSyncUtils.getObjectCategory("network", cloud.id)
				processData.objCategory = OpenstackSyncUtils.getObjectCategory("subnet", cloud.id)
				processData.listResults = OpenStackComputeUtility.listSubnets(apiClient, authConfig, cloudPool.externalId)
				if(processData.listResults.success == true) {
					
					Observable<NetworkSubnetIdentityProjection> domainRecords = morpheusContext.networkSubnet.listIdentityProjections(cloud.id, cloudPool.id, processData.networkObjCategory)
					SyncTask<NetworkSubnetIdentityProjection, Map, NetworkSubnet> syncTask = new SyncTask<>(domainRecords, processData.listResults?.results?.subnets)
					syncTask.addMatchFunction { NetworkSubnetIdentityProjection domainObject, Map cloudItem ->
						domainObject.externalId == cloudItem.id
					}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkSubnetIdentityProjection, Map>> updateItems ->
						Map<Long, SyncTask.UpdateItemDto<NetworkSubnetIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
						morpheusContext.networkSubnet.listById(updateItems?.collect { it.existingItem.id }).map { NetworkSubnet subnet ->
							SyncTask.UpdateItemDto<NetworkSubnetIdentityProjection, Map> matchItem = updateItemMap[subnet.id]
							return new SyncTask.UpdateItem<NetworkSubnet, Map>(existingItem: subnet, masterItem: matchItem.masterItem)
						}
					}.onAdd { itemsToAdd ->
						addSubnets(itemsToAdd, processData)
					}.onUpdate { List<SyncTask.UpdateItem<NetworkSubnet, Map>> updateItems ->
						updateSubnets(updateItems, processData)
					}.onDelete { removeItems ->
						removeSubnets(removeItems)
					}.start()
				}
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute SubnetsSync: ${cloud.id}"
		return ServiceResponse.create(rtn)
	}

	protected void addSubnets(List addList, Map config) {
		try {
			log.debug("addSubnets: ${addList?.size()}")

			List<Network> updateNetworks = []

			def objCategory = config.objCategory
			def subnetType = new NetworkSubnetType(code: 'openstack-plugin-cloud')
			def networkExternalIds = addList.collect  { it.network_id }

			List<Long> networkIds = []
			morpheusContext.network.listIdentityProjections(cloud).filter{ NetworkIdentityProjection it ->
				it.externalId in networkExternalIds
			}.blockingSubscribe{ networkIds << it.id }

			Map<Long, Network> networkMatches = [:]
			morpheusContext.network.listById(networkIds).filter { Network it ->
				it.assignedZonePools.find { it.id == cloudPool.id } != null
			}.blockingSubscribe { networkMatches[it.externalId] = it }

			for(remoteItem in addList) {
				Network networkMatch = networkMatches[remoteItem.network_id]
				if(networkMatch) {
					def networkCidr = NetworkUtility.getNetworkCidrConfig(remoteItem.cidr)
					def subnetName = (remoteItem.name ?: remoteItem.cidr ?: remoteItem.id)
					def addConfig = [account: new Account(id: cloud.owner.id), internalId:remoteItem.network_id, externalId:remoteItem.id, name:subnetName, networkSubnetType: subnetType,
					                 category:objCategory, code:objCategory + '.' + remoteItem.id, refType:'ComputeZone', refId:cloud.id,
					                 description:remoteItem.description, cidr:remoteItem.cidr, subnetAddress:remoteItem.cidr, dhcpServer:remoteItem.enable_dhcp,
					                 gateway:remoteItem.gateway_ip, netmask:networkCidr.config?.netmask, assignedZonePools: [cloudPool]]
					if(networkMatch.networkRole == 'external' && remoteItem.allocation_pools?.size() > 0) {
						//has floating ips available
						addConfig.hasFloatingIps = true
						if(!networkMatch.hasFloatingIps) {
							networkMatch.hasFloatingIps = true
							updateNetworks << networkMatch
						}
					}
					//dns
					if(remoteItem.dns_nameservers?.size() > 0)
						addConfig.dnsPrimary = remoteItem.dns_nameservers[0]
					if(remoteItem.dns_nameservers?.size() > 1)
						addConfig.dnsSecondary = remoteItem.dns_nameservers[1]
					if(remoteItem.allocation_pools?.size() > 0) {
						addConfig.dhcpStart = remoteItem.allocation_pools[0].start
						addConfig.dhcpEnd = remoteItem.allocation_pools[0].end
					}
					//dhcp
					def add = new NetworkSubnet(addConfig)
					add.setConfigMap(remoteItem)
					morpheusContext.networkSubnet.create([add], networkMatch).blockingGet()
				}
			}

			if(updateNetworks) {
				morpheusContext.network.save(updateNetworks).blockingGet()
			}
		} catch(e) {
			log.error("addSubnets error: ${e}", e)
		}
	}

	protected updateSubnets( List<SyncTask.UpdateItem<NetworkSubnet, Map>>updateList, Map processData) {
		log.debug("updateSubnets: ${updateList?.size()}")
		try {
			def objCategory = processData.objCategory

			// Get all the networks we are working with
			List<Network> networks = []
			List<Long> networkIds = updateList.collect { it.existingItem.networkId }?.unique()
			morpheusContext.network.listById(networkIds).blockingSubscribe{ networks << it }
			for(updateMap in updateList) {
				Map remoteItem = updateMap.masterItem
				NetworkSubnet localItem = updateMap.existingItem

				def doSave = false

				if(localItem.category != objCategory) {
					localItem.category = objCategory
					doSave = true
				}

				def subnetName = (remoteItem.name ?: remoteItem.cidr ?: remoteItem.id)
				if(localItem.name != subnetName) {
					localItem.name = subnetName
					doSave = true
				}

				if(localItem.description != remoteItem.description) {
					localItem.description = remoteItem.description
					doSave = true
				}

				if(localItem.cidr != remoteItem.cidr) {
					localItem.cidr = remoteItem.cidr
					doSave = true
				}

				if(localItem.dhcpServer != remoteItem.enable_dhcp) {
					localItem.dhcpServer = remoteItem.enable_dhcp
					doSave = true
				}

				if(localItem.gateway != remoteItem.gateway_ip) {
					localItem.gateway = remoteItem.gateway_ip
					doSave = true
				}
				//check data of last update to do more
				Network networkMatch
				def lastUpdateDate = MorpheusUtils.parseDate(remoteItem.updated_at)
				if(lastUpdateDate && (lastUpdateDate.time > (syncDate.time - SYNC_INTERVAL))) {
					//recent changes - get network too
					networkMatch = networks.find { it.externalId == remoteItem.network_id && it.assignedZonePools.find { it.id == cloudPool.id } != null }
					if(networkMatch) {
						def hasFloatingIps = networkMatch.networkRole == 'external' && remoteItem.allocation_pools?.size() > 0
						if(localItem.hasFloatingIps != hasFloatingIps) {
							//has floating ips available
							localItem.hasFloatingIps = true
							if(hasFloatingIps == true)
								networkMatch.hasFloatingIps = true //change to check all networks on subnet - but unlikely to encounter
							doSave = true
						}
					}
				}

				if(cloudPool && !localItem.assignedZonePools?.find{it.id == cloudPool?.id}) {
					localItem.assignedZonePools << cloudPool
					doSave=true
				}

				if(doSave == true) {
					morpheusContext.networkSubnet.save(localItem).blockingGet()
					if(networkMatch)
						morpheusContext.network.save(networkMatch).blockingGet()
				}
			}
		} catch (e) {
			log.error("updateSubnets error: ${e}", e)
		}
	}

	protected removeSubnets(List<NetworkSubnetIdentityProjection> removeItems) {
		try {
			log.debug("removeSubnets: ${removeItems?.size()}")
			List<NetworkSubnet> subnets = []
			morpheusContext.networkSubnet.listById( removeItems.collect { it.id }).blockingSubscribe{ subnets << it }
			for(NetworkSubnet localItem in subnets) {
				if(localItem.assignedZonePools.size() == 0 || (localItem.assignedZonePools.size() == 1 && localItem.assignedZonePools.find { it.id == cloudPool.id } != null) ) {
					morpheusContext.networkSubnet.remove([localItem]).blockingGet()
				}
			}
		} catch(e) {
			log.error "Error in removeSubnets ${e}", e
		}
	}

}
