package com.morpheusdata.openstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.OsType
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.openstack.plugin.OpenstackPlugin
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import com.morpheusdata.openstack.plugin.utils.OpenstackSyncUtils
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class HostsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private OpenstackPlugin plugin
	private HttpApiClient apiClient
	private AuthConfig authConfig
	private ComputeZonePoolIdentityProjection cloudPool

	public HostsSync(OpenstackPlugin openstackPlugin, Cloud cloud, HttpApiClient apiClient, AuthConfig authConfig, ComputeZonePoolIdentityProjection cloudPool) {
		this.plugin = openstackPlugin
		this.cloud = cloud
		this.morpheusContext = openstackPlugin.morpheusContext
		this.apiClient = apiClient
		this.authConfig = authConfig
		this.cloudPool = cloudPool
	}

	def execute() {
		log.debug "BEGIN: execute HostsSync: ${cloud.id}"
		def rtn = [success:false]
		try {
			def results = OpenStackComputeUtility.listHypervisors(apiClient, authConfig, cloudPool.externalId)
			def objCategory = OpenstackSyncUtils.getObjectCategory("host", cloud.id)
			if(results.success == true) {

				Observable<ComputeServerIdentityProjection> domainRecords = morpheusContext.computeServer.listSyncProjections(cloud.id).filter { ComputeServerIdentityProjection projection ->
					projection.category == objCategory
				}
				SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, results?.results?.hypervisors)
				syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == "${cloudItem.id}"
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
						SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]

						// Must fetch the Hypervisor details from Openstack
						def hostDetail = OpenStackComputeUtility.getHypervisor(apiClient, authConfig, cloudPool.externalId.toString(), matchItem.masterItem.id.toString())
						if(hostDetail.success == true && hostDetail.results?.hypervisor) {
							return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: hostDetail.results.hypervisor)
						} else {
							log.warn "Failure in getting hypervisor for project ${cloudPool.externalId} and id ${matchItem.masterItem.id}"
						}
					}
				}.onAdd { itemsToAdd ->
					addMissingHosts(itemsToAdd, objCategory)
				}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
					updateHosts(updateItems)
				}.onDelete { removeItems ->
					removeMissingHosts(removeItems)
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute HostsSync: ${cloud.id}"
		return ServiceResponse.create(rtn)
	}

	private addMissingHosts(List addList, String objCategory) {
		log.debug "addMissingHosts ${cloud} ${addList.size()}"
		def adds = []

		addList?.each { Map cloudItem ->

			def serverConfig = [
					account          : new Account(id: cloud.owner.id),
					category         : objCategory,
					name             : cloudItem.hypervisor_hostname,
					externalId       : cloudItem.id,
					cloud            : cloud,
					sshUsername      : 'root',
					status           : 'provisioned',
					provision        : false,
					singleTenant     : false,
					serverType       : 'hypervisor',
					computeServerType: new ComputeServerType(code: "openstack-provision-provider-pluginHypervisor"),
					statusDate       : new Date(),
					serverOs         : new OsType(code: 'linux'),
					osType           : 'linux',
					hostname         : cloudItem.hypervisor_hostname
			]
			def newServer = new ComputeServer(serverConfig)
			newServer.maxMemory = (cloudItem.memory_mb ?: 0) * ComputeUtility.ONE_MEGABYTE
			newServer.maxCores = (cloudItem.vcpus ?: 0)
			newServer.maxStorage = (cloudItem.local_gb ?: 0) * ComputeUtility.ONE_GIGABYTE
			newServer.capacityInfo = new ComputeCapacityInfo(maxMemory:newServer.maxMemory, maxCores:newServer.maxCores, maxStorage:newServer.maxStorage)

			adds << newServer
		}

		if(adds) {
			morpheusContext.computeServer.create(adds).blockingGet()
		}
	}

	private updateHosts(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList) {
		log.debug "updateHosts ${cloud} ${updateList.size()}"
		try {
			def serversToUpdate = []

			for(update in updateList) {
				ComputeServer server = update.existingItem
				def host = update.masterItem

				//cpu
				def maxCores = (host.vcpus ?: 0)
				def maxUsedCores = (host.vcpus_used ?: 0)
				//current_workload - check that for cpu usage?
				//storage
				def maxStorage = (host.local_gb ?: 0) * ComputeUtility.ONE_GIGABYTE
				def maxUsedStorage = (host.local_gb_used ?: 0) * ComputeUtility.ONE_GIGABYTE
				//memory
				def maxMemory = (host.memory_mb ?: 0) * ComputeUtility.ONE_MEGABYTE
				def maxUsedMemory = (host.memory_mb_used ?: 0) * ComputeUtility.ONE_MEGABYTE
				//power state
				def power = host.state
				def powerState = ComputeServer.PowerState.unknown
				if (power == 'up')
					powerState = ComputeServer.PowerState.on
				else if (power == 'down')
					powerState = ComputeServer.PowerState.off
				//save it all
				def updates = !server.getComputeCapacityInfo()
				ComputeCapacityInfo capacityInfo = server.computeCapacityInfo ?: new ComputeCapacityInfo(maxMemory: maxMemory, maxStorage: maxStorage)
				server.computeCapacityInfo = capacityInfo
				if (maxMemory > server.maxMemory) {
					server.maxMemory = maxMemory
					capacityInfo?.maxMemory = maxMemory
					updates = true
				}
				if (maxUsedMemory != capacityInfo.usedMemory) {
					capacityInfo.usedMemory = maxUsedMemory
					updates = true
				}
				if (maxStorage > server.maxStorage) {
					server.maxStorage = maxStorage
					capacityInfo?.maxStorage = maxStorage
					updates = true
				}
				if (maxUsedStorage != capacityInfo.usedStorage) {
					capacityInfo.usedStorage = maxUsedStorage
					updates = true
				}
				if (maxCores > server.maxCores) {
					server.maxCores = maxCores
					capacityInfo?.maxCores = maxCores
					updates = true
				}
				if (maxUsedCores != capacityInfo.usedCores) {
					capacityInfo.usedCores = maxUsedCores
					updates = true
				}
				if (server.powerState != powerState) {
					server.powerState = powerState
					updates = true
				}
				if (host.hypervisor_hostname && host.hypervisor_hostname != server.hostname) {
					server.hostname = host.hypervisor_hostname
					updates = true
				}
				if (updates == true) {
					serversToUpdate << server
				}
			}

			if(serversToUpdate) {
				morpheusContext.computeServer.save(serversToUpdate).blockingGet()
			}
		} catch(e) {
			log.error "Error updating hosts: ${e}", e
		}
	}

	private removeMissingHosts(List<ComputeServerIdentityProjection> removeList) {
		log.debug "removeMissingHosts: ${removeList?.size}"
		morpheusContext.computeServer.remove(removeList).blockingGet()
	}
}
