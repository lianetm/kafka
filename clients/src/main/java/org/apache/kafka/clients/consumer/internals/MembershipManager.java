package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.common.requests.ConsumerGroupHeartbeatResponse;

/**
 * Manages group membership for a single consumer.
 * Responsible for keeping member lifecycle as part of a consumer group.
 */
public interface MembershipManager {

    void processHeartbeatResponse(ConsumerGroupHeartbeatResponse response);

    boolean shouldHeartbeatNow();
}
