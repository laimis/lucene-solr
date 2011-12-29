package org.apache.solr.cloud;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.solr.cloud.NodeStateWatcher.NodeStateChangeListener;
import org.apache.solr.cloud.ShardLeaderWatcher.ShardLeaderListener;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.*;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cluster leader. Responsible node assignments, cluster state file?
 */
public class Overseer implements NodeStateChangeListener, ShardLeaderListener {
  
  public static final String ASSIGNMENTS_NODE = "/node_assignments";
  public static final String STATES_NODE = "/node_states";
  private static Logger log = LoggerFactory.getLogger(Overseer.class);
  
  private final SolrZkClient zkClient;
  private final ZkStateReader reader;
  
  // node stateWatches
  private HashMap<String,NodeStateWatcher> nodeStateWatches = new HashMap<String,NodeStateWatcher>();

  // shard leader watchers  (collection->slice->watcher)
  private HashMap<String, HashMap<String,ShardLeaderWatcher>> shardLeaderWatches = new HashMap<String,HashMap<String,ShardLeaderWatcher>>();

  public Overseer(final SolrZkClient zkClient, final ZkStateReader reader) throws KeeperException, InterruptedException {
    log.info("Constructing new Overseer");
    this.zkClient = zkClient;
    this.reader = reader;
    createWatches();
  }
  
  public synchronized void createWatches()
      throws KeeperException, InterruptedException {
    addCollectionsWatch();
    addLiveNodesWatch();
  }

  /* 
   * Watch for collections so we can add watches for its shard leaders.
   */
  private void addCollectionsWatch() throws KeeperException,
      InterruptedException {
    
    if(!zkClient.exists(ZkStateReader.COLLECTIONS_ZKNODE)) {
      try {
        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE);
      } catch (NodeExistsException ne) {
        //ok
      }
    }
    
    List<String> collections = zkClient.getChildren(ZkStateReader.COLLECTIONS_ZKNODE, new Watcher(){
      @Override
      public void process(WatchedEvent event) {
        try {
          List<String> collections = zkClient.getChildren(ZkStateReader.COLLECTIONS_ZKNODE, this);
          collectionsChanged(collections);
        } catch (KeeperException e) {
            if (e.code() == Code.CONNECTIONLOSS || e.code() == Code.SESSIONEXPIRED) {
            log.warn("ZooKeeper watch triggered, but Solr cannot talk to ZK");
            return;
          }
        } catch (InterruptedException e) {
          // Restore the interrupted status
          Thread.currentThread().interrupt();
          log.error("", e);
          throw new ZooKeeperException(
              SolrException.ErrorCode.SERVER_ERROR, "", e);
        }
      }
    });
    
    collectionsChanged(collections);
  }
  
  private void collectionsChanged(Collection<String> collections) throws KeeperException, InterruptedException {
    synchronized (shardLeaderWatches) {
      for(String collection: collections) {
        if(!shardLeaderWatches.containsKey(collection)) {
          shardLeaderWatches.put(collection, new HashMap<String,ShardLeaderWatcher>());
          addShardLeadersWatch(collection);
        }
      }
      //XXX not handling delete collections..
    }
  }

  /**
   * Add a watch for node containing shard leaders for a collection
   * @param collection
   * @throws KeeperException
   * @throws InterruptedException
   */
  private void addShardLeadersWatch(final String collection) throws KeeperException,
      InterruptedException {
    
    if(!zkClient.exists(ZkStateReader.getShardLeadersPath(collection, null))) {
      try {
        zkClient.makePath(ZkStateReader.getShardLeadersPath(collection, null));
      } catch (NodeExistsException ne) {
        //ok if someone created it
      }
    }
    
    final List<String> leaderNodes = zkClient.getChildren(
        ZkStateReader.getShardLeadersPath(collection, null), new Watcher() {
          
          @Override
          public void process(WatchedEvent event) {
            try {
              List<String> leaderNodes = zkClient.getChildren(
                  ZkStateReader.getShardLeadersPath(collection, null), this);
              
              processLeaderNodesChanged(collection, leaderNodes);
            } catch (KeeperException e) {
              if (e.code() == KeeperException.Code.SESSIONEXPIRED
                  || e.code() == KeeperException.Code.CONNECTIONLOSS) {
                log.warn("ZooKeeper watch triggered, but Solr cannot talk to ZK");
                return;
              }
              log.error("", e);
              throw new ZooKeeperException(
                  SolrException.ErrorCode.SERVER_ERROR, "", e);
            } catch (InterruptedException e) {
              // Restore the interrupted status
              Thread.currentThread().interrupt();
              log.error("", e);
              throw new ZooKeeperException(
                  SolrException.ErrorCode.SERVER_ERROR, "", e);
            }
          }
        });
    
    processLeaderNodesChanged(collection, leaderNodes);
  }

  /**
   * Process change in shard leaders. Make sure we have watches for each leader.
   */
  private void processLeaderNodesChanged(final String collection, final Collection<String> shardIds) {
    if(log.isInfoEnabled()) {
      log.info("Leader nodes changed for collection: " + collection + " nodes now:" + shardIds);
    }
    
    Map<String, ShardLeaderWatcher> watches = shardLeaderWatches.get(collection);
    Set<String> currentWatches = new HashSet<String>();
    currentWatches.addAll(watches.keySet());
    
    Set<String> newLeaders = complement(shardIds, currentWatches);

    Set<String> lostLeaders = complement(currentWatches, shardIds);
    //remove watches for lost shards
    for (String shardId : lostLeaders) {
      ShardLeaderWatcher watcher = watches.remove(shardId);
      if (watcher != null) {
        watcher.close();
        announceLeader(collection, shardId, new ZkNodeProps());  //removes loeader for shard
      }
    }
    
    //add watches for the new shards
    for(String shardId: newLeaders) {
      try {
        ShardLeaderWatcher watcher = new ShardLeaderWatcher(shardId, collection, zkClient, this);
        watches.put(shardId, watcher);
      } catch (KeeperException e) {
        log.error("Failed to create watcher for shard leader col:" + collection + " shard:" + shardId + ", exception: " + e.getClass());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("Failed to create watcher for shard leader col:" + collection + " shard:" + shardId + ", exception: " + e.getClass());
      }
    }
  }

  private void addLiveNodesWatch() throws KeeperException,
      InterruptedException {
    List<String> liveNodes = zkClient.getChildren(
        ZkStateReader.LIVE_NODES_ZKNODE, new Watcher() {
          
          @Override
          public void process(WatchedEvent event) {
            try {
                List<String> liveNodes = zkClient.getChildren(
                    ZkStateReader.LIVE_NODES_ZKNODE, this);
                Set<String> liveNodesSet = new HashSet<String>();
                liveNodesSet.addAll(liveNodes);
                processLiveNodesChanged(nodeStateWatches.keySet(), liveNodes);
            } catch (KeeperException e) {
              if (e.code() == KeeperException.Code.SESSIONEXPIRED
                  || e.code() == KeeperException.Code.CONNECTIONLOSS) {
                log.warn("ZooKeeper watch triggered, but Solr cannot talk to ZK");
                return;
              }
              log.error("", e);
              throw new ZooKeeperException(
                  SolrException.ErrorCode.SERVER_ERROR, "", e);
            } catch (InterruptedException e) {
              // Restore the interrupted status
              Thread.currentThread().interrupt();
              log.error("", e);
              throw new ZooKeeperException(
                  SolrException.ErrorCode.SERVER_ERROR, "", e);
            }
          }
        });
    
    processLiveNodesChanged(Collections.<String>emptySet(), liveNodes);
  }
  
  private void processLiveNodesChanged(Collection<String> oldLiveNodes,
      Collection<String> liveNodes) throws InterruptedException, KeeperException {
    
    Set<String> upNodes = complement(liveNodes, oldLiveNodes);
    if (upNodes.size() > 0) {
      addNodeStateWatches(upNodes);
    }
    
    Set<String> downNodes = complement(oldLiveNodes, liveNodes);
    for(String node: downNodes) {
      NodeStateWatcher watcher = nodeStateWatches.remove(node);
      if (watcher != null) {
        watcher.close();
      }
    }
  }
  
  private void addNodeStateWatches(Set<String> nodeNames) throws InterruptedException, KeeperException {
    
    for (String nodeName : nodeNames) {
      final String path = STATES_NODE + "/" + nodeName;
      synchronized (nodeStateWatches) {
        if (!nodeStateWatches.containsKey(nodeName)) {
          try {
            if (!zkClient.exists(path)) {
              zkClient.makePath(path);
            }
          } catch (NodeExistsException e) {
            // thats okay...
          }
          nodeStateWatches.put(nodeName, new NodeStateWatcher(zkClient, nodeName, path, this));
        } else {
          log.debug("watch already added");
        }
      }
    }
  }
  
  /**
   * Try to assign core to the cluster
   * @throws KeeperException 
   * @throws InterruptedException 
   */
  private CloudState updateState(CloudState state, String nodeName, CoreState coreState) throws KeeperException, InterruptedException {
    String collection = coreState.getCollectionName();
    String coreName = coreState.getCoreName();
    
      String shardId;
      if (coreState.getProperties().get(ZkStateReader.SHARD_ID_PROP) == null) {
        shardId = AssignShard.assignShard(collection, state);
      } else {
        shardId = coreState.getProperties().get(ZkStateReader.SHARD_ID_PROP);
      }
      
      Map<String,String> props = new HashMap<String,String>();
      for (Entry<String,String> entry : coreState.getProperties().entrySet()) {
        props.put(entry.getKey(), entry.getValue());
      }
      ZkNodeProps zkProps = new ZkNodeProps(props);
      Slice slice = state.getSlice(collection, shardId);
      Map<String,ZkNodeProps> shardProps;
      if (slice == null) {
        shardProps = new HashMap<String,ZkNodeProps>();
      } else {
        shardProps = state.getSlice(collection, shardId).getShardsCopy();
      }
      shardProps.put(coreName, zkProps);

      slice = new Slice(shardId, shardProps);
      CloudState newCloudState = updateSlice(state, collection, slice);
      return newCloudState;
  }
  
  private Set<String> complement(Collection<String> next,
      Collection<String> prev) {
    Set<String> downCollections = new HashSet<String>();
    downCollections.addAll(next);
    downCollections.removeAll(prev);
    return downCollections;
  }

  @Override
  public void coreChanged(final String nodeName, final Set<CoreState> states) throws KeeperException, InterruptedException  {
    log.debug("Cores changed: " + nodeName + " states:" + states);
    synchronized(reader.getUpdateLock()) {
      reader.updateCloudState(true);
      CloudState cloudState = reader.getCloudState();
      for (CoreState state : states) {
        cloudState = updateState(cloudState, nodeName, state);
      }
      
      try {
        zkClient.setData(ZkStateReader.CLUSTER_STATE,
            ZkStateReader.toJSON(cloudState));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
            "Interrupted while publishing new state", e);
      }
    }
  }
  
  public static void createClientNodes(SolrZkClient zkClient, String nodeName) throws KeeperException, InterruptedException {
    final String node = STATES_NODE + "/" + nodeName;
    if (log.isInfoEnabled()) {
      log.info("creating node:" + node);
    }
    
    try {
      if (!zkClient.exists(node)) {
        zkClient.makePath(node, CreateMode.PERSISTENT, null);
      }
      
    } catch (NodeExistsException e) {
      // it's ok
    }
  }
  
  private CloudState updateSlice(CloudState state, String collection, Slice slice) {
    
    final Map<String, Map<String, Slice>> newStates = new LinkedHashMap<String,Map<String,Slice>>();
    newStates.putAll(state.getCollectionStates());
    
    if (!newStates.containsKey(collection)) {
      log.info("New collection");
      newStates.put(collection, new LinkedHashMap<String,Slice>());
    }
    
    final Map<String, Slice> slices = newStates.get(collection);
    if (!slices.containsKey(slice.getName())) {
      log.info("New slice");
      slices.put(slice.getName(), slice);
    } else {
      final Map<String,ZkNodeProps> shards = new LinkedHashMap<String,ZkNodeProps>();
      final Slice existingSlice = slices.get(slice.getName());
      shards.putAll(existingSlice.getShards());
      //XXX preserve existing leader
      for(Entry<String, ZkNodeProps> edit: slice.getShards().entrySet()) {
        if(existingSlice.getShards().get(edit.getKey())!=null && existingSlice.getShards().get(edit.getKey()).containsKey(ZkStateReader.LEADER_PROP)) {
          HashMap<String, String> newProps = new HashMap<String,String>();
          newProps.putAll(edit.getValue().getProperties());
          newProps.put(ZkStateReader.LEADER_PROP, existingSlice.getShards().get(edit.getKey()).get(ZkStateReader.LEADER_PROP));
          shards.put(edit.getKey(), new ZkNodeProps(newProps));
        } else {
          shards.put(edit.getKey(), edit.getValue());
        }
      }
      final Slice updatedSlice = new Slice(slice.getName(), shards);
      slices.put(slice.getName(), updatedSlice);
    }
    return new CloudState(state.getLiveNodes(), newStates);
  }

  private CloudState setShardLeader(CloudState state, String collection, String sliceName, String leaderUrl) {
    
    boolean updated = false;
    final Map<String, Map<String, Slice>> newStates = new LinkedHashMap<String,Map<String,Slice>>();
    newStates.putAll(state.getCollectionStates());
    
    final Map<String, Slice> slices = newStates.get(collection);

    if(slices==null) {
      log.error("Could not mark shard leader for non existing collection.");
      return state;
    }
    
    if (!slices.containsKey(sliceName)) {
      log.error("Could not mark leader for non existing slice.");
      return state;
    } else {
      final Map<String,ZkNodeProps> newShards = new LinkedHashMap<String,ZkNodeProps>();
      for(Entry<String, ZkNodeProps> shard: slices.get(sliceName).getShards().entrySet()) {
        Map<String, String> newShardProps = new LinkedHashMap<String,String>();
        newShardProps.putAll(shard.getValue().getProperties());
        
        String wasLeader = newShardProps.remove(ZkStateReader.LEADER_PROP);  //clean any previously existed flag
       
        if(leaderUrl!=null && leaderUrl.equals(newShardProps.get(ZkStateReader.URL_PROP))) {
          newShardProps.put(ZkStateReader.LEADER_PROP,"true");
          if (wasLeader == null) {
            updated = true;
          }
        } else {
          if (wasLeader != null) {
            updated = true;
          }
        }
        newShards.put(shard.getKey(), new ZkNodeProps(newShardProps));
      }
      Slice slice = new Slice(sliceName, newShards);
      slices.put(sliceName, slice);
    }
    if (updated) {
      return new CloudState(state.getLiveNodes(), newStates);
    } else {
      return state;
    }
  }

  @Override
  public void announceLeader(String collection, String shardId, ZkNodeProps props) {
    synchronized (reader.getUpdateLock()) {
      try {
        reader.updateCloudState(true); // get fresh copy of the state
      final CloudState state = reader.getCloudState();
      CloudState newState = setShardLeader(state, collection, shardId,
          props.get(ZkStateReader.URL_PROP));
        if (state != newState) { // if same instance was returned no need to
                                 // update state
          log.info("Announcing new leader: coll: " + collection + " shard: " + shardId + " props:" + props);
          zkClient.setData(ZkStateReader.CLUSTER_STATE,
              ZkStateReader.toJSON(newState));
        } else {
          log.debug("State was not changed.");
        }
      } catch (KeeperException e) {
        log.warn("Could not announce new leader coll:" + collection + " shard:" + shardId + ", exception: " + e.getClass());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Could not promote new leader coll:" + collection + " shard:" + shardId + ", exception: " + e.getClass());
      }
    }
  }
  
}