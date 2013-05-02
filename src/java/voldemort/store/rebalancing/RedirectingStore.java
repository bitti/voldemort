/*
 * Copyright 2008-2010 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.store.rebalancing;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;

import voldemort.VoldemortException;
import voldemort.annotations.jmx.JmxGetter;
import voldemort.annotations.jmx.JmxSetter;
import voldemort.client.protocol.RequestFormatType;
import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.cluster.failuredetector.FailureDetector;
import voldemort.routing.StoreRoutingPlan;
import voldemort.server.RequestRoutingType;
import voldemort.server.StoreRepository;
import voldemort.store.DelegatingStore;
import voldemort.store.Store;
import voldemort.store.StoreDefinition;
import voldemort.store.StoreUtils;
import voldemort.store.UnreachableStoreException;
import voldemort.store.metadata.MetadataStore;
import voldemort.store.metadata.MetadataStore.VoldemortState;
import voldemort.store.readonly.ReadOnlyStorageConfiguration;
import voldemort.store.socket.SocketStoreFactory;
import voldemort.utils.ByteArray;
import voldemort.utils.ByteUtils;
import voldemort.utils.Time;
import voldemort.versioning.ObsoleteVersionException;
import voldemort.versioning.Version;
import voldemort.versioning.Versioned;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;

/**
 * The RedirectingStore extends {@link DelegatingStore}
 * <p>
 * If current server_state is {@link VoldemortState#REBALANCING_MASTER_SERVER} <br>
 * then before serving any client request do a remote get() call, put it locally
 * ignoring any {@link ObsoleteVersionException} and then serve the client
 * requests. This piece of code is run on the stealer nodes.
 */
public class RedirectingStore extends DelegatingStore<ByteArray, byte[], byte[]> {

    private final static Logger logger = Logger.getLogger(RedirectingStore.class);
    private final MetadataStore metadata;
    private final StoreRepository storeRepository;
    private final SocketStoreFactory storeFactory;
    private FailureDetector failureDetector;
    private AtomicBoolean isRedirectingStoreEnabled;
    private boolean isProxyPutEnabled;
    private final ExecutorService proxyPutWorkerPool;

    // statistics on proxy put tasks
    private ProxyPutStats proxyPutStats;

    public RedirectingStore(Store<ByteArray, byte[], byte[]> innerStore,
                            MetadataStore metadata,
                            StoreRepository storeRepository,
                            FailureDetector detector,
                            SocketStoreFactory storeFactory,
                            boolean isProxyPutEnabled,
                            ExecutorService proxyPutWorkerPool,
                            ProxyPutStats proxyPutStats) {
        super(innerStore);
        this.metadata = metadata;
        this.storeRepository = storeRepository;
        this.storeFactory = storeFactory;
        this.failureDetector = detector;
        this.isRedirectingStoreEnabled = new AtomicBoolean(true);
        this.isProxyPutEnabled = isProxyPutEnabled;
        this.proxyPutWorkerPool = proxyPutWorkerPool;
        this.proxyPutStats = proxyPutStats;
    }

    @JmxSetter(name = "setRedirectingStoreEnabled", description = "Enable the redirecting store for this store")
    public void setIsRedirectingStoreEnabled(boolean isRedirectingStoreEnabled) {
        logger.info("Setting redirecting store flag for " + getName() + " to "
                    + isRedirectingStoreEnabled);
        this.isRedirectingStoreEnabled.set(isRedirectingStoreEnabled);
    }

    @JmxGetter(name = "isRedirectingStoreEnabled", description = "Get the redirecting store state for this store")
    public boolean getIsRedirectingStoreEnabled() {
        return this.isRedirectingStoreEnabled.get();
    }

    @Override
    public List<Versioned<byte[]>> get(ByteArray key, byte[] transforms) throws VoldemortException {
        Integer redirectNode = getProxyNode(key.get());
        /**
         * If I am rebalancing for this key, try to do remote get(), put it
         * locally first to get the correct version ignoring any
         * {@link ObsoleteVersionException}
         */
        // FIXME AR There is some unneccessary performance hit here. keys
        // already moved over will always result in OVE and time spent waiting
        // on this (esp for cross zone moves) would be a total waste. Need to
        // rework to this logic to incur this when necessary
        if(redirectNode != null) {
            if(logger.isTraceEnabled()) {
                logger.trace("Proxying GET on stealer:" + metadata.getNodeId() + " for  key "
                             + ByteUtils.toHexString(key.get()) + " to node:" + redirectNode);
            }
            proxyGetAndLocalPut(key, redirectNode, transforms);
        }
        return getInnerStore().get(key, transforms);
    }

    @Override
    public List<Version> getVersions(ByteArray key) {
        Integer redirectNode = getProxyNode(key.get());
        /**
         * If I am rebalancing for this key, try to do remote get(), put it
         * locally first to get the correct version ignoring any
         * {@link ObsoleteVersionException}.
         */
        // TODO same fixes apply here as in get(..) above
        if(redirectNode != null) {
            if(logger.isTraceEnabled()) {
                logger.trace("Proxying GETVERSIONS on stealer:" + metadata.getNodeId()
                             + " for  key " + ByteUtils.toHexString(key.get()) + " to node:"
                             + redirectNode);
            }
            proxyGetAndLocalPut(key, redirectNode, null);
        }
        return getInnerStore().getVersions(key);
    }

    @Override
    public Map<ByteArray, List<Versioned<byte[]>>> getAll(Iterable<ByteArray> keys,
                                                          Map<ByteArray, byte[]> transforms)
            throws VoldemortException {
        Map<ByteArray, Integer> keyToProxyNodeMap = Maps.newHashMapWithExpectedSize(Iterables.size(keys));
        for(ByteArray key: keys) {
            Integer redirectNode = getProxyNode(key.get());
            if(redirectNode != null) {
                keyToProxyNodeMap.put(key, redirectNode);
            }
        }
        // FIXME AR Same optimizations. Go to the proxy only for keys that this
        // node does not have
        if(!keyToProxyNodeMap.isEmpty()) {
            if(logger.isTraceEnabled()) {
                String keyStr = "";
                for(ByteArray key: keys)
                    keyStr += key + " ";
                logger.trace("Proxying GETALL on stealer:" + metadata.getNodeId() + " for  keys "
                             + keyStr);
            }
            proxyGetAllAndLocalPut(keyToProxyNodeMap, transforms);
        }

        return getInnerStore().getAll(keys, transforms);
    }

    @Override
    public void put(ByteArray key, Versioned<byte[]> value, byte[] transforms)
            throws VoldemortException {

        Cluster currentCluster = metadata.getCluster();
        // FIXME AR O(n) linear lookup of storedef
        StoreDefinition storeDef = metadata.getStoreDef(getName());

        // defensively, error out if this is a read-only store and someone is
        // doing puts against it. We don't to do extra work and fill the log
        // with errors in that case.
        if(storeDef.getType().compareTo(ReadOnlyStorageConfiguration.TYPE_NAME) == 0) {
            throw new UnsupportedOperationException("put() not supported on read-only store");
        }
        StoreRoutingPlan currentRoutingPlan = new StoreRoutingPlan(currentCluster, storeDef);
        Integer redirectNode = getProxyNode(currentRoutingPlan, storeDef, key.get());

        /**
         * If I am rebalancing for this key, try to do remote get() , put it
         * locally first to get the correct version ignoring any
         * {@link ObsoleteVersionException}
         */
        // FIXME AR same optimizations apply here.. If the key already exists
        // skip this
        if(redirectNode != null) {
            if(logger.isTraceEnabled()) {
                logger.trace("Proxying GET (before PUT) on stealer:" + metadata.getNodeId()
                             + " for  key " + ByteUtils.toHexString(key.get()) + " to node:"
                             + redirectNode);
            }
            proxyGetAndLocalPut(key, redirectNode, transforms);
        }

        // put the data locally, if this step fails, there will be no proxy puts
        getInnerStore().put(key, value, transforms);

        // submit an async task to issue proxy puts to the redirectNode
        // NOTE : if the redirect node is also a current replica for the key (we
        // could have a situation where the online replicated write could lose
        // out to the proxy put and hence fail the client operation with an
        // OVE). So do not send proxy puts in those cases.
        if(isProxyPutEnabled && redirectNode != null
           && !currentRoutingPlan.getReplicationNodeList(key.get()).contains(redirectNode)) {
            AsyncProxyPutTask asyncProxyPutTask = new AsyncProxyPutTask(this,
                                                                        key,
                                                                        value,
                                                                        transforms,
                                                                        redirectNode);
            proxyPutStats.reportProxyPutSubmission();
            proxyPutWorkerPool.submit(asyncProxyPutTask);
            asyncProxyPutTask.run();
        }
    }

    /**
     * TODO : Handle delete correctly.
     * <p>
     * The options are:
     * <ol>
     * <li>
     * Delete locally and on remote node as well. The issue is cursor is open in
     * READ_UNCOMMITED mode while rebalancing and can push the value back.</li>
     * <li>
     * Keep the operation in separate slop store and apply all deletes after
     * rebalancing.</li>
     * <li>
     * Do not worry about deletes for now, Voldemort in general has an issue
     * that if node goes down during a delete, we will still keep the old
     * version.</li>
     * </ol>
     */
    @Override
    public boolean delete(ByteArray key, Version version) throws VoldemortException {
        StoreUtils.assertValidKey(key);
        return getInnerStore().delete(key, version);
    }

    /**
     * Checks if the server has to do any proxying of gets/puts to another
     * server, as a part of an ongoing rebalance operation.
     * 
     * Basic idea : Any given node which is a stealer of a partition, as the ith
     * replica of a given zone, will proxy to the old ith replica of the
     * partition in the given zone, as per the source cluster metadata.
     * Exception : if this amounts to proxying to itself.
     * 
     * Note on Zone Expansion : For zone expansion, there will be no proxying
     * within the new zone. This is a practical assumption since if we fail, we
     * fallback to a cluster topology without the new zone. As a result, reads
     * from the new zone are not guaranteed to return some values during the
     * course of zone expansion. This is a also reasonable since any
     * organization undertaking such effort would need to have the data in place
     * in the new zone, before the client apps are moved over.
     * 
     * 
     * @param currentRoutingPlan routing plan object based on cluster's current
     *        topology
     * @param storeDef definition of the store being redirected
     * @param key to decide where to proxy to
     * @return Null if no proxying is required else node id of the server to
     *         proxy to
     */
    private Integer getProxyNode(StoreRoutingPlan currentRoutingPlan,
                                 StoreDefinition storeDef,
                                 byte[] key) {
        // get out if not rebalancing or if redirecting is disabled.
        if(!VoldemortState.REBALANCING_MASTER_SERVER.equals(metadata.getServerState())
           || !isRedirectingStoreEnabled.get()) {
            return null;
        }

        // TODO a better design would be to get these state changes from
        // metadata listener callbacks, so we need not allocate these objects
        // all the time
        Cluster sourceCluster = metadata.getRebalancingSourceCluster();
        if(sourceCluster == null) {
            /*
             * This is more for defensive coding purposes. The update of the
             * source cluster key happens before the server is put in
             * REBALANCING mode and is reset to null after the server goes back
             * to NORMAL mode.
             */
            if(logger.isTraceEnabled()) {
                logger.trace("Old Cluster is null.. bail");
            }
            return null;
        }

        Integer nodeId = metadata.getNodeId();
        Integer zoneId = currentRoutingPlan.getCluster().getNodeById(nodeId).getZoneId();

        StoreRoutingPlan oldRoutingPlan = new StoreRoutingPlan(sourceCluster, storeDef);
        // Check the current node's relationship to the key.
        int zoneReplicaType = currentRoutingPlan.getZoneReplicaType(zoneId, nodeId, key);
        // Determine which node held the key with the same relationship in the
        // old cluster. That is your man!
        Integer redirectNodeId;
        try {
            redirectNodeId = oldRoutingPlan.getZoneReplicaNode(zoneId, zoneReplicaType, key);
        } catch(VoldemortException ve) {
            // If the zone does not exist, as in the case of Zone Expansion,
            // there will be no proxy bridges built
            return null;
        }
        // Unless he is the same as this node (where this is meaningless effort)
        if(redirectNodeId == nodeId) {
            return null;
        }
        return redirectNodeId;
    }

    /**
     * Wrapper around
     * {@link RedirectingStore#getProxyNode(StoreRoutingPlan, StoreDefinition, byte[])}
     * 
     * @param key
     * @return
     */
    private Integer getProxyNode(byte[] key) {
        Cluster currentCluster = metadata.getCluster();
        // FIXME AR O(n) linear lookup of storedef
        StoreDefinition storeDef = metadata.getStoreDef(getName());
        StoreRoutingPlan currentRoutingPlan = new StoreRoutingPlan(currentCluster, storeDef);
        return getProxyNode(currentRoutingPlan, storeDef, key);
    }

    /**
     * Performs a back-door proxy get to
     * {@link voldemort.client.rebalance.RebalancePartitionsInfo#getDonorId()
     * getDonorId}
     * 
     * @param key Key
     * @param donorNodeId donor node id
     * @throws ProxyUnreachableException if donor node can't be reached
     */
    private List<Versioned<byte[]>> proxyGet(ByteArray key, int donorNodeId, byte[] transform) {
        Node donorNode = metadata.getCluster().getNodeById(donorNodeId);
        checkNodeAvailable(donorNode);
        long startNs = System.nanoTime();
        try {
            Store<ByteArray, byte[], byte[]> redirectingStore = getRedirectingSocketStore(getName(),
                                                                                          donorNodeId);
            List<Versioned<byte[]>> values = redirectingStore.get(key, transform);
            recordSuccess(donorNode, startNs);
            return values;
        } catch(UnreachableStoreException e) {
            recordException(donorNode, startNs, e);
            throw new ProxyUnreachableException("Failed to reach proxy node " + donorNode, e);
        }
    }

    protected void checkNodeAvailable(Node donorNode) {
        if(!failureDetector.isAvailable(donorNode))
            throw new ProxyUnreachableException("Failed to reach proxy node " + donorNode
                                                + " is marked down by failure detector.");
    }

    /**
     * Performs a back-door proxy get to
     * {@link voldemort.client.rebalance.RebalancePartitionsInfo#getDonorId()
     * getDonorId}
     * 
     * @param keyToProxyNodeMap Map of keys to corresponding proxy nodes housing
     *        the keys in source cluster
     * @param transforms Map of keys to their corresponding transforms
     * @throws ProxyUnreachableException if donor node can't be reached
     */
    private Map<ByteArray, List<Versioned<byte[]>>> proxyGetAll(Map<ByteArray, Integer> keyToProxyNodeMap,
                                                                Map<ByteArray, byte[]> transforms)
            throws VoldemortException {
        Multimap<Integer, ByteArray> donorNodeToKeys = HashMultimap.create();
        int numKeys = 0;

        // Transform the map of key to plan to a map of donor node id to keys
        for(Map.Entry<ByteArray, Integer> entry: keyToProxyNodeMap.entrySet()) {
            numKeys++;
            donorNodeToKeys.put(entry.getValue(), entry.getKey());
        }

        Map<ByteArray, List<Versioned<byte[]>>> gatherMap = Maps.newHashMapWithExpectedSize(numKeys);

        for(int donorNodeId: donorNodeToKeys.keySet()) {
            Node donorNode = metadata.getCluster().getNodeById(donorNodeId);
            checkNodeAvailable(donorNode);
            long startNs = System.nanoTime();

            try {
                Map<ByteArray, List<Versioned<byte[]>>> resultsForNode = getRedirectingSocketStore(getName(),
                                                                                                   donorNodeId).getAll(donorNodeToKeys.get(donorNodeId),
                                                                                                                       transforms);
                recordSuccess(donorNode, startNs);

                for(Map.Entry<ByteArray, List<Versioned<byte[]>>> entry: resultsForNode.entrySet()) {
                    gatherMap.put(entry.getKey(), entry.getValue());
                }
            } catch(UnreachableStoreException e) {
                recordException(donorNode, startNs, e);
                throw new ProxyUnreachableException("Failed to reach proxy node " + donorNode, e);
            }
        }

        return gatherMap;
    }

    /**
     * In <code>REBALANCING_MASTER_SERVER</code> state put should be committed
     * on stealer node. To follow Voldemort version guarantees, stealer node
     * should query donor node and put that value (proxyValue) before committing
     * the value from client.
     * <p>
     * Stealer node should ignore {@link ObsoleteVersionException} while
     * commiting proxyValue to local storage.
     * 
     * @param key Key
     * @param donorId donorId
     * @return Returns the proxy value
     * @throws VoldemortException if {@link #proxyGet(ByteArray, int)} fails
     */
    private List<Versioned<byte[]>> proxyGetAndLocalPut(ByteArray key,
                                                        int donorId,
                                                        byte[] transforms)
            throws VoldemortException {
        List<Versioned<byte[]>> proxyValues = proxyGet(key, donorId, transforms);
        for(Versioned<byte[]> proxyValue: proxyValues) {
            try {
                getInnerStore().put(key, proxyValue, null);
            } catch(ObsoleteVersionException e) {
                // TODO this is in TRACE because OVE is expected here, for keys
                // that are already moved over or proxy got. This will become
                // ERROR later post redesign
                if(logger.isTraceEnabled())
                    logger.trace("OVE in proxy get local put for key "
                                         + ByteUtils.toHexString(key.get()) + " Stealer:"
                                         + metadata.getNodeId() + " Donor:" + donorId,
                                 e);
            }
        }
        return proxyValues;
    }

    /**
     * Similar to {@link #proxyGetAndLocalPut(ByteArray, int)} but meant for
     * {@link #getAll(Iterable)}
     * 
     * @param keyToProxyNodeMap Map of keys which are being routed to their
     *        corresponding proxy nodes
     * @param transforms Map of key to their corresponding transforms
     * @return Returns a map of key to its corresponding list of values
     * @throws VoldemortException if {@link #proxyGetAll(List, List)} fails
     */
    private Map<ByteArray, List<Versioned<byte[]>>> proxyGetAllAndLocalPut(Map<ByteArray, Integer> keyToProxyNodeMap,
                                                                           Map<ByteArray, byte[]> transforms)
            throws VoldemortException {
        Map<ByteArray, List<Versioned<byte[]>>> proxyKeyValues = proxyGetAll(keyToProxyNodeMap,
                                                                             transforms);
        for(Map.Entry<ByteArray, List<Versioned<byte[]>>> keyValuePair: proxyKeyValues.entrySet()) {
            for(Versioned<byte[]> proxyValue: keyValuePair.getValue()) {
                try {
                    getInnerStore().put(keyValuePair.getKey(), proxyValue, null);
                } catch(ObsoleteVersionException e) {
                    // ignore these
                }
            }
        }
        return proxyKeyValues;
    }

    /**
     * Get the {@link voldemort.store.socket.SocketStore} to redirect to for the
     * donor, creating one if needed.
     * 
     * @param storeName Name of the store
     * @param donorNodeId Donor node id
     * @return <code>SocketStore</code> object for <code>storeName</code> and
     *         <code>donorNodeId</code>
     */
    protected Store<ByteArray, byte[], byte[]> getRedirectingSocketStore(String storeName,
                                                                         int donorNodeId) {
        if(!storeRepository.hasRedirectingSocketStore(storeName, donorNodeId)) {
            synchronized(storeRepository) {
                if(!storeRepository.hasRedirectingSocketStore(storeName, donorNodeId)) {
                    Node donorNode = getNodeIfPresent(donorNodeId);
                    logger.info("Creating new redirecting store for donor node "
                                + donorNode.getId() + " and store " + storeName);
                    storeRepository.addRedirectingSocketStore(donorNode.getId(),
                                                              storeFactory.create(storeName,
                                                                                  donorNode.getHost(),
                                                                                  donorNode.getSocketPort(),
                                                                                  RequestFormatType.PROTOCOL_BUFFERS,
                                                                                  RequestRoutingType.IGNORE_CHECKS));
                }
            }
        }

        return storeRepository.getRedirectingSocketStore(storeName, donorNodeId);
    }

    private Node getNodeIfPresent(int donorId) {
        try {
            return metadata.getCluster().getNodeById(donorId);
        } catch(Exception e) {
            throw new VoldemortException("Failed to get donorNode " + donorId
                                         + " from current cluster " + metadata.getCluster()
                                         + " at node " + metadata.getNodeId(), e);
        }
    }

    protected void recordException(Node node, long startNs, UnreachableStoreException e) {
        failureDetector.recordException(node, (System.nanoTime() - startNs) / Time.NS_PER_MS, e);
    }

    protected void recordSuccess(Node node, long startNs) {
        proxyPutStats.reportProxyPutCompletion();
        failureDetector.recordSuccess(node, (System.nanoTime() - startNs) / Time.NS_PER_MS);
    }

    protected MetadataStore getMetadataStore() {
        return metadata;
    }

    protected void reporteProxyPutFailure() {
        proxyPutStats.reportProxyPutFailure();
    }

    public ProxyPutStats getProxyPutStats() {
        return this.proxyPutStats;
    }
}
