package org.gsc.db.api.index;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.disk.DiskIndex;
import com.googlecode.cqengine.persistence.disk.DiskPersistence;
import lombok.extern.slf4j.Slf4j;
import org.gsc.common.utils.ByteArray;
import org.gsc.common.utils.Sha256Hash;
import org.gsc.core.wrapper.BlockWrapper;
import org.gsc.core.wrapper.TransactionWrapper;
import org.gsc.db.GscDatabase;
import org.gsc.db.common.WrappedByteArray;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.gsc.protos.Protocol.Block;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.googlecode.cqengine.query.QueryFactory.attribute;

@Component
@Slf4j
public class BlockIndex extends AbstractIndex<BlockWrapper, Block> {

  public static SimpleAttribute<WrappedByteArray, String> Block_ID;
  public static Attribute<WrappedByteArray, Long> Block_NUMBER;
  public static Attribute<WrappedByteArray, String> TRANSACTIONS;
  public static Attribute<WrappedByteArray, Long> WITNESS_ID;
  public static Attribute<WrappedByteArray, String> WITNESS_ADDRESS;
  public static Attribute<WrappedByteArray, String> OWNERS;
  public static Attribute<WrappedByteArray, String> TOS;

  @Autowired
  public BlockIndex(
      @Qualifier("blockStore") final GscDatabase<BlockWrapper> database) {
    super(database);
  }

  @PostConstruct
  public void init() {
    initIndex(DiskPersistence.onPrimaryKeyInFile(Block_ID, indexPath));
//    index.addIndex(DiskIndex.onAttribute(Block_ID));
    index.addIndex(DiskIndex.onAttribute(Block_NUMBER));
    index.addIndex(DiskIndex.onAttribute(TRANSACTIONS));
    index.addIndex(DiskIndex.onAttribute(WITNESS_ID));
    index.addIndex(DiskIndex.onAttribute(WITNESS_ADDRESS));
    index.addIndex(DiskIndex.onAttribute(OWNERS));
    index.addIndex(DiskIndex.onAttribute(TOS));
  }

  @Override
  protected void setAttribute() {
    Block_ID =
        attribute("block id",
            bytes -> {
              Block block = getObject(bytes);
              return new BlockWrapper(block).getBlockId().toString();
            });
    Block_NUMBER =
        attribute("block number",
            bytes -> {
              Block block = getObject(bytes);
              return block.getBlockHeader().getRawData().getNumber();
            });
    TRANSACTIONS =
        attribute(String.class, "transactions",
            bytes -> {
              Block block = getObject(bytes);
              return block.getTransactionsList().stream()
                  .map(t -> Sha256Hash.of(t.getRawData().toByteArray()).toString())
                  .collect(Collectors.toList());
            });
    WITNESS_ID =
        attribute("witness id",
            bytes -> {
              Block block = getObject(bytes);
              return block.getBlockHeader().getRawData().getWitnessId();
            });
    WITNESS_ADDRESS =
        attribute("witness address",
            bytes -> {
              Block block = getObject(bytes);
              return ByteArray.toHexString(
                  block.getBlockHeader().getRawData().getWitnessAddress().toByteArray());
            });

    OWNERS =
        attribute(String.class, "owner address",
            bytes -> {
              Block block = getObject(bytes);
              return block.getTransactionsList().stream()
                  .map(transaction -> transaction.getRawData().getContractList())
                  .flatMap(List::stream)
                  .map(TransactionWrapper::getOwner)
                  .filter(Objects::nonNull)
                  .distinct()
                  .map(ByteArray::toHexString)
                  .collect(Collectors.toList());
            });
    TOS =
        attribute(String.class, "to address",
            bytes -> {
              Block block = getObject(bytes);
              return block.getTransactionsList().stream()
                  .map(transaction -> transaction.getRawData().getContractList())
                  .flatMap(List::stream)
                  .map(TransactionWrapper::getToAddress)
                  .filter(Objects::nonNull)
                  .distinct()
                  .map(ByteArray::toHexString)
                  .collect(Collectors.toList());
            });
  }
}
