package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.common.message.ConsumerGroupHeartbeatResponseData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.ConsumerGroupHeartbeatResponse;

import java.util.Optional;

/**
 * Membership manager that maintains group membership following the new consumer group protocol.
 * It sends periodic heartbeat requests according to the defined interval.
 * <p>
 * Heartbeat responses are processed to update the member state, and process assignments received.
 */
public class MembershipManagerImpl implements MembershipManager {

    private String groupId;
    private Optional<String> groupInstanceId;
    private MemberState state;
    private ConsumerGroupMetadata groupMetadata;
    private ConsumerGroupHeartbeatResponseData.Assignment assignment;

    public MembershipManagerImpl(String groupId) {
        this.state = MemberState.UNJOINED;
    }


    @Override
    public void processHeartbeatResponse(ConsumerGroupHeartbeatResponse response) {
        if (response.data().errorCode() == Errors.NONE.code()) {
            // Successful heartbeat response. Extract metadata and assignment
            groupMetadata = new ConsumerGroupMetadata(groupId, response.data().memberEpoch(),
                    response.data().memberId(), groupInstanceId);
            assignment = response.data().assignment();
            // TODO: process assignment
            transitionTo(MemberState.STABLE);
        } else {
            handleHeartbeatError(response);
        }
    }

    private void transitionTo(MemberState nextState){
        if (!nextState.getPreviousValidStates().contains(state)){
            // TODO: handle invalid state transition
            throw new RuntimeException(String.format("Invalid state transition from %s to %s",
                    state, nextState));
        }
        this.state = nextState;

    }

    private void handleHeartbeatError(ConsumerGroupHeartbeatResponse response) {
        // TODO: error handling and transition to the appropriate state
    }

    @Override
    public boolean shouldHeartbeatNow() {
        return false;
    }
}
