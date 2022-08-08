package com.morpheusdata.openstack.plugin

import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResponse
import groovy.util.logging.Slf4j

@Slf4j
class OpenstackProvisionProvider extends AbstractProvisionProvider {

	OpenstackPlugin plugin
	MorpheusContext morpheusContext

	OpenstackProvisionProvider(OpenstackPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		[]
	}

	@Override
	Collection<OptionType> getNodeOptionTypes() {
		return []
	}

	@Override
	Collection<ServicePlan> getServicePlans() {
		[]
	}

	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		[]
	}

	@Override
	Boolean hasDatastores() {
		false
	}

	@Override
	Boolean hasNetworks() {
		true
	}

	@Override
	Boolean hasPlanTagMatch() {
		true
	}

	@Override
	Integer getMaxNetworks() {
		return null
	}

	@Override
	ServiceResponse validateWorkload(Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse validateInstance(Instance instance, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse validateDockerHost(ComputeServer server, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	public ServiceResponse prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "prepareWorkload: ${workload} ${workloadRequest} ${opts}"
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse<WorkloadResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "runWorkload ${workload.configs} ${opts}"
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse stopWorkload(Workload workload) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse startWorkload(Workload workload) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse restartWorkload(Workload workload) {
		log.debug 'restartWorkload'
		ServiceResponse stopResult = stopWorkload(workload)
		if (stopResult.success) {
			return startWorkload(workload)
		}
		stopResult
	}

	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse<WorkloadResponse> getServerDetails(ComputeServer server) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	HostType getHostType() {
		return null
	}

	@Override
	Collection<VirtualImage> getVirtualImages() {
		return new ArrayList<VirtualImage>()
	}

	@Override
	Collection<ComputeTypeLayout> getComputeTypeLayouts() {
		return new ArrayList<ComputeTypeLayout>()
	}

	@Override
	Boolean canAddVolumes() {
		true
	}

	@Override
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return plugin
	}

	@Override
	Boolean canCustomizeRootVolume() {
		true
	}

	@Override
	Boolean canResizeRootVolume() {
		true
	}

	@Override
	Boolean canCustomizeDataVolumes() {
		true
	}

	@Override
	Boolean hasStorageControllers() {
		false
	}

	@Override
	Boolean supportsAutoDatastore() {
		false
	}

	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		getStorageVolumeTypes()
	}

	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		getStorageVolumeTypes()
	}

	private getStorageVolumeTypes() {
		def volumeTypes = []
		volumeTypes
	}

	@Override
	String getCode() {
		return 'openstack-provision-provider-plugin'
	}

	@Override
	String getName() {
		return 'Openstack'
	}
}
