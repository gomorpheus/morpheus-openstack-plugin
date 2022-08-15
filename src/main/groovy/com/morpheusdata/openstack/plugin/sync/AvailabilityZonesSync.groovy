package com.morpheusdata.openstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.openstack.plugin.OpenstackPlugin
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import com.morpheusdata.openstack.plugin.utils.OpenstackSyncUtils
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class AvailabilityZonesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private OpenstackPlugin plugin
	private HttpApiClient apiClient
	private AuthConfig authConfig
	private ComputeZonePoolIdentityProjection cloudPool

	public AvailabilityZonesSync(OpenstackPlugin openstackPlugin, Cloud cloud, HttpApiClient apiClient, AuthConfig authConfig, ComputeZonePoolIdentityProjection cloudPool) {
		this.plugin = openstackPlugin
		this.cloud = cloud
		this.morpheusContext = openstackPlugin.morpheusContext
		this.apiClient = apiClient
		this.authConfig = authConfig
		this.cloudPool = cloudPool
	}

	def execute() {
		log.debug "BEGIN: execute AvailabilityZonesSync: ${cloud.id}, ${cloudPool.externalId}"
		def rtn = [success:false]
		try {
			authConfig.projectId = cloudPool.externalId
			def token = OpenStackComputeUtility.getToken(apiClient, authConfig)
			if (token.token) {
				def processData = [:]
				processData.objCategory = OpenstackSyncUtils.getObjectCategory("availabilityzone", cloud.id, cloudPool.externalId)
				processData.listResults = OpenStackComputeUtility.listAvailabilityZones(apiClient, authConfig, authConfig.projectId)
				
				List<Long> existingProjectionsId = []
				morpheusContext.cloud.listReferenceDataByCategory(cloud, processData.objCategory).blockingSubscribe { existingProjectionsId << it.id }
				

				Observable<ReferenceData> domainRecords = morpheusContext.cloud.listReferenceDataById(existingProjectionsId)
				SyncTask<ReferenceData, Map, ReferenceData> syncTask = new SyncTask<>(domainRecords, processData.listResults?.results?.availabilityZoneInfo)
				syncTask.addMatchFunction { ReferenceData domainObject, Map cloudItem ->
					domainObject.keyValue == cloudItem?.zoneName && domainObject.category == processData.objCategory
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ReferenceData, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ReferenceData, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.cloud.pool.listById(updateItems?.collect { it.existingItem.id }).map { ReferenceData pool ->
						SyncTask.UpdateItemDto<ReferenceData, Map> matchItem = updateItemMap[pool.id]
						return new SyncTask.UpdateItem<ReferenceData, Map>(existingItem: pool, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					addAvailabilityZones(itemsToAdd, processData)
				}.onUpdate { List<SyncTask.UpdateItem<ReferenceData, Map>> updateItems ->
					// Nothing to do
				}.onDelete { removeItems ->
					removeAvailabilityZones(removeItems)
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute AvailabilityZonesSync: ${cloud.id}"
		return ServiceResponse.create(rtn)
	}

	protected void addAvailabilityZones(List addList, Map config) {
		try {
			log.debug("addAvailabilityZones: ${addList?.size()}")

			def objCategory = config.objCategory

			def adds = []
			for(remoteItem in addList) {
				if(remoteItem.zoneState.available) {
					def add = new ReferenceData([
							code    : "${objCategory}.${remoteItem.zoneName}",
							category: objCategory,
							name    : remoteItem.zoneName,
							keyValue: remoteItem.zoneName,
							value   : remoteItem.zoneName
					])
					adds << add
				}
			}

			if(adds) {
				morpheusContext.cloud.create(adds, cloud, objCategory).blockingGet()
			}
		} catch(e) {
			log.error("addAvailabilityZones error: ${e}", e)
		}
	}

	protected void removeAvailabilityZones(List removeList) {
		try {
			log.debug("removeAvailabilityZones: ${removeList?.size()}")
			morpheusContext.cloud.remove(removeList).blockingGet()
		} catch(e) {
			log.error("removeAvailabilityZones error: ${e}", e)
		}
	}
}
