package org.gsc.common.net.udp.message.discover;

import static org.gsc.common.net.udp.message.UdpMessageTypeEnum.DISCOVER_FIND_NODE;

import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.gsc.common.net.udp.message.Message;
import org.gsc.common.overlay.discover.node.Node;
import org.gsc.common.net.udp.message.Message;
import org.gsc.common.overlay.discover.node.Node;
import org.gsc.common.utils.ByteArray;
import org.gsc.protos.Discover;
import org.gsc.protos.Discover.Endpoint;
import org.gsc.protos.Discover.FindNeighbours;

@Slf4j
public class FindNodeMessage extends Message {

  private Discover.FindNeighbours findNeighbours;

  public FindNodeMessage(byte[] data) throws Exception{
    super(DISCOVER_FIND_NODE, data);
    this.findNeighbours = Discover.FindNeighbours.parseFrom(data);
  }

  public FindNodeMessage(Node from, byte[] targetId) {
    super(DISCOVER_FIND_NODE, null);
    Endpoint fromEndpoint = Endpoint.newBuilder()
        .setAddress(ByteString.copyFrom(ByteArray.fromString(from.getHost())))
        .setPort(from.getPort())
        .setNodeId(ByteString.copyFrom(from.getId()))
        .build();
    this.findNeighbours = FindNeighbours.newBuilder()
        .setFrom(fromEndpoint)
        .setTargetId(ByteString.copyFrom(targetId))
        .setTimestamp(System.currentTimeMillis())
        .build();
    this.data = this.findNeighbours.toByteArray();
  }

  public byte[] getTargetId() {
    return this.findNeighbours.getTargetId().toByteArray();
  }

  @Override
  public byte[] getNodeId() {
    return this.findNeighbours.getFrom().getNodeId().toByteArray();
  }

  @Override
  public String toString() {
    return "[findNeighbours: " + findNeighbours;
  }
}
