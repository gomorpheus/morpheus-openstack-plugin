package com.morpheusdata.openstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import com.morpheusdata.openstack.plugin.OpenstackPlugin
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import com.morpheusdata.openstack.plugin.utils.OpenstackSyncUtils
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class RolesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private OpenstackPlugin plugin
	private HttpApiClient apiClient
	private AuthConfig authConfig

	public RolesSync(OpenstackPlugin openstackPlugin, Cloud cloud, HttpApiClient apiClient, AuthConfig authConfig) {
		this.plugin = openstackPlugin
		this.cloud = cloud
		this.morpheusContext = openstackPlugin.morpheusContext
		this.apiClient = apiClient
		this.authConfig = authConfig
	}

	def execute() {
		log.debug "BEGIN: execute RolesSync: ${cloud.id}"
		def rtn = [success:false]
		try {
			def currentDomainId = OpenstackSyncUtils.getCloudDomainId(cloud)
			// need to pull in system level roles for cases where roles are inherited in the domain
			def unscopedResults = OpenStackComputeUtility.listRoles(apiClient, authConfig)
			def domainScopedResults = OpenStackComputeUtility.listRoles(apiClient, authConfig,  currentDomainId)
			log.debug("unscoped results: ${unscopedResults}")
			log.debug("scoped results: ${domainScopedResults}")
			if(domainScopedResults.success == true || unscopedResults.success == true) {
				def itemList = (unscopedResults?.data + domainScopedResults?.data).unique(false) { a, b -> a.id <=> b.id }
				def objCategory = OpenstackSyncUtils.getObjectCategory("role", cloud.id)
				Observable<ReferenceDataSyncProjection> domainRecords = morpheusContext.cloud.listReferenceDataByCategory(cloud, objCategory)
				SyncTask<ReferenceDataSyncProjection, Map, ReferenceData> syncTask = new SyncTask<>(domainRecords, itemList)
				syncTask.addMatchFunction { ReferenceDataSyncProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem.id
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.cloud.pool.listById(updateItems?.collect { it.existingItem.id }).map { ReferenceData rd ->
						SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map> matchItem = updateItemMap[rd.id]
						return new SyncTask.UpdateItem<ReferenceData, Map>(existingItem: rd, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					addMissingRoles(itemsToAdd, objCategory)
				}.onUpdate { List<SyncTask.UpdateItem<ReferenceData, Map>> updateItems ->
					// Nothing to update
				}.onDelete { removeItems ->
					removeMissingRoles(removeItems)
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute RolesSync: ${cloud.id}"
		return ServiceResponse.create(rtn)
	}

	private addMissingRoles(List addList, String objCategory) {
		log.debug "addMissingRoles ${cloud} ${addList.size()}"
		def adds = []

		addList?.each { Map cloudItem ->
			def addConfig = [
					code      : objCategory + '.' + cloudItem.id,
					category  : objCategory,
					name      : cloudItem.name,
					keyValue  : cloudItem.id,
					value     : cloudItem.name,
					internalId: cloudItem.id,
					externalId: cloudItem.id
			]
			def add = new ReferenceData(addConfig)
			add.setConfigMap(cloudItem)
			adds << add
		}

		if(adds) {
			morpheusContext.cloud.create(adds, cloud, objCategory).blockingGet()
		}
	}

	private removeMissingRoles(List<ReferenceData> removeList) {
		log.debug "removeMissingResourcePools: ${removeList?.size}"
		morpheusContext.cloud.remove(removeList).blockingGet()
	}
}
