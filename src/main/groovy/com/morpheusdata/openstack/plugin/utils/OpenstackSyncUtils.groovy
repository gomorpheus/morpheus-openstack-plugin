package com.morpheusdata.openstack.plugin.utils

import com.morpheusdata.model.Cloud

class OpenstackSyncUtils {

	static String getObjectCategory(String objType, Long cloudId=null, String projectId=null) {
		def rtn = "openstack.${objType}"
		// def rtn = "${getProvisionTypeCode()}.${objType}" // probably better, but requires a migration of existing data

		if(cloudId) {
			rtn += ".${cloudId}"
		}
		if(projectId) {
			rtn += ".${projectId}"
		}

		return rtn
	}

	static String getCloudProjectId(Cloud cloud) {
		cloud.getConfigProperty("projectId")
	}

	static String getCloudDomainId(cloud) {
		cloud.getConfigProperty("apiDomainId")
	}
	static String getCloudApiUserId(cloud) {
		cloud.getConfigProperty("apiUserId")
	}
}
