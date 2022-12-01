package com.morpheusdata.openstack.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.model.projection.SecurityGroupIdentityProjection
import com.morpheusdata.model.projection.SecurityGroupLocationIdentityProjection
import com.morpheusdata.openstack.plugin.OpenstackPlugin
import com.morpheusdata.openstack.plugin.utils.AuthConfig
import com.morpheusdata.openstack.plugin.utils.OpenStackComputeUtility
import com.morpheusdata.openstack.plugin.utils.OpenstackSyncUtils
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Observable

import java.security.MessageDigest

@Slf4j
class SecurityGroupSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private OpenstackPlugin plugin
	private HttpApiClient apiClient
	private AuthConfig authConfig
	private ComputeZonePoolIdentityProjection cloudPool
	private Date syncDate

	public SecurityGroupSync(OpenstackPlugin openstackPlugin, Cloud cloud, HttpApiClient apiClient, AuthConfig authConfig, ComputeZonePoolIdentityProjection cloudPool, Date syncDate) {
		this.plugin = openstackPlugin
		this.cloud = cloud
		this.morpheusContext = openstackPlugin.morpheusContext
		this.apiClient = apiClient
		this.authConfig = authConfig
		this.cloudPool = cloudPool
		this.syncDate = syncDate
	}

	def execute() {
		log.debug "BEGIN: execute SecurityGroupSync: ${cloud.id}, ${cloudPool.externalId}"
		def rtn = [success:false]
		try {
			authConfig.projectId = cloudPool.externalId
			def token = OpenStackComputeUtility.getToken(apiClient, authConfig)
			if (token.token) {
				def processData = [:]
				processData.objCategory = OpenstackSyncUtils.getObjectCategory("securityGroup", cloud.id, cloudPool.externalId)
				processData.listResults = OpenStackComputeUtility.listSecurityGroups(apiClient, authConfig, cloudPool.externalId)
				processData.allCloudSecurityGroupLocationsMap = new HashMap<String, SecurityGroupLocationIdentityProjection>()
				morpheusContext.securityGroup.location.listSyncProjections(cloud.id,null,null).blockingSubscribe {
					processData.allCloudSecurityGroupLocationsMap[(it.externalId)] = it
				}
				if(processData.listResults.success == true) {

					Observable<SecurityGroupLocationIdentityProjection> domainRecords = morpheusContext.securityGroup.location.listSyncProjections(cloud.id, cloudPool.id, processData.objCategory)
					SyncTask<SecurityGroupLocationIdentityProjection, Map, SecurityGroupLocation> syncTask = new SyncTask<>(domainRecords, processData.listResults?.results?.security_groups)
					syncTask.addMatchFunction { SecurityGroupLocationIdentityProjection domainObject, Map cloudItem ->
						domainObject.externalId == cloudItem.id
					}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<SecurityGroupLocationIdentityProjection, Map>> updateItems ->
						Map<Long, SyncTask.UpdateItemDto<SecurityGroupLocationIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
						morpheusContext.securityGroup.location.listByIds(updateItems?.collect { it.existingItem.id }).map { SecurityGroupLocation securityGroupLocation ->
							SyncTask.UpdateItemDto<SecurityGroupLocationIdentityProjection, Map> matchItem = updateItemMap[securityGroupLocation.id]
							return new SyncTask.UpdateItem<SecurityGroupLocation, Map>(existingItem: securityGroupLocation, masterItem: matchItem.masterItem)
						}
					}.onAdd { itemsToAdd ->
						addSecurityGroupLocations(itemsToAdd, processData)
					}.onUpdate { List<SyncTask.UpdateItem<SecurityGroupLocation, Map>> updateItems ->
						updateSecurityGroupLocations(updateItems, processData)
					}.onDelete { removeItems ->
						removeSecurityGroupLocations(removeItems)
					}.start()
				}
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute SecurityGroupSync: ${cloud.id}"
		return ServiceResponse.create(rtn)
	}

	protected void addSecurityGroupLocations(List addList, Map processData) {

		log.debug("addSecurityGroupLocations: ${addList?.size()}")

		for(cloudItem in addList) {
			try {
				// create the SecurityGroupLocation
				def addConfig = [
						refType       : 'ComputeZone',
						refId         : cloud.id,
						externalId    : cloudItem.id,
						name          : cloudItem.name,
						securityServer: cloud.securityServer,
						category      : processData.objCategory,
						description   : cloudItem.description,
						groupName     : cloudItem.name,
						zonePool      : cloudPool ? new ComputeZonePool(id: cloudPool.id) : null,
						ruleHash      : getDigest(cloudItem.toString())
				]

				def	add = new SecurityGroupLocation(addConfig)
				SecurityGroupLocation createdSecurityGroupLocation = morpheusContext.securityGroup.location.create(add).blockingGet()

				// Create/sync the rules
				List<SecurityGroupRuleLocation> rules = extractRules(cloudItem.rules ?: cloudItem.security_group_rules, processData.allCloudSecurityGroupLocationsMap)
				morpheusContext.securityGroup.location.syncRules(createdSecurityGroupLocation, rules).blockingGet()
			} catch(e) {
				log.error("addSecurityGroupLocations error: ${e} for ${cloudItem}", e)
			}
		}
	}

	protected updateSecurityGroupLocations( List<SyncTask.UpdateItem<SecurityGroupLocation, Map>>updateList, Map processData) {
		log.debug("updateSecurityGroupLocations: ${updateList?.size()}")

		// Load all the securityGroups that may be impacted here
		def securityGroups = new HashMap<String, SecurityGroup>()
		def securityGroupIds = new ArrayList<Long>()
		for(updateItem in updateList) {
			SecurityGroupLocation existingItem = updateItem.existingItem
			securityGroupIds << existingItem.securityGroup.id
		}
		morpheusContext.securityGroup.listByIds(securityGroupIds).blockingSubscribe {
			securityGroups[it.externalId] = it
		}


		for(updateItem in updateList) {
			try {
				SecurityGroupLocation existingItem = updateItem.existingItem
				def masterItem = updateItem.masterItem
				def hash = getDigest(masterItem.toString())
				def zonePool = cloudPool ? new ComputeZonePool(id: cloudPool.id) : null
				def doSave = false

				if (existingItem.category != processData.objCategory) {
					existingItem.category = processData.objCategory
					doSave = true
				}
				if (existingItem.ruleHash != hash) {
					existingItem.ruleHash = hash
					doSave = true
				}
				if (!existingItem.securityServer && cloud.securityServer) {
					existingItem.securityServer = cloud.securityServer
					doSave = true
				}
				if (existingItem.zonePool != zonePool) {
					existingItem.zonePool = zonePool
					doSave = true
				}

				if (doSave) {
					morpheusContext.securityGroup.location.save([existingItem]).blockingGet()
				}

				// Update the name and description of the parent security group... if sync'd
				def securityGroupsToSave = []
				SecurityGroup securityGroup = securityGroups[existingItem.securityGroup.externalId]
				if (securityGroup?.zoneId) {
					if (securityGroup.name != masterItem.name) {
						securityGroup.name = masterItem.name
						doSave = true
					}
					if (securityGroup.description != masterItem.description) {
						securityGroup.description = masterItem.description
						doSave = true
					}

					if (doSave) {
						securityGroupsToSave << securityGroup
					}
				}
				if(securityGroupsToSave) {
					morpheusContext.securityGroup.save(securityGroupsToSave).blockingGet()
				}

				List<SecurityGroupRuleLocation> rules = extractRules(masterItem.rules ?: masterItem.security_group_rules, processData.allCloudSecurityGroupLocationsMap)
				morpheusContext.securityGroup.location.syncRules(existingItem, rules).blockingGet()
			} catch (e) {
				log.error("updateSecurityGroupLocations error: ${e}", e)
			}
		}
	}

	protected removeSecurityGroupLocations(List<SecurityGroupLocationIdentityProjection> removeItems) {
		try {
			log.debug("removeSecurityGroupLocations: ${removeItems?.size()}")
			morpheusContext.securityGroup.location.removeSecurityGroupLocations(removeItems).blockingGet()
		} catch(e) {
			log.error "Error in removeSecurityGroupLocations ${e}", e
		}
	}

	private def getDigest(String digestString) {
		MessageDigest md = MessageDigest.getInstance("MD5")
		md.update(digestString.bytes)
		byte[] checksum = md.digest()
		return checksum.encodeHex().toString()
	}

	private List<SecurityGroupRuleLocation> extractRules(List rules, Map<String, SecurityGroupLocationIdentityProjection> allCloudSecurityGroupLocationsMap) {
		List<SecurityGroupRuleLocation> rtn = new ArrayList<SecurityGroupRuleLocation>()

		rules?.each { rule ->

			def direction = rule.direction
			def protocol = (rule.ip_protocol ?: rule.protocol) ?: 'any'
			def sourceType = rule.remote_group_id == null ? 'cidr' : null
			def destinationType
			def source = (rule.ip_range?.cidr ?: rule.remote_ip_prefix)
			def destination
			def etherType = rule.ethertype ?: 'IPv4'
			SecurityGroupIdentityProjection sourceGroup
			SecurityGroupIdentityProjection destinationGroup

			if(direction == 'egress') {
				def destinationGroupId = rule.remote_group_id
				destination = (rule.ip_range?.cidr ?: rule.remote_ip_prefix)
				if(destinationGroupId) {
					destinationGroup = allCloudSecurityGroupLocationsMap[destinationGroupId]?.securityGroup
					destinationType = 'group'
					sourceType = 'all'
				} else {
					sourceType = destination ? 'cidr' : 'all'
					destinationType = 'instance'
				}
			} else {
				def sourceGroupId = rule.remote_group_id
				if(sourceGroupId) {
					sourceGroup = allCloudSecurityGroupLocationsMap[sourceGroupId]?.securityGroup
					sourceType = 'group'
					destinationType = 'instance'
				} else {
					destinationType = source ? 'instance' : null
				}
			}
			
			// build up the portRange
			def portStart = (rule.from_port ?: rule.port_range_min)
			def portEnd = (rule.to_port ?: rule.port_range_max)
			def rulePortStart = (portStart != null && (portStart > 0 || protocol != 'icmp')) ? portStart : null
			def rulePortEnd = (portEnd != null && (portEnd > 0 || protocol != 'icmp')) ? portEnd : null
			def portRange
			if(rulePortStart == null && rulePortEnd == null) {
				portRange = null
			} else if(rulePortStart != null && rulePortEnd == null) {
				portRange = "${rulePortStart}"
			} else if(rulePortStart == null && rulePortEnd != null) {
				portRange = "${rulePortEnd}"
			} else if(rulePortStart == rulePortEnd) {
				portRange = "${rulePortStart}"
			} else {
				portRange = "${rulePortStart}-${rulePortEnd}"
			}

			//direction and protocol
			if(etherType == 'IPv6' && direction == 'ingress' && source == null)
				source = '::/0'
			else if(etherType == 'IPv6' && direction == 'egress' && destination == null)
				destination = '::/0'
			else if(etherType == 'IPv4' && direction == 'ingress' && source == null)
				source = '0.0.0.0/0'
			else if(etherType == 'IPv4' && direction == 'egress' && destination == null)
				destination = '0.0.0.0/0'

			SecurityGroupRuleLocation ruleLocation = new SecurityGroupRuleLocation([
					externalId          : rule.id.toString(),
					portRange           : portRange,
					protocol            : protocol,
					sourceType          : sourceType,
					destinationType     : destinationType,
					ruleType            : 'custom',
					source              : source,
					destination         : destination,
					policy              : 'accept',
					etherType           : etherType,
					sourceGroup         : sourceGroup,
					destinationGroup    : destinationGroup,
					direction           : direction,
					name                : rule.name ?: portRange
			])
			
			rtn << ruleLocation
		}
		return rtn
	}
}
