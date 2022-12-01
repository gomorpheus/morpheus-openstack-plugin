package com.morpheusdata.openstack.plugin.utils

import com.bertramlabs.plugins.karman.CloudFile
import com.morpheusdata.core.util.ProgressInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZUtils
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.conn.ssl.SSLContextBuilder
import org.apache.http.conn.ssl.TrustStrategy

import javax.json.Json
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import org.apache.http.client.methods.*
import org.apache.http.entity.*
import org.apache.http.impl.client.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.model.Cloud
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import java.security.cert.X509Certificate
import org.apache.http.conn.ssl.X509HostnameVerifier
import org.apache.http.entity.ContentType

@Slf4j
class OpenStackComputeUtility {

	static defaultPassword = 'morpheus'

	static ignoreSsl = true
	static final Long DEFAULT_SEGMENT_SIZE = 4.5 * 1024 * 1024 * 1024
	static final UNAUTHORIZED_ERROR = 'Unauthorized'
	static tokenBuffer = 1000l * 10l //10 second buffer
	static sleepTimeout = 1000l * 7l
	static SHARE_ACCESS_LEVELS = [
			"rw":[label:"Read/Write", apiValue:"rw"],
			"ro":[label:"Read Only", apiValue:"ro"],
			"rwr":[label:"Root", apiValue:"rw"]
	]

	static testConnection(HttpApiClient client, AuthConfig authConfig) {
		def rtn = [success:false, invalidLogin:false]
		try {
			authConfig.expireToken = true
			def tokenResults = getToken(client, authConfig)
			if(tokenResults.success) {
				rtn.success = true
			} else {
				if(tokenResults?.errorCode == 401) {
					rtn.invalidLogin = true
				}
			}
		} catch(e) {
			log.error("error testing connection to ${authConfig.cloudId}: ${e}")
		}
		return rtn
	}

	static listCatalog(HttpApiClient client, AuthConfig authConfig) {
		def rtn = [success:false]
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackIdentityUrl(authConfig)
			def osVersion = getOpenstackIdentityVersion(authConfig)
			if(token.token) {
				def headers = buildHeaders([:], token.token)
				def requestOpts = [headers:headers]
				def apiUrl = "${osVersion}/auth/catalog"
				def results = callApi(client, authConfig, apiUrl, token.token, requestOpts, "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					rtn.results = results.data
					log.debug("listCatalog: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listCatalog(client, authConfig)
				}
			} else {
				rtn.error = 'No Token found for identity api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listFlavors(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false]
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/${projectId}/flavors/detail", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true && !results.errorCode
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("listFlavors: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listFlavors(client, authConfig)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static getFlavor(HttpApiClient client, AuthConfig authConfig, String projectId=null, flavorId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/${projectId}/flavors/" + flavorId, token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("getFlavor: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return getFlavor(client, authConfig, flavorId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listNetworks(HttpApiClient client, AuthConfig authConfig, String projectId=null) {
		def rtn = [success:false, api:'neutron']
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackNetworkUrl(authConfig)
			def osVersion = getSafeOpenstackNetworkVersion(authConfig)
			if(token.token && projectId) {
				if(osUrl && osVersion) {
					def query = [:]
					def results = callApi(client, authConfig, '/' + osVersion + '/networks', token.token, [osUrl: osUrl, headers:['Content-Type':'application/json'], query: query], "GET")
					rtn.success = results?.success && results?.error != true
					log.debug("listNetworks ${results}")
					if(rtn.success) {
						rtn.results = results.data
						rtn.results.networks = rtn.results.networks?.findAll { it.shared || it.project_id == projectId || it.tenant_id == projectId || it['router:external'] == true}
					} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
						authConfig.expireToken = true
						return listNetworks(client, authConfig)
					}
				} else {
					rtn.api = 'nova'
					osUrl = getOpenstackComputeUrl(authConfig)
					osVersion = getOpenstackComputeVersion(authConfig)
					def results = callApi(client, authConfig, '/' + osVersion + "/${projectId}/os-networks", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
					rtn.success = results?.success && results?.error != true
					log.debug("listNetworks ${results}")
					if(rtn.success) {
						rtn.results = results.data
					} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
						authConfig.expireToken = true
						return listNetworks(client, authConfig)
					}
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static loadNetwork(HttpApiClient client, AuthConfig authConfig, String networkId) {
		def rtn = [success:false, api:'neutron']
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success) {
			def osUrl = getOpenstackNetworkUrl(authConfig)
			def osVersion = getSafeOpenstackNetworkVersion(authConfig)
			if(tokenResults.token) {
				if(osUrl && osVersion) {
					def results = callApi(client, authConfig, '/' + osVersion + '/networks/' + networkId, tokenResults.token, [headers:['Content-Type':'application/json']], "GET")
					rtn.success = results?.success && results?.error != true
					log.debug("loadNetwork ${results}")
					if(rtn.success) {
						if(results.data) {
							rtn.results = results.data
						} else {
							rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
						}
					} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
						authConfig.expireToken = true
						return loadNetwork(client, authConfig, networkId)
					}
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listSubnets(HttpApiClient client, AuthConfig authConfig, String projectId, networkId=null) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackNetworkUrl(authConfig)
			def osVersion = getSafeOpenstackNetworkVersion(authConfig)
			if(token.token) {
				if(osUrl && osVersion) {
					def headers = buildHeaders([:], token.token)
					def query = [:]
					if(networkId) {
						query.network_id = networkId
					}
					def results = callApi(client, authConfig, '/' + osVersion + '/subnets', token.token, [osUrl: osUrl, headers:headers, query:query], "GET")
					rtn.success = results?.success && results?.error != true
					log.debug("listSubnets ${results}")
					if(rtn.success) {
						if(results.data) {
							rtn.results = results.data
						} else {
							rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
						}
					}
				}
			} else {
				rtn.error = 'No Token found for network api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listImages(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false, results:[:]]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		log.debug "listImages"
		if(token.success == true) {
			def osUrl = getOpenstackImageUrl(authConfig)
			def osVersion = getOpenstackImageVersion(authConfig)
			if(token.token) {
				def retrieveImageOpts = [:]
				retrieveImageOpts.osUrl = osUrl
				retrieveImageOpts.osVersion = osVersion
				retrieveImageOpts.token = token.token
				retrieveImageOpts.query = [:]
				retrieveImageOpts.query.sort = "name:asc"
				rtn.results.images = [:]
				def results = retrieveImages(client, authConfig, retrieveImageOpts)
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					rtn.results.images = results.results.images
					def pending = false
					def nextUrlMap = [:]
					if(results?.nextUrl) {
						log.debug "listImages There are more images to cache"
						def nextUrlString = results.nextUrl.substring(results.nextUrl.indexOf("?")+1)
						nextUrlMap = nextUrlString.split('&').inject([:]) {map, kv-> def (key, value) = kv.split('=').toList(); map[key] = value != null ? URLDecoder.decode(value) : null; map }
						log.debug("listImages map: ${nextUrlMap}")
						pending = true
					} else {
						log.debug("listImages There are no more images to cache")
					}
					while(pending) {
						nextUrlMap.each { k,v ->
							retrieveImageOpts.query[k] = v
						}
						def imagesResults = retrieveImages(client, authConfig, retrieveImageOpts)
						if(imagesResults?.results?.images)
							rtn.results.images = rtn.results.images + imagesResults.results.images
						if(imagesResults && imagesResults.nextUrl) {
							log.debug("listImages continue caching images")
							def nextUrlString = imagesResults.nextUrl.substring(imagesResults.nextUrl.indexOf("?")+1)
							nextUrlMap = nextUrlString.split('&').inject([:]) {map, kv-> def (key, value) = kv.split('=').toList(); map[key] = value != null ? URLDecoder.decode(value) : null; map }
							log.debug("listImages map: ${nextUrlMap}")
						} else {
							pending = false
						}
					}
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listRoles(HttpApiClient client, AuthConfig authConfig, String domainId=null) {
		def rtn = [success:false, data:[], total:0]
		def token = getToken(client, authConfig, true)
		if(token.success && token.token) {
			def apiPath = "/${authConfig.identityVersion}/roles"
			def headers = buildHeaders([:])
			def query = [:]
			if(domainId) {
				query.domain_id = domainId
			}
			def requestOpts = [headers:headers, query:query]
			def results = callApi(client, authConfig, apiPath, token.token, requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					rtn.data = results.data.roles
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listProjects(HttpApiClient client, AuthConfig authConfig) {
		def rtn = [success:false]
		def token = getToken(client, authConfig, true)
		if(token.success && token.token) {
			def osVersion = authConfig.identityVersion ?: getOpenstackIdentityVersion(authConfig)
			if(!osVersion.startsWith('v2') && !osVersion.startsWith('v1')) {
				def headers = buildHeaders([:])
				def query = [is_domain:'false', enabled:'true']
				if(token.apiDomainId) {
					query.domain_id = token.apiDomainId
				}
				def requestOpts = [headers:headers, query:query]
				def apiUrl = "/${osVersion}/projects"
				def apiUserId = token.apiUserId
				if(apiUserId) {
					apiUrl = "/${osVersion}/users/${apiUserId}/projects"
				}
				def results = callApi(client, authConfig, apiUrl, token.token, requestOpts, 'GET')
				log.debug("list project results: ${results}")
				if(results.success == true) {
					rtn.success = true
					rtn.results = results.data
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listProjects(client, authConfig)
				}
			}
		} else {
			rtn.error = 'Not Authorized'
		}

		return rtn
	}

	static createProject(HttpApiClient client, AuthConfig authConfig, String projectId, Map config) {
		def rtn = [success:false, data:[:]]
		getApiToken(client, authConfig, true)
		if(authConfig.tokenResult) {
			def apiPath = '/' + authConfig.identityVersion + '/projects'
			def headers = buildHeaders([:], authConfig.tokenResult.token)
			def body = [
					project: [
							name: config.name,
							description: config.description,
							domain_id: authConfig.domainId,
							enabled:true,
							is_domain: false,
					]
			]
			log.debug("create project body: ${body}")
			def requestOpts = [headers:headers, body:body]
			def results = callApi(client, authConfig, apiPath, authConfig.tokenResult.token, requestOpts, 'POST')
			log.debug("create project results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.externalId = rtn.router?.id
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getResponseError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static updateProject(HttpApiClient client, AuthConfig authConfig, String projectId, Map config) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = '/' + authConfig.identityVersion + '/projects/' + projectId
			def headers = buildHeaders([:], tokenResults.token)
			def body = [
					project: [
							name: config.name,
							description: config.description
					]
			]

			log.debug("update project body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, tokenResults.token, requestOpts, 'PATCH')
			log.debug("update project results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.externalId = rtn.router?.id
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getResponseError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteProject(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = '/' + authConfig.identityVersion + '/projects/' + projectId
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			def results = callApi(client, authConfig, apiPath, tokenResults.token, requestOpts, 'DELETE')
			log.debug("delete project results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getResponseError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listUserRoleAssignments(HttpApiClient client, AuthConfig authConfig, String userId) {
		log.debug "listUserRoleAssignments: ${userId}"
		def rtn = [success:false, data:[:]]
		getApiToken(client, authConfig, true)
		if(authConfig.tokenResult) {
			def apiPath = "/${authConfig.identityVersion}/role_assignments"
			def headers = buildHeaders([:], authConfig.tokenResult.token)
			def query = [user: [id: userId]]
			def requestOpts = [headers:headers, query:query]
			def results = callApi(client, authConfig, apiPath, authConfig.tokenResult.token, requestOpts, 'GET')
			log.debug("listing user role results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getResponseError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static assignProjectUserRole(HttpApiClient client, AuthConfig authConfig, String projectId, String userId, String roleId, Map opts=[:]) {
		def rtn = [success:false, data:[:]]
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = "/${authConfig.identityVersion}/projects/${projectId}/users/${userId}/roles/${roleId}"
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			def results = callApi(client, authConfig, apiPath, tokenResults.token, requestOpts, 'PUT')
			log.debug("assign project user role results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success != true) {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getResponseError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static unassignProjectUserRole(HttpApiClient client, AuthConfig authConfig, String projectId, String userId, String roleId, Map opts=[:]) {
		def rtn = [success:false, data:[:]]
		def tokenResults = getApiToken(client, authConfig,  [projectId: projectId], false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = "/${authConfig.identityVersion}/projects/${projectId}/users/${userId}/roles/${roleId}"
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			def results = callApi(client, authConfig, apiPath, tokenResults.token, requestOpts, 'DELETE')
			log.debug("unassign project user role results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success != true) {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getResponseError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}


	static listServers(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/${projectId}/servers/detail", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("servers: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listServers(client, authConfig)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listHypervisors(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false]
		def token = getToken(client, authConfig)
		if(token.success == true) {
			authConfig.projectId = projectId
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/${projectId}/os-hypervisors", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("hypervisors: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listHypervisors(client, authConfig)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static getHypervisor(HttpApiClient client, AuthConfig authConfig, String projectId, String hypervisorId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/${projectId}/os-hypervisors/${hypervisorId}", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("hypervisors: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return getHypervisor(client, authConfig, projectId, hypervisorId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	//nova style floating ips
	static listFloatingIps(HttpApiClient client, AuthConfig authConfig, String projectId, String poolId=null) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def query = [:]
				if(poolId) {
					query.floating_network_id = poolId
				}
				def results = callApi(client, authConfig, '/' + osVersion + "/${projectId}/os-floating-ips", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json'], query: query], "GET")
				rtn.success = results?.success && results?.error != true
				log.debug("FLoating Ips: ${results}")
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
						rtn.results.floatingips = rtn.results.floatingips?.findAll{ it.tenant_id == projectId}
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
						rtn.results.floatingips = rtn.results.floatingips?.findAll{ it.tenant_id == projectId}
					}
					log.debug("listFloatingIps: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listFloatingIps(client, authConfig)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listFloatingIpPools(HttpApiClient client, AuthConfig authConfig, String projectId=null) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/${projectId}/os-floating-ip-pools", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("listFloatingIps: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listFloatingIpPools(client, authConfig)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static allocateFloatingIp(HttpApiClient client, AuthConfig authConfig, String projectId=null) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def poolResults = listFloatingIpPools(client, authConfig, projectId)
				def foundIp = false
				poolResults?.results?.floating_ip_pools?.each { pool ->
					if(foundIp == false) {
						def poolName = pool.name
						def body = [pool:poolName]
						def results = callApi(client, authConfig, '/' + osVersion + "/${projectId}/os-floating-ips", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json'], body:body], "POST")
						log.debug("allocateFloatingIp: ${results}")
						rtn.success = results?.success && results?.error != true
						if(rtn.success) {
							rtn.results = results.content ? new groovy.json.JsonSlurper().parseText(results.content) : [:]
							log.debug("allocateFloatingIp: ${rtn.results}")
							foundIp = true
						} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
							authConfig.expireToken = true
							return allocateFloatingIp(client, authConfig, projectId)
						}
					}
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static assignFloatingIp(HttpApiClient client, AuthConfig authConfig, String projectId=null, server=null) {
		def rtn = [success:false]
		def floatingIps = listFloatingIps(client, authConfig, projectId)
		def freeIps = floatingIps?.results?.floating_ips?.findAll{it.instance_id == null}
		if(freeIps == null || freeIps?.size() == 0) {
			allocateFloatingIp(client, authConfig)
			floatingIps = listFloatingIps(client, authConfig, projectId)
			freeIps = floatingIps?.results?.floating_ips?.findAll{it.instance_id == null}
		}
		def freeIp = freeIps?.size() > 0 ? freeIps.first().ip : null
		log.debug("found: ${freeIps?.size()} free ips - using ${freeIp}")
		if(freeIp) {
			def token = getToken(client, authConfig)
			if(token.success == true) {
				def osUrl = getOpenstackComputeUrl(authConfig)
				def osVersion = getOpenstackComputeVersion(authConfig)
				if(token.token && projectId) {
					def serverId = server?.externalId
					def body = [addFloatingIp:[address:freeIp]]
					def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/servers/" + serverId + "/action", token.token,
							[osUrl: osUrl, headers:['Content-Type':'application/json'], body:body], "POST")
					log.debug("assignFloatingIp: ${results}")
					rtn.success = results?.success && results?.error != true
					if(rtn.success) {
						rtn.ipAddress = freeIp
						rtn.results = results.content ? new groovy.json.JsonSlurper().parseText(results.content) : [:]
						log.debug("assignFloatingIp: ${rtn.results}")
					} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
						authConfig.expireToken = true
						return assignFloatingIp(client, authConfig, projectId, server)
					} else {
						log.warn("error assigning floating ip: ${results}")
					}
				} else {
					rtn.error = 'No Token found for compute api'
				}
			} else {
				rtn.error = 'Not Authorized'
			}
		} else {
			rtn.error = 'No Available Floating Ips'
		}
		return rtn
	}

	static unassignFloatingIp() {

	}

	//neutron version of floating ips
	static listAvailableFloatingIps(HttpApiClient client, AuthConfig authConfig, String projectId=null, String networkId=null) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osVersion = getOpenstackNetworkVersion(authConfig)
			if(token.token && projectId) {
				def query = ['tenant_id':projectId]
				if(networkId) {
					query.floating_network_id = networkId
				}
				def results = callApi(client, authConfig, '/' + osVersion + "/floatingips", token.token, [headers:['Content-Type':'application/json'],
				                                                                                        query:query], "GET")
				rtn.success = results?.success && results?.error != true
				log.debug("floating Ips: ${results}")
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
						if(rtn.results.floating_ips && !rtn.results.floatingips) {
							rtn.results.floatingips = rtn.results.floating_ips
						}
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					// filter out IPs that have been assigned to an instance or a port.
					rtn.results.floatingips = rtn.results.floatingips?.findAll{it.status == 'DOWN' || (it.status == 'ACTIVE' && !it.fixed_ip_address && !it.port_id)}
					log.debug("listAvailableFloatingIps: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listAvailableFloatingIps(client, authConfig, projectId, networkId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listNetworkAvailiablityZones(HttpApiClient client, AuthConfig authConfig, String projectId=null) {
		def rtn = [success:false, availablityZoneList:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && tokenResults.projectId) {
			def apiPath = authConfig.networkPath + "/${tokenResults.projectId}/os-availability-zone"
			def headers = buildHeaders([:], tokenResults.token)
			def query = ['state':'available', 'resource':'network', 'tenant_id':tokenResults.projectId]
			//filters
			def requestOpts = [headers:headers, query:query]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, null, requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.availabilty_zones?.each { row ->
						row.externalId = row.name
						rtn.availablityZoneList << row
					}
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static allocateNetworkFloatingIp(HttpApiClient client, AuthConfig authConfig, String projectId=projectId, String networkId=networkId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osVersion = getOpenstackNetworkVersion(authConfig)
			if(token.token && projectId) {
				def body = [floatingip:[floating_network_id:networkId]]
				def results = callApi(client, authConfig, '/' + osVersion + "/floatingips", token.token, [headers:['Content-Type':'application/json'], body:body], "POST")
				log.debug("allocateFloatingIp: ${results}")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					rtn.results = results.content ? new groovy.json.JsonSlurper().parseText(results.content) : [:]
					log.debug("allocateFloatingIp: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return allocateNetworkFloatingIp(client, authConfig, projectId, networkId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static assignNetworkFloatingIp(HttpApiClient client, AuthConfig authConfig, String freeIp, String projectId=projectId, server=null) {
		def rtn = [success:false]
		try {
			authConfig.projectId = projectId
			def token = getToken(client, authConfig)
			if(token.success == true) {
				def osUrl = getOpenstackComputeUrl(authConfig)
				def osVersion = getOpenstackComputeVersion(authConfig)
				if(token.token && projectId) {
					def serverId = server?.externalId
					def body = [addFloatingIp:[address:freeIp]]
					def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/servers/" + serverId + "/action", token.token,
							[osUrl: osUrl, headers:['Content-Type':'application/json'], body:body], "POST")
					log.debug("assignFloatingIp: ${results}")
					rtn.success = results?.success && results?.error != true
					if(rtn.success) {
						rtn.ipAddress = freeIp
						rtn.results = results.content ? new groovy.json.JsonSlurper().parseText(results.content) : [:]
						log.debug("assignFloatingIp: ${rtn.results}")
					} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
						authConfig.expireToken = true
						return assignNetworkFloatingIp(client, authConfig, freeIp, projectId, server)
					} else {
						log.warn("error assigning floating ip: ${results}")
					}
				} else {
					rtn.error = 'No Token found for compute api'
				}
			} else {
				rtn.error = 'Not Authorized'
			}
		} catch(Exception e) {
			log.error("assignNetworkFloatingIp error: ${e}", e)
		}
		return rtn
	}

	static checkFloatingIpAvailability(HttpApiClient client, AuthConfig authConfig, String floatingIp, String projectId=null, String networkId=null) {
		def rtn = [success:true, available:false]
		try {
			def floatingIps = listAvailableFloatingIps(client, authConfig, projectId, networkId)
			def freeIps = floatingIps?.results?.floatingips
			def ipAvailable = freeIps.find { it.floating_ip_address == floatingIp }
			if(ipAvailable) {
				rtn.available = true
			}
		} catch(Exception e) {
			log.error("checkFloatingIpAvailability error: {}", e, e)
		}
		return rtn
	}

	static waitForFloatingIp(HttpApiClient client, AuthConfig authConfig, String floatingIpAddress, String projectId=null, String networkId=null) {
		def rtn = [success:false]
		try{
			def pending = true
			def attempts = 0
			while(pending) {
				def floatingIps = listAvailableFloatingIps(client, authConfig, projectId, networkId)
				def floatingIpAvailable = floatingIps?.results?.floatingips?.find { it.floating_ip_address == floatingIpAddress }
				if(!floatingIpAvailable) {
					rtn.success = true
					pending = false
				} else {
					sleep(sleepTimeout)
				}
				attempts ++
				if(attempts > 20) {
					pending = false
				}
			}
		} catch(e) {
			log.error("Error waiting for floating ip allocation ${e.message}", e)
		}
		return rtn
	}

	static uploadKeypair(HttpApiClient client, AuthConfig authConfig, String projectId, String keyName, String publicKey) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def body = [
						keypair:[
								name:keyName,
								public_key:publicKey
						]
				]
				def requestOpts = [
						osUrl: osUrl,
						headers: buildHeaders([:], token.token),
						body:body
				]
				def results = callApi(client, authConfig, '/' + osVersion + '/' + projectId + "/os-keypairs", token.token, requestOpts, "POST")
				log.debug("uploadKeypair results: ${results}")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					rtn.results = results.data
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return uploadKeypair(client, authConfig, projectId, keyName, publicKey)
				} else {
					log.error("error uploading keypair")
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static getKeypair(HttpApiClient client, AuthConfig authConfig, String projectId, String keyName) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def requestOpts = [osUrl: osUrl, headers: buildHeaders([:], token.token)]
				def results = callApi(client, authConfig, '/' + osVersion + '/' + projectId + "/os-keypairs/" + keyName, token.token, requestOpts, "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					rtn.results = results.data
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return uploadKeypair(client, authConfig, projectId, keyName)
				} else {
					log.error("error fetching keypair")
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listKeypairs(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def requestOpts = [osUrl: osUrl, headers: buildHeaders([:], token.token)]
				def results = callApi(client, authConfig, '/' + osVersion + '/' + projectId + "/os-keypairs", token.token, requestOpts, "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					rtn.results = results.data
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return uploadKeypair(client, authConfig, projectId)
				} else {
					log.error("error fetching keypair")
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteKeypair(HttpApiClient client, AuthConfig authConfig, String projectId, String keyName=null) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				keyName = keyName ?: getGenericKeyName(authConfig.cloudId)
				def results = callApi(client, authConfig, '/' + osVersion +'/' + projectId + "/os-keypairs/" + keyName, token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "DELETE")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					log.debug("deleteKeypair results: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return deleteKeypair(client, authConfig)
				} else {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					if(results.errorCode == 404) {
						rtn.success = true
					} else {
						rtn.error = "Error deleting keypair from '${authConfig.cloudId}'.  "
						rtn.error += rtn.results.badRequest?.message ?: "Error deleting Keypair from cloud"
					}
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static stopVm(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId) {
		return serverAction(client, authConfig, projectId, externalId, 'os-stop')
	}

	static startVm(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId) {
		return serverAction(client, authConfig, projectId, externalId, 'os-start')
	}

	static serverAction(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId, String action) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token) {
				def body = [(action.toString()):null]
				log.debug "Server Action ${body.encodeAsJSON()}"
				def results = callApi(client, authConfig, '/' + osVersion + "/${projectId}/servers/${externalId}/action", token.token,
						[osUrl: osUrl, headers:['Content-Type':'application/json'], body:body.encodeAsJSON().toString()], "POST")
				rtn.success = results?.success && results?.error != true
				rtn.results = results
				if(rtn.success) {
					log.debug("serverAction: ${results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return serverAction(client, authConfig, projectId, externalId, action)
				} else {
					rtn.error = 'Error performing server action'
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = "Not Authorized"
		}
		return rtn
	}

	static createServer(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		log.debug("createServer opts:${opts}")
		def rtn = [success:false]
		if(!opts.imageRef && !opts.snapshotRef && !opts.volumeRef) {
			rtn.error = 'Please specify a volume, image, or snapshot'
		} else if(!opts.flavorRef) {
			rtn.error = 'Please specify a flavor'
		} else if(!opts.name) {
			rtn.error = 'Please specify a name'
		} else {
			def osUrl = getOpenstackComputeUrl(authConfig)
			authConfig.projectId = projectId
			def token = getToken(client, authConfig)
			if(token.success == true) {
				def osVersion = getOpenstackComputeVersion(authConfig)
				def osMicroVersion = getOpenstackComputeMicroVersion(client, authConfig, projectId)
				if(token.token) {
					def body = [server:[name:opts.name, flavorRef:opts.flavorRef, min_count:1, max_count:1,
					                    adminPass:defaultPassword, key_name:opts.keyName]]
					if(opts.cloudConfig)
						body.server.user_data = opts.cloudConfig.bytes.encodeBase64().toString()
					//security groups
					if(opts.securityGroup)
						body.server.security_groups = [[name:opts.securityGroup]]
					if(opts.securityGroupId)
						body.server.security_groups = [[id:opts.securityGroupId]]
					//server group
					if(opts.serverGroup) {
						body.server['os:scheduler_hints'] = ['group': opts.serverGroup]
					}
					//availability zone
					if(opts.availabilityZone)
						body.server.availability_zone = opts.availabilityZone
					// network config
					if(opts.networkConfig) {
						body.server.networks = []
						if(opts.networkConfig.primaryInterface?.network?.externalId) {
							def networkConfig = [uuid: opts.networkConfig.primaryInterface?.network?.externalId]
							if(opts.networkConfig.primaryInterface?.ipAddress) {
								networkConfig.fixed_ip = opts.networkConfig.primaryInterface?.ipAddress
							}
							body.server.networks << networkConfig
						}
						opts.networkConfig.extraInterfaces?.each { extraInterface ->
							if(extraInterface.network?.externalId) {
								def networkConfig = [uuid: extraInterface.network.externalId]
								if(extraInterface.ipAddress) {
									networkConfig.fixed_ip = extraInterface.ipAddress
								}
								body.server.networks << networkConfig
							}
						}
					}
					def baseDiskSize = opts.osFlavor?.flavor?.disk ?: 5 //TODO: Change min disk estimate to be real
					if(opts.imageMetadata) {
						def capacity = opts.imageMetadata[0].capacity ? (opts.imageMetadata[0].capacity.div(ComputeUtility.ONE_GIGABYTE)).toInteger() : null
						if(capacity && capacity  > baseDiskSize)
							baseDiskSize = capacity
					} else if(opts.minDisk) {
						if(opts.minDisk > baseDiskSize)
							baseDiskSize = opts.minDisk
					}
					def customDiskSize
					if(opts.maxStorage) {
						customDiskSize = (opts.maxStorage.div(ComputeUtility.ONE_GIGABYTE)).toInteger()
						if(customDiskSize < baseDiskSize) {
							customDiskSize = null
						}
					}
					def bootIndex = opts.defaultBootIndex ?: 0
					//we gotta do some snapshot lovewith opts.snapshots if its there
					def rootSnapshot = opts.snapshots?.find{it.snapshotType == "image"}
					if(opts.diskType == 'local') {
						// nova bug related to copy image to ephemeral storage, image ref required
						// not supported by bdmv2
						body.server.imageRef = opts.imageRef ?: opts.snapshotRef
					}
					def rootVolumeSourceType = opts.volumeRef ? 'volume' : (opts.imageRef || opts.diskType == 'local' ? 'image' : 'snapshot')
					def rootVolume = [
							boot_index: "${bootIndex++}".toString(),
							uuid: opts.volumeRef ?: opts.imageRef ?: opts.snapshotRef,
							volume_size: customDiskSize ?: opts.osFlavor?.flavor?.disk ?: baseDiskSize,
							source_type: rootVolumeSourceType,
							destination_type: opts.diskType == 'volume' || rootVolumeSourceType == 'volume' ? 'volume' : 'local',
							delete_on_termination:true
					]
					if(opts.volumeType) {
						rootVolume.volume_type = opts.volumeType
					}
					body.server.block_device_mapping_v2 = [rootVolume]
					if(!opts.snapshots && opts.imageMetadata && opts.imageMetadata.size() > 1) {
						opts.imageMetadata[1..opts.imageMetadata.size()-1].eachWithIndex { imageMeta, idx ->
							def diskMapping = [
									boot_index:"${bootIndex++}".toString(),
									uuid: imageMeta.imageId,
									image_id: imageMeta.imageId,
									volume_size: imageMeta.capacity ? (imageMeta.capacity.div(ComputeUtility.ONE_GIGABYTE)).toInteger().toString() : '20',
									source_type: 'image',
									destination_type: 'volume',
									delete_on_termination:true
							]
							if(imageMeta.volumeType) {
								diskMapping.volume_type = imageMeta.volumeType
							}
							body.server.block_device_mapping_v2 << diskMapping
						}
					} else if(opts.diskList?.size() > 0 && rootSnapshot?.volumeType != 'volume') {
						//TODO: Fix if image has additional disks already
						def dataSnapshots = opts.snapshots?.findAll{ it.diskType == "data" }
						def dataVolumeImages = opts.volumeImages.findAll{ it.diskType == "data" }
						opts.diskList?.eachWithIndex { disk, idx ->
							def diskMapping = [
									boot_index:"${bootIndex++}".toString(),
									volume_size: (disk.diskSize.div(ComputeUtility.ONE_GIGABYTE)).toInteger().toString(),
									source_type:'blank',
									destination_type: 'volume',
									delete_on_termination:true
							]
							if(disk.volumeType) {
								diskMapping.volume_type = disk.volumeType
							}
							if(dataSnapshots) {
								diskMapping.source_type = 'snapshot'
								diskMapping.uuid = dataSnapshots[idx].snapshotId
								diskMapping.snapshot_id = dataSnapshots[idx].snapshotId
							} else if(dataVolumeImages) {
								diskMapping.source_type = 'image'
								diskMapping.uuid = dataVolumeImages[idx].imageId
								diskMapping.image_id = dataVolumeImages[idx].imageId
							}
							body.server.block_device_mapping_v2 << diskMapping
						}
					}
					//this has password data. dont put it at info level
					log.debug("createServer body: ${body}")
					def headers = buildHeaders([:], token.token)
					if(osMicroVersion){
						// using volume type on upstream openstack requires micro version header
						headers["X-OpenStack-Nova-API-Version"] = osMicroVersion
					}
					def results = callApi(client, authConfig, '/' + osVersion + "/${projectId}/servers", token.token,	[osUrl: osUrl, headers:headers, body:body], "POST")
					rtn.success = results?.success && results?.error != true
					if(rtn.success) {
						rtn.results = results.data
						log.debug("createServer results: ${rtn.results}")
					} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
						authConfig.expireToken = true
						return createServer(client, authConfig, projectId, opts)
					} else {
						rtn.errorMsg = getNovaError(results) ?: 'Error creating server'
					}
				} else {
					rtn.error = 'No Token found for compute api'
				}
			} else {
				rtn.error = 'Not Authorized'
			}
		}
		return rtn
	}

	static getDefaultBootIndex() {
		return 0
	}

	static getDefaultDiskFormat() {
		return "qcow2"
	}

	static getBackupVolumeExportFormat(String defaultFormat) {
		return defaultFormat
	}

	static rebuildServer(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId, Map opts) {
		log.debug("rebuildServer ${opts}")
		def rtn = [success:false]
		if(!opts.imageId) {
			rtn.error = 'Please specify an image ID'
		} else if(!opts.name) {
			rtn.error = 'Please specify a name'
		} else {
			authConfig.projectId = projectId
			def token = getToken(client, authConfig)
			if(token.success == true) {
				def osUrl = getOpenstackComputeUrl(authConfig)
				def osVersion = getOpenstackComputeVersion(authConfig)
				if(token.token) {
					def body = [rebuild:[imageRef:opts.imageId, name:opts.name]] //preserve_ephemeral:false
					def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + '/servers/' + externalId + '/action', token.token,
							[osUrl: osUrl, headers:['Content-Type':'application/json'], body:body], "POST")
					rtn.success = results?.success && results?.error != true
					if(rtn.success) {
						if(results.data) {
							rtn.results = results.data
						} else {
							rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
						}
						log.debug("rebuildServer: ${rtn.results}")
					} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
						authConfig.expireToken = true
						return rebuildServer(client, authConfig, projectId, externalId, opts)
					} else {
						rtn.error = 'Error creating server'
					}
				} else {
					rtn.error = 'No Token found for compute api'
				}
			} else {
				rtn.error = 'Not Authorized'
			}
		}
		return rtn
	}

	static getServerDetail(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/servers/" + externalId, token.token,
						[osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					if(results.data?.server?.status.toLowerCase() == "error") {
						rtn.errorMsg = getNovaError(results)
					}
					log.debug("getServerDetail: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return getServerDetail(client, authConfig, projectId, externalId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static getConsole(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId, Map opts) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			def body = [
					"os-getVNCConsole": [
							type: opts.protocol ?: 'xvpvnc'
					]

			]
			// body = [
			//    remote_console: [
			//      protocol: 'vnc',
			//      type: 'novnc'
			//    ]
			// ]
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/servers/" + externalId + '/action', token.token, [osUrl: osUrl, headers:['Content-Type':'application/json'], body:body.encodeAsJSON().toString()], "POST")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					//rtn.results = results.data
					log.debug("getConsole: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return getConsole(client, authConfig, projectId, externalId, opts)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listAvailabilityZones(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/os-availability-zone", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("listAvailabilityZones results: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listAvailabilityZones(client, authConfig, projectId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}


	static listStorageAvailabilityZones(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackStorageUrl(authConfig)
			def osVersion = getOpenstackStorageVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/os-availability-zone", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("listStorageAvailabilityZones results: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listStorageAvailabilityZones(client, authConfig, projectId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listSecurityGroups(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackNetworkUrl(authConfig)
			def osVersion = getOpenstackNetworkVersion(authConfig)
			if(token.token && projectId) {
				def headers= buildHeaders([:], token.token)
				def query = [project_id: projectId]
				def requestOpts = [headers:headers, query:query, osUrl: osUrl]
				def results = callApi(client, authConfig, '/' + osVersion + "/security-groups", token.token, requestOpts, "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					if(rtn.results?.security_groups) {
						rtn.results.security_groups = rtn.results.security_groups.findAll { !it.tenant_id || it.tenant_id == projectId }
					}
					log.debug("listSecurityGroups results: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listSecurityGroups(client, authConfig, projectId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteSecurityGroup(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackNetworkUrl(authConfig)
			def osVersion = getOpenstackNetworkVersion(authConfig)
			if(token.token) {
				def results = callApi(client, authConfig, '/' + osVersion + "/security-groups/" + externalId, token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "DELETE")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					log.debug("deleteSecurityGroup results: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return deleteSecurityGroup(client, authConfig, projectId, externalId)
				} else {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					//rtn.results = results.data
					if(results.errorCode == 404) {
						rtn.success = true
					} else {
						rtn.error = "Error deleting security group from '${authConfig.cloudId}'.  "
						if (rtn.results.badRequest?.message?.contains('in use')) {
							rtn.error += "It is currently in use."
						} else {
							rtn.error += rtn.results.badRequest?.message ?: "Error deleting Security Group from cloud"
						}
					}
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static serverActionWithContent(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId, Map body) {
		log.debug("serverActionWithContent: ${externalId}")
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/servers/" + externalId + "/action", token.token,
						[osUrl: osUrl, headers:['Content-Type':'application/json'], body:body.encodeAsJSON().toString()], "POST")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					log.debug("serverActionWithContent: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return serverActionWithContent(client, authConfig, projectId, externalId, body)
				} else {
					def content = results.content ? new groovy.json.JsonSlurper().parseText(results.content) : []
					rtn.error = content?.badRequest?.message ?: 'Error performing server action with content'
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}


	static listServerGroups(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/os-server-groups", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("listServerGroups: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listServerGroups(client, authConfig, projectId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listVolumeTypes(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackStorageUrl(authConfig)
			def osVersion = getOpenstackStorageVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/${projectId}/types", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					rtn.data = rtn.results
					log.debug("listVolumeTypes results: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listVolumeTypes(client, authConfig, projectId)
				}
			} else {
				rtn.error = 'No Token found for storage api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listServerVolumes(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/servers/" + externalId + "/os-volume_attachments", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					//rtn.results = results.data
					log.debug("listServerVolumes: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listServerGroups(client, authConfig, projectId, externalId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static createVolume(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false]
		if(!opts.size) {
			rtn.error = 'Please specify a size in GB'
		} else {
			authConfig.projectId = projectId
			def token = getToken(client, authConfig)
			if(token.success == true) {
				def osUrl = getOpenstackStorageUrl(authConfig)
				def osVersion = getOpenstackStorageVersion(authConfig)
				if(token.token && projectId) {
					def body = [volume:[size:opts.size?.toInteger() ?: 1]]
					if(opts.name && opts.server) {
						body.volume.name = opts.server.name + "-" + opts.name
					} else if(opts.name) {
						body.volume.name = opts.name
					}
					if(opts.imageId) {
						body.volume.imageRef = opts.imageId
					} else if(opts.snapshotId){
						body.volume.snapshot_id = opts.snapshotId
					}
					if(opts.availabilityZone) {
						body.volume.availability_zone = opts.availabilityZone
					}
					if(opts.volumeType) {
						body.volume.volume_type = opts.volume_type ?: opts.volumeType
					}
					def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/volumes", token.token,
							[headers:['Content-Type':'application/json'], body:body, osUrl: osUrl], "POST")
					rtn.success = results?.success && results?.error != true
					if(rtn.success) {
						if(results.data) {
							rtn.results = results.data
						} else {
							rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
						}
						//rtn.results = results.data
						log.debug("create volume: ${rtn.results}")
					} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
						authConfig.expireToken = true
						return createVolume(client, authConfig, projectId, opts)
					}
				} else {
					rtn.error = 'No Token found for compute api'
				}
			} else {
				rtn.error = 'Not Authorized'
			}
		}
		return rtn
	}

	static attachVolume(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId, Map opts) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def body = [volumeAttachment:[volumeId:opts.volumeId]]
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/servers/" + externalId + "/os-volume_attachments", token.token,
						[osUrl: osUrl, headers:['Content-Type':'application/json'], body:body], "POST")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					//rtn.results = results.data
					log.debug("attachVolume: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return attachVolume(client, authConfig, projectId, externalId, opts)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static cinderAttachVolume(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId, Map opts) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackStorageUrl(authConfig)
			def osVersion = getOpenstackStorageVersion(authConfig)
			if(token.token && projectId) {
				def body = ["os-attach":[instance_uuid:externalId]]
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/volumes/" + opts.volumeId + "/action", token.token,
						[headers:['Content-Type':'application/json'], body:body, osUrl: osUrl], "POST")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					//rtn.results = results.data
					log.debug("cinderAttachVolume: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return cinderAttachVolume(client, authConfig, projectId, externalId, opts)
				}
			} else {
				rtn.error = 'No Token found for storage api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static detachVolume(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId, String volumeId) {
		log.debug "detachVolume: ${externalId}"
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/servers/" + externalId + "/os-volume_attachments/" + volumeId, token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "DELETE")
				rtn.success = results?.success && results?.error != true
				if(rtn?.success == true || results.errorCode == 404) {
					rtn.success = true
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return detachVolume(client, authConfig, projectId, externalId, volumeId)
				} else {
					rtn.error = "Failed to detach volume ${volumeId}"
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static waitForVolumeStatus(HttpApiClient client, AuthConfig authConfig, String projectId, String volumeId, String waitStatus, Boolean deleteRequest) {
		def rtn = [success:false]
		try{
			def pending = true
			def attempts = 0
			while(pending) {
				def results = getVolumeDetail(client, authConfig, projectId, volumeId)
				if(results.success == true && results.results) {
					if(results.results.volume.status == waitStatus) {
						rtn.success = true
						rtn.volume = results.results.volume
						rtn.data = results.results
						pending = false
					} else {
						sleep(sleepTimeout)
					}
				} else if(deleteRequest && results.errorCode == 404) {
					rtn.success = true
					pending = false
				} else {
					sleep(sleepTimeout)
				}
				attempts ++
				if(attempts > 90) {
					pending = false
				}
			}
		} catch(e) {
			log.error("Error waiting for volume to status: ${e.message}", e)
		}
		return rtn
	}

	static getVolumeDetail(HttpApiClient client, AuthConfig authConfig, String projectId, String volumeId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackStorageUrl(authConfig)
			def osVersion = getOpenstackStorageVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/volumes/" + volumeId, token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				rtn.errorCode = results.errorCode
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("getVolumeDetail results: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return getVolumeDetail(client, authConfig, projectId, volumeId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listVolumes(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackStorageUrl(authConfig)
			def osVersion = getOpenstackStorageVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/volumes/detail", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = results.content ? new groovy.json.JsonSlurper().parseText(results.content) : [:]
					}
					log.debug("listVolumes results: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listVolumes(client, authConfig, projectId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	//get list of snapshots for all volumes
	static listVolumeSnapshots(HttpApiClient client, AuthConfig authConfig, String projectId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackStorageUrl(authConfig)
			def osVersion = getOpenstackStorageVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/snapshots", token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = results.content ? new groovy.json.JsonSlurper().parseText(results.content) : [:]
					}
					log.debug("listVolumeSnapshots results: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return listVolumeSnapshots(client, authConfig, projectId)
				}
			} else {
				rtn.error = 'No Token found for storage api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static resizeVolume(HttpApiClient client, AuthConfig authConfig, String projectId, String volumeId, Long size) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackStorageUrl(authConfig)
			def osVersion = getOpenstackStorageVersion(authConfig)
			if(token.token && projectId) {
				def requestPath = '/' + osVersion + "/" + projectId + "/volumes/" + volumeId + "/action"
				def headers = buildHeaders([:], token.token)
				def body = ['os-extend': ['new_size': size]]
				def results = callApi(client, authConfig, requestPath, null, null, [osUrl:osUrl, headers: headers, body: body], 'POST')
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					log.debug("resizeVolume: ${rtn.results}")
				} else if(results.errorCode == 401 && !authConfig.expireToken) {
					authConfig.expireToken = true
					return resizeVolume(client, authConfig, projectId, volumeId, size)
				} else {
					rtn.error = results.data?.badRequest?.message ?: 'Error performing volume resize action'
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}


	static createVolumeImage(HttpApiClient client, AuthConfig authConfig, String projectId, String volumeId, Map opts) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackStorageUrl(authConfig)
			def osVersion = getOpenstackStorageVersion(authConfig)
			if(token.token && projectId) {
				if(osVersion && osUrl.endsWith(osVersion)) {
					osUrl = osUrl.replace("/${osVersion}", '')
				}
				def requestPath = '/' + osVersion + "/" + projectId + "/volumes/" + volumeId + "/action"
				def headers = buildHeaders([:], token.token)
				def body = [
						"os-volume_upload_image": [
								"image_name": opts.name,
								"disk_format": opts.imageFormat ?: "qcow2",
								"container_format": "bare"
						]
				]

				def results = callApi(client, authConfig, requestPath, token.token, [osUrl: osUrl, headers: headers, body: body], 'POST')
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					log.debug("resizeVolume: ${rtn.results}")
					rtn.results = results.content ? new groovy.json.JsonSlurper().parseText(results.content) : [:]
				} else if(results.errorCode == 401 && !authConfig.expireToken) {
					authConfig.expireToken = true
					return createVolumeImage(client, authConfig, projectId, volumeId)
				} else {
					rtn.error = results.data?.badRequest?.message ?: 'Error performing volume image creation'
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static waitForImageStatus(HttpApiClient client, AuthConfig authConfig, String projectId, String imageId, String waitStatus) {
		def rtn = [success:false]
		try{
			def pending = true
			def attempts = 0
			while(pending) {
				def results = getImageDetail(client, authConfig, projectId, imageId)
				if(results.success == true && results.results) {
					if(results.results.status == waitStatus) {
						rtn.success = true
						rtn.data = results.results
						pending = false
					} else if(['failed', 'killed', 'deleted'].contains(results.results.status)) {
						rtn.success = false
						rtn.data = results.results
						pending = false
					} else {
						sleep(sleepTimeout)
					}
				} else if(results.success == false && results.statusCode == 404) {
					rtn.success = false
					rtn.msg = results.msg
					pending = false
				} else {
					sleep(sleepTimeout)
				}
				attempts ++
				if(attempts > 90) {
					pending = false
				}
			}
		} catch(e) {
			log.error("Error waiting for image status: ${e.message}", e)
		}
		return rtn
	}

	static asyncJobDetail(String jobId, Map opts) {
		return [success:true, results:[:]]
	}


	//get all volume snapshots for a given volume
	static getVolumeSnapshots(HttpApiClient client, AuthConfig authConfig, String projectId, String volumeId){
		def snapshots = []
		def snapshotsResults = listVolumeSnapshots(client, authConfig, projectId)

		if(snapshotsResults?.success == true){
			def volumeSnapshots = snapshotsResults.results?.snapshots
			volumeSnapshots.each { snapshot ->
				if(snapshot.volume_id == volumeId){
					snapshots << snapshot
				}
			}
		}

		return snapshots
	}

	static getVolumeSnapshot(HttpApiClient client, AuthConfig authConfig, String projectId, String snapshotId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackStorageUrl(authConfig)
			def osVersion = getOpenstackStorageVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/snapshots/" + snapshotId, token.token, [headers:['Content-Type':'application/json'], osUrl: osUrl], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("getVolumeSnapshot results: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return getVolumeSnapshot(client, authConfig, projectId, snapshotId)
				}
			} else {
				rtn.error = 'No Token found for storage api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static createVolumeSnapshot(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		log.debug "createVolumeSnapshot ${opts}"
		def rtn = [success:false]
		if(!opts.volumeId) {
			rtn.error = 'Please specify a volume for the snapshot'
		} else if(!opts.snapshotName) {
			rtn.error = 'Please specify a name for the snapshot'
		} else {
			authConfig.projectId = projectId
			def token = getToken(client, authConfig)
			if(token.success == true) {
				def osUrl = getOpenstackStorageUrl(authConfig)
				def osVersion = getOpenstackStorageVersion(authConfig)
				if(token.token && projectId) {
					def body = [snapshot:[display_name: opts.snapshotName, volume_id:opts.volumeId, force:true]]
					def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/snapshots", token.token,
							[headers:['Content-Type':'application/json'], body:body, osUrl: osUrl], "POST")
					rtn.success = results?.success && results?.error != true
					if(rtn.success) {
						if(results.data) {
							rtn.results = results.data
						} else {
							rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
						}
						log.debug("createVolumeSnapshot results: ${rtn.results}")
						if(!opts.hasProperty('waitForReady') || opts.waitForReady == true) {
							rtn.snapshotId = rtn.results.snapshot.id
							def readyResults = checkVolumeSnapshotReady(client, authConfig, projectId, rtn.snapshotId)
							rtn.results = readyResults.results
							rtn.success = readyResults.success == true
						} else {
							rtn.results = [snapshotId: rtn.results.snapshot.id, name: opts.snapshotName]
						}

						log.debug("create volume snapshot: ${rtn.results}")
					} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
						authConfig.expireToken = true
						return createVolumeSnapshot(client, authConfig, projectId, opts)
					}
				} else {
					rtn.error = 'No Token found for storage api'
				}
			} else {
				rtn.error = 'Not Authorized'
			}
		}
		return rtn
	}

	static deleteVolume(HttpApiClient client, AuthConfig authConfig, String projectId, String volumeId) {
		log.debug("deleteVolume ${volumeId}")
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackStorageUrl(authConfig)
			def osVersion = getOpenstackStorageVersion(authConfig)
			if(token.token && projectId) {
				//need to delete snapshots for a volume before you can delete the volume
				deleteVolumeSnapshots(client, authConfig, projectId, volumeId)
				waitForVolumeSnapshotDeletes(client, authConfig, projectId, volumeId)
				log.debug("Deleting volume ${volumeId}")
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/volumes/" + volumeId, token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "DELETE")
				rtn.success = results?.success && results?.error != true
				if(results?.success == true || results.errorCode == 404) {
					rtn.success = true
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return deleteVolume(client, authConfig, projectId, volumeId)
				} else {
					log.error "Failed to delete volume ${volumeId}"
				}
			} else {
				rtn.error = 'No Token found for storage api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteVolumeSnapshots(HttpApiClient client, AuthConfig authConfig, String projectId, String volumeId){
		log.debug("deleteVolumeSnapshots ${volumeId}")
		def rtn = [success:true]
		def snapshots = getVolumeSnapshots(client, authConfig, projectId, volumeId)
		snapshots.each{ snapshot ->
			def snapshotId = snapshot.id
			if(snapshotId) {
				rtn = deleteVolumeSnapshot(client, authConfig, projectId, snapshotId)
			}
		}

		return rtn
	}

	static deleteVolumeSnapshot(HttpApiClient client, AuthConfig authConfig, String projectId, String snapshotId) {
		log.debug("deleteVolumeSnapshot ${snapshotId}")
		def rtn = [success:true]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackStorageUrl(authConfig)
			def osVersion = getOpenstackStorageVersion(authConfig)
			if(token.token && projectId) {
				def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/snapshots/" + snapshotId, token.token, [headers:['Content-Type':'application/json'], osUrl: osUrl], "DELETE")
				rtn.success = results?.success && results?.error != true
				if(results?.success == true || results.errorCode == 404) {
					rtn.success = true
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return deleteVolumeSnapshot(client, authConfig, projectId, snapshotId)
				} else {
					rtn.success = false
					log.error "Failed to delete volume snapshot ${snapshotId}"
				}
			} else {
				rtn.error = 'No Token found for storage api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static getImageDetail(HttpApiClient client, AuthConfig authConfig, String projectId, String imageId) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackImageUrl(authConfig)
			def osVersion = getOpenstackImageVersion(authConfig)
			if(token.token) {
				def results = callApi(client, authConfig, '/' + osVersion + "/images/" + imageId, token.token, [osUrl:osUrl, headers:['Content-Type':'application/json']], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("getImageDetail results: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return getImageDetail(client, authConfig, projectId, imageId)
				} else {
					rtn.success = false
					rtn.msg = results.content
					rtn.statusCode = results.statusCode
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static getSubnetDetail(HttpApiClient client, AuthConfig authConfig, String subnetId) {
		def rtn = [success:false]
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackNetworkUrl(authConfig)
			def osVersion = getOpenstackNetworkVersion(authConfig)
			if(token.token) {
				def results = callApi(client, authConfig, '/' + osVersion + "/subnets/" + subnetId, token.token, [headers:['Content-Type':'application/json'], osUrl: osUrl], "GET")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					log.debug("getSubnetDetail results: ${rtn.results}")
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return getSubnetDetail(client, authConfig, subnetId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteImage(HttpApiClient client, AuthConfig authConfig, String projectId, String imageId){
		log.debug("deleting image ${imageId}")
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def results = [success:true]
				if(imageId){
					results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/images/" + imageId, token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "DELETE")
				}
				if(results?.success == true || results.errorCode == 404) {
					rtn.success = true
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return deleteImage(client, authConfig, projectId, imageId)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteServer(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			if(token.token && projectId) {
				def serverId = opts.server.externalId
				def results = [success:true]
				if(serverId)
					results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + "/servers/" + serverId, token.token, [osUrl: osUrl, headers:['Content-Type':'application/json']], "DELETE")
				if(results?.success == true || results.errorCode == 404) {
					def volumes = opts.server.volumes
					if(volumes || opts.server.volumeId || opts.server.rootVolumeId){
						sleep(10000) //wait for the server to detach the volumes - lame
					}

					osUrl = getOpenstackStorageUrl(authConfig)
					osVersion = getOpenstackStorageVersion(authConfig)

					def volumeId = opts.server.volumeId
					if(volumeId) {
						log.debug("Deleting volume ${volumeId}")
						def volumeDetail = getVolumeDetail(client, authConfig, projectId, volumeId)
						if(volumeDetail.errorCode != 404 && volumeDetail.results && volumeDetail.results?.volume?.status != 'deleting') {
							deleteVolume(client, authConfig, projectId, volumeId)
						}
					}

					//delete data volumes
					volumes.each { volume ->
						volumeId = volume?.externalId
						if(volumeId) {
							log.debug("Deleting volume ${volumeId}")
							def volumeDetail = getVolumeDetail(client, authConfig, projectId, volumeId)
							if(volumeDetail.errorCode != 404 && volumeDetail.results && volumeDetail.results?.volume?.status != 'deleting') {
								if(volumeDetail.results?.volume?.status == 'in-use') {
									detachVolume(client, authConfig, projectId, volumeId)
								}
								waitForVolumeStatus(client, authConfig, projectId, volumeId, true)
								deleteVolume(client, authConfig, projectId, volumeId)
							}
						}
					}

					//delete root volume
					volumeId = opts.server.rootVolumeId
					if(volumeId) {
						log.debug("Deleting volume ${volumeId}")
						def volumeDetail = getVolumeDetail(client, authConfig, projectId, volumeId)
						if(volumeDetail.errorCode != 404 && volumeDetail.results && volumeDetail.results?.volume?.status != 'deleting') {
							deleteVolume(client, authConfig, projectId, volumeId)
						}
					}
					if(results.success == true || results.errorCode == 404) {
						rtn.success = true
					}
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return deleteServer(client, authConfig, projectId, opts)
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static checkServerReady(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 6l)
				def serverDetail = getServerDetail(client, authConfig, projectId, externalId)
				if(serverDetail.success == true && serverDetail.results?.server?.status) {
					if(serverDetail.results.server.status.toLowerCase() == 'active') {
						rtn.success = true
						rtn.results = serverDetail.results
						pending = false
					} else if(serverDetail.results.server.status.toLowerCase() == 'error') {
						rtn.error = true
						rtn.results = serverDetail.results
						rtn.errorMsg = serverDetail.errorMsg
						rtn.success = true
						pending = false
					}
				}
				attempts ++
				if(attempts > 100)
					pending = false
			}
		} catch(e) {
			log.error("Error checking for server ready: ${e.message}",e)
		}
		return rtn
	}

	static checkServerStopped(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 6l)
				def serverDetail = getServerDetail(client, authConfig, projectId, externalId)
				if(serverDetail.success == true && serverDetail.results?.server?.status) {
					if(serverDetail.results.server.status.toLowerCase() == 'shutoff') {
						rtn.success = true
						rtn.results = serverDetail.results
						pending = false
					} else if(serverDetail.results.server.status.toLowerCase() == 'error') {
						rtn.error = true
						rtn.results = serverDetail.results
						rtn.msg = serverDetail.errorMsg
						rtn.success = true
						pending = false
					}
				}
				attempts ++
				if(attempts > 100)
					pending = false
			}
		} catch(e) {
			log.error("Error checking for server power off status: ${e.message}",e)
		}
		return rtn
	}

	static checkServerResize(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 6l)
				def serverDetail = getServerDetail(client, authConfig, projectId, externalId)
				if(serverDetail.success == true && serverDetail.results?.server?.status) {
					if(serverDetail.results.server.status.toLowerCase() == 'verify_resize') {
						rtn.success = true
						rtn.results = serverDetail.results
						pending = false
					} else if(serverDetail.results.server.status.toLowerCase() == 'error') {
						rtn.error = true
						rtn.results = serverDetail.results
						rtn.msg = serverDetail.errorMsg
						rtn.success = true
						pending = false
					}
				}
				attempts ++
				if(attempts > 50)
					pending = false
			}
		} catch(e) {
			log.error("Error checking for server resize: ${e.message}",e)
		}
		return rtn
	}

	static checkVolumeReady(HttpApiClient client, AuthConfig authConfig, String projectId, String volumeId, Long waitMinutes=1) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(10000l)
				def volumeDetail = getVolumeDetail(client, authConfig, projectId, volumeId)
				if(volumeDetail.success == true && volumeDetail.results?.volume?.status) {
					if(volumeDetail.results.volume.status.toLowerCase() == 'available' || volumeDetail.results.volume.status.toLowerCase() == 'in-use') {
						rtn.success = true
						rtn.results = volumeDetail.results
						pending = false
					} else if(volumeDetail.results.volume.status.toLowerCase() == 'error') {
						rtn.error = true
						rtn.results = volumeDetail.results
						rtn.success = true
						pending = false
					}
				}
				attempts ++
				if(attempts > (waitMinutes ?: 1) * 10 )
					pending = false
			}
		} catch(e) {
			log.error("Error checking for server volume ready: ${e.message}",e)
		}
		return rtn
	}

	static checkVolumeDetached(HttpApiClient client, AuthConfig authConfig, String projectId, String volumeId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 6l)
				def volumeDetail = getVolumeDetail(client, authConfig, projectId, volumeId)
				if(volumeDetail.success == true && volumeDetail.results?.volume?.status) {
					if(volumeDetail.results.volume.status.toLowerCase() == 'available') {
						rtn.success = true
						rtn.results = volumeDetail.results
						pending = false
					} else if(volumeDetail.results.volume.status.toLowerCase() == 'error') {
						rtn.error = true
						rtn.results = volumeDetail.results
						rtn.success = true
						pending = false
					}
				} else if(volumeDetail.errorCode == 404) {
					rtn.success = true
					pending = false
				}
				attempts ++
				if(attempts > 10)
					pending = false
			}
		} catch(e) {
			log.error("Error waiting to check for volume detachment: ${e.message}",e)
		}
		return rtn
	}

	static checkVolumeSnapshotReady(HttpApiClient client, AuthConfig authConfig, String projectId, String snapshotId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 6l)
				def snapshotDetail = getVolumeSnapshot(client, authConfig, projectId, snapshotId)
				if(snapshotDetail.success == true && snapshotDetail.results?.snapshot?.status) {
					if(snapshotDetail.results.snapshot.status.toLowerCase() == 'available') {
						rtn.success = true
						rtn.results = snapshotDetail.results
						pending = false
					} else if(snapshotDetail.results.snapshot.status.toLowerCase() == 'error') {
						rtn.error = true
						rtn.results = snapshotDetail.results
						rtn.success = true
						pending = false
					}
				}
				attempts ++
				if(attempts > 10)
					pending = false
			}
		} catch(e) {
			log.error("Error checking for volume snapshots ready: ${e.message}",e)
		}
		return rtn
	}

	static checkImageReady(HttpApiClient client, AuthConfig authConfig, String projectId, String imageId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 6l) // initial wait for image record to register to OS api
				def imageDetail = getImageDetail(client, authConfig, projectId, imageId)
				rtn.results = imageDetail.results
				if(imageDetail.success == true && imageDetail.results) {
					if(imageDetail.results.status == 'active') {
						rtn.success = true
						pending = false
					} else if(['failed', 'killed', 'deleted', 'error'].contains(imageDetail.results.status.toLowerCase())) {
						rtn.success = false
						rtn.data = rtn.results
						pending = false
					} else {
						sleep(sleepTimeout)
					}
				} else if(imageDetail.success == false && imageDetail.statusCode == 404) {
					rtn.success = false
					rtn.msg = imageDetail.msg
					pending = false
				} else {
					sleep(sleepTimeout)
				}
				attempts ++
				// wait for upto an ~hour for image import on OS side
				if(attempts > 500) {
					pending = false
				}
			}
		} catch(e) {
			log.error("Checking for image ready status: ${e.message}",e)
		}
		return rtn
	}

	//wait for all snapshots for volume to be removed
	static waitForVolumeSnapshotDeletes(HttpApiClient client, AuthConfig authConfig, String projectId, String volumeId){
		def rtn = [success:false]
		try{
			def pending = true
			def attempts = 0
			while(pending) {
				def snapshots = getVolumeSnapshots(client, authConfig, projectId, volumeId)
				if(snapshots?.size() == 0){
					//no more snapshots remaining for volume, ok to proceed
					rtn.success = true
					pending = false
				} else {
					log.debug("waiting for snapshots to be removed for volume ${volumeId}")
					sleep(1000l * 6l)
				}

				attempts ++
				if(attempts > 10) {
					pending = false
				}
			}


		} catch(e) {
			log.error("Error waiting for volume snapshots delete ${e.message}",e)
		}
		return rtn
	}

	static createSnapshot(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false]
		if(!opts.externalId) {
			rtn.error = 'Please specify a vm for the snapshot'
		} else if(!opts.snapshotName) {
			rtn.error = 'Please specify a name for the snapshot'
		} else {
			authConfig.projectId = projectId
			def token = getToken(client, authConfig)
			if(token.success == true) {
				def osUrl = getOpenstackComputeUrl(authConfig)
				def osVersion = getOpenstackComputeVersion(authConfig)
				if(token.token && projectId) {
					def body = [createImage:[ name:opts.snapshotName]]

					def results = callApi(client, authConfig, '/' + osVersion + "/" + projectId + '/servers/' + opts.externalId + '/action', token.token,
							[osUrl: osUrl, headers:['Content-Type':'application/json'], body:body], "POST")
					rtn.success = results?.success && results?.error != true
					if(rtn.success) {
						if(results?.results?.image_id) {
							rtn.snapshotId = results.results.image_id
						} else {
							rtn.snapshotId = parseLocationId(results.headers['Location'])
						}
						if(!opts.containsKey("waitForReady") || opts.waitForReady == true) {
							def readyResults = checkImageReady(client, authConfig, projectId, rtn.snapshotId)
							rtn.results = readyResults.results
							rtn.success = readyResults.success == true
							log.debug("create snapshot: ${rtn.results}")
						} else {
							log.debug("Async snapshot request, snapshotId: {}", rtn.snapshotId)
							rtn.results = [snapshotId: rtn.snapshotId, name: opts.snapshotName]
						}
					} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
						authConfig.expireToken = true
						return createSnapshot(client, authConfig, projectId, opts)
					}
				} else {
					rtn.error = 'No Token found for compute api'
				}
			} else {
				rtn.error = 'Not Authorized'
			}
		}
		return rtn
	}

	static insertContainerImage(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false]
		def currentList = listImages(client, authConfig, projectId)?.results?.images
		def image = opts.image
		def metadataFile = image.cloudFiles?.find{ it.name.endsWith("metadata.json")}
		def metadata
		def imageFormat = image.imageFormat ?: 'qcow2'
		def srcImageFormat = image.srcImageFormat
		Collection images
		if(metadataFile) {
			metadata = new JsonSlurper().parseText(metadataFile.text)
		}
		if(!metadata) {
			image.imageFile = image.cloudFiles?.find{cloudFile -> cloudFile.name.toLowerCase().indexOf( '.' + imageFormat) > -1}
			if(!image.imageFile && (imageFormat == 'vmdk' || srcImageFormat == 'vmdk')) {
				image.imageFile = image.cloudFiles?.find{cloudFile -> cloudFile.name.toLowerCase().indexOf( '.' + 'qcow2') > -1}
			}
			def minDisk = extractImageMinDisk(client, authConfig, projectId, image, imageFormat)
			def imageFile = image.imageFile ?: image.cloudFiles[0]
			if(imageFile) {
				log.debug("Found image File: ${image.imageFile}")
				images = [[name: image.name, imageType: image.imageType, imageFile: imageFile, minDisk:minDisk, minRam: image.minRam, metaRow: [position:0], position: 0]]
			} else {
				rtn.msgCode = "gomorpheus.error.noImageFileFound"
			}

		} else {
			images = []
			def metadataDisks = metadata.disks.sort{a,b -> a.position <=> b.position}
			def idx = 0
			for(disk in metadataDisks) {
				String imageName = idx == 0 ? image.name : "${image.name}-${idx}"
				def diskName = disk.file
				if(!diskName.contains(".${imageFormat}")) {
					diskName += '.' + imageFormat
				}

				def imgFile = image.cloudFiles?.find{cloudFile -> cloudFile.name.toLowerCase().indexOf(diskName) > -1}
				if(!imgFile && srcImageFormat != imageFormat) {
					diskName = disk.file
					if(!diskName.contains(".${srcImageFormat}")) {
						diskName += '.' + srcImageFormat
					}
					imgFile = image.cloudFiles?.find{cloudFile -> cloudFile.name.toLowerCase().indexOf(diskName) > -1}
				}
				if(!imgFile) {
					diskName = disk.file
					imgFile = image.cloudFiles?.find{cloudFile -> cloudFile.name.toLowerCase().indexOf(diskName) > -1}
				}
				def minDisk = disk.capacity ? (disk.capacity.div(ComputeUtility.ONE_GIGABYTE)).toInteger() : extractImageMinDisk(client, authConfig, projectId, image, imageFormat)
				def imageType = diskName ? diskName.tokenize('.').getAt(-1) : null
				images << [name: imageName, imageType: imageType ?: image.imageType, imageFile:imgFile, minDisk: minDisk, minRam: image.minRam, metaRow: disk, position: disk.position ?: 0]
				idx++
			}
		}
		boolean failedUpload = false
		images.eachWithIndex { imageRow, idx ->
			def match = currentList.find{it.name == imageRow.name && it.disk_format == imageRow.imageType && (!it.status || it.status?.toLowerCase() == "active")}
			if(!match) {
				def insertOpts = [
						name:imageRow.name,  minDisk:imageRow.minDisk, minRam:imageRow.minRam, deviceName: imageRow.metaRow.name,
						imageType:imageRow.imageType, containerType:image.containerType, imageFile:imageRow.imageFile, cloudFiles:[imageRow.imageFile],
						position: imageRow.position, cachePath:image.cachePath, onImageCreate: opts.onImageCreate, storageProviderConfig: image.storageProviderConfig,
						metadata: metadata, virtualImageId: image.virtualImageId
				]
				def createResults = createImage(client, authConfig, projectId, insertOpts)
				log.debug("insertContainerImage: ${createResults}")
				if(createResults.success == true) {
					if(idx == 0) {
						rtn.imageId = createResults.imageId
					}
					imageRow.metaRow.imageId = createResults.imageId
					def imageSize = createResults.results.min_disk
					def imageSizeBytes = imageSize * ComputeUtility.ONE_GIGABYTE
					if(imageSize > imageRow.minDisk) {
						imageRow.minDisk = imageSize
					}
					if(imageSizeBytes  > imageRow.metaRow.capacity) {
						imageRow.metaRow.capacity = imageSizeBytes
					}
				} else {
					rtn.msg = createResults.msg ?: createResults.error
					failedUpload = true
				}
			} else {
				if(idx == 0) {
					log.debug("using image: ${match.id}")
					rtn.imageId = match.id
				}
				imageRow.metaRow.imageId = match.id
			}
		}

		if(failedUpload == false) {
			rtn.success = true
			rtn.metadata = metadata?.disks
		}
		return rtn
	}

	static extractImageMinDisk(Map imageConfig, String imageFormat) {
		def minDisk = imageConfig.minDisk
		if(imageFormat == 'raw') {
			def fileSize = imageConfig.imageFile?.contentLength ?: imageConfig.cloudFiles[0]?.contentLength
			minDisk = fileSize / ComputeUtility.ONE_GIGABYTE
			if(minDisk < 5) {
				minDisk = 5
			}
		}

		return minDisk
	}

	static createImage(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false]
		authConfig.projectId = projectId
		def token = getToken(client, authConfig)
		if(token.success == true) {
			def osUrl = getOpenstackImageUrl(authConfig)
			def osVersion = getOpenstackImageVersion(authConfig)
			if(token.token) {
				def createOpts = []
				def body = [name:opts.name, disk_format:opts.imageType, min_disk:opts.minDisk ?: 10, min_ram:opts.minRam ? (opts.minRam / ComputeUtility.ONE_MEGABYTE)?.toLong() : 512, container_format:opts.containerType, visibility:'private']
				if(opts.tags)
					body.tags = opts.tags.tokenize(',')
				log.debug("createImage body: ${body}")
				def results = callApi(client, authConfig, '/' + osVersion + "/images", token.token,
						[headers:['Content-Type':'application/json'], body:body, osUrl: osUrl], "POST")
				rtn.success = results?.success && results?.error != true
				if(rtn.success) {
					if(results.data) {
						rtn.results = results.data
					} else {
						rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
					}
					rtn.imageId = rtn.results.id
					opts.imageId = rtn.imageId
					if(opts.onImageCreate) {
						opts.onImageCreate.call([id: rtn.imageId, position: opts.position, name: opts.name, deviceName:opts.deviceName, imageMetadata: opts.metadata, imageRegion:authConfig.regionCode, imageFolder:projectId])
					}
					log.debug("createImage results: ${rtn.results}")
					def tgtUrl = osUrl + '/' + osVersion + '/images/' + opts.imageId + '/file'
					if(opts.cloudFiles) {
						log.debug("Uploading cloud files! ${opts.cloudFiles[0]}")
						def attempts = 0
						while(attempts < 3) {
							rtn += uploadImage(client, authConfig, token.token, (opts.imageFile ?: opts.cloudFiles[0]), tgtUrl, opts.cachePath, [progressUpdate:"Uploading image \"${opts.cloudFiles[0]}\""])
							if(!rtn.success) {
								attempts++
							} else {
								break
							}
						}
					} else {
						rtn += uploadImage(client, authConfig, token.token, opts.imageSrc, tgtUrl, opts.cachePath, [progressUpdate:"Uploading image \"${rtn.imageId}\""])
					}
				} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
					authConfig.expireToken = true
					return createImage(client, authConfig, projectId, opts)
				}
				if(!rtn.success && rtn.imageId) {
					//we should delete the dead image
					def deleteResults = callApi(client, authConfig, '/' + osVersion + "/images/" +  rtn.imageId, token.token,
							[headers:['Content-Type':'application/json'], osUrl: osUrl], "DELETE")
					log.debug("Deleting failed image upload reference... ${deleteResults}")
				} else {
					log.debug("Waiting for image ready status.")
					def readyResults = checkImageReady(client, authConfig, projectId, rtn.imageId)
					rtn.results = readyResults.results
					if(rtn.results?.status == "creating" && rtn.imageId) {
						def deleteResults = callApi(client, authConfig, '/' + osVersion + "/images/" +  rtn.imageId, token.token,
								[headers:['Content-Type':'application/json'], osUrl: osUrl], "DELETE")
						log.debug("Deleting reference to image upload in invalid state... ${deleteResults}")
					}
					rtn.success = readyResults.success == true
				}
			} else {
				rtn.error = 'No Token found for compute api'
			}
		} else {
			rtn.error = 'Not Authorized'
		}

		return rtn
	}

	static uploadImage(HttpApiClient client, AuthConfig authConfig, String token, cloudFile, String tgtUrl, String cachePath = null, Map opts = [:]) {
		log.debug("uploadImage cloudFile: ${cloudFile.name} tgt: ${tgtUrl} cachePath: ${cachePath}")
		def rtn = [success:false]
		def usingCache = false
		def sourceStream
		Long totalCount
		try {
			if(cachePath) {
				def cacheFile = new File(cachePath, cloudFile.name)
				if(cacheFile.exists()) {
					sourceStream = cacheFile.newInputStream()
					totalCount = cacheFile.length()
				} else {
					sourceStream = cloudFile.inputStream
					totalCount = cloudFile.getContentLength()
				}
			} else {
				sourceStream = cloudFile.inputStream
				totalCount = cloudFile.getContentLength()
			}
			opts.isTarGz = cloudFile.getName().endsWith('.tar.gz')
			opts.isXz = XZUtils.isCompressedFilename(cloudFile.getName())
			if(opts.isXz) {
				def xzStream = new XZCompressorInputStream(sourceStream)
				totalCount = 0
				int len
				byte[] buffer = new byte[102400]
				while ((len = xzStream.read(buffer)) != -1) {
					if(len >= 0) {
						totalCount += len
					}
				}
				xzStream.close()
				def cacheFile = new File(cachePath, cloudFile.name)
				sourceStream = cacheFile.newInputStream()
			}
			rtn = uploadImage(client, authConfig, token, sourceStream, totalCount, tgtUrl, opts)
		} catch(ex) {
			log.error("uploadImage cloudFile error: ${ex}", ex)
		} finally {
			try { sourceStream?.close()} catch(e){}
		}
	}

	static uploadImage(HttpApiClient client, AuthConfig authConfig, String token, String srcUrl, String tgtUrl, String cachePath = null, Map opts = [:]) {
		log.debug("uploadImage src: ${srcUrl} tgt: ${tgtUrl}")
		def rtn = [success:false]
		def inboundClient
		def sourceStream
		def urlPath = new URL(srcUrl).path
		try {
			Long totalCount
			if(cachePath) {
				def cacheFile = new File(cachePath,urlPath)
				if(cacheFile.exists()) {
					sourceStream = cacheFile.newInputStream()
					totalCount = cacheFile.length()
				} else {
					inboundClient = HttpClients.createDefault()
					def inboundGet = new HttpGet(srcUrl)
					def inboundResponse = inboundClient.execute(inboundGet)
					totalCount = inboundResponse.getEntity().getContentLength()
					sourceStream = inboundResponse.getEntity().getContent()
				}
			} else {
				inboundClient = HttpClients.createDefault()
				def inboundGet = new HttpGet(srcUrl)
				def inboundResponse = inboundClient.execute(inboundGet)
				totalCount = inboundResponse.getEntity().getContentLength()
				sourceStream = inboundResponse.getEntity().getContent()
			}
			opts.isTarGz = srcUrl.endsWith('.tar.gz')

			opts.isXz = XZUtils.isCompressedFilename(srcUrl)
			if(opts.isXz) {
				def xzStream = new XZCompressorInputStream(sourceStream)
				totalCount = 0
				int len
				byte[] buffer = new byte[102400]
				while ((len = xzStream.read(buffer)) != -1) {
					if(len >= 0) {
						totalCount += len
					}
				}
				xzStream.close()
				def cacheFile = new File(cachePath, urlPath)
				sourceStream = cacheFile.newInputStream()
			}
			opts.progressUpdate = opts.progressUpdate ?: "Uploading image to \"${srcUrl}\""
			rtn = uploadImage(client, authConfig, token, sourceStream, totalCount, tgtUrl, opts)
		} catch(e) {
			log.error("uploadImage error: ${e}", e)
		} finally {
			inboundClient?.close()
			try {sourceStream?.close()} catch(ex){}
		}
		return rtn
	}

	static uploadImage(HttpApiClient client, AuthConfig authConfig, String token, InputStream sourceStream, Long contentLength, String tgtUrl, Map opts = [:]) {
		def outboundClient
		def rtn = [success:false]
		try {
			def outboundSslBuilder = new SSLContextBuilder()
			outboundSslBuilder.loadTrustMaterial(null, new TrustStrategy() {
				@Override
				boolean isTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
					return true
				}
			})
			def outboundSocketFactory = new SSLConnectionSocketFactory(outboundSslBuilder.build(), SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
			def clientBuilder = HttpClients.custom().setSSLSocketFactory(outboundSocketFactory)
			clientBuilder.setHostnameVerifier(new X509HostnameVerifier() {
				boolean verify(String host, SSLSession sess) { return true }

				void verify(String host, SSLSocket ssl) { }

				void verify(String host, String[] cns, String[] subjectAlts) { }

				void verify(String host, X509Certificate cert) { }
			})
			outboundClient = clientBuilder.build()
			def outboundPut = new HttpPut(tgtUrl)
			def inputEntity
			log.debug("Uploading from source stream!")
			if(opts.isTarGz == true) {
				log.debug("its a tar gz who knew!")
				def tarStream = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
						new java.util.zip.GZIPInputStream(sourceStream))
				def tarEntry = tarStream.getNextTarEntry()
				contentLength = tarEntry.getSize()
				inputEntity = new InputStreamEntity(new ProgressInputStream(new BufferedInputStream(tarStream, 1200), contentLength, 1, 0, opts.progressUpdate),contentLength)
			} else if(opts.isXz) {
				def xzStream = new XZCompressorInputStream(sourceStream)
				inputEntity = new InputStreamEntity(new ProgressInputStream(new BufferedInputStream(xzStream, 1200), contentLength, 1, 0, opts.progressUpdate),contentLength)
			} else {
				inputEntity = new InputStreamEntity(new ProgressInputStream(new BufferedInputStream(sourceStream, 1200), contentLength, 1, 0, opts.progressUpdate),contentLength)

			}
			outboundPut.addHeader('X-Auth-Token', token)
			outboundPut.addHeader('Content-Type', 'application/octet-stream')
			outboundPut.setEntity(inputEntity)
			def responseBody = outboundClient.execute(outboundPut)
			if(responseBody.statusLine.statusCode < 400) {
				rtn.success = true
			} else {
				rtn.success = false
				rtn.msg = "Upload Image Error HTTP:${responseBody.statusLine.statusCode}"
			}
		} catch(e) {
			log.error("uploadImage From Stream error: ${e}",e)
		} finally {
			outboundClient.close()
		}
		return rtn
	}

	// Upload to buckets
	static uploadImage(HttpApiClient client, AuthConfig authConfig, String token, cloudFile, targetFile, String cachePath = null, Map opts = [:]) {
		log.debug("uploadImage cloudFile: ${cloudFile.name} tgt: ${targetFile} cachePath: ${cachePath}")
		def rtn = [success:false]
		def usingCache = false
		def sourceStream
		Long totalCount
		try {
			if(cachePath) {
				def cacheFile = new File(cachePath, cloudFile.name)
				if(cacheFile.exists()) {
					sourceStream = cacheFile.newInputStream()
					totalCount = cacheFile.length()
				} else {
					sourceStream = cloudFile.inputStream
					totalCount = cloudFile.getContentLength()
				}
			} else {
				sourceStream = cloudFile.inputStream
				totalCount = cloudFile.getContentLength()
			}
			opts.isTarGz = cloudFile.getName().endsWith('.tar.gz')
			opts.isXz = XZUtils.isCompressedFilename(cloudFile.getName())
			if(opts.isXz) {
				def xzStream = new XZCompressorInputStream(sourceStream)
				totalCount = 0
				int len
				byte[] buffer = new byte[102400]
				while ((len = xzStream.read(buffer)) != -1) {
					if(len >= 0) {
						totalCount += len
					}
				}
				xzStream.close()
				def cacheFile = new File(cachePath, cloudFile.name)
				sourceStream = cacheFile.newInputStream()
			}
			rtn = uploadImage(client, authConfig, token, sourceStream, totalCount, targetFile, 0, opts)
		} catch(ex) {
			log.error("uploadImage cloudFile error: ${ex}", ex)
		} finally {
			try { sourceStream?.close()} catch(e){}
		}
	}

	static uploadImage(HttpApiClient client, AuthConfig authConfig, String token, String srcUrl, targetFile, String cachePath = null, Map opts = [:]) {
		log.debug("uploadImage src: ${srcUrl} tgt: ${targetFile}")
		def rtn = [success:false]
		def inboundClient
		def sourceStream
		def urlPath = new URL(srcUrl).path
		try {
			Long totalCount
			if(cachePath) {
				def cacheFile = new File(cachePath,urlPath)
				if(cacheFile.exists()) {
					sourceStream = cacheFile.newInputStream()
					totalCount = cacheFile.length()
				} else {
					inboundClient = HttpClients.createDefault()
					def inboundGet = new HttpGet(srcUrl)
					def inboundResponse = inboundClient.execute(inboundGet)
					totalCount = inboundResponse.getEntity().getContentLength()
					sourceStream = inboundResponse.getEntity().getContent()
				}
			} else {
				inboundClient = HttpClients.createDefault()
				def inboundGet = new HttpGet(srcUrl)
				def inboundResponse = inboundClient.execute(inboundGet)
				totalCount = inboundResponse.getEntity().getContentLength()
				sourceStream = inboundResponse.getEntity().getContent()
			}
			opts.isTarGz = srcUrl.endsWith('.tar.gz')

			opts.isXz = XZUtils.isCompressedFilename(srcUrl)
			if(opts.isXz) {
				def xzStream = new XZCompressorInputStream(sourceStream)
				totalCount = 0
				int len
				byte[] buffer = new byte[102400]
				while ((len = xzStream.read(buffer)) != -1) {
					if(len >= 0) {
						totalCount += len
					}
				}
				xzStream.close()
				def cacheFile = new File(cachePath, urlPath)
				sourceStream = cacheFile.newInputStream()
			}
			rtn = uploadImage(client, authConfig, token, sourceStream, totalCount, targetFile, 0, opts)
		} catch(e) {
			log.error("uploadImage error: ${e}", e)
		} finally {
			inboundClient?.close()
			try {sourceStream?.close()} catch(ex){}
		}
		return rtn
	}

	static uploadImage(HttpApiClient client, AuthConfig authConfig, String token, InputStream sourceStream, Long contentLength, targetFile, fileSize = 0, Map opts = [:], Closure progressCallback = null) {
		log.debug("Upload image from input stream to cloud file ${targetFile}")
		def rtn = [success:false]
		try {
			def vmInputStream
			log.debug("Uploading from source stream!")
			if(opts.isTarGz == true) {
				log.debug("its a tar gz who knew!")
				def tarStream = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
						new GzipCompressorInputStream(sourceStream))
				def tarEntry = tarStream.getNextTarEntry()
				contentLength = tarEntry.getSize()
				vmInputStream = new ProgressInputStream(new BufferedInputStream(tarStream, 1200), contentLength, 1, 0, opts.progressUpdate)
			} else if(opts.isXz) {
				def xzStream = new XZCompressorInputStream(sourceStream)
				vmInputStream = new ProgressInputStream(new BufferedInputStream(xzStream, 1200), contentLength, 1, 0, opts.progressUpdate)
			} else {
				vmInputStream = new ProgressInputStream(new BufferedInputStream(sourceStream, 1200), contentLength, 1, 0, opts.progressUpdate)
			}

			vmInputStream.progressCallback = progressCallback
			targetFile.setContentLength(contentLength)
			targetFile.setInputStream(vmInputStream)
			targetFile.save()
			rtn.success = true
		} catch(e) {
			log.error("downloadImage From Stream error: ${e}", e)
		}
		return rtn
	}

	static archiveVm(HttpApiClient client, AuthConfig authConfig, String projectId, cloudBucket, archiveFolder, opts, Closure progressCallback = null) {
		def rtn = [success: false, ovfFiles: []]
		def exportUpdater
		try {
			log.debug("archiveVm")
			def osUrl = getOpenstackImageUrl(authConfig)
			def osVersion = getOpenstackImageVersion(authConfig)
			authConfig.projectId = projectId
			def token = getToken(client, authConfig)
			def imageId = opts.imageId ?: opts.snapshotId
			def imagePath = osUrl + '/' + osVersion + '/images/' + imageId + '/file'
			def imageName = (opts.vmName ?: imageId) + '.' + (opts.imageFormat ?: 'qcow2')
			def targetFile = cloudBucket["${archiveFolder}/${imageName}"]
			def downloadResults = archiveImage(client, authConfig, projectId, imagePath, targetFile, token.token, opts.diskCapacity ?: 0, progressCallback, [progressUpdate:"Archiving image \"${imageName}\""])
			rtn.success = downloadResults.success
		} catch(e) {
			log.error("downloadVm error: ${e}", e)
		}
		return rtn
	}

	static archiveImage(HttpApiClient client, AuthConfig authConfig, String projectId, String srcUrl, targetFile, String token, fileSize = 0, Closure progressCallback = null, Map opts=[:]) {
		log.debug("archiveImage: src: ${srcUrl}")
		def inboundClient
		def rtn = [success:false]
		try {
			def inboundSslBuilder = new SSLContextBuilder()
			inboundSslBuilder.loadTrustMaterial(null, new TrustStrategy() {
				@Override
				boolean isTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
					return true
				}
			})
			def inboundSocketFactory = new SSLConnectionSocketFactory(inboundSslBuilder.build(), SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
			def clientBuilder = HttpClients.custom().setSSLSocketFactory(inboundSocketFactory)
			clientBuilder.setHostnameVerifier(new X509HostnameVerifier() {
				boolean verify(String host, SSLSession sess) { return true }

				void verify(String host, SSLSocket ssl) {}

				void verify(String host, String[] cns, String[] subjectAlts) {}

				void verify(String host, X509Certificate cert) {}
			})
			inboundClient = clientBuilder.build()
			def inboundGet = new HttpGet(srcUrl)
			inboundGet.addHeader('X-Auth-Token', token)
			inboundGet.addHeader('Connection', 'keep-alive')
			def responseBody = inboundClient.execute(inboundGet)
			log.debug("responseBody: ${responseBody}")
			if(responseBody.statusLine.statusCode != 404) {
				rtn.contentLength = responseBody.getEntity().getContentLength()
				if(rtn.contentLength < 0 && fileSize > 0)
					rtn.contentLength = fileSize
				log.debug("download image contentLength: ${rtn.contentLength}")
				def vmInputStream = new ProgressInputStream(new BufferedInputStream(responseBody.getEntity().getContent(), 1200), rtn.contentLength, 1, 0, opts.progressUpdate)
				vmInputStream.progressCallback = progressCallback
				targetFile.setContentLength(rtn.contentLength)
				targetFile.setInputStream(vmInputStream)
				targetFile.save()
				rtn.success = true
			} else {
				rtn.msg = responseBody.statusLine.statusCode + " " + responseBody.statusLine.reasonPhrase
				rtn.success = false
			}
		} catch(e) {
			log.error("archiveImage From Stream error: ${e}", e)
		} finally {
			inboundClient.close()
		}
		return rtn
	}

	static archiveImage(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success: false]
		try {
			log.debug("Archiving image")
			def osUrl = getOpenstackImageUrl(authConfig)
			def osVersion = getOpenstackImageVersion(authConfig)
			authConfig.projectId = projectId
			def token = getToken(client, authConfig)
			def imageId = opts.imageId
			def imagePath = osUrl + '/' + osVersion + '/images/' + imageId + '/file'
			def imageName = (opts.imageName ?: imageId) + '.' + (opts.imageFormat ?: 'qcow2')
			def targetDir = opts.targetDir ?: opts.targetDirectory
			def archiveFolder = opts.archiveFolder ?: targetDir
			def targetFile
			if(opts.targetZipStream) {
				targetFile = (ZipOutputStream)opts.targetZipStream
				ZipEntry zipEntry = new ZipEntry(imageName)
				targetFile.putNextEntry(zipEntry)
				rtn = downloadImage(client, authConfig, projectId, imagePath, targetFile, token.token, opts.progressCallback, [progressUpdate: "Archiving image \"${imageName}\""])
			} else {
				if(opts.cloudBucket) {
					targetFile = opts.cloudBucket["${archiveFolder}/${imageName}"]
				} else {
					targetFile =  new File(targetDir, imageName).newOutputString()
				}
				rtn = archiveImage(client, authConfig, projectId, imagePath, targetFile, token.token, 0, opts.progressCallback, [progressUpdate: "Archiving image \"${imageName}\""])
			}
			if(rtn.success) {
				rtn.fileName = imageName
				rtn.filePath = archiveFolder
				rtn.sizeInMb = rtn.contentLength
			}
		} catch(e) {
			log.error("archiveImage error: ${e}", e)
		}
		return rtn
	}

	static downloadImage(HttpApiClient client, AuthConfig authConfig, String projectId, String srcUrl, OutputStream targetStream, String token, Closure progressCallback = null, Map opts=[:]) {
		log.debug("downloadImage: src: ${srcUrl}")
		def inboundClient
		def rtn = [success: false]
		try {
			def inboundSslBuilder = new SSLContextBuilder()
			inboundSslBuilder.loadTrustMaterial(null, new TrustStrategy() {
				@Override
				boolean isTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
					return true
				}
			})
			def inboundSocketFactory = new SSLConnectionSocketFactory(inboundSslBuilder.build(), SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
			def clientBuilder = HttpClients.custom().setSSLSocketFactory(inboundSocketFactory)
			clientBuilder.setHostnameVerifier(new X509HostnameVerifier() {
				boolean verify(String host, SSLSession sess) { return true }

				void verify(String host, SSLSocket ssl) {}

				void verify(String host, String[] cns, String[] subjectAlts) {}

				void verify(String host, X509Certificate cert) {}
			})
			inboundClient = clientBuilder.build()
			def inboundGet = new HttpGet(srcUrl)
			inboundGet.addHeader('X-Auth-Token', token)
			inboundGet.addHeader('Content-Type', 'application/octet-stream')
			inboundGet.addHeader('Cache-Control', 'no-cache')
			inboundGet.addHeader('Connection', 'keep-alive')

			def responseBody = inboundClient.execute(inboundGet)
			log.debug("responseBody: ${responseBody}")
			rtn.contentLength = (responseBody.getEntity()?.getContentLength() ?: 0l)
			log.debug("download image contentLength: ${rtn.contentLength}")

			def vmInputStream = new ProgressInputStream(new BufferedInputStream(responseBody.getEntity().getContent(), 1200), rtn.contentLength, 1, 0, opts.progressUpdate)
			vmInputStream.progressCallback = progressCallback
			targetStream << vmInputStream
			targetStream.flush()
			rtn.success = true
		} catch(org.apache.http.ConnectionClosedException e) {
			throw(e)
		} catch(Exception e) {
			log.error("downloadImage From Stream error: ${e}", e)
		} finally {
			inboundClient.close()
		}
		return rtn
	}

	static deleteArchiveImage(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success: false]
		try {
			log.debug("deleting image archives")
			def targetDir = opts.targetDir ?: opts.targetDirectory
			def archiveFolder = opts.archiveFolder ?: targetDir
			def deleteResults
			if(opts.storageProvider) {
				try {
					opts.storageProvider["${opts.targetBucket}/${archiveFolder}/${opts.targetArchive}"].delete()
					deleteResults = [success: true]
				} catch (e) {
					deleteResults = [success: false]
				}
				def backupDir = opts.storageProvider.getDirectory("${opts.targetBucket}/${opts.parentBackupDirectory}")
				if(backupDir.listFiles().size() == 0) {
					backupDir.delete()
				}
			} else {
				deleteResults =  [success: new File(archiveFolder).deleteDir()]
			}
			rtn.success = deleteResults.success
			if(!rtn.success) {
				rtn.msg = "Failed to delete image archive."
			}
		} catch(e) {
			log.error("deleteResults error: ${e}", e)
		}
		return rtn
	}

	static exportToTempStore(String imageId, String outputBucket, String outputFolder, String outputName, Map opts=[:]) {
		return [success: true]
	}

	static getStorageToken(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false]
		log.debug("Getting storage token for at ${opts.authPoint}")
		log.debug("Creds ${opts.authUser}/${opts.authKey}")
		def url = new URL(opts.authPoint)
		def results = callApi(
				"${url.protocol}://${url.host}".toString(),
				null,
				url.path,
				null,
				[headers:['X-Auth-User':opts.authUser, 'X-Auth-Key':opts.authKey]],
				"GET"
		)
		rtn.success = results?.success && results?.error != true
		if (rtn.success) {
			rtn.token = results.headers['X-Storage-Token']
			rtn.storageUrl = results.headers['X-Storage-Url']
		} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
			authConfig.expireToken = true
			return getStorageToken(client, authConfig, opts)
		}
		return rtn

	}

	/**
	 * Retrieve an API token (Uses the token cached if exists)
	 *
	 * @param skipProject (optional) Authenticate without the project scope included in the authentication payload
	 * @return Map On Success - [token: String, tokenExpiresAt: Date, expires: Date, projectId: String, osVersion: Integer], On Error - [data: Map, errorCode: Integer, headers: Map]
	 **/
	static TokenResult getToken(HttpApiClient client, AuthConfig authConfig, skipProject=false) {
		def rtn = new TokenResult(success:false)
		def config = authConfig.cloudConfig
		def projectId
		def projectName
		if(!skipProject) {
			projectId = authConfig.projectId ?: null
			projectName = config.projectName ?: authConfig.projectName
			if(projectName instanceof List) {
				projectName = projectName.getAt(0)
			}
		}

		def tokenProjectKey = projectId ?: projectName
		def cachedToken = getCachedApiToken(authConfig.cloudId, tokenProjectKey)

		// Check if a usable token is cached
		if(cachedToken?.token && !authConfig.expireToken) {
			rtn.success = true
			rtn.token = cachedToken.token
			rtn.expires = cachedToken.expires
			rtn.apiProjectId = cachedToken.apiProjectId
			rtn.apiUserId = cachedToken.apiUserId
			rtn.apiDomainId = cachedToken.apiDomainId
			return rtn
		}

		log.debug("No valid cache token available, fetching new token for project ${projectId}")
		// refresh token if no token, token has expired, or token project changed
		def osUrl = authConfig.identityUrl ?: getOpenstackIdentityUrl(authConfig)
		def apiVersion = authConfig.identityVersion ?: getOpenstackIdentityVersion(authConfig)
		def domainId = authConfig.domainId ?: config.domainId ?: 'default'
		def domainName = config?.domainId ?: authConfig.domainId ?: 'default'
		def username = authConfig.username ?: config?.username
		def password = authConfig.password ?: config?.password
		def apiPath
		def body
		def includeProject = ((projectName?.length() > 0 || projectId) && !skipProject)

		if(ignoreSsl) {
			com.morpheusdata.core.util.SSLUtility.trustAllHostnames()
			com.morpheusdata.core.util.SSLUtility.trustAllHttpsCertificates()
		}

		// Build token request body
		apiPath = "/${apiVersion}/auth/tokens"
		body = [
				auth:[
						identity:[
								methods:['password'],
								password:[
										user:[
												name:username,
												password:password,
												domain:[id:domainId]
										]
								]
						]
				]
		]
		if(includeProject) {
			body.auth.scope = [
					project: [
							domain: [id: domainId]
					]
			]
			if(projectId) {
				body.auth.scope.project.id = projectId
			} else {
				body.auth.scope.project.name = projectName
			}
		} // else if(opts.scope == "domain") {
//			body.auth.scope = [
//					domain: [id: domainId]
//			]
//		}

		def headers = buildHeaders([:])
		def requestOpts = [headers:headers, body:body]
		log.debug("get token url: ${osUrl} version: ${apiVersion} path: ${apiPath} headers: ${headers} body: ${body}")
		def results = callApi(client, authConfig, apiPath, null, requestOpts)
		log.debug("getToken got: ${results}")

		rtn.success = results?.success && results?.error != true
		rtn.osVersion = apiVersion
		if(rtn.success && !rtn.token) {
			rtn.results = results.data
			rtn.token = results.headers['X-Subject-Token']
			rtn.apiProjectId = rtn.results.token?.project?.id
			rtn.apiDomainId = rtn.results.token?.user?.domain?.id
			rtn.apiUserId = rtn.results.token?.user?.id
			try {
				rtn.expires = MorpheusUtils.parseDate(rtn.results.token.expires_at)
				rtn.tokenExpiresAt = formatDateNoMillis(rtn.expires)
			} catch (e) {
				// don't let date parse errors block authentication, token will just not be reused
				log.error("Openstack getToken error: ${e}", e)
			}

			if(authConfig.cloudId) {
				cacheApiToken(authConfig.cloudId, tokenProjectKey, rtn)
			}
		} else {
			rtn.data = results.data
			rtn.errorCode = results.errorCode?.toLong()
			rtn.headers = results.headers
		}
		return rtn
	}

	/**
	 * Retrieve an API token. Uses the token in the authConfig if one exists. Updates authConfig with updated token.
	 * @param authConfig api credentials for retrieving a token. Pass a blank map to fall back to the zone config.
	 * @param skipProject (optional) Authenticate without the project scope included in the authentication payload
	 * @return Map On Success - [token: String, expires: Date, projectId: String], On Error - [data: Map, errorCode: Int, headers: Map]
	 **/
	static getApiToken(HttpApiClient client, AuthConfig authConfig, Boolean skipProject = false) {
		def rtn = [success:false, projectId:null, token: null]
		def requestToken = true

		if(authConfig.tokenResult) {
			if(authConfig.tokenResult.expires) {
				def checkDate = new Date()
				def tokenValid = ((checkDate.time + tokenBuffer) <= authConfig.tokenResult.expires.time)
				def tokenProjectChanged = (authConfig.projectId != authConfig.tokenResult.apiProjectId)
				if(!tokenValid || tokenProjectChanged) {
					// token has expired or project context for token changed, request a new one
					requestToken = true
				} else {
					// Token in authConfig is still valid!
					requestToken = false
					rtn.success = true
				}
			} else {
				// Token in authConfig is still valid!
				requestToken = false
				rtn.success = true
			}
		}

		// request a new one if not present in auth config or token has expired
		if(requestToken == true) {
			authConfig.expireToken = true
			TokenResult tokenResult = getToken(client, authConfig, skipProject)
			if(tokenResult.success) {
				authConfig.tokenResult = tokenResult
				authConfig.expireToken = false
				rtn.token = tokenResult
				rtn.success = true
			} else {
				authConfig.tokenResult = null
			}
		}

		return rtn
	}

	static Thread apiTokenReaperThread
	static apiTokens = [:]
	static apiTokenLock = new Object()

	static getApiTokenCacheKey(Long cloudId, String projectKey) {
		return "apiKey-${cloudId}-${projectKey ?: "unscoped"}"
	}

	static getCachedApiToken(Long cloudId, String projectId) {
		def rtn
		try {
			synchronized(apiTokenLock) {
				def cacheKey = getApiTokenCacheKey(cloudId, projectId)
				synchronized(apiTokenLock) {
					if(!apiTokenReaperThread) {
						apiTokenReaperThread = new Thread().start {
							while(true) {
								try {
									sleep(60000L)
									reapExpiredApiTokens()
								} catch(t2) {
									// log.warn("Error Running API Token Reaper Thread {}",t2.message,t2)
								}
							}
						}
					}
					def cachedToken = apiTokens[cacheKey]
					if(cachedToken) {
						if(cachedToken.expires instanceof String) {
							cachedToken.expires = MorpheusUtils.parseDate(cachedToken.expires)
						}
						if(cachedToken.expires > new Date(new Date().time + (10l*60l*1000l))) {
							rtn = cachedToken
						}
					}
				}
			}
		} catch(Exception ex) {
			log.error("getCachedApiToken error: {}", ex, ex)
		}

		return rtn
	}

	static void cacheApiToken(Long cloudId, String projectKey, TokenResult tokenResult) {
		try {
			synchronized(apiTokenLock) {
				def cacheKey = getApiTokenCacheKey(cloudId, projectKey)
				apiTokens[cacheKey] = [
						token: tokenResult.token,
						expires: tokenResult.tokenExpiresAt,
						apiProjectId: tokenResult.apiProjectId,
						apiDomainId: tokenResult.apiDomainId,
						apiUserId: tokenResult.apiUserId
				]
			}
		} catch(Exception ex) {
			log.error("cacheApiToken error: {}", ex, ex)
		}
	}

	static void reapExpiredApiTokens() {
		try {
			synchronized(apiTokenLock) {
				for(cacheKey in apiTokens.keySet()) {
					def tokenExpires = apiTokens[cacheKey]?.expires
					if(tokenExpires instanceof String) {
						tokenExpires = MorpheusUtils.parseDate(tokenExpires)
					}
					if(tokenExpires && tokenExpires < new Date(new Date().time - (10l*60l*1000l))) {
						apiTokens.remove(cacheKey)
					}
				}
			}
		} catch(Exception ex) {
			log.error("reapExpiredApiTokens error: {}", ex, ex)
		}
	}

	static void purgeApiTokens(Long zoneId) {
		try {
			synchronized(apiTokenLock) {
				def keys = apiTokens.keySet().iterator()
				while(keys.hasNext()) {
					def cacheKey = keys.next()
					if(cacheKey.startsWith("apiKey-${zoneId}")) {
						keys.remove()
					}
				}
			}
		} catch (Exception ex) {
			log.error("purgeApiTokens error: {}", ex, ex)
		}
	}

	static ServiceResponse callApi(HttpApiClient client, AuthConfig authConfig, path, token, opts = [:], method = 'POST') {
		ServiceResponse rtn = new ServiceResponse([success: false, headers: [:], error: false])

		try {
			def osUrl = opts.osUrl ?: authConfig.identityUrl
			if(osUrl && osUrl.endsWith(authConfig.identityVersion)) {
				osUrl = osUrl.replace("/${authConfig.identityVersion}", '')
			}
			if(!opts.headers) {
				opts.headers = [:]
			}
			if(!opts.headers['Content-Type']) {
				opts.headers['Content-Type'] = 'application/json'
			}
			if(token) {
				opts.headers.'X-Auth-Token' = token
				opts.headers.'User-Agent' = 'Mozilla/5.0 Ubuntu/8.10 Firefox/3.0.4 Morpheus'
			}
			def query = [:]
			if(opts.query) {
				opts.query?.each { k,v ->
					query[k] = v.toString()
				}
			}
			rtn = client.callJsonApi(osUrl, path, authConfig.username, authConfig.password,
					new HttpApiClient.RequestOptions(headers: opts.headers, body:opts.body, queryParams: query, contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), method.toString())
			if(rtn.errorCode == '401') {
				rtn.error = UNAUTHORIZED_ERROR
			} else if(rtn.errorCode && !rtn.error) {
				rtn.error = getApiError(rtn)
			} else {
				if(!rtn.data && rtn.content) {
					try {
						rtn.results = new groovy.json.JsonSlurper().parseText(rtn.content)
					} catch(ex2) {
						log.warn("Error parsing json response: {}", ex2, ex2)
					}
				}
			}

		} catch(e) {
			log.error("callApi error: {}", e, e)
			rtn.error = e.message
		}
		return rtn
	}

	static getApiError(ServiceResponse apiResponse) {
		def rtn
		def apiData = apiResponse.data
		if(apiData instanceof Number) {
			rtn = apiData.toString()
		} else if(apiData instanceof CharSequence) {
			rtn = apiData
		} else if(apiData instanceof Map) {
			if(apiData.error instanceof Map) {
				rtn = "${apiData.error.message} (${apiData.error.code})"
			} else if(apiData.forbidden && apiData.forbidden instanceof Map) {
				rtn = apiData.forbidden.message?.toString()
			} else {
				rtn = (apiData.error ?: apiResponse.error)?.toString()
			}
		}
		return rtn
	}

	static getGenericKeyName(keyId) {
		return "morpheus-${keyId ?: 'global'}".toString()
	}

	static parseHost(osUrl) {
		def uri = new java.net.URI(osUrl)
		return uri.getHost()
	}

	//"access": { "serviceCatalog": [{"endpoints": [{"adminURL": "http://10.0.1.150:8774/v2.1/5647d58457b942839a691408b9b5d12b", "region": "RegionOne", "internalURL": "http://10.0.1.150:8774/v2.1/5647d58457b942839a691408b9b5d12b", "id": "0d40113e8fd34d2ba7f81a58b3589b82", "publicURL": "http://10.0.1.150:8774/v2.1/5647d58457b942839a691408b9b5d12b"}], "endpoints_links": [], "type": "compute", "name": "nova"}, {"endpoints": [{"adminURL": "http://10.0.1.150:9696/", "region": "RegionOne", "internalURL": "http://10.0.1.150:9696/", "id": "7749f46975a8425c996cb334dafcc50d", "publicURL": "http://10.0.1.150:9696/"}], "endpoints_links": [], "type": "network", "name": "neutron"}, {"endpoints": [{"adminURL": "http://10.0.1.150:8776/v2/5647d58457b942839a691408b9b5d12b", "region": "RegionOne", "internalURL": "http://10.0.1.150:8776/v2/5647d58457b942839a691408b9b5d12b", "id": "773acd0547064f43a3a3899866e86e0c", "publicURL": "http://10.0.1.150:8776/v2/5647d58457b942839a691408b9b5d12b"}], "endpoints_links": [], "type": "volumev2", "name": "cinderv2"}, {"endpoints": [{"adminURL": "http://10.0.1.150:9292", "region": "RegionOne", "internalURL": "http://10.0.1.150:9292", "id": "6cbea136a1514bdba735656b9f4bb614", "publicURL": "http://10.0.1.150:9292"}], "endpoints_links": [], "type": "image", "name": "glance"}, {"endpoints": [{"adminURL": "http://10.0.1.150:8774/v2/5647d58457b942839a691408b9b5d12b", "region": "RegionOne", "internalURL": "http://10.0.1.150:8774/v2/5647d58457b942839a691408b9b5d12b", "id": "07563911a2294a8091707ba09bf3f8d7", "publicURL": "http://10.0.1.150:8774/v2/5647d58457b942839a691408b9b5d12b"}], "endpoints_links": [], "type": "compute_legacy", "name": "nova_legacy"}, {"endpoints": [{"adminURL": "http://10.0.1.150:8776/v1/5647d58457b942839a691408b9b5d12b", "region": "RegionOne", "internalURL": "http://10.0.1.150:8776/v1/5647d58457b942839a691408b9b5d12b", "id": "15b670320fa9426eabcfff4409269d4d", "publicURL": "http://10.0.1.150:8776/v1/5647d58457b942839a691408b9b5d12b"}], "endpoints_links": [], "type": "volume", "name": "cinder"}, {"endpoints": [{"adminURL": "http://10.0.1.150:8773/", "region": "RegionOne", "internalURL": "http://10.0.1.150:8773/", "id": "99ec90c3db6947b8b88e5ee037770d8d", "publicURL": "http://10.0.1.150:8773/"}], "endpoints_links": [], "type": "ec2", "name": "ec2"}, {"endpoints": [{"adminURL": "http://10.0.1.150:35357/v2.0", "region": "RegionOne", "internalURL": "http://10.0.1.150:5000/v2.0", "id": "28770d15979a487080c65301b69509ed", "publicURL": "http://10.0.1.150:5000/v2.0"}], "endpoints_links": [], "type": "identity", "name": "keystone"}], "user": {"username": "bwheeler", "roles_links": [], "id": "2b637152a7d44270a26d2b6c605e3963", "roles": [{"name": "_member_"}, {"name": "Member"}], "name": "bwheeler"}, "metadata": {"is_admin": 0, "roles": ["9fe2ff9ee4384b1894a90878d3e92bab", "f9f572e6744a4e4da1195f89c2451ab5"]}}}
	//"access": {"token": { "tenant": { : [ [{"adminURL": "http://10.0.1.150:8774/v2.1/5647d58457b942839a691408b9b5d12b", "region": "RegionOne", "internalURL": "http://10.0.1.150:8774/v2.1/5647d58457b942839a691408b9b5d12b", "id": "0d40113e8fd34d2ba7f81a58b3589b82", "publicURL": "http://10.0.1.150:8774/v2.1/5647d58457b942839a691408b9b5d12b"}], "endpoints_links": [], "type": "compute", "name": "nova"}, {"endpoints": [{"adminURL": "http://10.0.1.150:9696/", "region": "RegionOne", "internalURL": "http://10.0.1.150:9696/", "id": "7749f46975a8425c996cb334dafcc50d", "publicURL": "http://10.0.1.150:9696/"}], "endpoints_links": [], "type": "network", "name": "neutron"}, {"endpoints": [{"adminURL": "http://10.0.1.150:8776/v2/5647d58457b942839a691408b9b5d12b", "region": "RegionOne", "internalURL": "http://10.0.1.150:8776/v2/5647d58457b942839a691408b9b5d12b", "id": "773acd0547064f43a3a3899866e86e0c", "publicURL": "http://10.0.1.150:8776/v2/5647d58457b942839a691408b9b5d12b"}], "endpoints_links": [], "type": "volumev2", "name": "cinderv2"}, {"endpoints": [{"adminURL": "http://10.0.1.150:9292", "region": "RegionOne", "internalURL": "http://10.0.1.150:9292", "id": "6cbea136a1514bdba735656b9f4bb614", "publicURL": "http://10.0.1.150:9292"}], "endpoints_links": [], "type": "image", "name": "glance"}, {"endpoints": [{"adminURL": "http://10.0.1.150:8774/v2/5647d58457b942839a691408b9b5d12b", "region": "RegionOne", "internalURL": "http://10.0.1.150:8774/v2/5647d58457b942839a691408b9b5d12b", "id": "07563911a2294a8091707ba09bf3f8d7", "publicURL": "http://10.0.1.150:8774/v2/5647d58457b942839a691408b9b5d12b"}], "endpoints_links": [], "type": "compute_legacy", "name": "nova_legacy"}, {"endpoints": [{"adminURL": "http://10.0.1.150:8776/v1/5647d58457b942839a691408b9b5d12b", "region": "RegionOne", "internalURL": "http://10.0.1.150:8776/v1/5647d58457b942839a691408b9b5d12b", "id": "15b670320fa9426eabcfff4409269d4d", "publicURL": "http://10.0.1.150:8776/v1/5647d58457b942839a691408b9b5d12b"}], "endpoints_links": [], "type": "volume", "name": "cinder"}, {"endpoints": [{"adminURL": "http://10.0.1.150:8773/", "region": "RegionOne", "internalURL": "http://10.0.1.150:8773/", "id": "99ec90c3db6947b8b88e5ee037770d8d", "publicURL": "http://10.0.1.150:8773/"}], "endpoints_links": [], "type": "ec2", "name": "ec2"}, {"endpoints": [{"adminURL": "http://10.0.1.150:35357/v2.0", "region": "RegionOne", "internalURL": "http://10.0.1.150:5000/v2.0", "id": "28770d15979a487080c65301b69509ed", "publicURL": "http://10.0.1.150:5000/v2.0"}], "endpoints_links": [], "type": "identity", "name": "keystone"}], "user": {"username": "bwheeler", "roles_links": [], "id": "2b637152a7d44270a26d2b6c605e3963", "roles": [{"name": "_member_"}, {"name": "Member"}], "name": "bwheeler"}, "metadata": {"is_admin": 0, "roles": ["9fe2ff9ee4384b1894a90878d3e92bab", "f9f572e6744a4e4da1195f89c2451ab5"]}}}
	//serviceCatalog.endpoints.type internalURL
	static setEndpoints(Cloud cloud, HttpApiClient client, AuthConfig authConfig, osVersion, tokenResults, overrides = [:]) {
		def rtn = [success:false, errors:[:]]
		def configMap = cloud.getConfigMap()
		def osUrl = getOpenstackIdentityUrl(authConfig)
		def osHost = parseHost(osUrl)
		def region = configMap.region
		def serviceCatalog = tokenResults?.token?.catalog
		def endpointTypes = [
				[name:'compute', accessLabel:'compute', version:[noDot:false, defaultValue:null], required:true],
				[name:'image', accessLabel:'image', version:[noDot:true, defaultValue:'v2'], required:true],
				[name:'volume', accessLabel:'storage', version:[noDot:false, defaultValue:null], required:true],
				[name:'network', accessLabel:'network', version:[noDot:false, defaultValue:'v2.0'], required:true],
				[name:'load-balancer', accessLabel:'loadBalancer', version:[noDot:false, defaultValue:'v2.0'], required:false],
				[name:'elb', accessLabel:'loadBalancer', version:[noDot:false, defaultValue:'v1.0'], required:false],
				[name:'elbv1', accessLabel:'loadBalancerV1', version:[noDot:false, defaultValue:'v1.0'], required:false],
				[name:'object', accessLabel:'objectStorage', version:[noDot:true, defaultValue:'v1'], required:false],
				[name:'share', accessLabel:'sharedFileSystem', version:[noDot:true, defaultValue:'v1'], required:false]
		]
		endpointTypes.each { endpointType ->
			def endpointsForType = findEndpointsForType(serviceCatalog, endpointType.name)
			if(region) {
				endpointsForType = filterEndpointsForRegion(endpointsForType, region)
			}
			def apiResults = findLatestEndpointInSet(endpointsForType)
			log.debug("setEndpoints: found available endpoints: ${apiResults}")
			def match = findEndpoint(apiResults?.endpoints, osHost)

			if(!match && endpointType.required) {
				log.error("Openstack: Failed to set endpoint for ${endpointType.name} API")
				rtn.errors[endpointType.name] = "Failed to find endpoint."
			}
			configMap["${endpointType.accessLabel}Api"] = match ? parseEndpoint(match) : null
			configMap["${endpointType.accessLabel}Version"] = match ? parseEndpointVersion(match, endpointType.version.noDot, endpointType.version.defaultValue) : null
			def catalogEndpoint = configMap["${endpointType.accessLabel}Api"]
			def catalogVersion = configMap["${endpointType.accessLabel}Version"]
			def endpointOverride = configMap?.getAt("${endpointType.accessLabel}Api")
			def versionOverride = endpointOverride ? parseEndpointVersion(endpointOverride, endpointType.version.noDot, endpointType.version.defaultValue) : null
			if ((catalogEndpoint || endpointOverride) && (versionOverride || catalogVersion)) {
				configMap["${endpointType.accessLabel}MicroVersion"] = fetchApiMicroVersion(client, authConfig, endpointOverride ?: catalogEndpoint, versionOverride ?: catalogVersion)
			} else {
				configMap["${endpointType.accessLabel}MicroVersion"] = null
			}
		}
		cloud.setConfigMap(configMap)
		if(rtn.errors.size() == 0) {
			rtn.success = true
		}
		return rtn
	}

	static findEndpointsForType(ArrayList serviceCatalog, String type) {
		return serviceCatalog?.findAll { it.type.replaceAll(/v\d+$/, '') == type || it.type == type }
	}

	static filterEndpointsForRegion(ArrayList endpointTypes, String region) {
		endpointTypes.each { endpointType ->
			def regionEndpoints = endpointType.endpoints.findAll { endpoint -> endpoint.region == region }
			endpointType.endpoints = regionEndpoints
		}

		return endpointTypes
	}

	static findLatestEndpointInSet(ArrayList endpoints) {
		return endpoints?.sort {a,b -> b.type <=> a.type }?.getAt(0)
	}

	static findEndpoint(endpoints, osHost) {
		def rtn
		def endpoint
		try {
			if(endpoints && endpoints.size() > 0) {
				// try to find public endpoints first
				def publicEndpoints = endpoints.findAll { it.interface == "public" }
				if(publicEndpoints.size() == 1) {
					endpoint = publicEndpoints.getAt(0)
					rtn = endpoint.url
				} else if(publicEndpoints.size() > 0) {
					// find best public??? (probably not a likely case)
					endpoint = publicEndpoints?.find { doesEndpointContainHost(it, osHost) }
					if(!endpoint) {
						endpoint = publicEndpoints.getAt(0)
					}
					rtn = endpoint?.url
				} else {
					// if no public endpoints found then do our best to find a usable endpoint (legacy endpoint detection method)
					endpoint = endpoints?.find { doesEndpointContainHost(it, osHost) }
					if(!endpoint) {
						osHost = osHost.substring(osHost.indexOf('.') + 1)
						endpoint = endpoints?.find { doesEndpointContainHost(it, osHost) }
					}
					endpoint = endpoint ?: endpoints?.first()
					if(endpoint) {
						log.debug("findEndpoint: using endpoint :${endpoint}")
						rtn = [endpoint.publicURL, endpoint.url, endpoint.adminURL].find { it && it.indexOf(osHost) > -1 }
					}

					if(!rtn) {
						/// Endpoint doesn't match osHost
						endpoint = endpoints.find { it.interface == "public" }
						rtn = endpoint?.url
					}
				}
			}
		} catch(e) {
			log.error("Openstack, Error parsing endpoint host: ${e}", e)
		}
		return rtn
	}

	static Boolean doesEndpointContainHost(endpoint, osHost) {
		return endpoint.publicURL?.indexOf(osHost) > -1 || endpoint.adminURL?.indexOf(osHost) > -1 || endpoint.internalURL?.indexOf(osHost) > -1 || endpoint.url?.indexOf(osHost) > -1
	}

	static parseEndpoint(osUrl) {
		def rtn = osUrl
		def hostStart = osUrl.toLowerCase().indexOf("://")
		def hostStartStr = osUrl.substring(0, hostStart + 3)
		def matchStartStr = osUrl.substring(hostStartStr.length() - 1)
		def pathArgs = matchStartStr.tokenize('/')
		def versionIdx = -1
		if(pathArgs.size() > 0) {
			versionIdx = pathArgs.findIndexOf { it =~ /^v\d(\.\d)*/ }
		}
		if(versionIdx >= 0) {
			rtn = hostStartStr + pathArgs[0..<versionIdx].join("/")
		}
		return rtn
	}

	static parseEndpointVersion(osUrl, noDot = false, defaultValue = '') {
		def rtn
		def url = new URL(osUrl)
		def pathArgs = url.path?.tokenize('/')?.findAll{it}
		if(pathArgs.size() > 0) {
			rtn = pathArgs.find { it =~ /^v\d(\.\d)*/ }
		}
		if(!rtn) {
			rtn = defaultValue
		}
		log.debug("parsing endpoint version ${osUrl} - ${pathArgs} - ${rtn}")
		if(rtn?.length() > 0 && noDot == true) {
			def dotIndex = rtn.indexOf('.')
			if(dotIndex > -1)
				rtn = rtn.substring(0, dotIndex)
		}
		return rtn
	}

	static getEndpointOverrideVersion(AuthConfig authConfig, String configServiceRef, Boolean noDot=false, String defaultValue='') {
		def overrideVersion
		def apiEndpoint
		def config = authConfig.cloudConfig
		if(apiEndpoint) {
			overrideVersion = parseEndpointVersion(apiEndpoint, noDot, defaultValue)
		}

		return overrideVersion
	}

	static fetchApiMicroVersion(HttpApiClient client, AuthConfig authConfig, apiUrl, apiVersion) {
		def rtn = ''
		log.debug "fetchApiMicroVersion: ${apiUrl} ${apiVersion}"
		try {
			def token = getToken(client, authConfig)
			def apiVersionResults = callApi(client, authConfig, null, token.token, [osUrl: apiUrl], 'GET')
			if(apiVersionResults.success) {
				rtn = apiVersionResults.data.versions.find { it.id == apiVersion }?.version
				if(!rtn && !apiVersion?.contains(".")) {
					// we're using the generic version, select the latest sub version
					rtn = apiVersionResults.data.versions.find { it.status == "CURRENT" }?.version
				}
			}
		} catch(Exception e) {
			log.error("fetchApiMicroVersion error: {}", e, e)
		}

		return rtn
	}

	static getOpenstackHost(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		if(config?.host)
			return config.host
		throw new Exception('no openstack host specified')
	}

	static getOpenstackIdentityUrl(AuthConfig authConfig) {
		return authConfig.identityUrl
		throw new Exception('no openstack identity api url specified')
	}

	static getOpenstackIdentityPath(String osVersion) {
		def apiPath = '/auth/tokens'

		return apiPath
	}

	static getOpenstackIdentityVersion(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		def rtn = "v3"
		if(config?.identityVersion) {
			rtn = config.identityVersion
		}

		return rtn
	}

	static getOpenstackComputeUrl(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		if(config?.computeApi) {
			return config.computeApi
		}
		throw new Exception('no openstack compute api url specified')
	}

	static getOpenstackComputeVersion(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		def overrideVersion = getEndpointOverrideVersion(authConfig, "computeApi")
		if(overrideVersion) {
			return overrideVersion
		} else if(config?.computeVersion) {
			return config.computeVersion
		}
		throw new Exception('no openstack compute api version specified')
	}

	static getOpenstackComputeMicroVersion(HttpApiClient client, AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		// def overrideVersion = getEndpointOverrideVersion(zone, "computeApi")
		// if(overrideVersion) {
		// 	return overrideVersion
		// } else if(config?.computeVersion) {
		// 	return config.computeVersion
		// }
		def rtn = ''
		// use cached if we have it
		if(config?.computeMicroVersion) {
			rtn = config.computeMicroVersion
		} else {
			// otherwise go get it.
			def apiUrl = getOpenstackComputeUrl(authConfig)
			def apiVersion = getOpenstackComputeVersion(authConfig)
			rtn = fetchApiMicroVersion(client, authConfig, apiUrl, apiVersion)
		}

		return rtn
	}

	static getOpenstackImageUrl(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		if(config?.imageApi) {
			return config.imageApi
		}
		throw new Exception('no openstack image api url specified')
	}

	static getOpenstackImageVersion(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		def overrideVersion = getEndpointOverrideVersion(authConfig, "imageApi")
		if(overrideVersion) {
			return overrideVersion
		} else if(config?.imageVersion) {
			return config.imageVersion
		}
		throw new Exception('no openstack image api version specified')
	}

	static getOpenstackStorageUrl(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		if(config?.storageApi) {
			return config.storageApi
		}
		throw new Exception('no openstack storage api url specified')
	}

	static getOpenstackStorageVersion(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		def overrideVersion = getEndpointOverrideVersion(authConfig, "storageApi")
		if(overrideVersion) {
			return overrideVersion
		} else if(config?.storageVersion) {
			return config.storageVersion
		}
		throw new Exception('no openstack storage api version specified')
	}

	static getOpenstackNetworkUrl(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		if(config?.networkApi) {
			return config.networkApi
		}
		throw new Exception('no openstack network api url specified')
	}

	static getSafeOpenstackNetworkVersion(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		def overrideVersion = getEndpointOverrideVersion(authConfig, "networkApi")
		if(overrideVersion) {
			return overrideVersion
		} else if(config?.networkVersion) {
			return config.networkVersion
		}
		return null
	}

	static getOpenstackNetworkVersion(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		def overrideVersion = getEndpointOverrideVersion(authConfig, "networkApi")
		if(overrideVersion) {
			return overrideVersion
		} else if(config?.networkVersion) {
			return config.networkVersion
		}
		throw new Exception('no openstack network api version specified')
	}

	static getOpenstackLoadBalancerUrl(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		if(config?.loadBalancerApi) {
			return config.loadBalancerApi
		}
		throw new Exception('no openstack load balancer api url specified')
	}

	static getSafeOpenstackLoadBalancerVersion(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		def overrideVersion = getEndpointOverrideVersion(authConfig, "loadBalancerApi")
		if(overrideVersion) {
			return overrideVersion
		} else if(config?.loadBalancerVersion) {
			return config.loadBalancerVersion
		}
		return null
	}

	static getOpenstackSharedFileSystemUrl(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		if(config?.sharedFileSystemApi) {
			return config.sharedFileSystemApi
		}
		throw new Exception('no openstack shared file system api url specified')
	}

	static getOpenstackSharedFileSystemVersion(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		def overrideVersion = getEndpointOverrideVersion(authConfig, "sharedFileSystemApi")
		if(overrideVersion) {
			return overrideVersion
		} else if(config?.sharedFileSystemVersion) {
			return config.sharedFileSystemVersion
		}
		return null
	}

	static getStaticSharedFileSystemUrl(AuthConfig authConfig) {
		return null
	}

	static getOpenstackObjectStorageUrl(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		if(config?.objectStorageApi) {
			return config.objectStorageApi
		}
		throw new Exception('no openstack object storage api url specified')
	}

	static getOpenstackObjectStorageVersion(AuthConfig authConfig) {
		def config = authConfig.cloudConfig
		def overrideVersion = getEndpointOverrideVersion(authConfig, "objectStorageApi")
		if(overrideVersion) {
			return overrideVersion
		} else if(config?.objectStorageVersion) {
			return config.objectStorageVersion
		}
		return null
	}

	static parseLocationId(String location) {
		def rtn
		if(location) {
			def lastSlash = location.lastIndexOf('/')
			if(lastSlash > -1)
				rtn = location.substring(lastSlash + 1)
		}
		return rtn
	}

	static checkImageId(HttpApiClient client, AuthConfig authConfig, String projectId, String imageId) {
		log.debug "checkImageId opts: ${imageId}"
		def rtn = [success:false]
		try {
			def imageDetail = getImageDetail(client, authConfig, projectId, imageId)
			log.debug "checkImageId imageDetail: ${imageDetail}"
			if(imageDetail.success == true && imageDetail.results?.status) {
				if(imageDetail.results.status.toLowerCase() == 'active') {
					rtn.success = true
					rtn.results = imageDetail.results
				} else if(imageDetail.results.status.toLowerCase() == 'error') {
					rtn.error = true
					rtn.results = imageDetail.results
					rtn.success = true
				}
			}
		} catch(e) {
			log.error("Unknown error occurred while validating image information in openstack: ${e.message}",e)
		}
		return rtn
	}

	protected String nameWithoutExtension(String uri) {
		String extension = extensionFromURI(uri)
		if (extension) {
			return uri?.substring(0, uri.lastIndexOf(".${extension}"))
		}
		return uri
	}

	protected String extensionFromURI(String uri) {
		if(!uri) {
			return null
		}
		String[] uriComponents = uri.split("/")
		String extension
		String lastUriComponent = uriComponents[uriComponents.length - 1]
		if (lastUriComponent.lastIndexOf(".") >= 0) {
			extension = uri.substring(uri.lastIndexOf(".") + 1)
		}

		return extension
	}

	protected static retrieveImages(HttpApiClient client, AuthConfig authConfig, opts) {
		def rtn = [success:false]
		log.debug "retrieveImages expireToken: ${authConfig.expireToken}, opts: ${opts}"
		def path = opts.path ?: '/' + opts.osVersion + "/images"
		path = path.trim()
		def customOpts = [osUrl: opts.osUrl]
		customOpts.headers = ['Content-Type':'application/json']
		if(opts.query) {
			customOpts.query = opts.query
		}
		log.debug "retrieveImages path: ${path}"
		def results = callApi(client, authConfig, path, opts.token, customOpts, "GET")
		rtn.success = results?.success && results?.error != true
		if(rtn.success) {
			if(results.data) {
				rtn.results = results.data
			} else {
				rtn.results = new groovy.json.JsonSlurper().parseText(results.content)
			}

			log.debug "retrieveImages rtn.results.images size: ${rtn.results?.images?.size()}"

			if(rtn.results?.next) {
				log.debug "retrieveImages There are more images to cache"
				log.debug "retrieveImages rtn.results?.next: ${rtn.results?.next}"
				rtn.nextUrl = rtn.results.next
			} else {
				log.debug "retrieveImages There are no more images to cache"
				rtn.nextUrl = null
			}
		} else if(results.error == UNAUTHORIZED_ERROR && !authConfig.expireToken) {
			authConfig.expireToken = true
			return retrieveImages(client, authConfig, opts)
		}
		rtn
	}

	static toList(value) {
		[value].flatten()
	}

	static validateServerConfig(Map opts=[:]) {
		log.debug "validateServerConfig: $opts"
		def rtn = [success: false, errors: []]
		try {
			if(opts.networkInterfaces?.size() > 0) {
				def hasNetwork = true
				opts.networkInterfaces?.each {
					if(!it.network.group && (it.network.id == null || it.network.id == '') && (it.network.subnet == null || it.network.subnet == '')) {
						hasNetwork = false
					}
					if (it.ipMode == 'static' && !it.ipAddress) {
						rtn.errors = [field:'networkInterface', msg:'You must enter an ip address']
					}
				}
				if(hasNetwork != true) {
					rtn.errors += [field:'networkInterface', msg:'You must choose a network for each interface']
					rtn.errors += [field:'networkInterface.network.id', msg:'You must choose a network']
				}
			}
			else if (opts?.networkInterface) {
				// UI params
				toList(opts?.networkInterface?.network?.id)?.eachWithIndex { networkId, index ->
					log.debug("network.id: ${networkId}")
					if(networkId?.length() < 1) {
						rtn.errors << [field:'networkInterface', msg:'Network is required']
					}
					if (networkInterface[index].ipMode == 'static' && !networkInterface[index].ipAddress) {
						rtn.errors = [field:'networkInterface', msg:'You must enter an ip address']
					}
				}
			} else {
				rtn.errors += [field:'networkInterface', msg:'You must choose a network']
				rtn.errors += [field:'networkInterface.network.id', msg:'You must choose a network']
			}

			if (opts.containsKey('imageId') && !opts.imageId)
				rtn.errors += [field: 'imageId', msg: 'You must choose a image']

			if(opts.containsKey('nodeCount') && !opts.nodeCount){
				rtn.errors += [field:'nodeCount', msg:'Cannot be blank']
				rtn.errors += [field:'config.nodeCount', msg:'Cannot be blank']
			}
			rtn.success = (rtn.errors.size() == 0)
			log.debug("validateServer results: ${rtn}")
		} catch(e)  {
			log.error("error in validateServerConfig: ${e}", e)
		}
		return rtn
	}

	static findBestSubnet(HttpApiClient client, AuthConfig authConfig, String projectId, networkId) {
		def rtn = [success:false, subnet:null]
		try {
			def backupSubnet
			def subnetDetailResults = listSubnets(client, authConfig, projectId, networkId)
			if(subnetDetailResults.success) {
				subnetDetailResults.results?.subnets.each { subnet ->
					if(rtn.subnet == null) {
						def cidr = subnet.cidr
						if(cidr?.indexOf(':') > -1) {
							//ipv6
							if(!backupSubnet)
								backupSubnet = subnet
						} else if(cidr?.indexOf('.') > -1) {
							//ipv4 - use it
							rtn.subnet = subnet
						}
					}
				}
			}
			if(rtn.subnet == null && backupSubnet)
				rtn.subnet = backupSubnet
			rtn.success = rtn.subnet != null
		} catch(e) {
			log.error("error in findBestSubnet: ${e}", e)
		}
		return rtn
	}

	//neutron
	static listFloatingIps(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false, ipList:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiPath = authConfig.networkPath + '/floatingips'
			def headers = buildHeaders([:], tokenResults.token)
			def query = ['tenant_id':projectId]
			//filters
			if(opts.networkId)
				query['floating_network_id'] = opts.networkId
			if(opts.status)
				query['status'] = opts.status
			def requestOpts = [headers:headers, query:query]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.floatingips?.each { row ->
						row.externalId = row.id
						rtn.ipList << row
					}
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static createFloatingIp(HttpApiClient client, AuthConfig authConfig, String projectId, Map config, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiPath = authConfig.networkPath + '/floatingips'
			def headers = buildHeaders([:], tokenResults.token)
			def ipBody = [
					port_id:config.portId,
					floating_network_id:config.networkId,
					tenant_id:projectId
			]
			//optional config
			if(config.subnetId)
				ipBody.subnet_id = config.subnetId
			//create it
			def body = [floatingip:ipBody]
			log.debug("create floating ip body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'POST')
			log.debug("create floating ip results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.floatingIp = rtn.data?.floatingip
				rtn.externalId = rtn.floatingIp?.id
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static updateFloatingIp(HttpApiClient client, AuthConfig authConfig, String projectId, String floatingIpId, Map config, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/floatingips/' + floatingIpId
			def headers = buildHeaders([:], tokenResults.token)
			def ipBody = [
					port_id:config.portId
			]
			//update it
			def body = [floatingip:ipBody]
			log.debug("update floating ip body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'PUT')
			log.debug("update floating ip results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.floatingIp = rtn.data?.floatingip
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static releaseFloatingIp(HttpApiClient client, AuthConfig authConfig, String projectId, String floatingIpId, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/floatingips/' + floatingIpId
			def headers = buildHeaders([:], tokenResults.token)
			def ipBody = [
					port_id:null
			]
			//update it
			def body = [floatingip:ipBody]
			log.debug("release floating ip body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'PUT')
			log.debug("release floating ip results: ${results}")
			rtn.success = (results?.success && results?.error != true) || results.errorCode == 404
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.floatingIp = rtn.data?.floatingip
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteFloatingIp(HttpApiClient client, AuthConfig authConfig, String projectId, String floatingIpId, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/floatingips/' + floatingIpId
			def headers = buildHeaders([:], tokenResults.token)
			//update it
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'DELETE')
			log.debug("delete floating ip results: ${results}")
			rtn.success = (results?.success && results?.error != true) || results.errorCode == 404
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listAgents(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false, agentList:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/agents'
			def headers = buildHeaders([:], tokenResults.token)
			//filters
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.agents?.each { row ->
						row.externalId = row.id
						rtn.agentList << row
					}
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listRouters(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false, routerList:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiPath = authConfig.networkPath + '/routers'
			def headers = buildHeaders([:], tokenResults.token)
			def query = ['project_id': projectId]
			//filters
			def requestOpts = [headers:headers, query:query]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.routers?.each { row ->
						row.externalId = row.id
						rtn.routerList << row
					}
					rtn.results = [routers:rtn.routerList]
					results.total = results.data?.routers?.size() ?: 0
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static createNetwork(HttpApiClient client, AuthConfig authConfig, String projectId, Map config) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiPath = authConfig.networkPath + '/networks'
			def headers = buildHeaders([:], tokenResults.token)
			def networkBody = [
					name:config.name,
					admin_state_up:config.active,
					tenant_id:projectId
			]
			//optional config
			if(config.type)
				networkBody['provider:network_type'] = config.type
			if(config.bridgeName)
				networkBody['provider:physical_network'] = config.bridgeName
			if(config.setmentId)
				networkBody['provider:segmentation_id'] = config.setmentId
			//create it
			def body = [network:networkBody]
			log.debug("create network body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'POST')
			log.debug("create network results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.network = rtn.data?.network
				rtn.externalId = rtn.network?.id
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteNetwork(HttpApiClient client, AuthConfig authConfig, String projectId, String networkId) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/networks/' + networkId
			def headers = buildHeaders([:], tokenResults.token)
			//update it
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'DELETE')
			log.debug("delete network results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static updateNetwork(HttpApiClient client, AuthConfig authConfig, String projectId, networkId, Map config) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/networks/' + networkId
			def headers = buildHeaders([:], tokenResults.token)
			def networkBody = [
					name:config.name
			]

			if(config.description)
				networkBody.description = config.description
			//create it
			def body = [network:networkBody]
			log.debug("update network body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'PUT')
			log.debug("update network results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.network = rtn.data?.network
				rtn.externalId = rtn.network?.id
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
				rtn.errorCode = results.errorCode
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static updateSubnet(HttpApiClient client, AuthConfig authConfig, String projectId, subnetId, Map config) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/subnets/' + subnetId
			def headers = buildHeaders([:], tokenResults.token)
			def subnetBody = [
					name:config.name,
			]

			if(config.containsKey('gatewayIp'))
				subnetBody.gateway_ip = config.gatewayIp
			if(config.description)
				subnetBody.description = config.description
			if(config.allocationPools?.size() > 0)
				subnetBody.allocation_pools = config.allocationPools
			if(config.dnsServers?.size() > 0)
				subnetBody.dns_nameservers = config.dnsServers
			if(config.hostRoutes?.size() > 0)
				subnetBody.host_routes = config.hostRoutes
			if(config.dhcpServer) {
				subnetBody.enable_dhcp = config.dhcpServer
			}
			//create it
			def body = [subnet:subnetBody]
			log.debug("update subnet body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'PUT')
			log.debug("update subnet results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.subnet = rtn.data?.subnet
				rtn.externalId = rtn.subnet?.id
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
				rtn.errorCode = results.errorCode
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static createSubnet(HttpApiClient client, AuthConfig authConfig, String projectId, Map config) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && tokenResults.projectId) {
			def apiPath = authConfig.networkPath + '/subnets'
			def headers = buildHeaders([:], tokenResults.token)
			def subnetBody = [
					name:config.name,
					tenant_id:tokenResults.projectId,
					network_id:config.networkId,
					ip_version:4
			]
			//optional config
			if(config.cidr)
				subnetBody.cidr = config.cidr
			if(config.gatewayIp)
				subnetBody.gateway_ip = config.gatewayIp
			if(config.description)
				subnetBody.description = config.description
			if(config.allocationPools?.size() > 0)
				subnetBody.allocation_pools = config.allocationPools
			if(config.dnsServers?.size() > 0)
				subnetBody.dns_nameservers = config.dnsServers
			if(config.hostRoutes?.size() > 0)
				subnetBody.host_routes = config.hostRoutes
			if(config.dhcpServer) {
				subnetBody.enable_dhcp = config.dhcpServer
			}
			//create it
			def body = [subnet:subnetBody]
			log.debug("create subnet body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'POST')
			log.debug("create subnet results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.subnet = rtn.data?.subnet
				rtn.externalId = rtn.subnet?.id
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
				rtn.errorCode = results.errorCode
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static createRouter(HttpApiClient client, AuthConfig authConfig, String projectId, Map config) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/routers'
			def headers = buildHeaders([:], tokenResults.token)
			def routerBody = [
					name: config.name,
					tenant_id: projectId,
					admin_state_up: config.enabled == true
			]
			//optional config
			if(config.externalNetworkId) {
				routerBody.external_gateway_info = [network_id: config.externalNetworkId]
				if(config.containsKey('enableSnat')) {
					routerBody.external_gateway_info.enable_snat = config.enableSnat
				}
			}
			//create it
			def body = [router:routerBody]
			log.debug("create router body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'POST')
			log.debug("create router results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.router = rtn.data?.router
				rtn.externalId = rtn.router?.id
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static updateRouter(HttpApiClient client, AuthConfig authConfig, String projectId, String routerId, Map config) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/routers/' + routerId
			def headers = buildHeaders([:], tokenResults.token)
			def routerBody = [
					name:config.name,
					admin_state_up:config.enabled == true
			]
			//optional config
			if(config.externalNetworkId) {
				routerBody.external_gateway_info = [network_id: config.externalNetworkId]
				if(config.containsKey('enableSnat')) {
					routerBody.external_gateway_info.enable_snat = config.enableSnat
				}
			}
			//create it
			def body = [router:routerBody]
			log.debug("update router body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'PUT')
			log.debug("update router results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.router = rtn.data?.router
				rtn.externalId = rtn.router?.id
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static loadRouter(HttpApiClient client, AuthConfig authConfig, String projectId, String routerId, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/routers/' + routerId
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'GET')
			log.debug("create router results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.router = rtn.data?.router
				rtn.externalId = rtn.router?.id
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteRouter(HttpApiClient client, AuthConfig authConfig, String projectId, String routerId, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			if(opts.removeInterfaces == true) {
				//load them
				def portResults = loadRouterInterfaces(client, authConfig, projectId, routerId, opts)
				if(portResults.success == true) {
					portResults.ports?.each { port ->
						if(port.device_owner != 'network:router_gateway') {
							def removeResults = removeRouterInterface(client, authConfig, projectId, routerId, [portId:port.id], opts)
							if(removeResults.success != true)
								log.debug("error remvoing router port: ${port.id}")
						}
					}
				}
			}
			def apiPath = authConfig.networkPath + '/routers/' + routerId
			def headers = buildHeaders([:], tokenResults.token)
			//update it
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'DELETE')
			log.debug("delete router results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static updateRouterInterfaces(HttpApiClient client, AuthConfig authConfig, String projectId, String routerId, Map config, Map opts) {
		def rtn = [success:true, interfaces:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			//load existing
			def portResults = loadRouterInterfaces(client, authConfig, projectId, routerId, opts)
			//build sync lists
			def matchFunction = { Map existingItem, Map newItem ->
				newItem?.networkId == existingItem?.network_id
			}
			def syncLists = ComputeUtility.buildSyncLists(portResults.ports, config.interfaces, matchFunction)
			//remove removes
			syncLists.removeList?.each { Map existingItem ->
				if(existingItem.device_owner != 'network:router_gateway') {
					def removeResults = removeRouterInterface(client, authConfig, projectId, routerId, [portId:existingItem.id], opts)
					log.debug("removing interface: ${removeResults}")
					if(removeResults.success == false)
						rtn.success = false
				}
			}
			//update list - nothing to do
			syncLists.updateList?.each { updateMap ->
				rtn.interfaces << updateMap.existingItem
			}
			//add add
			syncLists.addList?.each { Map newItem ->
				def addResults = addRouterInterface(client, authConfig, projectId, routerId, [subnetId:newItem.subnetId], opts)
				log.debug("added interface: ${addResults}")
				if(addResults.success) {
					rtn.interfaces << addResults.interface
				} else {
					rtn.success = false
					rtn.msg = getNeutronError(addResults)
				}
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static loadRouterPorts(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false, ports:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/ports'
			def headers = buildHeaders([:], tokenResults.token)
			def query = [device_onwer:'network:router_interface']
			def requestOpts = [headers:headers, query:query]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.ports?.each { row ->
						row.externalId = row.id
						row.routerId = row.device_id
						rtn.ports << row
					}
				}
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static loadRouterInterfaces(HttpApiClient client, AuthConfig authConfig, String projectId, String routerId, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/ports'
			def headers = buildHeaders([:], tokenResults.token)
			def query = [device_id:routerId]
			def requestOpts = [headers:headers, query:query]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.ports = rtn.data?.ports
				//set the order
				rtn.ports?.eachWithIndex{ port, index ->
					port.index = index
				}
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static addRouterInterface(HttpApiClient client, AuthConfig authConfig, String projectId, String routerId, Map config, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/routers/' + routerId + '/add_router_interface'
			def headers = buildHeaders([:], tokenResults.token)
			//if we have a specified ip address - create the port first
			if(config.networkId && config.ipAddress && config.subnetId) {
				def portResults = addRouterPort(client, authConfig, projectId, config, opts)
				if(portResults.success == true) {
					config.portId = portResults.externalId
				}
			}
			//create it
			def body = [:]
			if(config.portId) {
				body.port_id = config.portId
			} else if(config.subnetId) {
				body.subnet_id = config.subnetId
			}
			log.debug("create router interface body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'PUT')
			log.debug("create interface results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.interface = rtn.data
				rtn.externalId = rtn.interface?.id
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static removeRouterInterface(HttpApiClient client, AuthConfig authConfig, String projectId, String routerId, Map config, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/routers/' + routerId + '/remove_router_interface'
			def headers = buildHeaders([:], tokenResults.token)
			//prepare
			def body = [:]
			if(config.subnetId)
				body.subnet_id = config.subnetId
			if(config.portId)
				body.port_id = config.portId
			log.debug("remove router interface body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'PUT')
			log.debug("remove interface results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.interface = rtn.data
				rtn.externalId = rtn.interface?.id
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static addRouterPort(HttpApiClient client, AuthConfig authConfig, String projectId, Map config, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/ports'
			def headers = buildHeaders([:], tokenResults.token)
			//create it
			def body = [port:[admin_state_up:true]]
			//network id
			if(config.networkId)
				body.port.network_id = config.networkId
			//subnet id
			if(config.subnetId && config.ipAddress) {
				body.port.fixed_ips = []
				body.port.fixed_ips << [subnet_id:config.subnetId, ip_address:config.ipAddress]
			}
			//additional properties?
			log.debug("create router port body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'POST')
			log.debug("create port results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.port = rtn.data?.port
				rtn.externalId = rtn.port?.id
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static removeRouterPort(HttpApiClient client, AuthConfig authConfig, String projectId, String portId, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && tokenResults.projectId) {
			def apiPath = authConfig.networkPath + '/ports/' + portId
			def headers = buildHeaders([:], tokenResults.token)
			//prepare
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'DELETE')
			log.debug("remove port results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.port = rtn.data
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static addRouterRoutes(HttpApiClient client, AuthConfig authConfig, String projectId, String routerId, Collection routeList, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/routers/' + routerId + '/add_extraroutes'
			def headers = buildHeaders([:], tokenResults.token)
			//create it
			def body = [router:[routes:[]]]
			routeList?.each { row ->
				body.router.routes << row
			}
			log.debug("create router route: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'PUT')
			log.debug("create route results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static removeRouterRoutes(HttpApiClient client, AuthConfig authConfig, String projectId, String routerId, Collection routeList, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/routers/' + routerId + '/remove_extraroutes'
			def headers = buildHeaders([:], tokenResults.token)
			//create it
			def body = [router:[routes:[]]]
			routeList?.each { row ->
				body.router.routes << row
			}
			log.debug("create router route: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'PUT')
			log.debug("remove route results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static loadServerPorts(HttpApiClient client, AuthConfig authConfig, String projectId, String serverId, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.networkPath + '/ports'
			def query = [device_id:serverId]
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers, query:query]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.networkUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data.ports
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static loadPort(HttpApiClient client, AuthConfig authConfig, String projectId, String portId, Map opts=[:]) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def osUrl = getOpenstackNetworkUrl(authConfig)
			def osVersion = getOpenstackNetworkVersion(authConfig)
			def apiPath = '/' + osVersion + '/ports/' + portId
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: osUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data.port
				rtn.data.externalId = rtn.data?.id
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}

	static addPort(HttpApiClient client, AuthConfig authConfig, String projectId, Map config, Map opts=[:]) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def osUrl = getOpenstackNetworkUrl(authConfig)
			def osVersion = getOpenstackNetworkVersion(authConfig)
			def apiPath = '/' + osVersion + '/ports'
			def headers = buildHeaders([:], tokenResults.token)
			def body = [
					port:[
							network_id: config.networkId
					]
			]
			if(config.securityGroups) {
				body.port.security_groups = config.securityGroups
			}
			def ipConfig
			if(config.ipAddress) {
				ipConfig = [ip_address:config.ipAddress]
			}
			if(config.subnetId) {
				ipConfig = ipConfig ?: [:]
				ipConfig.subnet_id = config.subnetId
			}
			if(ipConfig) {
				body.port.fixed_ips = []
				body.port.fixed_ips << ipConfig
			}
			log.debug("update port body: ${body}")
			def requestOpts = [headers:headers, body:body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: osUrl] + requestOpts, 'POST')
			log.debug("update port results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data.port
				rtn.data.externalId = rtn.data?.id
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}

	static updatePort(HttpApiClient client, AuthConfig authConfig, String projectId, String portId, Map config, Map opts=[:]) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def osUrl = getOpenstackNetworkUrl(authConfig)
			def osVersion = getOpenstackNetworkVersion(authConfig)
			def apiPath = '/' + osVersion + '/ports/' + portId
			def headers = buildHeaders([:], tokenResults.token)
			def body = [
					port:[:]
			]
			if(config.securityGroups) {
				body.port.security_groups = config.securityGroups
			}
			log.debug("update port body: ${body}")
			def requestOpts = [headers:headers, body:body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: osUrl] + requestOpts, 'PUT')
			log.debug("update port results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data.port
				rtn.data.externalId = rtn.data?.id
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}

	static extractPortPrimaryInterfaceValue(Map port) {
		// not available
		return
	}

	static addServerNetworkInterface(HttpApiClient client, AuthConfig authConfig, String projectId, String serverId, Map config, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && tokenResults.projectId) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			def apiPath = '/' + osVersion + "/${tokenResults.projectId}/servers/" + serverId + '/os-interface'
			def headers = buildHeaders([:], tokenResults.token)
			def body = [
					interfaceAttachment:[:]
			]
			if(config.portId) {
				body.interfaceAttachment.port_id = config.portId
			} else  {
				body.interfaceAttachment.net_id = config.networkId
			}
			def ipConfig
			if(config.ipAddress) {
				ipConfig = [ip_address:config.ipAddress]
			}
			if(config.subnetId) {
				ipConfig = ipConfig ?: [:]
				ipConfig.subnet_id = config.subnetId
			}
			if(ipConfig) {
				body.interfaceAttachment.fixed_ips = []
				body.interfaceAttachment.fixed_ips << ipConfig
			}
			log.debug("create server port body: ${body}")
			def requestOpts = [osUrl: osUrl, headers:headers, body:body]
			def results = callApi(client, authConfig, apiPath, null, requestOpts, 'POST')
			log.debug("create server port results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data.interfaceAttachment
				rtn.data.externalId = rtn.data?.port_id
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}

	static removeServerNetworkInterface(HttpApiClient client, AuthConfig authConfig, String projectId, String serverId, String portId, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && tokenResults.projectId) {
			def osUrl = getOpenstackComputeUrl(authConfig)
			def osVersion = getOpenstackComputeVersion(authConfig)
			def apiPath = '/' + osVersion + "/${tokenResults.projectId}/servers/" + serverId + '/os-interface/' + portId
			def headers = buildHeaders([:], tokenResults.token)
			//prepare
			def requestOpts = [osUrl: osUrl, headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, requestOpts, 'DELETE')
			log.debug("remove server port results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}


	//octavia && lbaas
	static listAmphorae(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false, amphoraeList:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath2 + '/amphorae'
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.amphorae?.each { row ->
						row.externalId = row.id
						rtn.amphoraeList << row
					}
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listLoadBalancers(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false, loadBalancerList:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath + '/loadbalancers'
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.loadbalancers?.each { row ->
						row.externalId = row.id
						rtn.loadBalancerList << row
					}
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listListeners(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false, listenerList:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath + '/listeners'
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.listeners?.each { row ->
						row.externalId = row.id
						rtn.listenerList << row
					}
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listPools(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false, poolList:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && tokenResults.projectId) {
			def apiPath = authConfig.loadBalancerPath + '/pools'
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.pools?.each { row ->
						row.externalId = row.id
						rtn.poolList << row
					}
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listMembers(HttpApiClient client, AuthConfig authConfig, String projectId, String poolId, Map opts) {
		def rtn = [success:false, memberList:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath + '/pools/' + poolId + '/members'
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.members?.each { row ->
						row.externalId = row.id
						rtn.memberList << row
					}
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listHealthMonitors(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false, healthMonitorList:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && tokenResults.projectId) {
			def apiPath = authConfig.loadBalancerPath + '/healthmonitors'
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.healthmonitors?.each { row ->
						row.externalId = row.id
						rtn.healthMonitorList << row
					}
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listPolicies(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false, policyList:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath + '/l7policies'
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.l7policies?.each { row ->
						row.externalId = row.id
						rtn.policyList << row
					}
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listRules(HttpApiClient client, AuthConfig authConfig, String projectId, String policyId, Map opts) {
		def rtn = [success:false, ruleList:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath + '/l7policies/' + policyId + '/rules'
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.rules?.each { row ->
						row.externalId = row.id
						rtn.ruleList << row
					}
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static createLoadBalancer(HttpApiClient client, AuthConfig authConfig, String projectId, Map config, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiPath = authConfig.loadBalancerPath + '/loadbalancers'
			def headers = buildHeaders([:], tokenResults.token)
			def lbBody = [
					name:config.name,
					admin_state_up:config.active,
					tenant_id:projectId,
					vip_subnet_id:config.subnetId,
					provider:'octavia',
					//vip_qos_policy_id:
			]
			def listenerBody = [
					name:config.listenerName,
					protocol:config.protocol,
					protocol_port:config.port
			]
			//optional config
			if(config.vipAddress)
				lbBody['vip_address'] = config.vip_address
			if(config.description)
				lbBody['description'] = config.description

			lbBody.listeners = [listenerBody]
			//pool
			if(config.pool) {
				def poolBody = [
						name:config.pool.name,
						lb_algorithm:config.pool.balanceMode,
						protocol:config.protocol,
						session_persistence:config.persistence
				]
				//add monitor
				if(config.monitor) {
					def monitorBody = [
							name:config.monitor.name,
							admin_state_up:true,
							delay:config.monitor.delay,
							max_retries:config.monitor.maxRetries,
							max_retries_down:config.monitor.maxRetriesDown,
							timeout:config.monitor.timeout,
							type:config.monitor.type
					]
					if(config.monitor.type == 'HTTP') {
						monitorBody.http_method = config.monitor.httpMethod
						monitorBody.url_path = config.monitor.path
					}
					poolBody.healthmonitor = monitorBody
				}
				//add members
				poolBody.members = []
				config.members?.each { member ->
					def memberBody = [
							name:member.name,
							weight:member.weight,
							admin_state_up:member.active,
							address:member.address,
							protocol_port:member.protocolPort,
							subnet_id:member.subnetId
					]
					poolBody.members << memberBody
				}
				listenerBody.default_pool = poolBody
			}
			//create it
			def body = [loadbalancer:lbBody]
			log.debug("create body: ${body}")
			def requestOpts = [headers:headers, body:body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'POST')
			log.debug("create load balancer results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static createNeutronLoadBalancer(HttpApiClient client, AuthConfig authConfig, String projectId, Map config, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiPath = authConfig.loadBalancerPath + '/loadbalancers'
			def headers = buildHeaders([:], tokenResults.token)
			def lbBody = [
					loadbalancer: [
							name:config.name,
							admin_state_up:config.active,
							tenant_id:projectId,
							vip_subnet_id:config.subnetId
					]
			]
			//optional config
			if(config.vipAddress)
				lbBody.loadbalancer['vip_address'] = config.vipAddress
			if(config.description)
				lbBody.loadbalancer['description'] = config.description
			//create it
			log.debug("create body: ${lbBody}")
			def requestOpts = [headers:headers, body:lbBody]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'POST')
			log.debug("create load balancer results: ${results}")
			rtn.success = results?.success && results?.error != true
			if(results.success == true) {
				rtn.data.loadbalancer = results.data?.loadbalancer
				def listenerResults = createNeutronListener(client, authConfig, projectId, config, opts + [loadBalancer: results.data.loadbalancer])
				if(listenerResults.success && listenerResults.error != true) {
					rtn.data += listenerResults.data
				} else {
					rtn.success = false
					rtn.msg = listenerResults.msg
				}
			} else {
				//loadbalancer error
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static createNeutronListener(HttpApiClient client, AuthConfig authConfig, String projectId, Map config, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def headers = buildHeaders([:], tokenResults.token)
			def loadBalancerId = opts?.loadBalancer?.id
			def listenerBody = [
					listener: [
							loadbalancer_id:loadBalancerId,
							name:config.listenerName,
							protocol:config.protocol,
							protocol_port:config.port,
							admin_state_up:config.active
					]
			]
			if(config.sslCertId) {
				listenerBody.listener['default_tls_container_ref'] = config.sslCertId
			}
			def listenerApiPath = authConfig.loadBalancerPath + '/listeners'
			def listenerRequestOpts = [headers:headers, body:listenerBody]
			def listenerResults = callApi(client, authConfig, listenerApiPath, null, [osUrl: authConfig.loadBalancerUrl] + listenerRequestOpts, 'POST')
			if(listenerResults.success == true) {
				//create the pool
				def listenerId = listenerResults.data?.listener?.id
				rtn.data.listener = listenerResults.data?.listener
				//pool
				if(config.pool) {
					def poolBody = [
							pool:[
									listener_id:listenerId,
									name:config.pool.name,
									lb_algorithm:config.pool.balanceMode,
									protocol:config.listenerProtocol,
									admin_state_up:config.active
							]
					]
					if(config.pool?.persistence && config.pool.persistence.type != null) {
						poolBody.pool.session_persistence = config.pool.persistence
					}
					def poolApiPath = authConfig.loadBalancerPath + '/pools'
					def poolRequestOpts = [headers:headers, body:poolBody]
					def poolResults = callApi(client, authConfig, poolApiPath, null, [osUrl: authConfig.loadBalancerUrl] + poolRequestOpts, 'POST')
					if(poolResults.success == true) {
						def poolId = poolResults.data?.pool?.id
						rtn.data.pool = poolResults.data?.pool
						//add members
						def membersOk = true
						def memberResults
						rtn.data.members = []
						config.members?.each { member ->
							if(membersOk) {
								def memberBody = [
										member:[
												weight:member.weight,
												admin_state_up:member.active,
												address:member.address,
												protocol_port:member.protocolPort,
												subnet_id:member.subnetId
										]
								]
								//add it one by one
								def memberApiPath = authConfig.loadBalancerPath + '/pools/' + poolId + '/members'
								def memberRequestOpts = [headers:headers, body:memberBody]
								memberResults = callApi(client, authConfig, memberApiPath, null, [osUrl: authConfig.loadBalancerUrl] + memberRequestOpts, 'POST')
								if(memberResults.success == true) {
									//keep going
									def memberId = memberResults.data?.member?.id
									if(memberResults.data?.member)
										rtn.data.members << memberResults.data.member
								} else {
									membersOk = false
								}
							}
						}
						if(membersOk == true) {
							//add monitor
							if(config.monitor) {
								def monitorBody = [
										healthmonitor:[
												pool_id:poolId,
												name:config.monitor.name,
												admin_state_up:true,
												delay:config.monitor.delay,
												max_retries:config.monitor.maxRetries,
												timeout:config.monitor.timeout,
												type:config.monitor.type
										]
								]
								if(config.monitor.type == 'HTTP') {
									monitorBody.healthmonitor.http_method = config.monitor.httpMethod
									monitorBody.healthmonitor.url_path = config.monitor.path
								}
								def monitorApiPath = authConfig.loadBalancerPath + '/healthmonitors'
								def monitorRequestOpts = [headers:headers, body:monitorBody]
								def monitorResults = callApi(client, authConfig, monitorApiPath, null, [osUrl: authConfig.loadBalancerUrl] + monitorRequestOpts, 'POST')
								if(monitorResults.success == true) {
									//add done
									def monitorId = monitorResults.data?.healthmonitor?.id
									rtn.data.monitor = monitorResults.data.healthmonitor
									rtn.success = true
								} else {
									//monitor error
									rtn.data += monitorResults.data
									rtn.msg = getNeutronError(monitorResults)
								}
							} else {
								// no monitor to configure, so we're done
								rtn.success = true
							}
						} else {
							//member error
							rtn.data += memberResults.data
							rtn.msg = getNeutronError(memberResults)
						}
					} else {
						//pool error
						rtn.data += poolResults.data
						rtn.msg = getNeutronError(poolResults)
					}
				} else {
					//listener and lb created - no pool
					rtn.success = true
				}
			} else {
				//listener error
				rtn.data += listenerResults.data
				rtn.msg = getNeutronError(listenerResults)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteLoadBalancer(HttpApiClient client, AuthConfig authConfig, String projectId, String loadBalancerId, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath + '/loadbalancers/' + loadBalancerId
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//cascade?
			if(opts.cascade == true)
				requestOpts.query = [cascade:'true']
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'DELETE')
			rtn.success = (results?.success && results?.error != true) || results.errorCode == 404
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteNeutronLoadBalancer(HttpApiClient client, AuthConfig authConfig, String projectId, String loadBalancerId, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath + '/loadbalancers/' + loadBalancerId
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'DELETE')
			rtn.success = (results?.success && results?.error != true) || results.errorCode == 404
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static updatePool(HttpApiClient client, AuthConfig authConfig, String projectId, String poolId, Map config, Map opts) {
		def rtn = [success: false, data: [:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiUrl = authConfig.loadBalancerUrl
			def apiPath = authConfig.loadBalancerPath + '/pools/' + poolId
			def headers = buildHeaders([:], tokenResults.token)

			def body = [
					pool:[
							name: config.name,
							lb_algorithm: config.balanceMode,
							session_persistence: [
									type: config.persistenceType
							]
					]
			]
			if(config.persistenceType == "APP_COOKIE" && config.containsKey('cookieName')) {
				body.pool.session_persistence.cookie_name = config.cookieName
			}
			if(["APP_COOKIE", "HTTP_COOKIE"].contains(config.persistenceType) && config.containsKey('sessionTimeout')) {
				body.pool.session_persistence.persistence_timeout = config.sessionTimeout
			}

			def requestOpts = [headers: headers, body: body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'PUT')
			rtn.success = results?.success && !results?.errorCode
			if(rtn.success) {
				rtn.data = results.data?.pool ?: [:]
			} else {
				rtn.data = results.data
				rtn.errorCode = results.erroCode
				rtn.msg = getNeutronError(results) ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}

	static updateNeutronPool(HttpApiClient client, AuthConfig authConfig, String projectId, String poolId, Map config, Map opts) {
		def rtn = [success: false, data: [:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiUrl = authConfig.loadBalancerUrl
			def apiPath = authConfig.loadBalancerPath + '/pools/' + poolId
			def headers = buildHeaders([:], tokenResults.token)

			def body = [
					pool:[
							name: config.name,
							lb_algorithm: config.balanceMode,

					]
			]
			if(config.persistencyType) {
				body.pool.session_persistence = [
						type: config.persistenceType
				]
			}
			if(config.persistenceType == "APP_COOKIE" && config.containsKey('cookieName')) {
				body.pool.session_persistence.cookie_name = config.cookieName
			}
			if(["APP_COOKIE", "HTTP_COOKIE"].contains(config.persistenceType) && config.containsKey('sessionTimeout')) {
				body.pool.session_persistence.persistence_timeout = config.sessionTimeout
			}

			def requestOpts = [headers: headers, body: body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'PUT')
			rtn.success = results?.success && !results?.errorCode
			if(rtn.success) {
				rtn.data = results.data?.pool ?: [:]
			} else {
				rtn.data = results.data
				rtn.errorCode = results.erroCode
				rtn.msg = getNeutronError(results) ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}

	static deletePool(HttpApiClient client, AuthConfig authConfig, String projectId, String poolId, Map opts) {
		def rtn = [success:false, data:[:], errors:[:], results:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPoolPath =  authConfig.loadBalancerPath + '/pools/' + poolId
			def poolHeaders = buildHeaders([:], tokenResults.token)
			def poolRequestOpts = [headers:poolHeaders]
			def results = callApi(client, authConfig, apiPoolPath, null, [osUrl: authConfig.loadBalancerUrl] + poolRequestOpts, 'DELETE')
			rtn.success = (results?.success && results?.error != true) || results.errorCode == 404
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}


	static deleteNeutronPool(HttpApiClient client, AuthConfig authConfig, String projectId, String poolId, Map opts) {
		def rtn = [success:false, data:[:], errors:[:], results:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPoolPath =  authConfig.loadBalancerPath + '/pools/' + poolId
			def poolHeaders = buildHeaders([:], tokenResults.token)
			def poolRequestOpts = [headers:poolHeaders]
			def results = callApi(client, authConfig, apiPoolPath, null, [osUrl: authConfig.loadBalancerUrl] + poolRequestOpts, 'DELETE')
			rtn.success = (results?.success && results?.error != true) || results.errorCode == 404
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteListener(HttpApiClient client, AuthConfig authConfig, String projectId, String listenerId, Map opts) {
		def rtn = [success:false, data:[:], errors:[:], results:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiListenerPath =  authConfig.loadBalancerPath + '/listeners/' + listenerId
			def listenerHeaders = buildHeaders([:], tokenResults.token)
			def listenerRequestOpts = [headers:listenerHeaders]
			def results = callApi(client, authConfig, apiListenerPath, null, [osUrl:  authConfig.loadBalancerUrl] +  listenerRequestOpts, 'DELETE')
			rtn.success = (results?.success && results?.error != true) || results.errorCode == 404
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteNeutronListener(HttpApiClient client, AuthConfig authConfig, String projectId, String listenerId, Map opts) {
		def rtn = [success:false, data:[:], errors:[:], results:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiListenerPath =  authConfig.loadBalancerPath + '/listeners/' + listenerId
			def listenerHeaders = buildHeaders([:], tokenResults.token)
			def listenerRequestOpts = [headers:listenerHeaders]
			def results = callApi(client, authConfig, apiListenerPath, null, [osUrl: authConfig.loadBalancerUrl] +  listenerRequestOpts, 'DELETE')
			rtn.success = (results?.success && results?.error != true) || results.errorCode == 404
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static addBackendGroupMember(HttpApiClient client, AuthConfig authConfig, String projectId, String poolId, Map memberConfig, Map opts=[:]) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath + "/pools/${poolId}/members"
			def headers = buildHeaders([:], tokenResults.token)
			def body = [member: memberConfig]
			def requestOpts = [headers:headers, body: body]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'POST')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static updateBackendGroupMember(HttpApiClient client, AuthConfig authConfig, String projectId, String poolId, String memberId, Map config, Map opts=[:]) {
		def rtn = [success:false, data:[:], errors:[:], results:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath =  authConfig.loadBalancerPath + '/pools/' + poolId + '/members/' + memberId
			def headers = buildHeaders([:], tokenResults.token)
			def body = [
					member: [
							name: config.name,
							weight: config.weight
					]
			]
			def requestOpts = [headers:headers, body:body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'PUT')
			rtn.success = (results?.success && results?.error != true)
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}

	static updateNeutronBackendGroupMember(HttpApiClient client, AuthConfig authConfig, String projectId, String poolId, String memberId, Map config, Map opts=[:]) {
		def rtn = [success:false, data:[:], errors:[:], results:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath =  authConfig.loadBalancerPath + '/pools/' + poolId + '/members/' + memberId
			def headers = buildHeaders([:], tokenResults.token)
			def body = [
					member: [
							name: config.name,
							weight: config.weight
					]
			]
			def requestOpts = [headers:headers, body:body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'PUT')
			rtn.success = (results?.success && results?.error != true)
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}

	static removeBackendGroupMember(HttpApiClient client, AuthConfig authConfig, String projectId, String poolId, String memberId, Map opts=[:]) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath + "/pools/${poolId}/members/${memberId}"
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'DELETE')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteNeutronBackendGroupMember(HttpApiClient client, AuthConfig authConfig, String projectId, String poolId, String memberId, Map opts) {
		def rtn = [success:false, data:[:], errors:[:], results:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiMemberPath =  authConfig.loadBalancerPath + '/pools/' + poolId + '/members/' + memberId
			def memberHeaders = buildHeaders([:], tokenResults.token)
			def memberRequestOpts = [headers:memberHeaders]
			def results = callApi(client, authConfig, apiMemberPath, null, [osUrl: authConfig.loadBalancerUrl] + memberRequestOpts, 'DELETE')
			rtn.success = (results?.success && results?.error != true) || results.errorCode == 404
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static updateMonitor(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId, Map config, Map opts=[:]) {
		def rtn = [success:false, data:[:], errors:[:], results:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiMonitorPath =  authConfig.loadBalancerPath + '/healthmonitors/' + externalId
			def requestHeaders = buildHeaders([:], tokenResults.token)
			def body = [
					healthmonitor: [
							name: config.name,
							admin_state_up: config.enabled,
							delay: config.delay,
							timeout: config.timeout
					]
			]
			if(config.max_retries) {
				body.healthmonitor.max_retries = config.max_retries
			}
			if(config.port) {
				body.healthmonitor.monitor_port = config.port
			}
			if(config.type == "http") {
				if(config.host) {
					body.healthmonitor.domain_name = config.host
				}
				if(config.path) {
					body.healthmonitor.url_path = config.path
				}
			}
			def requestOpts = [headers:requestHeaders, body: body]
			def results = callApi(client, authConfig, apiMonitorPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'PUT')
			rtn.success = (results?.success && results?.error != true)
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}

	static updateNeutronMonitor(HttpApiClient client, AuthConfig authConfig, String projectId, String externalId, Map config, Map opts=[:]) {
		def rtn = [success:false, data:[:], errors:[:], results:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiMonitorPath =  authConfig.loadBalancerPath + '/healthmonitors/' + externalId
			def requestHeaders = buildHeaders([:], tokenResults.token)
			def body = [
					healthmonitor: [
							name: config.name,
							admin_state_up: config.enabled,
							delay: config.delay,
							timeout: config.timeout
					]
			]
			if(config.max_retries) {
				body.healthmonitor.max_retries = config.max_retries
			}
			if(config.port) {
				body.healthmonitor.monitor_port = config.port
			}
			if(config.type == "http") {
				if(config.host) {
					body.healthmonitor.domain_name = config.host
				}
				if(config.path) {
					body.healthmonitor.url_path = config.path
				}
			}
			def requestOpts = [headers:requestHeaders, body: body]
			def results = callApi(client, authConfig, apiMonitorPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'PUT')
			rtn.success = (results?.success && results?.error != true)
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}

	static deleteMonitor(HttpApiClient client, AuthConfig authConfig, String projectId, String monitorId, Map opts) {
		def rtn = [success:false, data:[:], errors:[:], results:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiMonitorPath =  authConfig.loadBalancerPath + '/healthmonitors/' + monitorId
			def monitorHeaders = buildHeaders([:], tokenResults.token)
			def monitorRequestOpts = [headers:monitorHeaders]
			def results = callApi(client, authConfig, apiMonitorPath, null, [osUrl: authConfig.loadBalancerUrl] + monitorRequestOpts, 'DELETE')
			rtn.success = (results?.success && results?.error != true) || results.errorCode == 404
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteNeutronMonitor(HttpApiClient client, AuthConfig authConfig, String projectId, String monitorId, Map opts) {
		def rtn = [success:false, data:[:], errors:[:], results:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiMonitorPath =  authConfig.loadBalancerPath + '/healthmonitors/' + monitorId
			def monitorHeaders = buildHeaders([:], tokenResults.token)
			def monitorRequestOpts = [headers:monitorHeaders]
			def results = callApi(client, authConfig, apiMonitorPath, null, [osUrl: authConfig.loadBalancerUrl] + monitorRequestOpts, 'DELETE')
			rtn.success = (results?.success && results?.error != true) || results.errorCode == 404
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteNeutronCert(HttpApiClient client, AuthConfig authConfig, String projectId, String certId, Map opts) {
		def rtn = [success:false, data:[:], errors:[:], results:[]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiCertPath =  authConfig.loadBalancerPath + '/certificates/' + certId
			def certHeaders = buildHeaders([:], tokenResults.token)
			def certRequestOpts = [headers:certHeaders]
			def results = callApi(client, authConfig, apiCertPath, null, [osUrl: authConfig.loadBalancerUrl] + certRequestOpts, 'DELETE')
			rtn.success = (results?.success && results?.error != true) || results.errorCode == 404
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
			} else {
				rtn.errorCode = results.errorCode
				rtn.data = results.data
				rtn.msg = getNeutronError(results)
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static loadLoadBalancer(HttpApiClient client, AuthConfig authConfig, String projectId, String loadBalancerId, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath + '/loadbalancers/' + loadBalancerId
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.loadBalancer = rtn.data?.loadbalancer
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static waitForNetwork(HttpApiClient client, AuthConfig authConfig, String projectId, String networkId, Map opts=[:]) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				def results = loadNetwork(client, authConfig, projectId, networkId, opts)
				if(results.success == true && results.results?.network) {
					if(results.results.network?.status == 'ACTIVE') {
						rtn.success = true
						rtn.data = results.results
						pending = false
					} else if(["DOWN", "ERROR"].contains(results.results?.network?.status)) {
						rtn.data = results.results
						pending = false
					} else {
						sleep(sleepTimeout)
					}
				} else {
					sleep(sleepTimeout)
				}
				attempts++
				if(attempts > 20) {
					pending = false
				}
			}
		} catch(Exception e) {
			log.error("Error waiting for network ${e.message}", e)
		}
		return rtn
	}

	static waitForLoadBalancer(HttpApiClient client, AuthConfig authConfig, String projectId, String loadBalancerId, Map opts) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				def results = loadLoadBalancer(client, authConfig, projectId, loadBalancerId, opts)
				if(results.success == true && results.loadBalancer) {
					if(results.loadBalancer.provisioning_status == 'ACTIVE') {
						rtn.success = true
						rtn.loadBalancer = results.loadBalancer
						rtn.data = results.data
						pending = false
					} else if(results.loadBalancer.provisioning_status == 'ERROR') {
						rtn.success = true
						rtn.loadBalancer = results.loadBalancer
						rtn.data = results.data
						pending = false
					} else {
						sleep(sleepTimeout)
					}
				} else {
					sleep(sleepTimeout)
				}
				attempts++
				if(attempts > 20) {
					pending = false
				}
			}
		} catch(Exception e) {
			log.error("Error waiting for load balancer ${e.message}", e)
		}
		return rtn
	}

	static createNeutronCertificate(HttpApiClient client, AuthConfig authConfig, String projectId, Map createConfig, Map opts=[:]) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath + "/certificates"
			def headers = buildHeaders([:], tokenResults.token)
			def body = createConfig
			def requestOpts = [headers:headers, body: body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'POST')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.data.externalId = results.data.id
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static loadPool(HttpApiClient client, AuthConfig authConfig, String projectId, String poolId, Map opts) {
		def rtn = [success:false, data:[:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiPath = authConfig.loadBalancerPath + '/pools/' + poolId
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null, [osUrl: authConfig.loadBalancerUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				rtn.data = results.data
				rtn.pool = rtn.data?.pool
			} else {
				rtn.data = results.data
				rtn.msg = results.data?.faultstring ?: results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}


	// Object Storage
	static listContainers(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success:false, data:[], total:0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiPath = authConfig.objectStoragePath + '/' + projectId
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers:headers]
			//execute the call
			def results = callApi(client, authConfig, apiPath, null,[osUrl: authConfig.objectStoragePath] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.headers = results.headers
				if(results.data) {
					results.data.each { row ->
						row.externalId = row.name
						rtn.data << row
					}
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.msg ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	// Shared File Systems
	static listFileShareApiVersions(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success: false, data: [], total: 0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token) {
			def apiUrl = authConfig.sharedFileSystemUrl ?: getOpenstackSharedFileSystemUrl(authConfig)
			def apiVersion = authConfig.sharedFileSystemVersion ?: getOpenstackSharedFileSystemVersion(authConfig)
			def apiPath =  '/'
			def headers = buildHeaders([:], tokenResults.token)
			def requestOpts = [headers: headers]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.data = results.data
			} else {
				rtn.data = results.data
				rtn.msg = results?.badRequest?.message ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static listFileShares(HttpApiClient client, AuthConfig authConfig, String projectId, Map opts) {
		def rtn = [success: false, data: [], total: 0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiUrl = authConfig.sharedFileSystemUrl ?: getOpenstackSharedFileSystemUrl(authConfig)
			def apiVersion = authConfig.sharedFileSystemVersion ?: getOpenstackSharedFileSystemVersion(authConfig)
			def apiPath =  '/' + apiVersion + '/' + projectId + '/shares'
			def headerOpts = [:]
			if(authConfig.storageShareApiMicroVersion) {
				headerOpts['X-Openstack-Manila-Api-Version'] = authConfig.storageShareApiMicroVersion
			}
			def headers = buildHeaders(headerOpts, tokenResults.token)
			def requestOpts = [headers: headers]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.total = results.count ?: results.data?.shares?.size()
				results.data?.shares.each { row ->
					row.externalId = row.id
					rtn.data << row
				}
			} else {
				rtn.results = results.data
				rtn.msg = results?.badRequest?.message ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static getFileShare(HttpApiClient client, AuthConfig authConfig, String projectId, String shareId, Map opts) {
		def rtn = [success: false, data: [:], total: 0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiUrl = authConfig.sharedFileSystemUrl ?: getOpenstackSharedFileSystemUrl(authConfig)
			def apiVersion = authConfig.sharedFileSystemVersion ?: getOpenstackSharedFileSystemVersion(authConfig)
			def apiPath =  '/' + apiVersion + '/' + projectId + '/shares/' + shareId
			def headerOpts = [:]
			if(authConfig.storageShareApiMicroVersion) {
				headerOpts['X-Openstack-Manila-Api-Version'] = authConfig.storageShareApiMicroVersion
			}
			def headers = buildHeaders(headerOpts, tokenResults.token)
			def requestOpts = [headers: headers]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.data = results.data?.share
				rtn.data.externalId = results.data?.share?.id
			} else {
				rtn.results = results.data
				rtn.msg = results?.badRequest?.message ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static getFileShareExportLocations(HttpApiClient client, AuthConfig authConfig, String projectId, String shareId, Map opts) {
		def rtn = [success: false, data: [:], total: 0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiUrl = authConfig.sharedFileSystemUrl ?: getOpenstackSharedFileSystemUrl(authConfig)
			def apiVersion = authConfig.sharedFileSystemVersion ?: getOpenstackSharedFileSystemVersion(authConfig)
			def apiPath =  '/' + apiVersion + '/' + projectId + '/shares/' + shareId + '/export_locations'
			def headerOpts = [:]
			if(authConfig.storageShareApiMicroVersion) {
				headerOpts['X-Openstack-Manila-Api-Version'] = authConfig.storageShareApiMicroVersion
			}
			def headers = buildHeaders(headerOpts, tokenResults.token)
			def requestOpts = [headers: headers]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true
			if(rtn.success) {
				rtn.data = results.data?.export_locations
			} else {
				rtn.results = results.data
				rtn.msg = results?.badRequest?.message ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static createFileShare(HttpApiClient client, AuthConfig authConfig, String projectId, Map config,  Map opts) {
		def rtn = [success: false, data: [], total: 0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiUrl = authConfig.sharedFileSystemUrl ?: getOpenstackSharedFileSystemUrl(authConfig)
			def apiVersion = authConfig.sharedFileSystemVersion ?: getOpenstackSharedFileSystemVersion(authConfig)
			def apiPath =  '/' + apiVersion + '/' + projectId + '/shares'
			def headerOpts = [:]
			// if(authConfig.storageShareApiMicroVersion) {
			// 	headerOpts['X-Openstack-Manila-Api-Version'] = authConfig.storageShareApiMicroVersion
			// }
			def headers = buildHeaders(headerOpts, tokenResults.token)
			def body = [
					share: [
							share_proto: 'NFS',
							size: config.size,
							name: config.name,
							availability_zone: config.availabilityZone
					]
			]

			def requestOpts = [headers: headers, body: body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'POST')

			rtn.success = results?.success && !results?.errorCode
			if(rtn.success) {
				rtn.data = results.data?.share ?: [:]
				rtn.data.externalId = results.data?.share?.id
			} else {
				rtn.results = results.data
				rtn.errorCode = results.errorCode
				rtn.msg = results?.data?.badRequest?.message ?: results?.data?.itemNotFound?.message ?: 'unknown error'
			}
		} else {
			rtn.msg = 'Not Authorized'
		}
		return rtn
	}

	static updateFileShare(HttpApiClient client, AuthConfig authConfig, String projectId, String shareId, Map config, Map opts) {
		def rtn = [success: false, data: [:]]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiUrl = authConfig.sharedFileSystemUrl ?: getOpenstackSharedFileSystemUrl(authConfig)
			def apiVersion = authConfig.sharedFileSystemVersion ?: getOpenstackSharedFileSystemVersion(authConfig)
			def apiPath =  '/' + apiVersion + '/' + projectId + '/shares/' + shareId
			def headerOpts = [:]
			// if(authConfig.storageShareApiMicroVersion) {
			// 	headerOpts['X-Openstack-Manila-Api-Version'] = authConfig.storageShareApiMicroVersion
			// }
			def headers = buildHeaders(headerOpts, tokenResults.token)
			def body = [
					share: [
							display_name: config.name
					]
			]

			def requestOpts = [headers: headers, body: body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'PUT')
			rtn.success = results?.success && !results?.errorCode
			if(rtn.success) {
				rtn.data = results.data?.share ?: [:]
			} else {
				rtn.results = results.data
				rtn.errorCode = results.erroCode
				rtn.msg = results?.data?.badRequest?.message ?: 'unknown error'
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static deleteFileShare(HttpApiClient client, AuthConfig authConfig, String projectId, String shareId, Map opts) {
		def rtn = [success: false, data: [], total: 0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiUrl = authConfig.sharedFileSystemUrl ?: getOpenstackSharedFileSystemUrl(authConfig)
			def apiVersion = authConfig.sharedFileSystemVersion ?: getOpenstackSharedFileSystemVersion(authConfig)
			def apiPath =  '/' + apiVersion + '/' + projectId + '/shares/' + shareId
			def headerOpts = [:]
			// if(authConfig.storageShareApiMicroVersion) {
			// 	headerOpts['X-Openstack-Manila-Api-Version'] = authConfig.storageShareApiMicroVersion
			// }
			def headers = buildHeaders(headerOpts, tokenResults.token)
			def requestOpts = [headers: headers]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'DELETE')
			rtn.success = results?.success && !results?.errorCode
			if(!rtn.success) {
				rtn.errorCode = results.errorCode
				rtn.msg = results?.data?.badRequest?.message ?: 'Unable to delete file share: ' + results.errorCode
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static expandFileShare(HttpApiClient client, AuthConfig authConfig, String projectId, String shareId, Map config, Map opts) {
		def rtn = [success: false, data: [], total: 0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiUrl = authConfig.sharedFileSystemUrl ?: getOpenstackSharedFileSystemUrl(authConfig)
			def apiVersion = authConfig.sharedFileSystemVersion ?: getOpenstackSharedFileSystemVersion(authConfig)
			def apiPath =  '/' + apiVersion + '/' + projectId + '/shares/' + shareId + '/action'
			def headerOpts = [:]
			// if(authConfig.storageShareApiMicroVersion) {
			// 	headerOpts['X-Openstack-Manila-Api-Version'] = authConfig.storageShareApiMicroVersion
			// }
			def headers = buildHeaders(headerOpts, tokenResults.token)
			def body = [
					'os-extend': [
							'new_size': config.size
					]
			]
			def requestOpts = [headers: headers, body: body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'POST')
			rtn.success = results?.success && !results?.errorCode
			if(!rtn.success) {
				rtn.errorCode = results.errorCode
				rtn.msg = results?.data?.badRequest?.message ?: 'Unable to extend file share: ' + results.errorCode
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static shrinkFileShare(HttpApiClient client, AuthConfig authConfig, String projectId, String shareId, Map config, Map opts) {
		def rtn = [success: false]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiUrl = authConfig.sharedFileSystemUrl ?: getOpenstackSharedFileSystemUrl(authConfig)
			def apiVersion = authConfig.sharedFileSystemVersion ?: getOpenstackSharedFileSystemVersion(authConfig)
			def apiPath =  '/' + apiVersion + '/' + projectId + '/shares/' + shareId + '/action'
			def headerOpts = [:]
			// if(authConfig.storageShareApiMicroVersion) {
			// 	headerOpts['X-Openstack-Manila-Api-Version'] = authConfig.storageShareApiMicroVersion
			// }
			def headers = buildHeaders(headerOpts, tokenResults.token)
			def body = [
					'os-shrink': [
							'new_size': config.size
					]
			]
			def requestOpts = [headers: headers, body: body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'POST')
			rtn.success = results?.success && !results?.errorCode
			if(!rtn.success) {
				rtn.errorCode = results.errorCode
				rtn.msg = results?.data?.badRequest?.message ?: 'Unable to shrink file share: ' + results.errorCode
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}

	static listFileShareAccess(HttpApiClient client, AuthConfig authConfig, String projectId, String shareId, Map opts) {
		def rtn = [success: false, data: [:], total: 0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiUrl = authConfig.sharedFileSystemUrl ?: getOpenstackSharedFileSystemUrl(authConfig)
			def apiVersion = authConfig.sharedFileSystemVersion ?: getOpenstackSharedFileSystemVersion(authConfig)
			def apiPath =  '/' + apiVersion + '/' + projectId + '/shares/' + shareId + '/action'
			def headerOpts = [:]
			def headers = buildHeaders(headerOpts, tokenResults.token)
			def body = [
					'os-access_list': null
			]

			def requestOpts = [headers: headers, body: body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'POST')
			rtn.success = results?.success && !results?.errorCode
			if(rtn.success) {
				rtn.data.accessList = []
				results.data.access_list?.each { accessRule ->
					def row = accessRule
					row.externalId = row.id
					rtn.data.accessList << row
				}
				rtn.data.total = results.data.access_list?.size() ?: 0
			} else {
				rtn.errorCode = results.errorCode
				rtn.msg = results?.data?.badRequest?.message ?: 'Unable to fetch file share access list: ' + results.errorCode
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return ServiceResponse.create(rtn)
	}

	static createFileShareAccess(HttpApiClient client, AuthConfig authConfig, String projectId, String shareId, Map config, Map opts) {
		def rtn = [success: false, data: [], total: 0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiUrl = authConfig.sharedFileSystemUrl ?: getOpenstackSharedFileSystemUrl(authConfig)
			def apiVersion = authConfig.sharedFileSystemVersion ?: getOpenstackSharedFileSystemVersion(authConfig)
			def apiPath =  '/' + apiVersion + '/' + projectId + '/shares/' + shareId + '/action'
			def headerOpts = [:]
			if(authConfig.storageShareApiMicroVersion) {
				headerOpts['X-Openstack-Manila-Api-Version'] = authConfig.storageShareApiMicroVersion
			}
			def headers = buildHeaders(headerOpts, tokenResults.token)

			def body = [
					'allow_access': [
							'access_to': buildAccessRule(config),
							"access_type": config.config.accessType,
							"access_level": SHARE_ACCESS_LEVELS[config.config.accessLevel].apiValue ?: "ro"
					]
			]

			def requestOpts = [headers: headers, body: body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'POST')
			rtn.success = results?.success && !results?.errorCode
			if(rtn.success) {
				rtn.data = results.data?.access
				rtn.data.externalId = rtn.data.id
			} else {
				rtn.errorCode = results.errorCode
				rtn.msg = results?.data?.badRequest?.message ?: 'Unable to add file share access: ' + results.errorCode
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static buildAccessRule(config) {
		config.config?.accessConstraint ?: "0.0.0.0/0"
	}

	static parseAccessRule(String rule) {
		return [
				authorizedSegment: rule
		]
	}

	static deleteFileShareAccess(HttpApiClient client, AuthConfig authConfig, String projectId, String shareId, String accessId, Map opts) {
		def rtn = [success: false, data: [], total: 0]
		authConfig.projectId = projectId
		def tokenResults = getApiToken(client, authConfig, false)
		if(tokenResults.success && tokenResults.token && projectId) {
			def apiUrl = authConfig.sharedFileSystemUrl ?: getOpenstackSharedFileSystemUrl(authConfig)
			def apiVersion = authConfig.sharedFileSystemVersion ?: getOpenstackSharedFileSystemVersion(authConfig)
			def apiPath =  '/' + apiVersion + '/' + projectId + '/shares/' + shareId + '/action'
			def headers = buildHeaders([:], tokenResults.token)
			def body = [
					'os-deny_access': [
							'access_id': accessId
					]
			]

			def requestOpts = [headers: headers, body: body]
			def results = callApi(client, authConfig, apiPath, null, [osUrl: apiUrl] + requestOpts, 'POST')
			rtn.success = results?.success && !results?.errorCode
			if(!rtn.success) {
				rtn.errorCode = results.errorCode
				rtn.msg = results?.data?.badRequest?.message ?: 'Unable to delete file share access: ' + results.errorCode
			}
		} else {
			rtn.error = 'Not Authorized'
		}
		return rtn
	}

	static getTranslatedVolumeTypeName(String volumeType) {
		return volumeType
	}

	static extractVolumeTypeAvailabilityZone(Map volumeType) {
		return []
	}

	static buildHeaders(Map headers, String token=null) {
		headers = headers ?: [:]

		if(!headers['Content-Type']) {
			headers['Content-Type'] = 'application/json'
		}
		if(token) {
			headers['X-Auth-Token'] = token
		}

		return headers
	}

	static isCloudProjectScoped(Cloud cloud) {
		return (cloud?.getConfigProperty("projectName") != null && cloud?.getConfigProperty("projectName") != "")
	}

	static getResponseError(Map results) {
		def rtn
		rtn = getNeutronError(results)
		if(!rtn || rtn == "uknown error") {
			rtn = getNovaError(results)
		}

		return rtn
	}

	static getNeutronError(Map results) {
		def rtn
		try {
			if(results) {
				if(results.data) {
					if(results.data instanceof Number) {
						rtn = results.content ?: results.data.toString()
					} else if(results.data instanceof CharSequence) {
						rtn = results.data
					} else if(results.data['NeutronError']?.message) {
						rtn = results.data['NeutronError'].message
					} else if(results.data.badRequest?.message) {
						rtn = results.data.badRequest.message
					} else if(results.data.message) {
						try {
							def errorMsg = new JsonSlurper().parseText(results.data.message)
							rtn = errorMsg['NeutronError']?.message
						} catch (e) {
							rtn = results.data.message
						}
					} else if(results.data.faultstring) {
						rtn = results.data.faultstring
					} else if(results.data.error) {
						if(results.data.error instanceof Map && results.data.error.message) {
							rtn = results.data.error.message.toString()
						} else {
							rtn = results.error.toString()
						}
					}
				} else if(results.msg) {
					rtn = results.msg
				} else if(results.content) {
					rtn = results.content
				} else if(results.statusCode == 404) {
					rtn = 'not found'
				} else if(results.error) {
					rtn = results.error
				}
			}
			rtn = rtn ?: 'unknown error'
		} catch(Exception e) {
			log.error("getNeutronError error: {}", e, e)
		}
		return rtn
	}

	static getNovaError(Map results) {
		def rtn
		try {
			if(results?.data instanceof Map) {
				if(results.data.server?.fault?.message) {
					rtn = results.data.server.fault.message
				}
				if(results.data.badRequest?.message) {
					rtn = results.data.badRequest?.message
				}
				if(results.data.forbidden?.message) {
					rtn = results.data.forbidden.message
				}
				if(results.data.error?.message) {
					rtn = results.data.error.message
				}
			}

			if(!rtn) {
				if(results?.error) {
					if(results.error instanceof Map && results.error.message) {
						rtn = results.error.message.toString()
					} else {
						rtn = results.error.toString()
					}
				} else if(results?.msg) {
					rtn = results.msg
				} else if(results?.statusCode == 404) {
					rtn = 'not found'
				}
			}

			rtn = rtn ?: 'unknown error'
		} catch(Exception e) {
			log.error("getNovaError error: {}", e, e)
		}

		rtn
	}

	static formatDateNoMillis(Date date) {
		def rtn
		try {
			if(date) {
				rtn = date.format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('GMT'))
			}
		} catch(e) {
			log.warn("error formatting date: ${date}")
		}
		rtn
	}
}
