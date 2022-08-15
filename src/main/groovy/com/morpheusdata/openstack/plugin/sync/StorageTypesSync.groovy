package com.morpheusdata.openstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.openstack.plugin.OpenstackPlugin
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import com.morpheusdata.openstack.plugin.utils.OpenstackSyncUtils
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class StorageTypesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private OpenstackPlugin plugin
	private HttpApiClient apiClient
	private AuthConfig authConfig
	private ComputeZonePoolIdentityProjection cloudPool

	public StorageTypesSync(OpenstackPlugin openstackPlugin, Cloud cloud, HttpApiClient apiClient, AuthConfig authConfig, ComputeZonePoolIdentityProjection cloudPool) {
		this.plugin = openstackPlugin
		this.cloud = cloud
		this.morpheusContext = openstackPlugin.morpheusContext
		this.apiClient = apiClient
		this.authConfig = authConfig
		this.cloudPool = cloudPool
	}

	def execute() {
		log.debug "BEGIN: execute StorageTypesSync: ${cloud.id}, ${cloudPool.externalId}"
		def rtn = [success:false]
		try {
			authConfig.projectId = cloudPool.externalId
			def token = OpenStackComputeUtility.getToken(apiClient, authConfig)
			if (token.token) {
				def processData = [:]
				processData.objCategory = OpenstackSyncUtils.getObjectCategory("volumeType", cloud.id, cloud.externalId)
				processData.listResults = processVolumeTypeListResults(OpenStackComputeUtility.listVolumeTypes(apiClient, authConfig, authConfig.projectId))
				processData.apiMicroVersion = OpenStackComputeUtility.getOpenstackComputeMicroVersion(apiClient, authConfig)
				processData.openstackVolumeType = new StorageVolumeType(code: "openstack-plugin-cloudVolume")

				List<Long> datastoreIds = []
				morpheusContext.cloud.datastore.listSyncProjections(cloud.id).blockingSubscribe {datastoreIds << it.id }

				Observable<Datastore> domainRecords = morpheusContext.cloud.datastore.listById(datastoreIds).filter { Datastore it ->
					it.owner.id == cloud.account.id && it.category == processData.objCategory && it.assignedZonePools.find { ComputeZonePool czp -> czp.id == cloudPool.id } != null
				}

				// volume type is only supported in nova api, since the volume types are currently only used for provisioning and there
				// are no simple ways to adjust the UI as needed per cloud, just clear out volume types if they can't be used on this cloud
				// TODO: remove this after the UI is adjusted
				if(processData.apiMicroVersion && MorpheusUtils.compareVersions(processData.apiMicroVersion, '2.67') < 0) {
					processData.listResults = []
				}

				SyncTask<Datastore, Map, Datastore> syncTask = new SyncTask<>(domainRecords, processData.listResults.data?.volume_types)
				syncTask.addMatchFunction { Datastore domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem.id
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<Datastore, Map>> updateItems ->
					updateItems.collect { new SyncTask.UpdateItem<ServicePlan, Map>(existingItem: it.existingItem, masterItem: it.masterItem) }
				}.onAdd { itemsToAdd ->
					addVolumeTypes(itemsToAdd, processData)
				}.onUpdate { List<SyncTask.UpdateItem<Datastore, Map>> updateItems ->
					updateVolumeTypes(updateItems, processData)
				}.onDelete { removeItems ->
					removeVolumeTypes(removeItems)
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute StorageTypesSync: ${cloud.id}"
		return ServiceResponse.create(rtn)
	}

	protected void addVolumeTypes(List addList, Map config) {
		try {
			log.debug("addVolumeTypes: ${addList?.size()}")

			def adds = []
			def objCategory = config.objCategory

			for(remoteItem in addList) {
				Datastore add = new Datastore([
						owner: new Account(id: cloud.account.id),
						cloud: cloud,
						code:objCategory + '.' + remoteItem.id,
						category:objCategory,
						name:remoteItem.display_name,
						externalId: remoteItem.id,
						assignedZonePools: [cloudPool],
						volumeType: config.openstackVolumeType
				])
				add.rawData = JsonOutput.toJson(remoteItem).toString()
				adds << add
			}

			if(adds) {
				morpheusContext.cloud.datastore.create(adds).blockingGet()
			}
		} catch(e) {
			log.error("addVolumeTypes error: ${e}", e)
		}
	}

	protected void updateVolumeTypes(List updateList, Map config) {
		try {
			log.debug("updateVolumeTypes: ${updateList?.size()}")

			def updates = []
			for(updateMap in updateList) {
				def remoteItem = updateMap.masterItem
				Datastore localItem = updateMap.existingItem
				def doSave = false

				if(localItem.name != remoteItem.name) {
					localItem.name = remoteItem.name
					doSave = true
				}

				if(cloudPool && !localItem.assignedZonePools?.find{it.id == cloudPool?.id}) {
					localItem.assignedZonePools << cloudPool
					doSave = true
				}

				if(doSave == true) {
					localItem.rawData = JsonOutput.toJson(remoteItem).toString()
					updates << localItem
				}
			}

			if(updates) {
				morpheusContext.cloud.datastore.save(updates).blockingGet()
			}
		} catch(e) {
			log.error("updateVolumeTypes error: ${e}", e)
		}
	}

	protected void removeVolumeTypes(removeItems) {
		log.debug "removeVolumeTypes: ${removeItems?.size()}"

		for(Datastore localItem in removeItems) {
			int idx = localItem.assignedZonePools?.findIndexOf { it.id == cloudPool.id }
			if(idx >= 0) {
				localItem.assignedZonePools.remove(idx)
			}
			if(localItem.assignedZonePools.size() == 0) {
				morpheusContext.cloud.datastore.remove([localItem]).blockingGet()
			} else {
				morpheusContext.cloud.datastore.save([localItem]).blockingGet()
			}
		}
	}

	private processVolumeTypeListResults(Map volumeTypeResults) {
		if(volumeTypeResults.results) {
			volumeTypeResults.results.volume_types = translateVolumeTypeNames(volumeTypeResults.results.volume_types)
			volumeTypeResults.results.volume_types = parseVolumeTypeAvailabilityZones(volumeTypeResults.results.volume_types)
		}
		volumeTypeResults.data = volumeTypeResults.results

		return volumeTypeResults
	}

	private translateVolumeTypeNames(List volumeTypes) {
		for(volumeType in volumeTypes) {
			volumeType.display_name = OpenStackComputeUtility.getTranslatedVolumeTypeName(volumeType.name)
		}

		return volumeTypes
	}

	private parseVolumeTypeAvailabilityZones(List volumeTypes) {
		for(volumeType in volumeTypes) {
			volumeType.availability_zones = OpenStackComputeUtility.extractVolumeTypeAvailabilityZone(volumeType)
		}

		return volumeTypes
	}

}
