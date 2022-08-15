package com.morpheusdata.openstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import com.morpheusdata.openstack.plugin.OpenstackPlugin
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import com.morpheusdata.openstack.plugin.utils.OpenstackSyncUtils
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class ProjectsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private OpenstackPlugin plugin
	private HttpApiClient apiClient
	private AuthConfig authConfig

	public ProjectsSync(OpenstackPlugin openstackPlugin, Cloud cloud, HttpApiClient apiClient, AuthConfig authConfig) {
		this.plugin = openstackPlugin
		this.cloud = cloud
		this.morpheusContext = openstackPlugin.morpheusContext
		this.apiClient = apiClient
		this.authConfig = authConfig
	}

	def execute() {
		log.debug "BEGIN: execute ProjectsSync: ${cloud.id}, ${authConfig}"
		def rtn = [success:false]
		try {
			def userRoleAssignments = OpenStackComputeUtility.listUserRoleAssignments(apiClient, authConfig, OpenstackSyncUtils.getCloudApiUserId(cloud))
			if(!userRoleAssignments.success) {
				log.warn "Error in getting projects or role assignments so not continuing with ProjectsSync"
				return
			}

			// Fetch the 'master' items
			def listResults = OpenStackComputeUtility.listProjects(apiClient, authConfig)
			if(!listResults.success) {
				log.warn "Error in getting projects so not continuing with ProjectsSync"
				return
			}

			// Build up the data to sync
			def processData = [
					cloudAccount             : cloud.account,
					cloudOwner               : cloud.owner,
					cloudId                  : cloud.id,
					cloudVisibility          : cloud.visibility,
					objCategory              : OpenstackSyncUtils.getObjectCategory("resourcepool", cloud.id),
					roleCategory             : OpenstackSyncUtils.getObjectCategory("role", cloud.id),
					cloudProjectId           : OpenstackSyncUtils.getCloudProjectId(cloud),
					cloudProjectName         : cloud.getConfigProperty("projectName"),
					currentDomainId          : OpenstackSyncUtils.getCloudDomainId(cloud),
					apiUserId                : OpenstackSyncUtils.getCloudApiUserId(cloud),
					listResults              : [],
					listRoleAssignmentResults: userRoleAssignments,
					syncCloudPermissionsToPool: false,
					localRoleList            : new ArrayList<ReferenceDataSyncProjection>(),
					syncCloudPermissionsToPool: false,
					isCloudProjectScoped     : false,
			]

			morpheusContext.cloud.listReferenceDataByCategory(cloud, processData.roleCategory).blockingSubscribe{  processData.localRoleList << it }
			if(OpenStackComputeUtility.isCloudProjectScoped(cloud)) {
				processData.syncCloudPermissionsToPool = true
				processData.isCloudProjectScoped = true
			}
			if(morpheusContext.cloud.pool.listSyncProjections(cloud.id, '').count().blockingGet() == 0 || processData.listResults?.size() == 1) {
				// copy zone tenant access to a new zone or single project zone
				processData.syncCloudPermissionsToPool = true
			}

			// We're either scoped to a single project.. or all so filter the list down
			processData.listResults = listResults.results.projects?.findAll { it ->
				def include = it.enabled && !it.is_domain
				if(include) {
					if(processData.isCloudProjectScoped) {
						if (processData.cloudProjectId) {
							include = it.id == processData.cloudProjectId
						} else {
							include = it.name == processData.cloudProjectName
						}
					}
				}
				include
			}

			Observable<ComputeZonePoolIdentityProjection> domainRecords = morpheusContext.cloud.pool.listSyncProjections(cloud.id, '')
			SyncTask<ComputeZonePoolIdentityProjection, Map, ComputeZonePool> syncTask = new SyncTask<>(domainRecords, processData.listResults)
			syncTask.addMatchFunction { ComputeZonePoolIdentityProjection domainObject, Map cloudItem ->
				domainObject.externalId == cloudItem.id
			}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeZonePoolIdentityProjection, Map>> updateItems ->
				Map<Long, SyncTask.UpdateItemDto<ComputeZonePoolIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
				morpheusContext.cloud.pool.listById(updateItems?.collect { it.existingItem.id }).map { ComputeZonePool pool ->
					SyncTask.UpdateItemDto<ComputeZonePoolIdentityProjection, Map> matchItem = updateItemMap[pool.id]
					return new SyncTask.UpdateItem<ComputeZonePool, Map>(existingItem: pool, masterItem: matchItem.masterItem)
				}
			}.onAdd { itemsToAdd ->
				addMissingResourcePools(itemsToAdd, processData)
			}.onUpdate { List<SyncTask.UpdateItem<ComputeZonePool, Map>> updateItems ->
				updateMatchedResourcePools(updateItems, processData)
			}.onDelete { removeItems ->
				removeMissingResourcePools(removeItems)
			}.start()
		} catch(Exception e) {
			log.error("ProjectsSync error: {}", e, e)
		}
	
		log.debug "END: execute ProjectsSync: ${cloud.id}"
		return ServiceResponse.create(rtn)
	}

	private addMissingResourcePools(List addList, Map processData) {
		log.debug "addMissingResourcePools ${cloud} ${addList.size()}"
		def adds = []

		def objCategory = processData.objCategory
		def parentPools = getParentPoolsMap(addList)

		for(cloudItem in addList) {
			def poolConfig = [
					owner     : processData.cloudOwner,
					name      : cloudItem.name,
					externalId: cloudItem.id,
					category  : objCategory,
					refType   : 'ComputeZone',
					refId     : cloud.id,
					cloud     : cloud,
					type      : 'default',
					code      : "${objCategory}.${cloudItem.id}"
			]

			if(processData.syncCloudPermissionsToPool) {
				poolConfig.visibility = processData.cloudVisibility
			}

			def add = new ComputeZonePool(poolConfig)
			if(cloudItem.parent_id) {
				def parentPool = parentPools[cloudItem.parent_id]
				if(parentPool) {
					add.parent = new ComputeZonePool(id: parentPool.id)
				}
			}

			def roleAssignment = processData.listRoleAssignmentResults?.data?.role_assignments.find { it.scope?.project?.id == cloudItem.id }
			if(roleAssignment) {
				def localRoleAssignment = processData.localRoleList.find { it.externalId == roleAssignment.id }
				if(localRoleAssignment) {
					add.setConfigProperty("roleId", localRoleAssignment.id)
				}
			}

			adds << add
		}

		if(adds) {
			morpheusContext.cloud.pool.create(adds).blockingGet()
		}
	}

	private updateMatchedResourcePools(List updateList, Map processData) {
		log.debug "updateMatchedResourcePools: ${updateList.size()}"

		def updates = []

		def objCategory = processData.objCategory
		def parentPools = getParentPoolsMap(updateList.collect { it.masterItem } )

		for(updateMap in updateList) {
			def remoteItem = updateMap.masterItem
			def localItem = updateMap.existingItem
			def doSave = false

			if(localItem.category != objCategory) {
				localItem.category = objCategory
				doSave = true
			}

			if(localItem.name !=  remoteItem.name) {
				localItem.name = remoteItem.name
				doSave = true
			}

			if(localItem.parent?.externalId != remoteItem.parent_id) {
				def parentPool = parentPools[remoteItem.parent_id]
				localItem.parent = new ComputeZonePool(id: parentPool?.id)
				doSave = true
			}

			// cache the user assigned role for the project
			def externalRoleAssignment = processData.listRoleAssignmentResults.data.role_assignments.find { it.scope?.project?.id == remoteItem.id }
			if(externalRoleAssignment) {
				def localRoleAssignment = processData.localRoleList.find { it.externalId == externalRoleAssignment.id }
				if(externalRoleAssignment.id != localRoleAssignment?.externalId) {
					localItem.setConfigProperty("roleId", externalRoleAssignment.id)
					doSave = true
				}
			}

			if(doSave) {
				updates << localItem
			}
		}

		if(updates) {
			morpheusContext.cloud.pool.save(updates).blockingGet()
		}
	}

	private removeMissingResourcePools(List<ComputeZonePoolIdentityProjection> removeList) {
		log.debug "removeMissingResourcePools: ${removeList?.size}"
		morpheusContext.cloud.pool.remove(removeList).blockingGet()
	}

	private Map<String, ComputeZonePoolIdentityProjection> getParentPoolsMap(cloudItems) {
		// Gather up all the parent pools
		def parentIds = []
		for(cloudItem in cloudItems) {
			if(cloudItem.parent_id) {
				parentIds << cloudItem.parent_id
			}
		}

		def parentPools = [:]
		if(parentIds) {
			morpheusContext.cloud.pool.listSyncProjections(cloud.id, '').filter { ComputeZonePoolIdentityProjection projection ->
				return (projection.externalId in parentIds)
			}.blockingSubscribe {
				parentPools[it.externalId] = it
			}
		}
		parentPools
	}
}
