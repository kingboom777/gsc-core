package org.gsc.net.node;

import static org.gsc.net.message.MessageTypes.BLOCK;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javafx.util.Pair;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.gsc.common.overlay.message.Message;
import org.gsc.common.overlay.server.Channel.GscState;
import org.gsc.common.overlay.server.SyncPool;
import org.gsc.common.utils.ExecutorLoop;
import org.gsc.common.utils.Sha256Hash;
import org.gsc.common.utils.SlidingWindowCounter;
import org.gsc.common.utils.Time;
import org.gsc.core.wrapper.BlockWrapper;
import org.gsc.core.wrapper.BlockWrapper.BlockId;
import org.gsc.config.Parameter.ChainConstant;
import org.gsc.config.Parameter.NetConstants;
import org.gsc.config.Parameter.NodeConstant;
import org.gsc.config.args.Args;
import org.gsc.core.exception.BadBlockException;
import org.gsc.core.exception.BadTransactionException;
import org.gsc.core.exception.NonCommonBlockException;
import org.gsc.core.exception.StoreException;
import org.gsc.core.exception.TraitorPeerException;
import org.gsc.core.exception.GscException;
import org.gsc.core.exception.UnLinkedBlockException;
import org.gsc.net.message.BlockMessage;
import org.gsc.net.message.ChainInventoryMessage;
import org.gsc.net.message.FetchInvDataMessage;
import org.gsc.net.message.InventoryMessage;
import org.gsc.net.message.ItemNotFound;
import org.gsc.net.message.MessageTypes;
import org.gsc.net.message.SyncBlockChainMessage;
import org.gsc.net.message.TransactionMessage;
import org.gsc.net.message.TransactionsMessage;
import org.gsc.net.message.GscMessage;
import org.gsc.net.peer.PeerConnection;
import org.gsc.net.peer.PeerConnectionDelegate;
import org.gsc.protos.Protocol;
import org.gsc.protos.Protocol.Inventory.InventoryType;
import org.gsc.protos.Protocol.ReasonCode;
import org.gsc.protos.Protocol.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * p2p node
 */

@Slf4j
@Component
public class NodeImpl extends PeerConnectionDelegate implements Node {

  @Autowired
  private SyncPool pool;

  private Cache<Sha256Hash, TransactionMessage> TrxCache = CacheBuilder.newBuilder()
      .maximumSize(100_000).expireAfterWrite(1, TimeUnit.HOURS).initialCapacity(100_000)
      .recordStats().build();

  private Cache<Sha256Hash, BlockMessage> BlockCache = CacheBuilder.newBuilder()
      .maximumSize(10).expireAfterWrite(60, TimeUnit.SECONDS)
      .recordStats().build();

  private SlidingWindowCounter fetchWaterLine =
      new SlidingWindowCounter(ChainConstant.BLOCK_PRODUCED_INTERVAL * NetConstants.MSG_CACHE_DURATION_IN_BLOCKS / 100);

  private int maxTrxsSize = 1_000_000;

  private int maxTrxsCnt = 100;

  @Getter
  class PriorItem implements java.lang.Comparable<PriorItem> {

    private long count;

    private Item item;

    private long time;

    public Sha256Hash getHash() {
      return item.getHash();
    }

    public InventoryType getType() {
      return item.getType();
    }

    public PriorItem(Item item, long count) {
      this.item = item;
      this.count = count;
      this.time = Time.getCurrentMillis();
    }

    public void refreshTime() {
      this.time = Time.getCurrentMillis();
    }

    @Override
    public int compareTo(final PriorItem o) {
      if (!this.item.getType().equals(o.getItem().getType())) {
        return this.item.getType().equals(InventoryType.BLOCK) ? -1 : 1;
      }
      return Long.compare(this.count, o.getCount());
    }
  }

  class InvToSend {

    private HashMap<PeerConnection, HashMap<InventoryType, LinkedList<Sha256Hash>>> send
        = new HashMap<>();

    public void clear() {
      this.send.clear();
    }

    public void add(Entry<Sha256Hash, InventoryType> id, PeerConnection peer) {
      if (send.containsKey(peer) && send.get(peer).containsKey(id.getValue())) {
        send.get(peer).get(id.getValue()).offer(id.getKey());
      } else if (send.containsKey(peer)) {
        send.get(peer).put(id.getValue(), new LinkedList<>());
        send.get(peer).get(id.getValue()).offer(id.getKey());
      } else {
        send.put(peer, new HashMap<>());
        send.get(peer).put(id.getValue(), new LinkedList<>());
        send.get(peer).get(id.getValue()).offer(id.getKey());
      }
    }

    public void add(PriorItem id, PeerConnection peer) {
      if (send.containsKey(peer) && send.get(peer).containsKey(id.getType())) {
        send.get(peer).get(id.getType()).offer(id.getHash());
      } else if (send.containsKey(peer)) {
        send.get(peer).put(id.getType(), new LinkedList<>());
        send.get(peer).get(id.getType()).offer(id.getHash());
      } else {
        send.put(peer, new HashMap<>());
        send.get(peer).put(id.getType(), new LinkedList<>());
        send.get(peer).get(id.getType()).offer(id.getHash());
      }
    }

    public int getSize(PeerConnection peer) {
      if (send.containsKey(peer)) {
        return send.get(peer).values().stream().mapToInt(LinkedList::size).sum();
      }

      return 0;
    }

    void sendInv() {
      send.forEach((peer, ids) ->
          ids.forEach((key, value) -> {
            if (key.equals(InventoryType.BLOCK)) {
              value.sort(Comparator.comparingLong(value1 -> new BlockId(value1).getNum()));
            }
            peer.sendMessage(new InventoryMessage(value, key));
          }));
    }

    void sendFetch() {
      send.forEach((peer, ids) ->
          ids.forEach((key, value) -> {
            if (key.equals(InventoryType.BLOCK)) {
              value.sort(Comparator.comparingLong(value1 -> new BlockId(value1).getNum()));
            }
            peer.sendMessage(new FetchInvDataMessage(value, key));
          }));
    }
  }

  private ScheduledExecutorService logExecutor = Executors.newSingleThreadScheduledExecutor();

  private ExecutorService trxsHandlePool = Executors
      .newFixedThreadPool(Args.getInstance().getValidateSignThreadNum(),
          new ThreadFactoryBuilder()
              .setNameFormat("TrxsHandlePool-%d").build());

  //public
  //TODO:need auto erase oldest block

  private Queue<BlockId> freshBlockId = new ConcurrentLinkedQueue<BlockId>() {
    @Override
    public boolean offer(BlockId blockId) {
      if (size() > 200) {
        super.poll();
      }
      return super.offer(blockId);
    }
  };

  private ConcurrentHashMap<Sha256Hash, PeerConnection> syncMap = new ConcurrentHashMap<>();

  private ConcurrentHashMap<Sha256Hash, PeerConnection> fetchMap = new ConcurrentHashMap<>();

  private NodeDelegate del;

  private volatile boolean isAdvertiseActive;

  private volatile boolean isFetchActive;


  private ScheduledExecutorService disconnectInactiveExecutor = Executors
      .newSingleThreadScheduledExecutor();

  private ScheduledExecutorService cleanInventoryExecutor = Executors
      .newSingleThreadScheduledExecutor();

  //broadcast
  private ConcurrentHashMap<Sha256Hash, InventoryType> advObjToSpread = new ConcurrentHashMap<>();

  private HashMap<Sha256Hash, Long> advObjWeRequested = new HashMap<>();

  private ConcurrentHashMap<Sha256Hash, PriorItem> advObjToFetch = new ConcurrentHashMap<Sha256Hash, PriorItem>();

  private ExecutorService broadPool = Executors.newFixedThreadPool(2, new ThreadFactory() {
    @Override
    public Thread newThread(Runnable r) {
      return new Thread(r, "broad-msg-");
    }
  });

  private HashMap<Sha256Hash, Long> badAdvObj = new HashMap<>(); //TODO:need auto erase oldest obj

  //blocks we requested but not received

  private Cache<BlockId, Long> syncBlockIdWeRequested = CacheBuilder.newBuilder()
      .maximumSize(10000).expireAfterWrite(1, TimeUnit.HOURS).initialCapacity(10000)
      .recordStats().build();

  private Long unSyncNum = 0L;

  private Thread handleSyncBlockLoop;

  private Map<BlockMessage, PeerConnection> blockWaitToProc = new ConcurrentHashMap<>();

  private Map<BlockMessage, PeerConnection> blockJustReceived = new ConcurrentHashMap<>();

  private ExecutorLoop<SyncBlockChainMessage> loopSyncBlockChain;

  private ExecutorLoop<FetchInvDataMessage> loopFetchBlocks;

  private ExecutorLoop<Message> loopAdvertiseInv;

  private ExecutorService handleBackLogBlocksPool = Executors.newCachedThreadPool();


  private ScheduledExecutorService fetchSyncBlocksExecutor = Executors
      .newSingleThreadScheduledExecutor();

  private ScheduledExecutorService handleSyncBlockExecutor = Executors
      .newSingleThreadScheduledExecutor();

  private ScheduledExecutorService fetchWaterLineExecutor = Executors
      .newSingleThreadScheduledExecutor();

  private volatile boolean isHandleSyncBlockActive = false;

  private AtomicLong fetchSequenceCounter = new AtomicLong(0L);

  //private volatile boolean isHandleSyncBlockRunning = false;

  private volatile boolean isSuspendFetch = false;

  private volatile boolean isFetchSyncActive = false;

  @Override
  public void onMessage(PeerConnection peer, GscMessage msg) {
    switch (msg.getType()) {
      case BLOCK:
        onHandleBlockMessage(peer, (BlockMessage) msg);
        break;
      case TRX:
        onHandleTransactionMessage(peer, (TransactionMessage) msg);
        break;
      case TRXS:
        onHandleTransactionsMessage(peer, (TransactionsMessage) msg);
        break;
      case SYNC_BLOCK_CHAIN:
        onHandleSyncBlockChainMessage(peer, (SyncBlockChainMessage) msg);
        break;
      case FETCH_INV_DATA:
        onHandleFetchDataMessage(peer, (FetchInvDataMessage) msg);
        break;
      case BLOCK_CHAIN_INVENTORY:
        onHandleChainInventoryMessage(peer, (ChainInventoryMessage) msg);
        break;
      case INVENTORY:
        onHandleInventoryMessage(peer, (InventoryMessage) msg);
        break;
      default:
        throw new IllegalArgumentException("No such message");
    }
  }

  @Override
  public Message getMessage(Sha256Hash msgId) {
    return null;
  }


  @Override
  public void setNodeDelegate(NodeDelegate nodeDel) {
    this.del = nodeDel;
  }

  // for test only
  public void setPool(SyncPool pool) {
    this.pool = pool;
  }

  /**
   * broadcast msg.
   *
   * @param msg msg to broadcast
   */
  public void broadcast(Message msg) {
    InventoryType type;
    if (msg instanceof BlockMessage) {
      logger.info("Ready to broadcast block {}", ((BlockMessage) msg).getBlockId());
      freshBlockId.offer(((BlockMessage) msg).getBlockId());
      BlockCache.put(msg.getMessageId(), (BlockMessage) msg);
      type = InventoryType.BLOCK;
    } else if (msg instanceof TransactionMessage) {
      TrxCache.put(msg.getMessageId(), (TransactionMessage) msg);
      type = InventoryType.TRX;
    } else {
      return;
    }
    //TODO: here need to cache fresh message to let peer fetch these data not from DB
    synchronized (advObjToSpread) {
      advObjToSpread.put(msg.getMessageId(), type);
    }
  }

  @Override
  public void listen() {
    pool.init(this);
    isAdvertiseActive = true;
    isFetchActive = true;
    activeGscPump();
  }

  @Override
  public void close() {
    getActivePeer().forEach(peer -> disconnectPeer(peer, ReasonCode.REQUESTED));
  }

  private void activeGscPump() {
    // broadcast inv
    loopAdvertiseInv = new ExecutorLoop<>(2, 10, b -> {
      //logger.info("loop advertise inv");
      for (PeerConnection peer : getActivePeer()) {
        if (!peer.isNeedSyncFromUs()) {
          logger.info("Advertise adverInv to " + peer);
          peer.sendMessage(b);
        }
      }
    }, throwable -> logger.error("Unhandled exception: ", throwable));

    // fetch blocks
    loopFetchBlocks = new ExecutorLoop<>(2, 10, c -> {
      logger.info("loop fetch blocks");
      if (fetchMap.containsKey(c.getMessageId())) {
        fetchMap.get(c.getMessageId()).sendMessage(c);
      }
    }, throwable -> logger.error("Unhandled exception: ", throwable));

    // sync block chain
    loopSyncBlockChain = new ExecutorLoop<>(2, 10, d -> {
      //logger.info("loop sync block chain");
      if (syncMap.containsKey(d.getMessageId())) {
        syncMap.get(d.getMessageId()).sendMessage(d);
      }
    }, throwable -> logger.error("Unhandled exception: ", throwable));

    broadPool.submit(() -> {
      while (isAdvertiseActive) {
        consumerAdvObjToSpread();
      }
    });

    broadPool.submit(() -> {
      while (isFetchActive) {
        consumerAdvObjToFetch();
      }
    });

    //TODO: wait to refactor these threads.
    //handleSyncBlockLoop.start();

    handleSyncBlockExecutor.scheduleWithFixedDelay(() -> {
      try {
        if (isHandleSyncBlockActive) {
          isHandleSyncBlockActive = false;
          //Thread handleSyncBlockThread = new Thread(() -> handleSyncBlock());
          handleSyncBlock();
        }
      } catch (Throwable t) {
        logger.error("Unhandled exception", t);
      }
    }, 10, 1, TimeUnit.SECONDS);

    //terminate inactive loop
    disconnectInactiveExecutor.scheduleWithFixedDelay(() -> {
      try {
        disconnectInactive();
      } catch (Throwable t) {
        logger.error("Unhandled exception", t);
      }
    }, 30000, ChainConstant.BLOCK_PRODUCED_INTERVAL / 2, TimeUnit.MILLISECONDS);

    logExecutor.scheduleWithFixedDelay(() -> {
      try {
        logNodeStatus();
      } catch (Throwable t) {
        logger.error("Exception in log worker", t);
      }
    }, 10, 10, TimeUnit.SECONDS);

    cleanInventoryExecutor.scheduleWithFixedDelay(() -> {
      try {
        getActivePeer().forEach(p -> p.cleanInvGarbage());
      } catch (Throwable t) {
        logger.error("Unhandled exception", t);
      }
    }, 2, NetConstants.MAX_INVENTORY_SIZE_IN_MINUTES / 2, TimeUnit.MINUTES);

    fetchSyncBlocksExecutor.scheduleWithFixedDelay(() -> {
      try {
        if (isFetchSyncActive) {
          if (!isSuspendFetch) {
            startFetchSyncBlock();
          } else {
            logger.debug("suspend");
          }
        }
        isFetchSyncActive = false;
      } catch (Throwable t) {
        logger.error("Unhandled exception", t);
      }
    }, 10, 1, TimeUnit.SECONDS);

    //fetchWaterLine:
    fetchWaterLineExecutor.scheduleWithFixedDelay(() -> {
      try {
        fetchWaterLine.advance();
      } catch (Throwable t) {
        logger.error("Unhandled exception", t);
      }
    }, 1000, 100, TimeUnit.MILLISECONDS);
  }

  private void consumerAdvObjToFetch() {
    Collection<PeerConnection> filterActivePeer = getActivePeer().stream()
        .filter(peer -> !peer.isBusy()).collect(Collectors.toList());

    if (advObjToFetch.isEmpty() || filterActivePeer.isEmpty()) {
      try {
        Thread.sleep(100);
        return;
      } catch (InterruptedException e) {
        logger.debug(e.getMessage(), e);
      }
    }
    InvToSend sendPackage = new InvToSend();
    long now = Time.getCurrentMillis();
    advObjToFetch.values().stream().sorted(PriorItem::compareTo).forEach(idToFetch -> {
      Sha256Hash hash = idToFetch.getHash();
      if (idToFetch.getTime() < now - NetConstants.MSG_CACHE_DURATION_IN_BLOCKS * ChainConstant.BLOCK_PRODUCED_INTERVAL) {
        logger.info("This obj is too late to fetch: " + idToFetch);
        advObjToFetch.remove(hash);
        return;
      }
      filterActivePeer.stream()
          .filter(peer -> peer.getAdvObjSpreadToUs().containsKey(hash)
              && sendPackage.getSize(peer) < NetConstants.MAX_TRX_PER_PEER)
          .sorted(Comparator.comparingInt(peer -> sendPackage.getSize(peer)))
          .findFirst().ifPresent(peer -> {
        sendPackage.add(idToFetch, peer);
        peer.getAdvObjWeRequested().put(idToFetch.getItem(), now);
        advObjToFetch.remove(hash);
      });
    });

    sendPackage.sendFetch();
  }

  private void consumerAdvObjToSpread() {
    if (advObjToSpread.isEmpty()) {
      try {
        Thread.sleep(100);
        return;
      } catch (InterruptedException e) {
        logger.debug(e.getMessage(), e);
      }
    }
    InvToSend sendPackage = new InvToSend();
    HashMap<Sha256Hash, InventoryType> spread = new HashMap<>();
    synchronized (advObjToSpread) {
      spread.putAll(advObjToSpread);
      advObjToSpread.clear();
    }
    getActivePeer().stream()
        .filter(peer -> !peer.isNeedSyncFromUs())
        .forEach(peer ->
            spread.entrySet().stream()
                .filter(idToSpread ->
                    !peer.getAdvObjSpreadToUs().containsKey(idToSpread.getKey())
                        && !peer.getAdvObjWeSpread().containsKey(idToSpread.getKey()))
                .forEach(idToSpread -> {
                  peer.getAdvObjWeSpread().put(idToSpread.getKey(), Time.getCurrentMillis());
                  sendPackage.add(idToSpread, peer);
                }));
    sendPackage.sendInv();
  }

  private synchronized void handleSyncBlock() {
    if (((ThreadPoolExecutor) handleBackLogBlocksPool).getActiveCount() > NodeConstant.MAX_BLOCKS_IN_PROCESS) {
      logger.info("we're already processing too many blocks");
      return;
    } else if (isSuspendFetch) {
      isSuspendFetch = false;
    }

    final boolean[] isBlockProc = {true};

    while (isBlockProc[0]) {

      isBlockProc[0] = false;

      synchronized (blockJustReceived) {
        blockWaitToProc.putAll(blockJustReceived);
        blockJustReceived.clear();
      }

      blockWaitToProc.forEach((msg, peerConnection) -> {

        if (peerConnection.isDisconnect()) {
          logger.error("Peer {} is disconnect, drop block {}", peerConnection.getNode().getHost(),
              msg.getBlockId().getString());
          blockWaitToProc.remove(msg);
          syncBlockIdWeRequested.invalidate(msg.getBlockId());
          isFetchSyncActive = true;
          return;
        }

        synchronized (freshBlockId) {
          final boolean[] isFound = {false};
          getActivePeer().stream()
              .filter(
                  peer -> !peer.getSyncBlockToFetch().isEmpty() && peer.getSyncBlockToFetch().peek()
                      .equals(msg.getBlockId()))
              .forEach(peer -> {
                peer.getSyncBlockToFetch().pop();
                peer.getBlockInProc().add(msg.getBlockId());
                isFound[0] = true;
              });
          if (isFound[0]) {
            blockWaitToProc.remove(msg);
            isBlockProc[0] = true;
            if (freshBlockId.contains(msg.getBlockId()) || processSyncBlock(
                msg.getBlockCapsule())) {
              finishProcessSyncBlock(msg.getBlockCapsule());
            }
          }
        }
      });

      if (((ThreadPoolExecutor) handleBackLogBlocksPool).getActiveCount() > NodeConstant.MAX_BLOCKS_IN_PROCESS) {
        logger.info("we're already processing too many blocks");
        if (blockWaitToProc.size() >= NodeConstant.MAX_BLOCKS_ALREADY_FETCHED) {
          isSuspendFetch = true;
        }
        break;
      }

    }
  }

  private synchronized void logNodeStatus() {
    StringBuilder sb = new StringBuilder("LocalNode stats:\n");
    sb.append("============\n");

    sb.append(String.format(
        "MyHeadBlockNum: %d\n"
            + "advObjToSpread: %d\n"
            + "advObjToFetch: %d\n"
            + "advObjWeRequested: %d\n"
            + "unSyncNum: %d\n"
            + "blockWaitToProc: %d\n"
            + "blockJustReceived: %d\n"
            + "syncBlockIdWeRequested: %d\n"
            + "badAdvObj: %d\n",
        del.getHeadBlockId().getNum(),
        advObjToSpread.size(),
        advObjToFetch.size(),
        advObjWeRequested.size(),
        getUnSyncNum(),
        blockWaitToProc.size(),
        blockJustReceived.size(),
        syncBlockIdWeRequested.size(),
        badAdvObj.size()
    ));

    logger.info(sb.toString());
  }

  public synchronized void disconnectInactive() {
    //logger.debug("size of activePeer: " + getActivePeer().size());
    getActivePeer().forEach(peer -> {
      final boolean[] isDisconnected = {false};
      final ReasonCode[] reasonCode = {ReasonCode.USER_REASON};

      peer.getAdvObjWeRequested().values().stream()
          .filter(time -> time < Time.getCurrentMillis() - NetConstants.ADV_TIME_OUT)
          .findFirst().ifPresent(time -> {
        isDisconnected[0] = true;
        reasonCode[0] = ReasonCode.FETCH_FAIL;
      });

      if (!isDisconnected[0]) {
        peer.getSyncBlockRequested().values().stream()
            .filter(time -> time < Time.getCurrentMillis() - NetConstants.SYNC_TIME_OUT)
            .findFirst().ifPresent(time -> {
          isDisconnected[0] = true;
          reasonCode[0] = ReasonCode.SYNC_FAIL;
        });
      }

//    TODO:optimize here
//      if (!isDisconnected[0]) {
//        if (del.getHeadBlockId().getNum() - peer.getHeadBlockWeBothHave().getNum()
//            > 2 * NetConstants.HEAD_NUM_CHECK_TIME / ChainConstant.BLOCK_PRODUCED_INTERVAL
//            && peer.getConnectTime() < Time.getCurrentMillis() - NetConstants.HEAD_NUM_CHECK_TIME
//            && peer.getSyncBlockRequested().isEmpty()) {
//          isDisconnected[0] = true;
//        }
//      }

      if (isDisconnected[0]) {
        disconnectPeer(peer, ReasonCode.TIME_OUT);
      }
    });
  }


  private void onHandleInventoryMessage(PeerConnection peer, InventoryMessage msg) {
    for (Sha256Hash id : msg.getHashList()) {
      if (msg.getInventoryType().equals(InventoryType.TRX) && TrxCache.getIfPresent(id) != null) {
        logger.info("{} {} from peer {} Already exist.", msg.getInventoryType(), id,
            peer.getNode().getHost());
        continue;
      }
      final boolean[] spreaded = {false};
      final boolean[] requested = {false};
      getActivePeer().forEach(p -> {
        if (p.getAdvObjWeSpread().containsKey(id)) {
          spreaded[0] = true;
        }
        if (p.getAdvObjWeRequested().containsKey(new Item(id, msg.getInventoryType()))) {
          requested[0] = true;
        }
      });

      if (!spreaded[0]
          && !peer.isNeedSyncFromPeer()
          && !peer.isNeedSyncFromUs()) {

        //avoid TRX flood attack here.
        if (msg.getInventoryType().equals(InventoryType.TRX)
            && (peer.isAdvInvFull()
            || isFlooded())) {
          logger.warn("A peer is flooding us, stop handle inv, the peer is: " + peer);
          return;
        }

        peer.getAdvObjSpreadToUs().put(id, System.currentTimeMillis());
        if (!requested[0]) {
          if (!badAdvObj.containsKey(id)) {
            PriorItem targetPriorItem = this.advObjToFetch.get(id);

            if (targetPriorItem != null) {
              //another peer tell this trx to us, refresh its time.
              targetPriorItem.refreshTime();
            } else {
              fetchWaterLine.increase();
              this.advObjToFetch.put(id, new PriorItem(new Item(id, msg.getInventoryType()),
                  fetchSequenceCounter.incrementAndGet()));
            }
          }
        }
      }
    }
  }

  private boolean isFlooded() {
    return fetchWaterLine.totalCount()
        > ChainConstant.BLOCK_PRODUCED_INTERVAL * NetConstants.NET_MAX_TRX_PER_SECOND * NetConstants.MSG_CACHE_DURATION_IN_BLOCKS / 1000;
  }

  @Override
  public void syncFrom(Sha256Hash myHeadBlockHash) {
    try {
      while (getActivePeer().isEmpty()) {
        logger.info("other peer is nil, please wait ... ");
        Thread.sleep(10000L);
      }
    } catch (InterruptedException e) {
      logger.debug(e.getMessage(), e);
    }
    logger.info("wait end");
  }

  private void onHandleBlockMessage(PeerConnection peer, BlockMessage blkMsg) {
    Map<Item, Long> advObjWeRequested = peer.getAdvObjWeRequested();
    Map<BlockId, Long> syncBlockRequested = peer.getSyncBlockRequested();
    BlockId blockId = blkMsg.getBlockId();
    Item item = new Item(blockId, InventoryType.BLOCK);
    boolean syncFlag = false;
    if (syncBlockRequested.containsKey(blockId)) {
      if (!peer.getSyncFlag()) {
        logger.info("Received a block {} from no need sync peer {}", blockId.getNum(),
            peer.getNode().getHost());
        return;
      }
      peer.getSyncBlockRequested().remove(blockId);
      synchronized (blockJustReceived) {
        blockJustReceived.put(blkMsg, peer);
      }
      isHandleSyncBlockActive = true;
      syncFlag = true;
      if (!peer.isBusy()) {
        if (peer.getUnfetchSyncNum() > 0
            && peer.getSyncBlockToFetch().size() <= NodeConstant.SYNC_FETCH_BATCH_NUM) {
          syncNextBatchChainIds(peer);
        } else {
          isFetchSyncActive = true;
        }
      }
    }

    if (advObjWeRequested.containsKey(item)) {
      advObjWeRequested.remove(item);
      if (!syncFlag) {
        processAdvBlock(peer, blkMsg.getBlockCapsule());
        startFetchItem();
      }
    }

  }

  private void processAdvBlock(PeerConnection peer, BlockWrapper block) {
    //TODO: lack the complete flow.
    if (!freshBlockId.contains(block.getBlockId())) {
      try {
        LinkedList<Sha256Hash> trxIds = null;
        trxIds = del.handleBlock(block, false);
        freshBlockId.offer(block.getBlockId());

        trxIds.forEach(trxId -> advObjToFetch.remove(trxId));

        getActivePeer().stream()
            .filter(p -> p.getAdvObjSpreadToUs().containsKey(block.getBlockId()))
            .forEach(p -> updateBlockWeBothHave(p, block));

        broadcast(new BlockMessage(block));

      } catch (BadBlockException e) {
        logger.error("We get a bad block {}, from {}, reason is {} ",
            block.getBlockId().getString(), peer.getNode().getHost(), e.getMessage());
        badAdvObj.put(block.getBlockId(), System.currentTimeMillis());
        disconnectPeer(peer, ReasonCode.BAD_BLOCK);
      } catch (UnLinkedBlockException e) {
        logger.error("We get a unlinked block {}, from {}, head is {}",
            block.getBlockId().getString(), peer.getNode().getHost(),
            del.getHeadBlockId().getString());
        startSyncWithPeer(peer);
      } catch (NonCommonBlockException e) {
        logger.error("We get a block {} that do not have the most recent common ancestor with the main chain, from {}, reason is {} ",
            block.getBlockId().getString(), peer.getNode().getHost(), e.getMessage());
        badAdvObj.put(block.getBlockId(), System.currentTimeMillis());
        disconnectPeer(peer, ReasonCode.FORKED);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      // logger.error("Fail to process adv block {} from {}", block.getBlockId().getString(),
      // peer.getNode().getHost(), e);

    }
  }

  private boolean processSyncBlock(BlockWrapper block) {
    boolean isAccept = false;
    ReasonCode reason = null;
    try {
      try {
        del.handleBlock(block, true);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      freshBlockId.offer(block.getBlockId());
      logger.info("Success handle block {}", block.getBlockId().getString());
      isAccept = true;
    } catch (BadBlockException e) {
      logger.error("We get a bad block {}, reason is {} ", block.getBlockId().getString(),
          e.getMessage());
      badAdvObj.put(block.getBlockId(), System.currentTimeMillis());
      reason = ReasonCode.BAD_BLOCK;
    } catch (UnLinkedBlockException e) {
      logger.error("We get a unlinked block {}, head is {}", block.getBlockId().getString(),
          del.getHeadBlockId().getString());
      reason = ReasonCode.UNLINKABLE;
    } catch (NonCommonBlockException e) {
      logger.error("We get a block {} that do not have the most recent common ancestor with the main chain, head is {}",
          block.getBlockId().getString(),
          del.getHeadBlockId().getString());
      reason = ReasonCode.FORKED;
    }

    if (!isAccept) {
      ReasonCode finalReason = reason;
      getActivePeer().stream()
          .filter(peer -> peer.getBlockInProc().contains(block.getBlockId()))
          .forEach(peer -> disconnectPeer(peer, finalReason));
    }
    isHandleSyncBlockActive = true;
    return isAccept;
  }

  private void finishProcessSyncBlock(BlockWrapper block) {
    getActivePeer().forEach(peer -> {
      if (peer.getSyncBlockToFetch().isEmpty()
          && peer.getBlockInProc().isEmpty()
          && !peer.isNeedSyncFromPeer()
          && !peer.isNeedSyncFromUs()) {
        startSyncWithPeer(peer);
      } else if (peer.getBlockInProc().remove(block.getBlockId())) {
        updateBlockWeBothHave(peer, block);
        if (peer.getSyncBlockToFetch().isEmpty()) { //send sync to let peer know we are sync.
          syncNextBatchChainIds(peer);
        }
      }
    });
  }

  synchronized boolean isTrxExist(TransactionMessage trxMsg) {
    if (TrxCache.getIfPresent(trxMsg.getMessageId()) != null) {
      return true;
    }
    TrxCache.put(trxMsg.getMessageId(), trxMsg);
    return false;
  }

  private void onHandleTransactionMessage(PeerConnection peer, TransactionMessage trxMsg) {
    try {
      Item item = new Item(trxMsg.getMessageId(), InventoryType.TRX);
      if (!peer.getAdvObjWeRequested().containsKey(item)) {
        throw new TraitorPeerException("We don't send fetch request to" + peer);
      }
      peer.getAdvObjWeRequested().remove(item);
      if (isTrxExist(trxMsg)) {
        logger.info("Trx {} from Peer {} already processed.", trxMsg.getMessageId(),
            peer.getNode().getHost());
        return;
      }
      if(del.handleTransaction(trxMsg.getTransactionWrapper())){
        broadcast(trxMsg);
      }
    } catch (TraitorPeerException e) {
      logger.error(e.getMessage());
      banTraitorPeer(peer, ReasonCode.BAD_PROTOCOL);
    } catch (BadTransactionException e) {
      badAdvObj.put(trxMsg.getMessageId(), System.currentTimeMillis());
      banTraitorPeer(peer, ReasonCode.BAD_TX);
    }
  }

  private void onHandleTransactionsMessage(PeerConnection peer, TransactionsMessage msg) {
    for (Transaction trans : msg.getTransactions().getTransactionsList()) {
      trxsHandlePool
          .submit(() -> onHandleTransactionMessage(peer, new TransactionMessage(trans)));
    }
  }

  private void onHandleSyncBlockChainMessage(PeerConnection peer, SyncBlockChainMessage syncMsg) {
    peer.setGscState(GscState.SYNCING);
    LinkedList<BlockId> blockIds = new LinkedList<>();
    List<BlockId> summaryChainIds = syncMsg.getBlockIds();
    long remainNum = 0;

    try {
      blockIds = del.getLostBlockIds(summaryChainIds);
    } catch (StoreException e) {
      logger.error(e.getMessage());
    }

    if (blockIds.isEmpty()) {
      if (CollectionUtils.isNotEmpty(summaryChainIds) && !del
          .canChainRevoke(summaryChainIds.get(0).getNum())) {
        logger.info("Node sync block fail, disconnect peer {}, no block {}", peer,
            summaryChainIds.get(0).getString());
        peer.disconnect(ReasonCode.SYNC_FAIL);
        return;
      } else {
        peer.setNeedSyncFromUs(false);
      }
    } else if (blockIds.size() == 1
        && !summaryChainIds.isEmpty()
        && (summaryChainIds.contains(blockIds.peekFirst())
        || blockIds.peek().getNum() == 0)) {
      peer.setNeedSyncFromUs(false);
    } else {
      peer.setNeedSyncFromUs(true);
      remainNum = del.getHeadBlockId().getNum() - blockIds.peekLast().getNum();
    }

    //TODO: need a block older than revokingDB size exception. otherwise will be a dead loop here
    if (!peer.isNeedSyncFromPeer()
        && CollectionUtils.isNotEmpty(summaryChainIds)
        && !del.contain(Iterables.getLast(summaryChainIds), BLOCK)
        && del.canChainRevoke(summaryChainIds.get(0).getNum())) {
      startSyncWithPeer(peer);
    }

    peer.sendMessage(new ChainInventoryMessage(blockIds, remainNum));
  }

  private void onHandleFetchDataMessage(PeerConnection peer, FetchInvDataMessage fetchInvDataMsg) {

    MessageTypes type = fetchInvDataMsg.getInvMessageType();

    BlockWrapper block = null;

    List<Protocol.Transaction> transactions = Lists.newArrayList();

    int size = 0;

    for (Sha256Hash hash : fetchInvDataMsg.getHashList()) {

      Message msg;

      if (type == BLOCK) {
        msg = BlockCache.getIfPresent(hash);
      } else {
        msg = TrxCache.getIfPresent(hash);
      }

      if (msg == null) {
        msg = del.getData(hash, type);
      }

      if (msg == null) {
        logger.error("fetch message {} {} failed.", type, hash);
        peer.sendMessage(new ItemNotFound());
        return;
      }

      if (type.equals(BLOCK)) {
        block = ((BlockMessage) msg).getBlockCapsule();
        peer.sendMessage(msg);
      } else {
        transactions.add(((TransactionMessage) msg).getTransactionWrapper().getInstance());
        size += ((TransactionMessage) msg).getTransactionWrapper().getInstance().getSerializedSize();
        if (transactions.size() % maxTrxsCnt == 0 || size > maxTrxsSize) {
          peer.sendMessage(new TransactionsMessage(transactions));
          transactions = Lists.newArrayList();
          size = 0;
        }
      }
    }

    if (block != null) {
      updateBlockWeBothHave(peer, block);
    }
    if (transactions.size() > 0) {
      peer.sendMessage(new TransactionsMessage(transactions));
    }
  }

  private void banTraitorPeer(PeerConnection peer, ReasonCode reason) {
    disconnectPeer(peer, reason); //TODO: ban it
  }

  private void onHandleChainInventoryMessage(PeerConnection peer, ChainInventoryMessage msg) {
    //logger.info("on handle block chain inventory message");
    try {
      if (peer.getSyncChainRequested() != null) {
        //List<BlockId> blockIds = msg.getBlockIds();
        Deque<BlockId> blockIdWeGet = new LinkedList<>(msg.getBlockIds());

        //check if the peer is a traitor
        if (!blockIdWeGet.isEmpty()) {
          long num = blockIdWeGet.peek().getNum();
          for (BlockId id : blockIdWeGet) {
            if (id.getNum() != num++) {
              throw new TraitorPeerException("We get a not continuous block inv from " + peer);
            }
          }

          if (peer.getSyncChainRequested().getKey().isEmpty()) {
            if (blockIdWeGet.peek().getNum() != 1) {
              throw new TraitorPeerException(
                  "We want a block inv starting from beginning from " + peer);
            }
          } else {
            if (!peer.getSyncChainRequested().getKey().contains(blockIdWeGet.peek())) {
              throw new TraitorPeerException(String.format(
                  "We get a unlinked block chain from " + peer
                      + "\n Our head is " + peer.getSyncChainRequested().getKey().getLast()
                      .getString()
                      + "\n Peer give us is " + blockIdWeGet.peek().getString()));
            }
          }

          if (del.getHeadBlockId().getNum() > 0) {
            long maxRemainTime = ChainConstant.CLOCK_MAX_DELAY + System.currentTimeMillis() - del
                .getBlockTime(del.getSolidBlockId());
            long maxFutureNum =
                maxRemainTime / ChainConstant.BLOCK_PRODUCED_INTERVAL + del.getSolidBlockId()
                    .getNum();
            if (blockIdWeGet.peekLast().getNum() + msg.getRemainNum() > maxFutureNum) {
              throw new TraitorPeerException(
                  "Block num " + blockIdWeGet.peekLast().getNum() + "+" + msg.getRemainNum()
                      + " is gt future max num " + maxFutureNum + " from " + peer);
            }
          }
        }
        //check finish

        //here this peer's answer is legal
        peer.setSyncChainRequested(null);
        if (msg.getRemainNum() == 0
            && (blockIdWeGet.isEmpty() || (blockIdWeGet.size() == 1 && del
            .containBlock(blockIdWeGet.peek())))
            && peer.getSyncBlockToFetch().isEmpty()
            && peer.getUnfetchSyncNum() == 0) {
          peer.setNeedSyncFromPeer(false);
          unSyncNum = getUnSyncNum();
          if (unSyncNum == 0) {
            del.syncToCli(0);
          }
          //TODO: check whole sync status and notify del sync status.
          //TODO: if sync finish call del.syncToCli();
          return;
        }

        if (!blockIdWeGet.isEmpty() && peer.getSyncBlockToFetch().isEmpty()) {
          boolean isFound = false;

          for (PeerConnection peerToCheck :
              getActivePeer()) {
            if (!peerToCheck.equals(peer)
                && !peerToCheck.getSyncBlockToFetch().isEmpty()
                && peerToCheck.getSyncBlockToFetch().peekFirst()
                .equals(blockIdWeGet.peekFirst())) {
              isFound = true;
              break;
            }
          }

          if (!isFound) {
            while (!blockIdWeGet.isEmpty() && del.containBlock(blockIdWeGet.peek())) {
              updateBlockWeBothHave(peer, blockIdWeGet.peek());
              blockIdWeGet.poll();
            }
          }
        } else if (!blockIdWeGet.isEmpty()) {
          while (!peer.getSyncBlockToFetch().isEmpty()) {
            if (!peer.getSyncBlockToFetch().peekLast().equals(blockIdWeGet.peekFirst())) {
              peer.getSyncBlockToFetch().pollLast();
            } else {
              break;
            }
          }

          if (peer.getSyncBlockToFetch().isEmpty() && del.containBlock(blockIdWeGet.peek())) {
            updateBlockWeBothHave(peer, blockIdWeGet.peek());

          }
          //poll the block we both have.
          blockIdWeGet.poll();
        }

        //sew it
        peer.setUnfetchSyncNum(msg.getRemainNum());
        peer.getSyncBlockToFetch().addAll(blockIdWeGet);
        synchronized (freshBlockId) {
          while (!peer.getSyncBlockToFetch().isEmpty() && freshBlockId
              .contains(peer.getSyncBlockToFetch().peek())) {
            BlockId blockId = peer.getSyncBlockToFetch().pop();
            updateBlockWeBothHave(peer, blockId);
            logger.info("Block {} from {} is processed", blockId.getString(),
                peer.getNode().getHost());
          }
        }

        if (msg.getRemainNum() == 0 && peer.getSyncBlockToFetch().size() == 0) {
          peer.setNeedSyncFromPeer(false);
        }

        long newUnSyncNum = getUnSyncNum();
        if (unSyncNum != newUnSyncNum) {
          unSyncNum = newUnSyncNum;
          del.syncToCli(unSyncNum);
        }

        if (msg.getRemainNum() == 0) {
          if (!peer.getSyncBlockToFetch().isEmpty()) {
            //startFetchSyncBlock();
            isFetchSyncActive = true;
          } else {
            //let peer know we are sync.
            syncNextBatchChainIds(peer);
          }
        } else {
          if (peer.getSyncBlockToFetch().size() > NodeConstant.SYNC_FETCH_BATCH_NUM) {
            //one batch by one batch.
            //startFetchSyncBlock();
            isFetchSyncActive = true;
          } else {
            syncNextBatchChainIds(peer);
          }
        }

        //TODO: check head block time is legal here
        //TODO: refresh sync status to cli. call del.syncToCli() here

      } else {
        throw new TraitorPeerException("We don't send sync request to " + peer);
      }

    } catch (TraitorPeerException e) {
      logger.error(e.getMessage());
      banTraitorPeer(peer, ReasonCode.BAD_PROTOCOL);
    }
  }

  private void startFetchItem() {

  }

  private long getUnSyncNum() {
    if (getActivePeer().isEmpty()) {
      return 0;
    }
    return getActivePeer().stream()
        .mapToLong(peer -> peer.getUnfetchSyncNum() + peer.getSyncBlockToFetch().size())
        .max()
        .getAsLong();
  }

  private synchronized void startFetchSyncBlock() {
    //TODO: check how many block is processing and decide if fetch more
    HashMap<PeerConnection, List<BlockId>> send = new HashMap<>();
    HashSet<BlockId> request = new HashSet<>();

    getActivePeer().stream()
        .filter(peer -> peer.isNeedSyncFromPeer() && !peer.isBusy())
        .forEach(peer -> {
          if (!send.containsKey(peer)) { //TODO: Attention multi thread here
            send.put(peer, new LinkedList<>());
          }
          for (BlockId blockId :
              peer.getSyncBlockToFetch()) {
            if (!request.contains(blockId) //TODO: clean processing block
                && (syncBlockIdWeRequested.getIfPresent(blockId) == null)) {
              send.get(peer).add(blockId);
              request.add(blockId);
              //TODO: check max block num to fetch from one peer.
              if (send.get(peer).size()
                  > NodeConstant.MAX_BLOCKS_SYNC_FROM_ONE_PEER) { //Max Blocks peer get one time
                break;
              }
            }
          }
        });

    send.forEach((peer, blockIds) -> {
      //TODO: use collector
      blockIds.forEach(blockId -> {
        syncBlockIdWeRequested.put(blockId, System.currentTimeMillis());
        peer.getSyncBlockRequested().put(blockId, System.currentTimeMillis());
      });
      List<Sha256Hash> ids = new LinkedList<>();
      ids.addAll(blockIds);
      if (!ids.isEmpty()) {
        peer.sendMessage(new FetchInvDataMessage(ids, InventoryType.BLOCK));
      }
    });

    send.clear();
  }

  private void updateBlockWeBothHave(PeerConnection peer, BlockWrapper block) {
    logger.info("update peer {} block both we have {}", peer.getNode().getHost(),
        block.getBlockId().getString());
    peer.setHeadBlockWeBothHave(block.getBlockId());
    peer.setHeadBlockTimeWeBothHave(block.getTimeStamp());
  }

  private void updateBlockWeBothHave(PeerConnection peer, BlockId blockId) {
    logger.info("update peer {} block both we have, {}", peer.getNode().getHost(),
        blockId.getString());
    peer.setHeadBlockWeBothHave(blockId);
    long time = ((BlockMessage) del.getData(blockId, BLOCK)).getBlockCapsule()
        .getTimeStamp();
    peer.setHeadBlockTimeWeBothHave(time);
  }

  private Collection<PeerConnection> getActivePeer() {
    return pool.getActivePeers();
  }

  private void startSyncWithPeer(PeerConnection peer) {
    peer.setNeedSyncFromPeer(true);
    peer.getSyncBlockToFetch().clear();
    peer.setUnfetchSyncNum(0);
    updateBlockWeBothHave(peer, del.getGenesisBlock());
    peer.setBanned(false);
    syncNextBatchChainIds(peer);
  }

  private void syncNextBatchChainIds(PeerConnection peer) {
    if (peer.getSyncChainRequested() != null) {
      logger.info("Peer {} is in sync.", peer.getNode().getHost());
      return;
    }
    try {
      Deque<BlockId> chainSummary =
          del.getBlockChainSummary(peer.getHeadBlockWeBothHave(),
              peer.getSyncBlockToFetch());
      peer.setSyncChainRequested(
          new Pair<>(chainSummary, System.currentTimeMillis()));
      peer.sendMessage(new SyncBlockChainMessage((LinkedList<BlockId>) chainSummary));
    } catch (GscException e) {
      logger.error("Peer {} sync next batch chainIds failed, error: {}", peer.getNode().getHost(),
          e.getMessage());
      disconnectPeer(peer, ReasonCode.FORKED);
    }
  }

  @Override
  public void onConnectPeer(PeerConnection peer) {
    if (peer.getHelloMessage().getHeadBlockId().getNum() > del.getHeadBlockId().getNum()) {
      peer.setGscState(GscState.SYNCING);
      startSyncWithPeer(peer);
    } else {
      peer.setGscState(GscState.SYNC_COMPLETED);
    }
  }

  @Override
  public void onDisconnectPeer(PeerConnection peer) {

    if (!peer.getSyncBlockRequested().isEmpty()) {
      peer.getSyncBlockRequested().keySet()
          .forEach(blockId -> syncBlockIdWeRequested.invalidate(blockId));
      isFetchSyncActive = true;
    }

    if (!peer.getAdvObjWeRequested().isEmpty()) {
      peer.getAdvObjWeRequested().keySet()
          .forEach(item -> {
            if (getActivePeer().stream()
                .filter(peerConnection -> !peerConnection.equals(peer))
                .filter(peerConnection -> peerConnection.getInvToUs().contains(item.getHash()))
                .findFirst()
                .isPresent()) {
              advObjToFetch.put(item.getHash(), new PriorItem(item,
                  fetchSequenceCounter.incrementAndGet()));
            }
          });
    }
  }

  public void shutDown() {
    logExecutor.shutdown();
    trxsHandlePool.shutdown();
    disconnectInactiveExecutor.shutdown();
    cleanInventoryExecutor.shutdown();
    broadPool.shutdown();
    loopSyncBlockChain.shutdown();
    loopFetchBlocks.shutdown();
    loopAdvertiseInv.shutdown();
    handleBackLogBlocksPool.shutdown();
    fetchSyncBlocksExecutor.shutdown();
    handleSyncBlockExecutor.shutdown();
  }

  private void disconnectPeer(PeerConnection peer, ReasonCode reason) {
    peer.setSyncFlag(false);
    peer.disconnect(reason);
  }

}

