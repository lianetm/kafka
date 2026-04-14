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
import org.apache.kafka.coordinator.common.runtime.KRaftCoordinatorMetadataImage;
import org.apache.kafka.coordinator.common.runtime.MetadataImageBuilder;
import org.apache.kafka.coordinator.group.api.assignor.GroupAssignment;
import org.apache.kafka.coordinator.group.api.assignor.GroupSpec;
import org.apache.kafka.coordinator.group.api.assignor.MemberAssignment;
import org.apache.kafka.coordinator.group.modern.Assignment;
import org.apache.kafka.coordinator.group.modern.GroupSpecImpl;
import org.apache.kafka.coordinator.group.modern.MemberSubscriptionAndAssignmentImpl;
import org.apache.kafka.coordinator.group.modern.SubscribedTopicDescriberImpl;
import org.apache.kafka.image.MetadataImage;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.apache.kafka.coordinator.group.AssignmentTestUtil.invertedTargetAssignment;
import static org.apache.kafka.coordinator.group.api.assignor.SubscriptionType.HOMOGENEOUS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CanaryAssignorTest {
    private final CanaryAssignor assignor = new CanaryAssignor();
    private final Uuid topic1Uuid = Uuid.randomUuid();
    private final String topic1Name = "topic1";
    private final String memberA = "A";
    private final String memberB = "B";
    private final String memberC = "C";
    private final String memberD = "D";

    @Test
    public void testNoCanaryMembers() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 10)
            .build();
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();
        // All members are regular (no canary rack)
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("us-east-1a"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));
        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("us-east-1b"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );

        GroupAssignment groupAssignment = assignor.assign(groupSpec, subscribedTopicMetadata);

        // All 10 partitions should be assigned to regular members
        int totalAssigned = countTotalPartitions(groupAssignment);
        assertEquals(10, totalAssigned);

        // Both members should have partitions
        assertTrue(groupAssignment.members().get(memberA).partitions().containsKey(topic1Uuid));
        assertTrue(groupAssignment.members().get(memberB).partitions().containsKey(topic1Uuid));
    }

    @Test
    public void testNoRegularMembers() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 10)
            .build();
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();
        // All members are canary
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("canary"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));
        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("canary"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );

        GroupAssignment groupAssignment = assignor.assign(groupSpec, subscribedTopicMetadata);

        // All 10 partitions should be assigned to canary members
        int totalAssigned = countTotalPartitions(groupAssignment);
        assertEquals(10, totalAssigned);
    }

    @Test
    public void testCanaryTrafficSplit() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 20)
            .build();
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();
        // 1 canary member
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("canary"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));
        // 3 regular members
        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("us-east-1a"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));
        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("us-east-1b"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));
        members.put(memberD, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("us-east-1c"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );

        GroupAssignment groupAssignment = assignor.assign(groupSpec, subscribedTopicMetadata);

        // 10% of 20 = 2 partitions should go to canary
        int canaryPartitions = countPartitionsForMember(groupAssignment, memberA);
        int regularPartitions = countPartitionsForMember(groupAssignment, memberB)
            + countPartitionsForMember(groupAssignment, memberC)
            + countPartitionsForMember(groupAssignment, memberD);

        assertEquals(2, canaryPartitions, "Canary should get 10% of partitions (2 out of 20)");
        assertEquals(18, regularPartitions, "Regular members should get 90% of partitions (18 out of 20)");

        // Total should be 20
        assertEquals(20, canaryPartitions + regularPartitions);
    }

    @Test
    public void testMinimumOnePartitionForCanary() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 5)
            .build();
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();
        // 1 canary member
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("canary"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));
        // 1 regular member
        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("us-east-1a"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );

        GroupAssignment groupAssignment = assignor.assign(groupSpec, subscribedTopicMetadata);

        // 10% of 5 = 0.5, but minimum 1 partition for canary
        int canaryPartitions = countPartitionsForMember(groupAssignment, memberA);
        assertTrue(canaryPartitions >= 1, "Canary should get at least 1 partition");
    }

    @Test
    public void testCalculateCanaryPartitionCount() {
        // Test various scenarios
        assertEquals(0, assignor.calculateCanaryPartitionCount(100, 0, 4));  // No canary members
        assertEquals(100, assignor.calculateCanaryPartitionCount(100, 2, 0));  // No regular members
        assertEquals(10, assignor.calculateCanaryPartitionCount(100, 1, 3));  // 10% of 100
        assertEquals(2, assignor.calculateCanaryPartitionCount(20, 1, 3));   // 10% of 20
        assertEquals(1, assignor.calculateCanaryPartitionCount(5, 1, 3));    // 10% of 5, minimum 1
        assertEquals(1, assignor.calculateCanaryPartitionCount(1, 1, 3));    // Single partition
    }

    @Test
    public void testMultipleCanaryMembers() {
        MetadataImage metadataImage = new MetadataImageBuilder()
            .addTopic(topic1Uuid, topic1Name, 20)
            .build();
        SubscribedTopicDescriberImpl subscribedTopicMetadata = new SubscribedTopicDescriberImpl(
            new KRaftCoordinatorMetadataImage(metadataImage)
        );

        Map<String, MemberSubscriptionAndAssignmentImpl> members = new TreeMap<>();
        // 2 canary members
        members.put(memberA, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("canary"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));
        members.put(memberB, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("canary"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));
        // 2 regular members
        members.put(memberC, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("us-east-1a"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));
        members.put(memberD, new MemberSubscriptionAndAssignmentImpl(
            Optional.of("us-east-1b"),
            Optional.empty(),
            Set.of(topic1Uuid),
            Assignment.EMPTY
        ));

        GroupSpec groupSpec = new GroupSpecImpl(
            members,
            HOMOGENEOUS,
            invertedTargetAssignment(members)
        );

        GroupAssignment groupAssignment = assignor.assign(groupSpec, subscribedTopicMetadata);

        // Canary partitions should be split between canary members
        int canaryAPartitions = countPartitionsForMember(groupAssignment, memberA);
        int canaryBPartitions = countPartitionsForMember(groupAssignment, memberB);
        int totalCanaryPartitions = canaryAPartitions + canaryBPartitions;

        assertEquals(2, totalCanaryPartitions, "Total canary partitions should be 10% of 20");
        // Each canary member should get 1 partition
        assertEquals(1, canaryAPartitions);
        assertEquals(1, canaryBPartitions);
    }

    private int countTotalPartitions(GroupAssignment groupAssignment) {
        int total = 0;
        for (MemberAssignment memberAssignment : groupAssignment.members().values()) {
            for (Set<Integer> partitions : memberAssignment.partitions().values()) {
                total += partitions.size();
            }
        }
        return total;
    }

    private int countPartitionsForMember(GroupAssignment groupAssignment, String memberId) {
        MemberAssignment memberAssignment = groupAssignment.members().get(memberId);
        if (memberAssignment == null) {
            return 0;
        }
        int total = 0;
        for (Set<Integer> partitions : memberAssignment.partitions().values()) {
            total += partitions.size();
        }
        return total;
    }
}
