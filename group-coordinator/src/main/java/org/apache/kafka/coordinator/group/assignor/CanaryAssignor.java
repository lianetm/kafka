/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.coordinator.group.assignor;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.coordinator.group.api.assignor.ConsumerGroupPartitionAssignor;
import org.apache.kafka.coordinator.group.api.assignor.GroupAssignment;
import org.apache.kafka.coordinator.group.api.assignor.GroupSpec;
import org.apache.kafka.coordinator.group.api.assignor.MemberAssignment;
import org.apache.kafka.coordinator.group.api.assignor.MemberSubscription;
import org.apache.kafka.coordinator.group.api.assignor.PartitionAssignorException;
import org.apache.kafka.coordinator.group.api.assignor.SubscribedTopicDescriber;
import org.apache.kafka.coordinator.group.modern.MemberAssignmentImpl;
import org.apache.kafka.server.common.TopicIdPartition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Canary Assignor implements a traffic-splitting strategy for safe consumer deployments.
 * <p>
 * Members identified as "canary" (via {@code client.rack=canary}) receive a small percentage
 * of partitions (default 10%), allowing validation of new consumer versions before full rollout.
 * <p>
 * Assignment logic:
 * <ol>
 *     <li>Members are partitioned into canary vs regular pools based on their rack ID.</li>
 *     <li>A percentage of partitions (default 10%) is assigned to canary members.</li>
 *     <li>Remaining partitions are assigned to regular members.</li>
 *     <li>Within each pool, partitions are distributed using the same balanced quota-based
 *         algorithm as {@link UniformAssignor}: each member receives a minimum quota
 *         (total partitions / number of members), with any remaining partitions distributed
 *         one per member. This ensures the difference in partition count between any two
 *         members in the same pool is at most one.</li>
 * </ol>
 * <p>
 * Edge cases:
 * <ul>
 *     <li>No canary members: All partitions go to regular members (behaves like UniformAssignor).</li>
 *     <li>No regular members: All partitions go to canary members.</li>
 *     <li>Canary percentage results in less than 1 partition: At least 1 partition to canary.</li>
 * </ul>
 *
 * @see UniformAssignor
 */
public class CanaryAssignor implements ConsumerGroupPartitionAssignor {
    private static final Logger LOG = LoggerFactory.getLogger(CanaryAssignor.class);

    public static final String NAME = "canary";
    public static final String CANARY_RACK_ID = "canary";
    public static final double DEFAULT_CANARY_PERCENTAGE = 0.10;

    private final double canaryPercentage;

    public CanaryAssignor() {
        this(DEFAULT_CANARY_PERCENTAGE);
    }

    public CanaryAssignor(double canaryPercentage) {
        if (canaryPercentage < 0.0 || canaryPercentage > 1.0) {
            throw new IllegalArgumentException("Canary percentage must be between 0.0 and 1.0");
        }
        this.canaryPercentage = canaryPercentage;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GroupAssignment assign(
        GroupSpec groupSpec,
        SubscribedTopicDescriber subscribedTopicDescriber
    ) throws PartitionAssignorException {
        if (groupSpec.memberIds().isEmpty()) {
            return new GroupAssignment(Map.of());
        }

        // Partition members into canary and regular pools
        List<String> canaryMembers = new ArrayList<>();
        List<String> regularMembers = new ArrayList<>();

        for (String memberId : groupSpec.memberIds()) {
            MemberSubscription subscription = groupSpec.memberSubscription(memberId);
            String rackId = subscription.rackId().orElse("");
            if (CANARY_RACK_ID.equals(rackId)) {
                canaryMembers.add(memberId);
            } else {
                regularMembers.add(memberId);
            }
        }

        LOG.debug("Canary assignor found {} canary members and {} regular members",
            canaryMembers.size(), regularMembers.size());

        // Collect all subscribed topic IDs from all members
        Set<Uuid> allSubscribedTopics = new HashSet<>();
        for (String memberId : groupSpec.memberIds()) {
            allSubscribedTopics.addAll(groupSpec.memberSubscription(memberId).subscribedTopicIds());
        }

        // Collect all partitions to assign
        List<TopicIdPartition> allPartitions = new ArrayList<>();
        for (Uuid topicId : allSubscribedTopics) {
            int numPartitions = subscribedTopicDescriber.numPartitions(topicId);
            if (numPartitions == -1) {
                throw new PartitionAssignorException(
                    "Members are subscribed to topic " + topicId + " which doesn't exist in the topic metadata."
                );
            }
            for (int partition = 0; partition < numPartitions; partition++) {
                allPartitions.add(new TopicIdPartition(topicId, partition));
            }
        }

        if (allPartitions.isEmpty()) {
            return new GroupAssignment(Map.of());
        }

        // Calculate canary partition count
        int canaryPartitionCount = calculateCanaryPartitionCount(
            allPartitions.size(),
            canaryMembers.size(),
            regularMembers.size()
        );

        LOG.debug("Assigning {} partitions to canary members and {} to regular members",
            canaryPartitionCount, allPartitions.size() - canaryPartitionCount);

        // Split partitions between canary and regular pools
        List<TopicIdPartition> canaryPartitions = allPartitions.subList(0, canaryPartitionCount);
        List<TopicIdPartition> regularPartitions = allPartitions.subList(canaryPartitionCount, allPartitions.size());

        // Build assignments
        Map<String, MemberAssignment> assignments = new HashMap<>();

        // Assign canary partitions to canary members
        assignPartitionsToMembers(canaryPartitions, canaryMembers, groupSpec, assignments);

        // Assign regular partitions to regular members
        assignPartitionsToMembers(regularPartitions, regularMembers, groupSpec, assignments);

        return new GroupAssignment(assignments);
    }

    /**
     * Calculates the number of partitions to assign to canary members.
     */
    int calculateCanaryPartitionCount(int totalPartitions, int canaryMemberCount, int regularMemberCount) {
        if (canaryMemberCount == 0) {
            // No canary members - all partitions go to regular
            return 0;
        }
        if (regularMemberCount == 0) {
            // No regular members - all partitions go to canary
            return totalPartitions;
        }

        int canaryCount = (int) Math.ceil(totalPartitions * canaryPercentage);
        // Ensure at least 1 partition for canary if there are canary members
        return Math.max(1, Math.min(canaryCount, totalPartitions));
    }

    /**
     * Assigns partitions to members using the same balanced quota-based distribution
     * as {@link UniformAssignor}. Each member gets a minimum quota (partitions / members),
     * and remaining partitions are distributed one per member to ensure balance.
     * The difference in partition count between any two members is at most one.
     */
    private void assignPartitionsToMembers(
        List<TopicIdPartition> partitions,
        List<String> memberIds,
        GroupSpec groupSpec,
        Map<String, MemberAssignment> assignments
    ) {
        if (memberIds.isEmpty()) {
            return;
        }

        // Initialize assignment maps for each member
        Map<String, Map<Uuid, Set<Integer>>> memberAssignments = new HashMap<>();
        for (String memberId : memberIds) {
            memberAssignments.put(memberId, new HashMap<>());
        }

        if (partitions.isEmpty()) {
            // No partitions to assign, just create empty assignments
            for (String memberId : memberIds) {
                assignments.put(memberId, new MemberAssignmentImpl(Map.of()));
            }
            return;
        }

        // Calculate quotas and distribute partitions
        int[] quotas = calculateMemberQuotas(memberIds.size(), partitions.size());
        distributePartitionsWithQuotas(partitions, memberIds, groupSpec, memberAssignments, quotas);

        // Convert to MemberAssignment objects
        for (String memberId : memberIds) {
            assignments.put(memberId, new MemberAssignmentImpl(memberAssignments.get(memberId)));
        }
    }

    /**
     * Calculates the quota for each member using the same balanced distribution as
     * {@link UniformAssignor}. Returns an array where quotas[i] is the target number
     * of partitions for member i. The first (partitions % members) members receive
     * one extra partition to ensure all partitions are assigned.
     */
    private int[] calculateMemberQuotas(int numberOfMembers, int numberOfPartitions) {
        int minimumQuota = numberOfPartitions / numberOfMembers;
        int membersWithExtraPartition = numberOfPartitions % numberOfMembers;

        LOG.debug("Distributing {} partitions to {} members: minQuota={}, membersWithExtra={}",
            numberOfPartitions, numberOfMembers, minimumQuota, membersWithExtraPartition);

        int[] quotas = new int[numberOfMembers];
        for (int i = 0; i < numberOfMembers; i++) {
            quotas[i] = minimumQuota + (i < membersWithExtraPartition ? 1 : 0);
        }
        return quotas;
    }

    /**
     * Distributes partitions to members respecting their quotas and subscriptions.
     */
    private void distributePartitionsWithQuotas(
        List<TopicIdPartition> partitions,
        List<String> memberIds,
        GroupSpec groupSpec,
        Map<String, Map<Uuid, Set<Integer>>> memberAssignments,
        int[] quotas
    ) {
        int partitionIndex = 0;

        // First pass: assign partitions to members up to their quota
        for (int memberIndex = 0; memberIndex < memberIds.size(); memberIndex++) {
            String memberId = memberIds.get(memberIndex);
            Set<Uuid> subscribedTopics = groupSpec.memberSubscription(memberId).subscribedTopicIds();

            int assigned = 0;
            while (assigned < quotas[memberIndex] && partitionIndex < partitions.size()) {
                TopicIdPartition tp = partitions.get(partitionIndex);
                partitionIndex++;
                if (subscribedTopics.contains(tp.topicId())) {
                    addPartitionToMember(memberAssignments, memberId, tp);
                    assigned++;
                }
            }
        }

        // Second pass: assign any remaining partitions
        assignRemainingPartitions(partitions, partitionIndex, memberIds, groupSpec, memberAssignments);
    }

    /**
     * Assigns remaining partitions to any member that is subscribed.
     */
    private void assignRemainingPartitions(
        List<TopicIdPartition> partitions,
        int startIndex,
        List<String> memberIds,
        GroupSpec groupSpec,
        Map<String, Map<Uuid, Set<Integer>>> memberAssignments
    ) {
        for (int i = startIndex; i < partitions.size(); i++) {
            TopicIdPartition tp = partitions.get(i);
            boolean assigned = false;
            for (String memberId : memberIds) {
                if (groupSpec.memberSubscription(memberId).subscribedTopicIds().contains(tp.topicId())) {
                    addPartitionToMember(memberAssignments, memberId, tp);
                    assigned = true;
                    break;
                }
            }
            if (!assigned) {
                LOG.warn("Could not assign partition {} - no member in pool subscribed to topic {}",
                    tp, tp.topicId());
            }
        }
    }

    /**
     * Adds a partition to a member's assignment map.
     */
    private void addPartitionToMember(
        Map<String, Map<Uuid, Set<Integer>>> memberAssignments,
        String memberId,
        TopicIdPartition tp
    ) {
        memberAssignments.get(memberId)
            .computeIfAbsent(tp.topicId(), k -> new HashSet<>())
            .add(tp.partitionId());
    }

}
