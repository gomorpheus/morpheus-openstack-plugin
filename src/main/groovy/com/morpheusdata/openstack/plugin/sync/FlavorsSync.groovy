package com.morpheusdata.openstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ProvisionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.ServicePlanIdentityProjection
import com.morpheusdata.openstack.plugin.OpenstackPlugin
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import com.morpheusdata.openstack.plugin.utils.OpenstackSyncUtils
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class FlavorsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private OpenstackPlugin plugin
	private HttpApiClient apiClient
	private AuthConfig authConfig
	private ComputeZonePoolIdentityProjection cloudPool

	public FlavorsSync(OpenstackPlugin openstackPlugin, Cloud cloud, HttpApiClient apiClient, AuthConfig authConfig, ComputeZonePoolIdentityProjection cloudPool) {
		this.plugin = openstackPlugin
		this.cloud = cloud
		this.morpheusContext = openstackPlugin.morpheusContext
		this.apiClient = apiClient
		this.authConfig = authConfig
		this.cloudPool = cloudPool
	}

	def execute() {
		log.debug "BEGIN: execute FlavorsSync: ${cloud.id}, ${cloudPool.externalId}"
		def rtn = [success:false]
		try {
			authConfig.projectId = cloudPool.externalId
			def token = OpenStackComputeUtility.getToken(apiClient, authConfig)
			if (token.token) {
				def processData = [:]
				processData.objCategory = OpenstackSyncUtils.getObjectCategory("openstack.flavor.${cloud.id}.${cloudPool.externalId}", cloud.id, cloud.externalId)
				processData.listResults = OpenStackComputeUtility.listFlavors(apiClient, authConfig, authConfig.projectId)

				Observable<ServicePlanIdentityProjection> domainRecords = morpheusContext.servicePlan.listSyncProjections(cloud.id).filter{ it.deleted == false && it.category == processData.objCategory }
				 SyncTask<ServicePlanIdentityProjection, Map, ServicePlanIdentityProjection> syncTask = new SyncTask<>(domainRecords, processData.listResults?.results?.flavors)
				syncTask.addMatchFunction { ServicePlanIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem.id
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ServicePlanIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ServicePlanIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.servicePlan.listById(updateItems?.collect { it.existingItem.id }).map { ServicePlan servicePlan ->
						SyncTask.UpdateItemDto<ServicePlanIdentityProjection, Map> matchItem = updateItemMap[servicePlan.id]
						return new SyncTask.UpdateItem<ServicePlan, Map>(existingItem: servicePlan, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					addFlavors(itemsToAdd, processData)
				}.onUpdate { List<SyncTask.UpdateItem<ServicePlanIdentityProjection, Map>> updateItems ->
					updateFlavors(updateItems, processData)
				}.onDelete { removeItems ->
					removeFlavors(removeItems)
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute FlavorsSync: ${cloud.id}"
		return ServiceResponse.create(rtn)
	}

	protected void addFlavors(List addList, Map config) {
		try {
			log.debug("addFlavors: ${addList?.size()}")

			def objCategory = config.objCategory
			def adds = []
			for(remoteItem in addList) {
				def addConfig = [
						code                : "${objCategory}.${remoteItem.id}",
						category            : objCategory,
						description         : "${remoteItem.name} - Memory: ${remoteItem.ram}MB, Storage: ${remoteItem.disk}GB, vCPUs: ${remoteItem.vcpus}",
						name                : remoteItem.name,
						editable            : false,
						externalId          : remoteItem.id,
						maxStorage          : getApiSize(remoteItem.disk == 0 ? 10L : remoteItem.disk, ComputeUtility.ONE_GIGABYTE),
						maxDataStorage      : (20 * ComputeUtility.ONE_GIGABYTE),
						maxMemory           : getApiSize(remoteItem.ram, ComputeUtility.ONE_MEGABYTE),
						maxCores            : (remoteItem.vcpus ?: 1),
						visibility          : 'public',
						customCpu           : false,
						customCores         : false,
						customMaxMemory     : false,
						customMaxStorage    : remoteItem.disk == 0 ? true : false,
						provisionType       : new ProvisionType(code: 'openstack-provision-provider-plugin'),
						addVolumes          : true,
						customMaxDataStorage: true,
						regionCode          : "${cloud.id}",
						refType             : 'ComputeZone',
						refId               : cloud.id,
						account             : new Account(id: cloud.account.id),
						owner               : new Account(id: cloud.owner.id),
						visibility          : cloud.owner.id == cloud.account.id ? 'public' : 'private',
						sortOrder           : remoteItem.sortOrder
				]
				def add = new ServicePlan(addConfig)
				adds << add
			}

			if(adds) {
				morpheusContext.servicePlan.create(adds).blockingGet()
			}
		} catch(e) {
			log.error("addFlavors error: ${e}", e)
		}
	}

	protected void updateFlavors(List updateList, Map config) {
		try {
			log.debug("updateFlavors: ${updateList?.size()}")

			def objCategory = config.objCategory
			def updates = []
			for(updateMap in updateList) {
				def remoteItem = updateMap.masterItem
				ServicePlan localItem = updateMap.existingItem
				def doSave = false

				def maxStorage = getApiSize(remoteItem.disk == 0 ? 10L : remoteItem.disk, ComputeUtility.ONE_GIGABYTE)
				def maxMemory = getApiSize(remoteItem.ram, ComputeUtility.ONE_MEGABYTE)
				def customStorage = remoteItem.disk == 0 ? true : false
				def maxCpu = remoteItem.vcpus ?: 1
				def maxCores = remoteItem.vcpus ?: 1

				if(localItem.deleted) {
					localItem.deleted = false
					doSave = true
				}
				if(!localItem.owner) {
					localItem.owner = new Account(id: cloud.owner.id)
					localItem.account = new Account(id: cloud.account.id)
					localItem.visibility = cloud.owner.id == cloud.account.id ? 'public' : 'private'
					doSave = true
				}
				if(localItem.name != remoteItem.name){
					localItem.name = remoteItem.name
					doSave = true
				}
				if(localItem.maxMemory != maxMemory){
					localItem.maxMemory = maxMemory
					doSave = true
				}
				if(localItem.maxStorage != maxStorage){
					localItem.maxStorage = maxStorage
					doSave = true
				}
				if(localItem.customMaxStorage != customStorage) {
					localItem.customMaxStorage = customStorage
					doSave = true
				}
				if(localItem.maxCpu != maxCpu) {
					localItem.maxCpu = maxCpu
					doSave = true
				}
				if(localItem.maxCores != maxCores) {
					localItem.maxCores = maxCores
					doSave = true
				}
				if(localItem.addVolumes != true) {
					localItem.addVolumes = true
					doSave = true
				}
				if(localItem.customMaxDataStorage != true) {
					localItem.customMaxDataStorage = true
					doSave = true
				}
				if(remoteItem.sortOrder != null && localItem.sortOrder != remoteItem.sortOrder) {
					localItem.sortOrder = remoteItem.sortOrder
					doSave = true
				}
				if(localItem.category != objCategory) {
					localItem.category = objCategory
					doSave = true
				}

				if(doSave) {
					localItem.description = "${remoteItem.name} - Memory: ${remoteItem.ram}MB, Storage: ${remoteItem.disk}GB, vCPUs: ${remoteItem.vcpus}"
					updates << localItem
				}
			}

			if(updates) {
				morpheusContext.servicePlan.save(updates).blockingGet()
			}
		} catch(e) {
			log.error("updateFlavors error: ${e}", e)
		}
	}

	protected void removeFlavors(removeItems) {
		log.debug "removeFlavors: ${removeItems?.size()}"

		List<ServicePlan> plans = []
		morpheusContext.servicePlan.listById( removeItems.collect { it.id }).blockingSubscribe { plans << it }

		for(ServicePlan servicePlan in plans) {
			servicePlan.active = false
			servicePlan.deleted = true
		}

		morpheusContext.servicePlan.save(plans).blockingGet()
	}

	def getApiSize(val, multiplier) {
		def rtn = 0
		try {
			rtn = val ? val.toLong() : 0
			rtn = rtn * multiplier
		} catch(e) {
			//oh well
		}
		return rtn
	}
}
