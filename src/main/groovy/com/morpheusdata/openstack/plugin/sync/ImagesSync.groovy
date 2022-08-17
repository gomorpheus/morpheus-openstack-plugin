package com.morpheusdata.openstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncList
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import com.morpheusdata.openstack.plugin.OpenstackPlugin
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import com.morpheusdata.openstack.plugin.utils.OpenstackSyncUtils
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class ImagesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private OpenstackPlugin plugin
	private HttpApiClient apiClient
	private AuthConfig authConfig
	private ComputeZonePoolIdentityProjection cloudPool

	public ImagesSync(OpenstackPlugin openstackPlugin, Cloud cloud, HttpApiClient apiClient, AuthConfig authConfig, ComputeZonePoolIdentityProjection cloudPool) {
		this.plugin = openstackPlugin
		this.cloud = cloud
		this.morpheusContext = openstackPlugin.morpheusContext
		this.apiClient = apiClient
		this.authConfig = authConfig
		this.cloudPool = cloudPool
	}

	def execute() {
		log.debug "BEGIN: execute ImagesSync: ${cloud.id}"
		def rtn = [success:false]
		try {
			def results = OpenStackComputeUtility.listImages(apiClient, authConfig, authConfig.projectId)
			log.debug "cacheImages results.success: ${results.success}"
			log.debug "cacheImages results?.results?.images?.size(): ${results?.results?.images?.size()}"

			if(results.success == true) {

				dedupeLocations()

				def objList = results?.results?.images

				Observable<VirtualImageLocation> domainRecords = loadLocations()
				SyncTask<VirtualImageLocation, Map, VirtualImageLocation> syncTask = new SyncTask<>(domainRecords, objList)
				syncTask.addMatchFunction { VirtualImageLocation domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem.id
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<VirtualImageLocation, Map>> updateItems ->
					List<SyncTask.UpdateItem<VirtualImageLocation, Map>> list = updateItems.collect { it ->
						return new SyncTask.UpdateItem<VirtualImageLocation, Map>(existingItem: it.existingItem, masterItem: it.masterItem)
					}
					Observable.fromIterable(list);
				}.onAdd { itemsToAdd ->
					addMissingVirtualImageLocations(itemsToAdd)
				}.onUpdate { List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems ->
					updateMatchedVirtualImages(updateItems)
				}.onDelete { removeItems ->
					removeMissingVirtualImages(removeItems)
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute ImagesSync: ${cloud.id}"
		return ServiceResponse.create(rtn)
	}

	protected addMissingVirtualImageLocations(List objList) {
		log.debug "addMissingVirtualImageLocations: ${objList.size()}"
		List<ImageType> imageTypes = objList.collect { ImageType.valueOf(it.disk_format?.toLowerCase() ?: 'qcow2')}?.unique()
		def names = objList.collect{it.name}?.unique()
		List<VirtualImageIdentityProjection> existingItems = []
		morpheusContext.virtualImage.listSyncProjections(cloud.id).filter{ VirtualImageIdentityProjection it ->
			it.name in names && it.imageType in imageTypes && (it.systemImage || !it.ownerId || it.ownerId == cloud.owner.id)
		}.blockingSubscribe { existingItems << it }


		def existingIds = existingItems.collect{existing -> [name:existing.name, externalId:existing.externalId, imageType:existing.imageType.toString(), id:existing.id]} ?: []
		existingIds.unique{"${it.imageType}:${it.name}"}

		SyncList.MatchFunction matchFunction = { Map morpheusItem, Map cloudItem ->
			cloudItem.name == morpheusItem.name && ((cloudItem.disk_format ?: 'qcow2') == morpheusItem.imageType || cloudItem.disk_format?.toLowerCase() == 'raw')
		}
		SyncList<Map, Map> syncList = new SyncList(matchFunction)
		SyncList.SyncResult<Map, Map> syncResult = syncList.buildSyncLists(existingIds, objList)

		//add missing
		while(syncResult.addList?.size() > 0) {
			Collection<Map> chunkedAddList = syncResult.addList.take(50)
			syncResult.addList = syncResult.addList.drop(50)
			addMissingVirtualImages(chunkedAddList)
		}
		//update list
		while(syncResult.updateList?.size() > 0) {
			Collection<SyncList.UpdateItem> chunkedUpdateList = syncResult.updateList.take(50)
			syncResult.updateList = syncResult.updateList.drop(50)
			updateMatchedVirtualImages(chunkedUpdateList)
		}
		//removes?
		syncResult.removeList?.each { removeItem ->
			println("need to remove: ${removeItem}")
		}
	}

	protected addMissingVirtualImages(Collection<Map> addList) {
		log.debug "addMissingVirtualImages: ${addList?.size()}"
		def objCategory = OpenstackSyncUtils.getObjectCategory("image", cloud.id)
		Map<String, VirtualImage> adds = [:]
		addList?.each { it ->
			def imageConfig = [
					account     : cloud.account,
					ownerId     : cloud.owner.id,
					category    : objCategory,
					name        : it.name,
					code        : "${objCategory}.${it.id}",
					imageType   : ImageType.valueOf(it.disk_format ?: 'qcow2'),
					externalType: it.container_format,
					minDisk     : it.min_disk,
					minRam      : it.min_ram ? (it.min_ram * ComputeUtility.ONE_MEGABYTE) : null,
					remotePath  : it.file,
					externalId  : it.id,
					status      : it.status,
					isPublic    : it.visibility == 'public',
			]
			def add = new VirtualImage(imageConfig)
			adds[add.externalId] = add
		}

		// Create em all!
		log.debug "About to create ${adds.size()} virtualImages"
		morpheusContext.virtualImage.create(adds.collect { it.value }, cloud).blockingGet()

		// Fetch the images that we just created
		def imageMap = [:]
		morpheusContext.virtualImage.listSyncProjections(cloud.id).filter { VirtualImageIdentityProjection proj ->
			adds.containsKey(proj.externalId)
		}.blockingSubscribe { imageMap[it.externalId] = it }

		// Now add the locations
		def locationAdds = []
		adds?.each { externalId, VirtualImage virtualImage ->
			log.debug "Adding location for ${externalId}"
			VirtualImageIdentityProjection virtualImageLocationProj = imageMap[externalId]
			if(virtualImageLocationProj) {
				def locationConfig = [
						virtualImage: new VirtualImage(id: virtualImageLocationProj.id),
						code        : "${objCategory}.${externalId}",
						externalId  : externalId,
						imageRegion : cloud.regionCode,
						imageName   : virtualImage.name,
						isPublic    : virtualImage.public,
						owner       : new Account(id: virtualImage.ownerId),
						imageFolder : cloudPool.externalId
				]
				def addLocation = new VirtualImageLocation(locationConfig)
				locationAdds << addLocation
			} else {
				log.warn "Unable to find virtualImage for ${externalId}"
			}
		}

		if(locationAdds) {
			log.debug "About to create ${locationAdds.size()} locations"
			morpheusContext.virtualImage.location.create(locationAdds, cloud).blockingGet()
		}
	}

	protected updateMatchedVirtualImages(Collection<SyncList.UpdateItem> updateList) {
		log.debug "updateMatchedVirtualImage: ${updateList.size()}"
		def objCategory = OpenstackSyncUtils.getObjectCategory("image", cloud.id)
		def locationIds = updateList?.findAll{ it ->
			it.existingItem instanceof VirtualImageLocation && it.existingItem.id || it.existingItem.locationId != null
		}?.collect{ it.existingItem instanceof VirtualImageLocation && it.existingItem.id ?: it.existingItem.locationId}
		List<VirtualImageLocationIdentityProjection> existingLocations = []
		if(locationIds) {
			morpheusContext.virtualImage.location.listSyncProjections(cloud.id).filter { it ->
				it.id in locationIds
			}.blockingSubscribe{ existingLocations << it }
		}
		def imageIds = updateList?.findAll{ it.existingItem.id }?.collect{ it.existingItem.id }
		def externalIds = updateList?.findAll{ it.existingItem.externalId }?.collect{ it.existingItem.externalId }
		List<VirtualImage> existingItems = []
		if(imageIds && externalIds) {
			List<Long> virtualImageProjIds = []
			morpheusContext.virtualImage.listSyncProjections(cloud.id).filter { it ->
				it.id in imageIds || (it.systemImage == false && it.externalId != null && it.externalId in externalIds)
			}.blockingSubscribe { virtualImageProjIds << it.id }

			morpheusContext.virtualImage.listById(virtualImageProjIds).filter { it ->
				it.id in imageIds || it.imageLocations.size() == 0
			}.blockingSubscribe{ existingItems << it }
		} else if(imageIds) {
			morpheusContext.virtualImage.listById(imageIds).blockingSubscribe{ existingItems << it }
		}

		//dedupe
		def groupedImages = existingItems.groupBy({ row -> row.externalId })
		def dupedImages = groupedImages.findAll{ key, value -> key != null && value.size() > 1 }
		if(dupedImages?.size() > 0) {
			log.warn("removing duplicate images: {}", dupedImages.collect { it.key })
		}
		dupedImages?.each { key, value ->
			//each pass is set of all the images with the same external id
			def dupeCleanup = []
			value.eachWithIndex { row, index ->
				def locationMatch = existingLocations.find{ it.virtualImage.id == row.id }
				if(locationMatch == null) {
					dupeCleanup << row
					existingItems.remove(row)
				}
			}
			//cleanup
			log.info("duplicate key: ${key} total: ${value.size()} remove count: ${dupeCleanup.size()}")
			//remove the dupes
			morpheusContext.virtualImage.remove([dupeCleanup], cloud)
		}

		//updates
		List<VirtualImageLocation> imageLocations = []
		def locationIdsToLoad = existingLocations?.collect { it.id }
		if(locationIdsToLoad) {
			morpheusContext.virtualImage.location.listById(locationIdsToLoad).blockingSubscribe { imageLocations << it }
		}
		updateList?.each { update ->
			log.debug "Working on update ${update.masterItem.name} ${update.existingItem.externalId}"
			def matchedTemplate = update.masterItem
			VirtualImageLocation imageLocation = imageLocations?.find { it ->
				def locationId = update.existingItem instanceof VirtualImageLocation ? update.existingItem.id : update.existingItem.locationId
				it.id == locationId
			}
			if(imageLocation) {
				// println "image match found! ${imageLocation.imageName}"
				def save = false
				def saveImage = false
				VirtualImage tmpImage
				if(imageLocation.imageName != matchedTemplate.name) {
					imageLocation.imageName = matchedTemplate.name
					tmpImage = tmpImage ?: morpheusContext.virtualImage.get(imageLocation.virtualImage.id).blockingGet()
					if(tmpImage && tmpImage.imageLocations?.size() < 2) {
						tmpImage.name = matchedTemplate.name
						saveImage = true
					}
					save = true
				}
				if(imageLocation.imageRegion != cloud.regionCode) {
					imageLocation.imageRegion = cloud.regionCode
					save = true
				}
				if(imageLocation.imageFolder != cloudPool.externalId) {
					imageLocation.imageFolder = cloudPool.externalId
					save = true
				}
				if(imageLocation.externalId != matchedTemplate.id) {
					imageLocation.externalId = matchedTemplate.id
					save = true
				}
				if(imageLocation.owner != matchedTemplate.owner) {
					imageLocation.owner = matchedTemplate.owner
					save = true
				}
				def isPublic = matchedTemplate.visibility != 'private'
				if(imageLocation.isPublic != isPublic) {
					imageLocation.isPublic = isPublic
					save = true
				}
				if(imageLocation.virtualImage.deleted) {
					tmpImage = tmpImage ?: morpheusContext.virtualImage.get(imageLocation.virtualImage.id).blockingGet()
					tmpImage.deleted = false
					saveImage = true
				}
//				if(!imageLocation.virtualImage.osType) {
//					addMissingVirtualImageOsTypeAndPlatform(imageLocation.virtualImage, matchedTemplate)
//					save = true
//				}
				//save it
				if(saveImage) {
					morpheusContext.virtualImage.save([tmpImage], cloud).blockingGet()
				}
				if(save) {
					morpheusContext.virtualImage.location.save([imageLocation], cloud).blockingGet()
				}
			} else {
				VirtualImage image = existingItems?.find { (it.externalId == matchedTemplate.id || it.name == matchedTemplate.name)}
				if(image) {
					// println "adding image : ${matchedTemplate.name}"
					//if we matched by virtual image and not a location record we need to create that location record
					def isPublic = matchedTemplate.visibility != 'private'
					def locationConfig = [
							virtualImage: image,
							code        : "${objCategory}.${matchedTemplate.id}",
							externalId  : matchedTemplate.id,
							imageName   : matchedTemplate.name,
							isPublic    : isPublic,
							imageRegion : cloud.regionCode,
							owner       : matchedTemplate.owner
					]
					def addLocation = new VirtualImageLocation(locationConfig)
					morpheusContext.virtualImage.location.create([addLocation], cloud).blockingGet()

					image.deleted = false
					morpheusContext.virtualImage.save([image], cloud).blockingGet()
				}
			}
		}
	}

	protected removeMissingVirtualImages(List<VirtualImageLocation> removeList) {
		log.debug "removeMissingVirtualImages: ${removeList?.size()}"
		try {
			Map<Long, VirtualImage> images = [:]
			def imageIdsToLoad = removeList?.collect { it.virtualImage.id }
			if(imageIdsToLoad) {
				morpheusContext.virtualImage.listById(imageIdsToLoad).blockingSubscribe { images[it.id] = it }
			}

			removeList?.each { VirtualImageLocation vlocation ->
				VirtualImage virtualImage = images[vlocation.virtualImage.id]
				if(vlocation.virtualImage) {
					if(virtualImage?.imageLocations?.size() == 1) {
						if(virtualImage.systemImage != true && !virtualImage?.userUploaded && !virtualImage?.userDefined) {
							virtualImage.deleted = true
							morpheusContext.virtualImage.save([virtualImage], cloud).blockingGet()
						}
						morpheusContext.virtualImage.location.remove([vlocation]).blockingGet()
					} else if(virtualImage?.imageLocations?.size() > 1 && virtualImage.imageLocations?.every{it.refId == cloud.id}) {
						virtualImage.imageLocations.findAll{it.refId == cloud.id && it.id != vlocation.id}?.each { vloc ->
							morpheusContext.virtualImage.location.remove([vloc]).blockingGet()
						}
						virtualImage = morpheusContext.virtualImage.get(virtualImage.id).blockingGet() // must reload
						if(virtualImage.systemImage != true && !virtualImage.userUploaded && !virtualImage.userDefined) {
							virtualImage.deleted = true
							morpheusContext.virtualImage.save([virtualImage], cloud).blockingGet()
						}
						morpheusContext.virtualImage.location.remove([vlocation]).blockingGet()
					} else {
						morpheusContext.virtualImage.location.remove([vlocation]).blockingGet()
					}
				} else {
					log.debug("removeMissingVirtualImages: Possible orphaned virtual image location")
					log.debug("removeMissingVirtualImages: Virtual image location has already been removed in this session")
				}
			}
		} catch(e) {
			log.error("error deleting synced virtual image: ${e}", e)
		}
	}

	private dedupeLocations() {
		List<VirtualImageLocation> existingLocations = []
		loadLocations().blockingSubscribe {existingLocations << it }

		//dedupe
		def groupedLocations = existingLocations.groupBy({ row -> row.externalId })
		def dupeCleanup = []
		def dupedLocations = groupedLocations.findAll{ key, value -> value.size() > 1 }
		if(dupedLocations?.size() > 0)
			log.warn("removing duplicate image locations: {}", dupedLocations.collect{ it.key })
		dupedLocations?.each { key, value ->
			value.eachWithIndex { row, index ->
				if(index > 0)
					dupeCleanup << row
			}
		}
		//remove the dupes
		if(dupeCleanup.size() > 0 ) {
			morpheusContext.virtualImage.location.remove(dupeCleanup)
		}
	}

	private Observable<VirtualImageLocation> loadLocations() {
		List<Long> virtualImageLocationIds = []
		morpheusContext.virtualImage.location.listSyncProjections(cloud.id).blockingSubscribe { virtualImageLocationIds << it.id }

		Observable<VirtualImageLocation> domainRecords = morpheusContext.virtualImage.location.listById(virtualImageLocationIds).filter { VirtualImageLocation location ->
			location.imageFolder == null || location.imageFolder == cloudPool.externalId
		}

		domainRecords
	}
}
